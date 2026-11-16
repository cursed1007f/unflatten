package decompiler.ast;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Стек-эмулятор для Этапа 6: разбирает линейную последовательность байткод-
 * инструкций блока в список операторов ({@link Ast.Stmt}).
 *
 * <p>Является единственным стеком для блока: прогоняет все инструкции подряд,
 * в конце возвращая присваивания ({@code prelude}) и терминальный {@code return},
 * если блок им оканчивается. Значение, возвращаемое через IRETURN/ARETURN/DRETURN,
 * берётся с вершины стека и не сохраняется в локаль.
 *
 * <p>Имена локальных переменных — через {@link Ast.VarNameResolver}: параметры
 * получают {@code p0,p1,...}, остальные — префикс по типу ({@code iVar}, {@code objVar}…).
 */
public final class BytecodeToExpr {

    private final Deque<Ast.Expr> stack = new ArrayDeque<>();
    private final List<Ast.Stmt> stmts = new ArrayList<>();
    /** Блок уже завершён return/throw — остальные инструкции недостижимы. */
    private boolean terminated;
    /** Выражения заведомо boolean-типа (для условий без сравнения с 0). */
    private final java.util.Set<Ast.Expr> booleanExprs =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Ast.VarNameResolver names;
    /** Тип слота -> имя (заполняется извне анализатором типов). */
    private final String[] slotTypes;
    private final StringDecryptor decryptor; // может быть null — строки не расшифровываются
    /** Версионно-точный резолвер слота 0 (this vs спилл) в instance-методах. */
    private SlotZeroResolver slotZero = SlotZeroResolver.none();
    /** Метод возвращает boolean (IRETURN 0/1 -> return false/true). */
    private boolean booleanReturn;

    public BytecodeToExpr(Ast.VarNameResolver names, String[] slotTypes) {
        this(names, slotTypes, null);
    }

    /** Полный конструктор с опциональным дешифратором строк. */
    public BytecodeToExpr(Ast.VarNameResolver names, String[] slotTypes, StringDecryptor decryptor) {
        this.names = names;
        this.slotTypes = slotTypes;
        this.decryptor = decryptor;
    }

    /** Привязывает версионный резолвер слота 0 (для instance-методов). */
    public BytecodeToExpr withSlotZero(SlotZeroResolver resolver) {
        if (resolver != null) this.slotZero = resolver;
        return this;
    }

    /** Признак, что метод возвращает boolean (IRETURN 0/1 -> return false/true). */
    public BytecodeToExpr booleanReturning(boolean b) {
        this.booleanReturn = b;
        return this;
    }

    /** Упрощённый конструктор без типов (имена по слотам). */
    public BytecodeToExpr(Ast.VarNameResolver names) {
        this(names, null);
    }

    /**
     * Прогоняет инструкции блока, возвращая операторы в порядке исполнения
     * (присваивания + терминальный return, если есть).
     *
     * @param insns все инструкции блока, включая терминатор
     * @param stopBeforeReturn если true — обрывать на условном-переходе (для условий)
     */
    public List<Ast.Stmt> runBlock(List<AbstractInsnNode> insns, boolean stopBeforeReturn) {
        stack.clear();
        stmts.clear();
        terminated = false;
        for (AbstractInsnNode n : insns) {
            consume(n, stopBeforeReturn);
        }
        return stmts;
    }

    /**
     * Прогоняет инструкции и возвращает значение на вершине стека (после
     * обработки всех), либо null, если стек пуст. Удобно для построения условия:
     * блок-предикат оканчивается условным переходом, который оставляет на стеке
     * сравнение.
     */
    public Ast.Expr runExpr(List<AbstractInsnNode> insns) {
        stack.clear();
        stmts.clear();
        terminated = false;
        for (AbstractInsnNode n : insns) {
            consume(n, false);
        }
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Извлекает выражение-ключ switch: прогоняет инструкции блока до
     * TABLESWITCH/LOOKUPSWITCH и возвращает верх стека (значение, на котором
     * переключаемся). {@code null}, если ключ не найден.
     */
    public Ast.Expr extractSwitchKey(List<AbstractInsnNode> insns) {
        stack.clear();
        for (AbstractInsnNode n : insns) {
            if (n.getOpcode() == Opcodes.TABLESWITCH || n.getOpcode() == Opcodes.LOOKUPSWITCH) {
                break; // ключ уже на стеке
            }
            if (n.getOpcode() < 0) continue;
            consume(n, false);
        }
        return stack.isEmpty() ? null : stack.peek();
    }

    /** Возвращает терминальный оператор (return или null), если стек даёт одно значение сверху. */
    public static Ast.Expr topOfStack() {
        return null; // не используется; return обрабатывается внутри consume
    }

    /** Имя слота по типу. */
    private String name(int slot) {
        String type = slotTypes != null && slot < slotTypes.length ? slotTypes[slot] : "Object";
        return names.name(slot, type);
    }

    /**
     * Контекстное имя слота в точке загрузки ({@code *LOAD}).
     *
     * <p>Корень {@code >= this} (см. BUGREPORT.md): в спилл/де-флаттнинг-коде
     * обфускатор переиспользует слот 0 instance-метода под int-временную наряду
     * с {@code this}. Статический {@code slotTypes[0]} неоднозначен (по месту
     * может быть и объект, и int), поэтому {@link #name(int)} звал слот 0
     * «this» и в числовом сравнении выходило {@code this >= ...}. Числовая
     * загрузка ({@code *LOAD}) из слота 0 не может быть {@code this} — это
     * всегда спилл-значение, значит имя надо брать по опкоду загрузки.</p>
     */
    private String nameForLoad(int slot, int loadOpcode) {
        if (slot == 0 && "this".equals(name(0))) {
            // instance-метод: слот 0 статически называется «this», но сейчас его
            // грузят числом — это спилл, а не объект. Даём примитивное имя.
            return switch (loadOpcode) {
                case Opcodes.LLOAD -> "lVar0";
                case Opcodes.FLOAD -> "fVar0";
                case Opcodes.DLOAD -> "dVar0";
                default -> "iVar0"; // ILOAD и всё прочее числовое
            };
        }
        return name(slot);
    }

    /** Слот объявлен как boolean? */
    private boolean isBooleanSlot(int var) {
        return slotTypes != null && var >= 0 && var < slotTypes.length
                && "boolean".equals(slotTypes[var]);
    }

    /** Литерал 0/1 -> false/true, если цель boolean. */
    private static Ast.Expr boolLit(Ast.Expr v, boolean isBool) {
        if (!isBool || !(v instanceof Ast.Literal l)) return v;
        Object val = l.value();
        if (val instanceof Number num) {
            int i = num.intValue();
            if (i == 0) return new Ast.Literal(false);
            if (i == 1) return new Ast.Literal(true);
        }
        return v;
    }

    private void consume(AbstractInsnNode n, boolean stopBeforeReturn) {
        if (n.getOpcode() < 0) return; // label/line/frame
        if (terminated) return; // недостижимый код после return/throw
        // условный переход: обрываем разбор (условие построено отдельно)
        if (stopBeforeReturn && n instanceof JumpInsnNode && n.getOpcode() != Opcodes.GOTO) {
            return;
        }
        switch (n.getOpcode()) {
            case Opcodes.NOP -> {
            }
            // --- константы ---
            case Opcodes.ACONST_NULL -> stack.push(new Ast.NullLit());
            case Opcodes.ICONST_M1 -> stack.push(new Ast.Literal(-1));
            case Opcodes.ICONST_0 -> stack.push(new Ast.Literal(0));
            case Opcodes.ICONST_1 -> stack.push(new Ast.Literal(1));
            case Opcodes.ICONST_2 -> stack.push(new Ast.Literal(2));
            case Opcodes.ICONST_3 -> stack.push(new Ast.Literal(3));
            case Opcodes.ICONST_4 -> stack.push(new Ast.Literal(4));
            case Opcodes.ICONST_5 -> stack.push(new Ast.Literal(5));
            case Opcodes.LCONST_0 -> stack.push(new Ast.Literal(0L));
            case Opcodes.LCONST_1 -> stack.push(new Ast.Literal(1L));
            case Opcodes.FCONST_0 -> stack.push(new Ast.Literal(0.0f));
            case Opcodes.DCONST_0 -> stack.push(new Ast.Literal(0.0d));
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> stack.push(new Ast.Literal(((IntInsnNode) n).operand));
            case Opcodes.LDC -> {
                Object cst = ((LdcInsnNode) n).cst;
                if (cst instanceof org.objectweb.asm.Type t) {
                    // класс-литерал: Foo.class (через карту переименований)
                    String typeName = Ast.friendlyType(t.getDescriptor());
                    stack.push(new Ast.Field(null, typeName + ".class", true));
                } else {
                    stack.push(new Ast.Literal(cst));
                }
            }
            // --- локальные ---
            case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
                int var = ((VarInsnNode) n).var;
                // Версионно-точная типизация слота 0: если резолвер знает, что здесь
                // слот 0 — спилл (перезаписан), а не this, даём примитивное имя
                // (закрывает >= this, this == null, this[индекс] полностью по данным,
                // а не по статической таблице slotTypes).
                String nm;
                if (var == 0) {
                    String s0 = slotZero.nameForSlot0Load((VarInsnNode) n);
                    nm = s0 != null ? s0 : name(var);
                } else {
                    nm = (n.getOpcode() == Opcodes.ALOAD)
                            ? name(var)
                            : nameForLoad(var, n.getOpcode());
                }
                Ast.Var lv = new Ast.Var(nm);
                if (n.getOpcode() == Opcodes.ILOAD && isBooleanSlot(var)) booleanExprs.add(lv);
                stack.push(lv);
            }
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                int var = ((VarInsnNode) n).var;
                Ast.Expr v = pop();
                Ast.Var tgt = new Ast.Var(name(var));
                boolean isBool = n.getOpcode() == Opcodes.ISTORE && isBooleanSlot(var);
                stmts.add(new Ast.Assign(tgt, boolLit(v, isBool)));
            }
            case Opcodes.IINC -> {
                IincInsnNode i = (IincInsnNode) n;
                Ast.Var tgt = new Ast.Var(name(i.var));
                stmts.add(new Ast.Assign(tgt, new Ast.Binary("+", tgt, new Ast.Literal(i.incr))));
            }
            // --- арифметика (int) ---
            case Opcodes.IADD -> bin("+");
            case Opcodes.ISUB -> bin("-");
            case Opcodes.IMUL -> bin("*");
            case Opcodes.IDIV -> bin("/");
            case Opcodes.IREM -> bin("%");
            case Opcodes.INEG -> un("-");
            case Opcodes.IAND -> bin("&");
            case Opcodes.IOR -> bin("|");
            case Opcodes.IXOR -> bin("^");
            case Opcodes.ISHL -> bin("<<");
            case Opcodes.ISHR -> bin(">>");
            case Opcodes.IUSHR -> bin(">>>");
            // --- long ---
            case Opcodes.LADD -> bin("+");
            case Opcodes.LSUB -> bin("-");
            case Opcodes.LMUL -> bin("*");
            case Opcodes.LDIV -> bin("/");
            case Opcodes.LREM -> bin("%");
            case Opcodes.LAND -> bin("&");
            case Opcodes.LOR -> bin("|");
            case Opcodes.LXOR -> bin("^");
            // --- поля ---
            case Opcodes.GETSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) n;
                Ast.Field fld = new Ast.Field(null, f.name, true);
                if ("Z".equals(f.desc)) booleanExprs.add(fld);
                stack.push(fld);
            }
            case Opcodes.PUTFIELD -> {
                Ast.Expr v = pop();
                Ast.Expr t = pop();
                FieldInsnNode f = (FieldInsnNode) n;
                Ast.Expr val = boolLit(v, "Z".equals(f.desc)); // boolean-поле: 0/1 -> false/true
                stmts.add(new Ast.Assign(new Ast.Var("this." + f.name), val)); // упрощённо, field-write
            }
            case Opcodes.GETFIELD -> {
                // instance-поле: receiver.field. Ресивер берём со стека; если это
                // голый «this» (ALOAD 0 без переиспользования) — печатается this.field.
                // Это закрывает корень «>= this»: раньше GETFIELD не обрабатывался,
                // стек портился и голый this прилипал к числовым операциям.
                FieldInsnNode f = (FieldInsnNode) n;
                Ast.Expr target = pop();
                Ast.Field fld = new Ast.Field(target, f.name, false);
                if ("Z".equals(f.desc)) booleanExprs.add(fld);
                stack.push(fld);
            }
            // --- вызовы ---
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE ->
                    doCall((MethodInsnNode) n);
            // --- new + dup + init ---
            case Opcodes.NEW -> {
                TypeInsnNode t = (TypeInsnNode) n;
                // t.desc — internal-имя (java/util/Random); friendlyType ждёт дескриптор L...;
                String newDesc = t.desc.startsWith("[") ? t.desc : "L" + t.desc + ";";
                stack.push(new Ast.NewObject(newDesc, new ArrayList<>()));
            }
            case Opcodes.DUP -> {
                // пустой стек (например, DUP после store) — peek() вернул бы null
                if (!stack.isEmpty()) stack.push(stack.peek());
            }
            // --- условные переходы (даем выражение, если stopBeforeReturn=false) ---
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                 Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                Ast.Expr r = pop();
                Ast.Expr l = pop();
                stack.push(new Ast.Binary(cmpOp(n.getOpcode(), true), l, r));
            }
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> {
                Ast.Expr v = pop();
                // boolean-выражение не сравниваем с 0: hasNext() != 0 -> hasNext()
                if ((isBooleanExpr(v) || booleanExprs.contains(v))
                        && (n.getOpcode() == Opcodes.IFEQ || n.getOpcode() == Opcodes.IFNE)) {
                    stack.push(n.getOpcode() == Opcodes.IFEQ ? new Ast.Unary("!", v) : v);
                } else {
                    stack.push(new Ast.Binary(cmpOp(n.getOpcode(), false), v, new Ast.Literal(0)));
                }
            }
            case Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                Ast.Expr v = pop();
                stack.push(n.getOpcode() == Opcodes.IFNULL
                        ? new Ast.Binary("==", v, new Ast.NullLit())
                        : new Ast.Binary("!=", v, new Ast.NullLit()));
            }
            // --- массивы ---
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
                 Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> {
                Ast.Expr idx = pop();
                Ast.Expr arr = pop();
                stack.push(new Ast.Binary("[]", arr, idx)); // arr[idx]
            }
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
                 Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                Ast.Expr val = pop();
                Ast.Expr idx = pop();
                Ast.Expr arr = pop();
                stmts.add(new Ast.ExprStmt(new Ast.Binary("[]=", arr, new Ast.Binary("[]", idx, val))));
            }
            case Opcodes.ARRAYLENGTH -> {
                Ast.Expr arr = pop();
                // длина массива — это ПОЛЕ length, а не метод: arr.length
                stack.push(new Ast.Field(arr, "length", false));
            }
            case Opcodes.NEWARRAY -> {
                Ast.Expr size = pop();
                int atype = ((org.objectweb.asm.tree.IntInsnNode) n).operand;
                stack.push(new Ast.NewObject("[" + arrayTypeName(atype), List.of(size)));
            }
            case Opcodes.ANEWARRAY -> {
                Ast.Expr size = pop();
                TypeInsnNode t = (TypeInsnNode) n;
                stack.push(new Ast.NewObject("L" + t.desc + ";[]", List.of(size)));
            }
            case Opcodes.MULTIANEWARRAY -> {
                // multi-мерный массив: dims размеров на стеке (по порядку)
                org.objectweb.asm.tree.MultiANewArrayInsnNode t =
                        (org.objectweb.asm.tree.MultiANewArrayInsnNode) n;
                List<Ast.Expr> sizes = new ArrayList<>();
                for (int d = 0; d < t.dims; d++) sizes.add(0, pop());
                stack.push(new Ast.NewObject(t.desc, sizes));
            }
            // --- касты и проверки ---
            case Opcodes.CHECKCAST -> {
                TypeInsnNode t = (TypeInsnNode) n;
                Ast.Expr v = pop();
                if ("java/lang/Object".equals(t.desc)) {
                    stack.push(v); // каст в Object бессмыслен — опускаем
                } else {
                    String typeName = t.desc.startsWith("[")
                            ? Ast.friendlyType(t.desc)                 // уже дескриптор массива
                            : Ast.friendlyType("L" + t.desc + ";");    // internal-имя -> дескриптор
                    stack.push(new Ast.Call(v, "cast", List.of(new Ast.Var(typeName)), false));
                }
            }
            case Opcodes.INSTANCEOF -> {
                TypeInsnNode t = (TypeInsnNode) n;
                Ast.Expr v = pop();
                String typeName = t.desc.startsWith("[")
                        ? Ast.friendlyType(t.desc)
                        : Ast.friendlyType("L" + t.desc + ";");
                stack.push(new Ast.Binary("instanceof", v, new Ast.Var(typeName)));
            }
            // --- конвертации int/long/float/double ---
            case Opcodes.I2L -> conv("(long)");
            case Opcodes.I2F -> conv("(float)");
            case Opcodes.I2D -> conv("(double)");
            case Opcodes.L2I -> conv("(int)");
            case Opcodes.L2F -> conv("(float)");
            case Opcodes.L2D -> conv("(double)");
            case Opcodes.F2I -> conv("(int)");
            case Opcodes.F2L -> conv("(long)");
            case Opcodes.F2D -> conv("(double)");
            case Opcodes.D2I -> conv("(int)");
            case Opcodes.D2L -> conv("(long)");
            case Opcodes.D2F -> conv("(float)");
            case Opcodes.I2B -> conv("(byte)");
            case Opcodes.I2C -> conv("(char)");
            case Opcodes.I2S -> conv("(short)");
            // --- управление ---
            case Opcodes.ATHROW -> {
                // мёртвый ATHROW без объекта-исключения (после return) — не эмитим throw(0)
                if (stack.isEmpty()) return;
                Ast.Expr v = pop();
                // ATHROW по JVM всегда кидает ссылку на Throwable. Если на стеке
                // примитив (число/primitive-var) — стек испорчен (catch-слот
                // переиспользован, де-флаттенинг), настоящий throw объект потерян.
                // Такой throw бессмыслен — не эмитим, но блок завершаем.
                if (isPrimitiveThrowArg(v)) { terminated = true; stack.clear(); return; }
                stmts.add(new Ast.ExprStmt(new Ast.Call(null, "throw", List.of(v), false)));
                terminated = true;
                stack.clear();
            }
            case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                pop(); // монитор
            }
            case Opcodes.POP -> pop();
            case Opcodes.POP2 -> { pop(); pop(); }
            case Opcodes.SWAP -> {
                Ast.Expr a = pop();
                Ast.Expr b = pop();
                stack.push(a);
                stack.push(b);
            }
            // --- return: берём верх стека и эмитим return ---
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> {
                Ast.Expr v = pop();
                // boolean-метод: IRETURN 0/1 -> return false/true (вместо return Literal(0))
                if (booleanReturn && n.getOpcode() == Opcodes.IRETURN) v = boolLit(v, true);
                stmts.add(new Ast.Return(v));
                terminated = true;
                stack.clear(); // очистка после return — значение "протекать" не должно
            }
            case Opcodes.RETURN -> {
                stmts.add(new Ast.Return(null));
                terminated = true;
                stack.clear();
            }
            // --- GOTO: терминатор без значения; стек очищаем ---
            case Opcodes.GOTO -> stack.clear();
            default -> {
                // незнакомые: потребляем (не даём тихому падению)
            }
        }
    }

    private void doCall(MethodInsnNode m) {
        // --- распознавание дешифратора строк: LDC class; LDC idx; a(String,I)String ---
        if (m.getOpcode() == Opcodes.INVOKESTATIC
                && m.name.equals("a")
                && m.desc.equals("(Ljava/lang/String;I)Ljava/lang/String;")) {
            Ast.Expr idxExpr = pop();
            Ast.Expr clsExpr = pop();
            String className = literalString(clsExpr);
            Integer index = literalInt(idxExpr);
            if (className != null && index != null) {
                if (decryptor != null) {
                    String s = decryptor.decrypt(className, index);
                    if (s != null) {
                        stack.push(new Ast.Literal(s));
                        return;
                    }
                }
                // ключа нет или класс не загружен — помечаем как зашифрованную строку
                stack.push(new Ast.Literal("<!-- enc:" + className + "#" + index + " -->"));
                return;
            }
        }

        List<Ast.Expr> args = new ArrayList<>();
        int argc = argsCount(m.desc);
        for (int i = 0; i < argc; i++) args.add(0, pop());
        Ast.Expr target = m.getOpcode() == Opcodes.INVOKESTATIC ? null : pop();

        // --- конструктор: NEW Foo; DUP; args; INVOKESPECIAL Foo.<init>(args)V ---
        // Склеиваем в один NewObject(desc, args), который остаётся на стеке вместо
        // DUP-копии. Иначе печатается мусорное `new Foo().<init>(a)` + лишнее `Foo`.
        if (m.name.equals("<init>") && target instanceof Ast.NewObject no) {
            Ast.NewObject merged = new Ast.NewObject(no.desc(), args);
            // стек сверху обычно держит DUP-копию того же NewObject — заменяем её
            if (!stack.isEmpty() && stack.peek() instanceof Ast.NewObject p
                    && p.desc() != null && p.desc().equals(no.desc())) {
                stack.pop();
            }
            stack.push(merged);
            return;
        }

        String retDesc = m.desc.substring(m.desc.indexOf(')') + 1);
        Ast.Call call = new Ast.Call(target, m.name, args, m.getOpcode() == Opcodes.INVOKESTATIC, retDesc);
        // void-вызов — оператор, а не значение
        if (m.desc.endsWith(")V")) {
            stmts.add(new Ast.ExprStmt(call));
        } else {
            stack.push(call);
        }
    }

    /** Если выражение — строковый литерал, возвращает его значение; иначе null. */
    private static String literalString(Ast.Expr e) {
        if (e instanceof Ast.Literal l && l.value() instanceof String s) return s;
        return null;
    }

    /** Если выражение — целочисленный литерал, возвращает Integer; иначе null. */
    private static Integer literalInt(Ast.Expr e) {
        if (e instanceof Ast.Literal l && l.value() instanceof Number n) return n.intValue();
        return null;
    }

    private int argsCount(String desc) {
        int args = 0;
        boolean inParen = false;
        for (int i = 0; i < desc.length(); i++) {
            char c = desc.charAt(i);
            if (c == '(') { inParen = true; continue; }
            if (c == ')') break;
            if (inParen) {
                if (c == 'L') { args++; while (desc.charAt(i) != ';') i++; }
                else if (c == '[') { /* массив: весь массив — 1 слот */ }
                else if (c == 'J' || c == 'D') args += 1;
                else args++;
            }
        }
        return args;
    }

    private void bin(String op) {
        Ast.Expr r = pop();
        Ast.Expr l = pop();
        // свёртка константной арифметики: -191 + 193 -> 2 (и во всех выражениях)
        Object cv = Ast.constValue(new Ast.Binary(op, l, r));
        stack.push(cv != null ? new Ast.Literal(cv) : new Ast.Binary(op, l, r));
    }

    /** Конвертация типа: (targetType)operand. */
    private void conv(String cast) {
        stack.push(new Ast.Unary(cast, pop()));
    }

    /** Имя типа примитивного массива по opcode NEWARRAY (atype). */
    private static String arrayTypeName(int atype) {
        return switch (atype) {
            case Opcodes.T_BOOLEAN -> "Z";
            case Opcodes.T_CHAR -> "C";
            case Opcodes.T_FLOAT -> "F";
            case Opcodes.T_DOUBLE -> "D";
            case Opcodes.T_BYTE -> "B";
            case Opcodes.T_SHORT -> "S";
            case Opcodes.T_INT -> "I";
            case Opcodes.T_LONG -> "J";
            default -> "";
        };
    }

    private void un(String op) {
        Ast.Expr o = pop();
        Object cv = Ast.constValue(new Ast.Unary(op, o));
        stack.push(cv != null ? new Ast.Literal(cv) : new Ast.Unary(op, o));
    }

    private Ast.Expr pop() {
        if (stack.isEmpty()) return new Ast.Literal(0);
        return stack.pop();
    }

    /** Истинно, если аргумент ATHROW — примитив (числовой/литерал), а не ссылка на Throwable. */
    private static boolean isPrimitiveThrowArg(Ast.Expr v) {
        if (v instanceof Ast.Literal l) return l.value() instanceof Number;
        if (v instanceof Ast.Var var) {
            String n = var.name();
            // примитив-var: iVar/fVar/lVar/dVar (oVar — ссылка, корректный catch-реитрoat)
            return n.startsWith("iVar") || n.startsWith("fVar") || n.startsWith("lVar") || n.startsWith("dVar");
        }
        return false;
    }

    /** Выражение заведомо булева типа (сравнение/логика/вызов с типом Z). */
    private static boolean isBooleanExpr(Ast.Expr e) {
        if (e instanceof Ast.Binary b) {
            String op = b.op();
            return op.equals("==") || op.equals("!=") || op.equals("<") || op.equals("<=")
                    || op.equals(">") || op.equals(">=") || op.equals("&&") || op.equals("||")
                    || op.equals("instanceof");
        }
        if (e instanceof Ast.Unary u) return u.op().equals("!");
        if (e instanceof Ast.Call c) return "Z".equals(c.retDesc());
        return false;
    }

    /** Оператор сравнения для IF-кода. */
    private static String cmpOp(int opcode, boolean twoOperands) {
        return switch (opcode) {
            case Opcodes.IF_ICMPEQ, Opcodes.IFEQ -> "==";
            case Opcodes.IF_ICMPNE, Opcodes.IFNE -> "!=";
            case Opcodes.IF_ICMPLT, Opcodes.IFLT -> "<";
            case Opcodes.IF_ICMPGE, Opcodes.IFGE -> ">=";
            case Opcodes.IF_ICMPGT, Opcodes.IFGT -> ">";
            case Opcodes.IF_ICMPLE, Opcodes.IFLE -> "<=";
            default -> "?";
        };
    }
}