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
    /** Категория значения (1/2), структурно при push — основа entry-stack. */
    private final java.util.Map<Ast.Expr, Integer> categories = new java.util.IdentityHashMap<>();

    private void pushCat(Ast.Expr e, int cat) {
        stack.push(e);
        categories.put(e, cat);
    }
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
    /** 1 для instance (слот 0 = this), 0 для static — для типов pN-слотов. */
    private int thisOffset = 1;
    /** Внутреннее имя текущего класса (для super()/this() в конструкторах). */
    private String classOwner;
    /**
     * Обфускатор разрывает создание и инициализацию объекта:
     * {@code new X; astore k; ...; aload k; invokespecial <init>}. Здесь храним
     * «в переменной k лежит создаваемый NewObject» (по имени), чтобы при
     * встрече {@code <init>} собрать корректное {@code k = new X(args)}.
     */
    private final java.util.Map<String, Ast.NewObject> pendingNew = new java.util.LinkedHashMap<>();
    /** Слоты отложенных new — чтобы отличить мёртвую запись от живой. */
    private final java.util.Map<String, Integer> pendingSlots = new java.util.LinkedHashMap<>();
    /** Читается ли слот где-либо в методе (для отсева мёртвых pendingNew). */
    private java.util.function.IntPredicate slotReads = s -> true;

    /** Привязывает карту чтений слотов (slotReadAnywhere из StructureToAst). */
    public BytecodeToExpr withSlotReads(java.util.function.IntPredicate p) {
        if (p != null) this.slotReads = p;
        return this;
    }
    /** Общий на метод приёмник литералов: инвариант provenance (см. StructureToAst). */
    private java.util.Set<Ast.Expr> literalSink;

    /** Привязывает общий реестр литералов (identity-set на метод). */
    public BytecodeToExpr withLiteralSink(java.util.Set<Ast.Expr> sink) {
        this.literalSink = sink;
        return this;
    }

    /**
     * Единственная точка создания литералов. Каждый литерал в выхлопе обязан
     * происходить из байткодной инструкции/её свёртки; фабрикация значений
     * (0/null на пустом стеке) запрещена и детектится инвариантом provenance.
     */
    private Ast.Literal lit(Object v) {
        Ast.Literal l = new Ast.Literal(v);
        if (literalSink != null) literalSink.add(l);
        return l;
    }

    /** null тоже литерал: фабрикуется только из ACONST_NULL/IFNULL/IFNONNULL. */
    private Ast.NullLit nullLit() {
        Ast.NullLit n = new Ast.NullLit();
        if (literalSink != null) literalSink.add(n);
        return n;
    }

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

    /** static-метод: thisOffset = 0 (нужно для категорий pN-слотов). */
    public BytecodeToExpr staticMethod(boolean isStatic) {
        this.thisOffset = isStatic ? 0 : 1;
        return this;
    }

    /** Внутреннее имя текущего класса — чтобы отличить super(...) от this(...). */
    public BytecodeToExpr currentOwner(String owner) {
        this.classOwner = owner;
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
        return runBlock(insns, stopBeforeReturn, List.of());
    }

    /**
     * Прогон блока с заданным входным стеком (снизу вверх). Нужен для catch-блока:
     * на его входе ровно [exception], поэтому эмиттер стартует с {@code e}, а не
     * с пустого стека (иначе ATHROW молча терялся).
     */
    public List<Ast.Stmt> runBlock(List<AbstractInsnNode> insns, boolean stopBeforeReturn,
                                   List<Ast.Expr> entryStack) {
        stack.clear();
        categories.clear();
        stmts.clear();
        pendingNew.clear();
        pendingSlots.clear();
        terminated = false;
        for (Ast.Expr e : entryStack) pushCat(e, 1);
        for (AbstractInsnNode n : insns) {
            consume(n, stopBeforeReturn);
        }
        // new X без <init> в этом блоке: если локаль где-то читается, присваивание
        // k = new X() без аргументов тихо неверно (теряются реальные args) — отказ.
        // Если локаль не читается ни разу, это мёртвая запись: её можно не эмитить.
        java.util.List<String> live = new ArrayList<>();
        for (String nm : pendingNew.keySet()) {
            Integer slot = pendingSlots.get(nm);
            if (slot == null || slotReads.test(slot)) live.add(nm);
        }
        if (!live.isEmpty()) {
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER,
                    "pending new without <init>: " + live);
        }
        pendingNew.clear();
        pendingSlots.clear();
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
        categories.clear();
        stmts.clear();
        pendingNew.clear();
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
        categories.clear();
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
    private Ast.Expr boolLit(Ast.Expr v, boolean isBool) {
        if (!isBool || !(v instanceof Ast.Literal l)) return v;
        Object val = l.value();
        if (val instanceof Number num) {
            int i = num.intValue();
            if (i == 0) return lit(false);
            if (i == 1) return lit(true);
        }
        return v;
    }

    /** Значения, оставшиеся на стеке после прогона блока (для phi на join). */
    public java.util.List<Ast.Expr> stackSnapshot() {
        java.util.List<Ast.Expr> out = new java.util.ArrayList<>(stack);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Операторы, произведённые последним runExpr/extractSwitchKey (prelude). */
    public List<Ast.Stmt> lastStatements() {
        return new ArrayList<>(stmts);
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
            case Opcodes.ACONST_NULL -> pushCat(nullLit(), 1);
            case Opcodes.ICONST_M1 -> pushCat(lit(-1), 1);
            case Opcodes.ICONST_0 -> pushCat(lit(0), 1);
            case Opcodes.ICONST_1 -> pushCat(lit(1), 1);
            case Opcodes.ICONST_2 -> pushCat(lit(2), 1);
            case Opcodes.ICONST_3 -> pushCat(lit(3), 1);
            case Opcodes.ICONST_4 -> pushCat(lit(4), 1);
            case Opcodes.ICONST_5 -> pushCat(lit(5), 1);
            case Opcodes.LCONST_0 -> pushCat(lit(0L), 2);
            case Opcodes.LCONST_1 -> pushCat(lit(1L), 2);
            case Opcodes.FCONST_0 -> pushCat(lit(0.0f), 1);
            case Opcodes.FCONST_1 -> pushCat(lit(1.0f), 1);
            case Opcodes.FCONST_2 -> pushCat(lit(2.0f), 1);
            case Opcodes.DCONST_0 -> pushCat(lit(0.0d), 2);
            case Opcodes.DCONST_1 -> pushCat(lit(1.0d), 2);
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> pushCat(lit(((IntInsnNode) n).operand), 1);
            case Opcodes.LDC -> {
                Object cst = ((LdcInsnNode) n).cst;
                if (cst instanceof org.objectweb.asm.Type t) {
                    // класс-литерал: Foo.class (через карту переименований)
                    String typeName = Ast.friendlyType(t.getDescriptor());
                    pushCat(new Ast.Field(null, typeName + ".class", true), 1);
                } else {
                    pushCat(lit(cst), (cst instanceof Long || cst instanceof Double) ? 2 : 1);
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
                pushCat(lv, (n.getOpcode() == Opcodes.LLOAD || n.getOpcode() == Opcodes.DLOAD) ? 2 : 1);
            }
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                int var = ((VarInsnNode) n).var;
                Ast.Expr v = pop();
                // Разрывная инициализация: new X сохраняется в локал до <init>.
                // Откладываем присваивание до встречи invokespecial <init> на этом локале.
                if (n.getOpcode() == Opcodes.ASTORE && v instanceof Ast.NewObject no && no.args().isEmpty()) {
                    String nm = name(var);
                    pendingNew.put(nm, no);
                    pendingSlots.put(nm, var);
                    // снять оставшуюся DUP-копию того же объекта, если она на стеке
                    if (!stack.isEmpty() && stack.peek() == no) stack.pop();
                    break;
                }
                pendingNew.remove(name(var));
                pendingSlots.remove(name(var));
                Ast.Var tgt = new Ast.Var(name(var));
                boolean isBool = n.getOpcode() == Opcodes.ISTORE && isBooleanSlot(var);
                stmts.add(new Ast.Assign(tgt, boolLit(v, isBool)));
            }
            case Opcodes.IINC -> {
                IincInsnNode i = (IincInsnNode) n;
                Ast.Var tgt = new Ast.Var(name(i.var));
                stmts.add(new Ast.Assign(tgt, new Ast.Binary("+", tgt, lit(i.incr))));
            }
            // --- арифметика (int) ---
            case Opcodes.IADD -> bin("+", 1);
            case Opcodes.ISUB -> bin("-", 1);
            case Opcodes.IMUL -> bin("*", 1);
            case Opcodes.IDIV -> bin("/", 1);
            case Opcodes.IREM -> bin("%", 1);
            case Opcodes.INEG -> un("-", 1);
            case Opcodes.IAND -> bin("&", 1);
            case Opcodes.IOR -> bin("|", 1);
            case Opcodes.IXOR -> bin("^", 1);
            case Opcodes.ISHL -> bin("<<", 1);
            case Opcodes.ISHR -> bin(">>", 1);
            case Opcodes.IUSHR -> bin(">>>", 1);
            // --- long ---
            case Opcodes.LADD -> bin("+", 2);
            case Opcodes.LSUB -> bin("-", 2);
            case Opcodes.LMUL -> bin("*", 2);
            case Opcodes.LDIV -> bin("/", 2);
            case Opcodes.LREM -> bin("%", 2);
            case Opcodes.LAND -> bin("&", 2);
            case Opcodes.LOR -> bin("|", 2);
            case Opcodes.LXOR -> bin("^", 2);
            case Opcodes.LSHL -> bin("<<", 2);
            case Opcodes.LSHR -> bin(">>", 2);
            case Opcodes.LUSHR -> bin(">>>", 2);
            case Opcodes.LNEG -> un("-", 2);
            // --- float ---
            case Opcodes.FADD -> bin("+", 1);
            case Opcodes.FSUB -> bin("-", 1);
            case Opcodes.FMUL -> bin("*", 1);
            case Opcodes.FDIV -> bin("/", 1);
            case Opcodes.FREM -> bin("%", 1);
            case Opcodes.FNEG -> un("-", 1);
            // --- double ---
            case Opcodes.DADD -> bin("+", 2);
            case Opcodes.DSUB -> bin("-", 2);
            case Opcodes.DMUL -> bin("*", 2);
            case Opcodes.DDIV -> bin("/", 2);
            case Opcodes.DREM -> bin("%", 2);
            case Opcodes.DNEG -> un("-", 2);
            // --- сравнения: нужен peephole с IFxx, пока честный отказ ---
            case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG ->
                    throw new UndecompilableException(
                            UndecompilableException.Kind.OTHER, "cmp opcode");
            // --- поля ---
            case Opcodes.GETSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) n;
                Ast.Field fld = new Ast.Field(null, staticFieldName(f), true);
                if ("Z".equals(f.desc)) booleanExprs.add(fld);
                pushCat(fld, ("J".equals(f.desc) || "D".equals(f.desc)) ? 2 : 1);
            }
            case Opcodes.PUTSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) n;
                Ast.Expr v = pop();
                Ast.Expr val = boolLit(v, "Z".equals(f.desc));
                stmts.add(new Ast.Assign(new Ast.Field(null, staticFieldName(f), true), val));
            }
            case Opcodes.PUTFIELD -> {
                Ast.Expr v = pop();
                Ast.Expr t = pop();
                FieldInsnNode f = (FieldInsnNode) n;
                Ast.Expr val = boolLit(v, "Z".equals(f.desc)); // boolean-поле: 0/1 -> false/true
                // Получатель — реальный, а не всегда this (oVar3.x = 1).
                Ast.Field target = new Ast.Field(t, f.name, false);
                stmts.add(new Ast.Assign(target, val));
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
                pushCat(fld, ("J".equals(f.desc) || "D".equals(f.desc)) ? 2 : 1);
            }
            // --- вызовы ---
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE ->
                    doCall((MethodInsnNode) n);
            case Opcodes.INVOKEDYNAMIC ->
                    consumeIndy((org.objectweb.asm.tree.InvokeDynamicInsnNode) n);
            // --- new + dup + init ---
            case Opcodes.NEW -> {
                TypeInsnNode t = (TypeInsnNode) n;
                // t.desc — internal-имя (java/util/Random); friendlyType ждёт дескриптор L...;
                String newDesc = t.desc.startsWith("[") ? t.desc : "L" + t.desc + ";";
                pushCat(new Ast.NewObject(newDesc, new ArrayList<>()), 1);
            }
            case Opcodes.DUP -> {
                if (stack.isEmpty()) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "DUP on empty stack");
                }
                pushCat(stack.peek(), catOf(stack.peek()));
            }
            case Opcodes.DUP_X1 -> {
                Boolean b1 = category2(stackAt(0));
                Boolean b2 = category2(stackAt(1));
                if (!Boolean.FALSE.equals(b1) || !Boolean.FALSE.equals(b2)) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "DUP_X1 category");
                }
                Ast.Expr a = pop();
                Ast.Expr b = pop();
                pushCat(a, 1);
                pushCat(b, 1);
                pushCat(a, 1);
            }
            case Opcodes.DUP_X2 -> {
                Boolean b1 = category2(stackAt(0));
                Boolean b2 = category2(stackAt(1));
                if (Boolean.FALSE.equals(b1) && Boolean.TRUE.equals(b2)) {
                    Ast.Expr a = pop();
                    Ast.Expr b = pop();
                    pushCat(a, 1);
                    pushCat(b, 2);
                    pushCat(a, 1);
                } else if (Boolean.FALSE.equals(b1) && Boolean.FALSE.equals(b2)
                        && Boolean.FALSE.equals(category2(stackAt(2)))) {
                    Ast.Expr a = pop();
                    Ast.Expr b = pop();
                    Ast.Expr c = pop();
                    pushCat(a, 1);
                    pushCat(c, 1);
                    pushCat(b, 1);
                    pushCat(a, 1);
                } else {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "DUP_X2 category");
                }
            }
            case Opcodes.DUP2 -> {
                Boolean b1 = category2(stackAt(0));
                if (Boolean.TRUE.equals(b1)) {
                    Ast.Expr a = pop();
                    pushCat(a, 2);
                    pushCat(a, 2);
                } else if (Boolean.FALSE.equals(b1)
                        && Boolean.FALSE.equals(category2(stackAt(1)))) {
                    Ast.Expr a = pop();
                    Ast.Expr b = pop();
                    pushCat(b, 1);
                    pushCat(a, 1);
                    pushCat(b, 1);
                    pushCat(a, 1);
                } else {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "DUP2 category");
                }
            }
            // --- условные переходы (даем выражение, если stopBeforeReturn=false) ---
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                 Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                Ast.Expr r = pop();
                Ast.Expr l = pop();
                pushCat(new Ast.Binary(cmpOp(n.getOpcode(), true), l, r), 1);
            }
            case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                Ast.Expr r = pop();
                Ast.Expr l = pop();
                pushCat(new Ast.Binary(
                        n.getOpcode() == Opcodes.IF_ACMPEQ ? "==" : "!=", l, r), 1);
            }
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> {
                Ast.Expr v = pop();
                // boolean-выражение не сравниваем с 0: hasNext() != 0 -> hasNext()
                if ((isBooleanExpr(v) || booleanExprs.contains(v))
                        && (n.getOpcode() == Opcodes.IFEQ || n.getOpcode() == Opcodes.IFNE)) {
                    pushCat(n.getOpcode() == Opcodes.IFEQ ? new Ast.Unary("!", v) : v, 1);
                } else {
                    pushCat(new Ast.Binary(cmpOp(n.getOpcode(), false), v, lit(0)), 1);
                }
            }
            case Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                Ast.Expr v = pop();
                pushCat(n.getOpcode() == Opcodes.IFNULL
                        ? new Ast.Binary("==", v, nullLit())
                        : new Ast.Binary("!=", v, nullLit()), 1);
            }
            // --- массивы ---
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
                 Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> {
                Ast.Expr idx = pop();
                Ast.Expr arr = pop();
                pushCat(new Ast.Binary("[]", arr, idx), (n.getOpcode() == Opcodes.LALOAD || n.getOpcode() == Opcodes.DALOAD) ? 2 : 1); // arr[idx]
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
                pushCat(new Ast.Field(arr, "length", false), 1);
            }
            case Opcodes.NEWARRAY -> {
                Ast.Expr size = pop();
                int atype = ((org.objectweb.asm.tree.IntInsnNode) n).operand;
                pushCat(new Ast.NewObject("[" + arrayTypeName(atype), List.of(size)), 1);
            }
            case Opcodes.ANEWARRAY -> {
                Ast.Expr size = pop();
                TypeInsnNode t = (TypeInsnNode) n;
                pushCat(new Ast.NewObject("L" + t.desc + ";[]", List.of(size)), 1);
            }
            case Opcodes.MULTIANEWARRAY -> {
                // multi-мерный массив: dims размеров на стеке (по порядку)
                org.objectweb.asm.tree.MultiANewArrayInsnNode t =
                        (org.objectweb.asm.tree.MultiANewArrayInsnNode) n;
                List<Ast.Expr> sizes = new ArrayList<>();
                for (int d = 0; d < t.dims; d++) sizes.add(0, pop());
                pushCat(new Ast.NewObject(t.desc, sizes), 1);
            }
            // --- касты и проверки ---
            case Opcodes.CHECKCAST -> {
                TypeInsnNode t = (TypeInsnNode) n;
                Ast.Expr v = pop();
                if ("java/lang/Object".equals(t.desc)) {
                    pushCat(v, 1); // каст в Object бессмыслен — опускаем
                } else {
                    String typeName = t.desc.startsWith("[")
                            ? Ast.friendlyType(t.desc)                 // уже дескриптор массива
                            : Ast.friendlyType("L" + t.desc + ";");    // internal-имя -> дескриптор
                    pushCat(new Ast.Cast(typeName, v), 1);
                }
            }
            case Opcodes.INSTANCEOF -> {
                TypeInsnNode t = (TypeInsnNode) n;
                Ast.Expr v = pop();
                String typeName = t.desc.startsWith("[")
                        ? Ast.friendlyType(t.desc)
                        : Ast.friendlyType("L" + t.desc + ";");
                pushCat(new Ast.Binary("instanceof", v, new Ast.Var(typeName)), 1);
            }
            // --- конвертации int/long/float/double ---
            case Opcodes.I2L -> conv("(long)", 2);
            case Opcodes.I2F -> conv("(float)", 1);
            case Opcodes.I2D -> conv("(double)", 2);
            case Opcodes.L2I -> conv("(int)", 1);
            case Opcodes.L2F -> conv("(float)", 1);
            case Opcodes.L2D -> conv("(double)", 2);
            case Opcodes.F2I -> conv("(int)", 1);
            case Opcodes.F2L -> conv("(long)", 2);
            case Opcodes.F2D -> conv("(double)", 2);
            case Opcodes.D2I -> conv("(int)", 1);
            case Opcodes.D2L -> conv("(long)", 2);
            case Opcodes.D2F -> conv("(float)", 1);
            case Opcodes.I2B -> conv("(byte)", 1);
            case Opcodes.I2C -> conv("(char)", 1);
            case Opcodes.I2S -> conv("(short)", 1);
            // --- управление ---
            case Opcodes.ATHROW -> {
                // ATHROW по JVM всегда кидает ссылку. Пустой стек здесь — потеря
                // значения (стек из другого блока/непрогружённый handler): это
                // честный отказ, а не молчаливое проглатывание throw.
                if (stack.isEmpty()) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "empty stack at ATHROW");
                }
                Ast.Expr v = pop();
                // ATHROW по JVM всегда кидает ссылку на Throwable. Если на стеке
                // примитив (число/primitive-var) — стек испорчен (catch-слот
                // переиспользован, де-флаттенинг), настоящий throw объект потерян.
                // Такой throw бессмыслен — не эмитим, но блок завершаем.
                if (isPrimitiveThrowArg(v)) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "primitive throw");
                }
                stmts.add(new Ast.ExprStmt(new Ast.Call(null, "throw", List.of(v), false)));
                terminated = true;
                stack.clear();
            }
            case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                // synchronized-семантика пока не восстанавливается: молча терять
                // монитор нельзя, это тихо неверный код.
                throw new UndecompilableException(
                        UndecompilableException.Kind.OTHER, "monitor opcode");
            }
            case Opcodes.POP -> pop();
            case Opcodes.POP2 -> {
                Boolean b1 = category2(stackAt(0));
                if (Boolean.TRUE.equals(b1)) {
                    pop();
                } else if (Boolean.FALSE.equals(b1)
                        && Boolean.FALSE.equals(category2(stackAt(1)))) {
                    pop();
                    pop();
                } else {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "POP2 category");
                }
            }
            case Opcodes.SWAP -> {
                Ast.Expr a = pop();
                Ast.Expr b = pop();
                pushCat(a, catOf(a));
                pushCat(b, catOf(b));
            }
            // --- return: берём верх стека и эмитим return ---
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> {
                Ast.Expr v = pop();
                // boolean-метод: IRETURN 0/1 -> return false/true (вместо return Literal(0))
                if (booleanReturn && n.getOpcode() == Opcodes.IRETURN) v = boolLit(v, true);
                // ARETURN с числовым литералом = потерянное значение (нельзя вернуть
                // int из объектного метода). Раньше тут фабриковался null — теперь
                // это честный отказ (provenance: null не из ACONST_NULL).
                if (n.getOpcode() == Opcodes.ARETURN && v instanceof Ast.Literal l
                        && l.value() instanceof Number) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.UNDERFLOW, "int on ARETURN");
                }
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
            case Opcodes.GOTO -> { }
            default -> {
                // Незнакомый опкод нельзя «потреблять» молча: стек разъедется,
                // и дальше получится тихо неверный код.
                throw new UndecompilableException(
                        UndecompilableException.Kind.OTHER, "unsupported opcode " + n.getOpcode());
            }
        }
    }

    /**
     * invokedynamic: StringConcatFactory -> конкатенация строк, LambdaMetafactory ->
     * ссылка на метод (best effort), прочие bootstrap (ObjectMethods и т.п.) —
     * типизированная заглушка, чтобы не ломать стек.
     */
    private void consumeIndy(org.objectweb.asm.tree.InvokeDynamicInsnNode id) {
        String bsmName = id.bsm != null ? id.bsm.getName() : "";
        int argc = argsCount(id.desc);
        List<Ast.Expr> args = new ArrayList<>();
        for (int i = 0; i < argc; i++) args.add(0, pop());

        // ObjectMethods::bootstrap — record equals/hashCode/toString.
        if (id.bsm != null && id.bsm.getOwner().contains("ObjectMethods")) {
            objectMethods(id, args);
            return;
        }
        // dev/andromeda/guard/LinkBootstrap.connect: цель вызова зашита в
        // ConstantDynamic "<owner>\0<method>" (Base64 + xorshift-поток, алгоритм
        // в самом LinkBootstrap.decode). tag 184 — INVOKESTATIC, иначе получатель
        // первый аргумент desc (connect делает findVirtual с dropParameterTypes).
        if (id.bsm != null && id.bsm.getOwner().endsWith("LinkBootstrap")
                && "connect".equals(id.bsm.getName())
                && id.bsmArgs.length >= 2
                && id.bsmArgs[0] instanceof org.objectweb.asm.ConstantDynamic cd
                && cd.getBootstrapMethod() != null
                && "symbol".equals(cd.getBootstrapMethod().getName())
                && cd.getBootstrapMethodArgumentCount() == 2
                && cd.getBootstrapMethodArgument(0) instanceof String payload
                && cd.getBootstrapMethodArgument(1) instanceof Integer key
                && id.bsmArgs[1] instanceof Integer tag) {
            String dec = decodeGuardString(payload, key);
            int nul = dec.indexOf('\0');
            if (nul > 0 && nul < dec.length() - 1) {
                String owner = dec.substring(0, nul).replace('.', '/');
                String name = dec.substring(nul + 1);
                String retDesc = id.desc.substring(id.desc.indexOf(')') + 1);
                String ownerName = Ast.friendlyType("L" + owner + ";");
                Ast.Call call;
                if (tag == 184) {
                    call = new Ast.Call(null, ownerName + "." + name, args, true, retDesc);
                } else if (!args.isEmpty()) {
                    Ast.Expr recv = args.get(0);
                    call = new Ast.Call(recv, name,
                            new ArrayList<>(args.subList(1, args.size())), false, retDesc);
                } else {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.OTHER, "guard virtual without receiver");
                }
                // void-вызов — оператор, а не значение: иначе он остаётся на стеке
                // и теряется из вывода (NativeCore.<clinit> -> load()).
                if ("V".equals(retDesc)) {
                    stmts.add(new Ast.ExprStmt(call));
                } else {
                    pushCat(call, catOfDesc(retDesc));
                }
                return;
            }
        }
        // StringConcatFactory: recipe + аргументы -> "a" + x + b ...
        if ("makeConcatWithConstants".equals(bsmName) && id.bsmArgs.length >= 1
                && id.bsmArgs[0] instanceof String recipe) {
            pushCat(buildConcat(recipe, id.bsmArgs, args), 1);
            return;
        }
        // LambdaMetafactory: ссылка на метод только без захватов. Захваченные
        // значения (args indy) в method-ref не выражаются — иначе тихо теряются.
        if ("metafactory".equals(bsmName) && id.bsmArgs.length >= 2
                && id.bsmArgs[1] instanceof org.objectweb.asm.Handle h) {
            if (!args.isEmpty()) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.OTHER, "lambda captures");
            }
            pushCat(methodRef(h), 1);
            return;
        }
        // Прочие bootstrap (ObjectMethods::bootstrap и т.п.) — типизированная заглушка.
        String ret = id.desc.substring(id.desc.indexOf(')') + 1);
        Ast.Expr ph = placeholderFor(ret);
        if (ph != null) pushCat(ph, 1);
    }

    /** ObjectMethods::bootstrap — корректные record equals/hashCode/toString. */
    private void objectMethods(org.objectweb.asm.tree.InvokeDynamicInsnNode id, List<Ast.Expr> args) {
        List<org.objectweb.asm.Handle> handles = new ArrayList<>();
        for (Object a : id.bsmArgs) {
            if (a instanceof org.objectweb.asm.Handle h) handles.add(h);
        }
        Ast.Expr recv = args.isEmpty() ? new Ast.Var("this") : args.get(0);
        String simple = classOwner != null
                ? classOwner.substring(classOwner.lastIndexOf('/') + 1) : "Record";

        if ("toString".equals(id.name)) {
            List<Ast.Expr> parts = new ArrayList<>();
            StringBuilder sb = new StringBuilder(simple).append('[');
            for (int i = 0; i < handles.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(handles.get(i).getName()).append('=');
                parts.add(lit(sb.toString()));
                sb.setLength(0);
                parts.add(component(handles.get(i), recv));
            }
            sb.append(']');
            parts.add(lit(sb.toString()));
            Ast.Expr e = parts.get(0);
            for (int i = 1; i < parts.size(); i++) e = new Ast.Binary("+", e, parts.get(i));
            pushCat(e, 1);
            return;
        }
        if ("hashCode".equals(id.name)) {
            List<Ast.Expr> fs = new ArrayList<>();
            for (var h : handles) fs.add(component(h, recv));
            pushCat(new Ast.Call(new Ast.Var("java.util.Objects"), "hash", fs, false, "I"), 1);
            return;
        }
        if ("equals".equals(id.name) && args.size() >= 2) {
            Ast.Expr other = args.get(1);
            Ast.Expr cast = new Ast.Cast(simple, other);
            Ast.Expr acc = null;
            for (var h : handles) {
                Ast.Expr cmp = new Ast.Call(new Ast.Var("java.util.Objects"), "equals",
                        List.of(component(h, recv), component(h, cast)), false, "Z");
                acc = acc == null ? cmp : new Ast.Binary("&&", acc, cmp);
            }
            Ast.Expr inst = new Ast.Binary("instanceof", other, new Ast.Var(simple));
            Ast.Expr body = acc == null ? lit(true) : new Ast.Binary("&&", inst, acc);
            pushCat(new Ast.Binary("||", new Ast.Binary("==", other, recv), body), 1);
            return;
        }
        String ret = id.desc.substring(id.desc.indexOf(')') + 1);
        Ast.Expr ph = placeholderFor(ret);
        if (ph != null) pushCat(ph, 1);
    }

    /** decode(String,int) из dev/andromeda/guard/LinkBootstrap: Base64 + xorshift32. */
    private static String decodeGuardString(String b64, int key) {
        byte[] b = java.util.Base64.getDecoder().decode(b64);
        int st = key;
        for (int i = 0; i < b.length; i++) {
            st ^= st << 13;
            st ^= st >>> 17;
            st ^= st << 5;
            b[i] ^= (byte) st;
        }
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Собирает выражение конкатенации из StringConcat-рецепта и аргументов. */
    private Ast.Expr buildConcat(String recipe, Object[] bsmArgs, List<Ast.Expr> dynArgs) {
        List<Ast.Expr> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int dyn = 0, cst = 1;
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\1') { // \u0001 — очередной динамический аргумент
                if (cur.length() > 0) { parts.add(lit(cur.toString())); cur.setLength(0); }
                if (dyn < dynArgs.size()) parts.add(dynArgs.get(dyn++));
            } else if (ch == '\2') { // \u0002 — очередная константа из bsmArgs
                if (cur.length() > 0) { parts.add(lit(cur.toString())); cur.setLength(0); }
                if (cst < bsmArgs.length && bsmArgs[cst] != null) parts.add(lit(bsmArgs[cst]));
                cst++;
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) parts.add(lit(cur.toString()));
        if (parts.isEmpty()) return lit("");
        Ast.Expr e = parts.get(0);
        for (int i = 1; i < parts.size(); i++) e = new Ast.Binary("+", e, parts.get(i));
        return e;
    }

    /** Компонент record: field-handle -> поле, method-handle -> вызов аксессора. */
    private Ast.Expr component(org.objectweb.asm.Handle h, Ast.Expr recv) {
        if (h.getTag() == Opcodes.H_GETFIELD || h.getTag() == Opcodes.H_GETSTATIC) {
            return new Ast.Field(recv, h.getName(), h.getTag() == Opcodes.H_GETSTATIC);
        }
        return new Ast.Call(recv, h.getName(), List.of(), false, h.getDesc());
    }

    /** Ссылка на метод (для LambdaMetafactory): Owner::m, this::m, Owner::new. */
    private Ast.Expr methodRef(org.objectweb.asm.Handle h) {
        String owner = Ast.friendlyType("L" + h.getOwner() + ";");
        String nm = h.getName();
        int tag = h.getTag();
        if ("<init>".equals(nm) || tag == Opcodes.H_NEWINVOKESPECIAL) {
            return new Ast.Var(owner + "::new");
        }
        if (tag == Opcodes.H_INVOKESTATIC) return new Ast.Var(owner + "::" + nm);
        return new Ast.Var("this::" + nm); // виртуальный/специальный — обычно this::m
    }

    /**
     * Неизвестный invokedynamic: раньше подставлялась типизированная заглушка
     * (0/null) без provenance. Теперь это явный отказ.
     */
    private static Ast.Expr placeholderFor(String ret) {
        if (ret.isEmpty() || "V".equals(ret)) return null;
        throw new UndecompilableException(
                UndecompilableException.Kind.OTHER, "unknown bootstrap");
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
                        pushCat(lit(s), 1);
                        return;
                    }
                }
                // ключа нет или класс не загружен — помечаем как зашифрованную строку
                pushCat(lit("<!-- enc:" + className + "#" + index + " -->"), 1);
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
            pushCat(merged, 1);
            return;
        }

        // --- конструкторный вызов на this: это super(...) или this(...) ---
        // INVOKESPECIAL <init> на ALOAD 0 внутри конструктора — вызов родительского
        // конструктора (owner != текущий класс) либо делегирование this(...).
        // Печатать this.<init>(...) невалидно — эмитим super(...)/this(...).
        if (m.name.equals("<init>") && target instanceof Ast.Var tv && "this".equals(tv.name())) {
            String keyword = classOwner != null && classOwner.equals(m.owner) ? "this" : "super";
            stmts.add(new Ast.ExprStmt(new Ast.Call(null, keyword, args, false, "V")));
            return;
        }

        // --- конструктор на локалке с отложенным new (разрывная инициализация) ---
        // new X; astore k; ...; aload k; invokespecial <init>(args) -> k = new X(args)
        if (m.name.equals("<init>") && target instanceof Ast.Var tv2 && pendingNew.containsKey(tv2.name())) {
            Ast.NewObject base = pendingNew.remove(tv2.name());
            pendingSlots.remove(tv2.name());
            stmts.add(new Ast.Assign(new Ast.Var(tv2.name()), new Ast.NewObject(base.desc(), args)));
            return;
        }

        // --- остаточный невалидный <init> на переменной/литерале (стек испорчен) ---
        // Печатать `p0.<init>(...)`, `16.0.<init>(...)` невалидно — подавляем.
        if (m.name.equals("<init>")) {
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER, "stray <init>");
        }

        String retDesc = m.desc.substring(m.desc.indexOf(')') + 1);
        Ast.Call call = new Ast.Call(target, m.name, args, m.getOpcode() == Opcodes.INVOKESTATIC, retDesc);
        // void-вызов — оператор, а не значение
        if (m.desc.endsWith(")V")) {
            stmts.add(new Ast.ExprStmt(call));
        } else {
            pushCat(call, catOfDesc(retDesc));
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

    private void bin(String op, int cat) {
        Ast.Expr r = pop();
        Ast.Expr l = pop();
        // свёртка константной арифметики: -191 + 193 -> 2 (и во всех выражениях)
        Object cv = Ast.constValue(new Ast.Binary(op, l, r));
        pushCat(cv != null ? lit(cv) : new Ast.Binary(op, l, r), cat);
    }

    /** Конвертация типа: (targetType)operand. */
    private void conv(String cast, int cat) {
        pushCat(new Ast.Unary(cast, pop()), cat);
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

    private void un(String op, int cat) {
        Ast.Expr o = pop();
        Object cv = Ast.constValue(new Ast.Unary(op, o));
        pushCat(cv != null ? lit(cv) : new Ast.Unary(op, o), cat);
    }

    /** Имя static-поля: свой класс — голое имя, чужой — Class.field. */
    private String staticFieldName(FieldInsnNode f) {
        if (classOwner != null && classOwner.equals(f.owner)) return f.name;
        return Ast.friendlyType("L" + f.owner + ";") + "." + f.name;
    }

    /** Категория значения: только структурно (при push), без эвристик по имени. */
    private Boolean category2(Ast.Expr e) {
        if (e == null) return null;
        Integer c = categories.get(e);
        return c == null ? null : (c == 2);
    }

    /** Категория уже положенного на стек значения; по умолчанию 1. */
    private int catOf(Ast.Expr e) {
        Integer c = categories.get(e);
        return c == null ? 1 : c;
    }

    /** Категория по дескриптору возвращаемого типа: J/D — 2, остальное — 1. */
    private static int catOfDesc(String desc) {
        return desc != null && (desc.startsWith("J") || desc.startsWith("D")) ? 2 : 1;
    }

    /** Значение на позиции index от вершины (0 = top). */
    private Ast.Expr stackAt(int index) {
        int k = 0;
        for (Ast.Expr e : stack) {
            if (k++ == index) return e;
        }
        return null;
    }

    private Ast.Expr pop() {
        if (stack.isEmpty()) {
            // Значение должно было прийти из другого блока (entry-stack ещё не
            // реализован) или стек реально испорчен. Фабриковать 0/null нельзя:
            // это тихо неверный код. Помечаем метод undecompilable.
            throw new UndecompilableException(UndecompilableException.Kind.UNDERFLOW, null);
        }
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
