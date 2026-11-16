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
    private final Ast.VarNameResolver names;
    /** Тип слота -> имя (заполняется извне анализатором типов). */
    private final String[] slotTypes;
    private final StringDecryptor decryptor; // может быть null — строки не расшифровываются

    public BytecodeToExpr(Ast.VarNameResolver names, String[] slotTypes) {
        this(names, slotTypes, null);
    }

    /** Полный конструктор с опциональным дешифратором строк. */
    public BytecodeToExpr(Ast.VarNameResolver names, String[] slotTypes, StringDecryptor decryptor) {
        this.names = names;
        this.slotTypes = slotTypes;
        this.decryptor = decryptor;
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

    private void consume(AbstractInsnNode n, boolean stopBeforeReturn) {
        if (n.getOpcode() < 0) return; // label/line/frame
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
                stack.push(new Ast.Var(name(var)));
            }
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                int var = ((VarInsnNode) n).var;
                Ast.Expr v = pop();
                Ast.Var tgt = new Ast.Var(name(var));
                stmts.add(new Ast.Assign(tgt, v));
                // стек: локальная переменная после store не остаётся на стеке,
                // но для корректных программ дальше её читают через load.
                // ничего не пушим.
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
                stack.push(new Ast.Field(null, f.name, true));
            }
            case Opcodes.PUTFIELD -> {
                Ast.Expr v = pop();
                Ast.Expr t = pop();
                FieldInsnNode f = (FieldInsnNode) n;
                stmts.add(new Ast.Assign(new Ast.Var("this." + f.name), v)); // упрощённо, field-write
            }
            // --- вызовы ---
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE ->
                    doCall((MethodInsnNode) n);
            // --- new + dup + init ---
            case Opcodes.NEW -> {
                TypeInsnNode t = (TypeInsnNode) n;
                stack.push(new Ast.NewObject(t.desc, new ArrayList<>()));
            }
            case Opcodes.DUP -> stack.push(stack.peek());
            // --- условные переходы (даем выражение, если stopBeforeReturn=false) ---
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                 Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                Ast.Expr r = pop();
                Ast.Expr l = pop();
                stack.push(new Ast.Binary(cmpOp(n.getOpcode(), true), l, r));
            }
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> {
                Ast.Expr v = pop();
                stack.push(new Ast.Binary(cmpOp(n.getOpcode(), false), v, new Ast.Literal(0)));
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
                // размерностей может быть несколько — упрощённо берём последний
                Ast.Expr size = pop();
                TypeInsnNode t = (TypeInsnNode) n;
                stack.push(new Ast.NewObject(t.desc, List.of(size)));
            }
            // --- касты и проверки ---
            case Opcodes.CHECKCAST -> {
                TypeInsnNode t = (TypeInsnNode) n;
                Ast.Expr v = pop();
                stack.push(new Ast.Call(v, "cast", List.of(new Ast.Var(Ast.friendlyType(t.desc))), false));
            }
            case Opcodes.INSTANCEOF -> {
                TypeInsnNode t = (TypeInsnNode) n;
                Ast.Expr v = pop();
                stack.push(new Ast.Binary("instanceof", v, new Ast.Var(Ast.friendlyType(t.desc))));
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
                Ast.Expr v = pop();
                stmts.add(new Ast.ExprStmt(new Ast.Call(null, "throw", List.of(v), false)));
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
                stmts.add(new Ast.Return(v));
                stack.clear(); // очистка после return — значение "протекать" не должно
            }
            case Opcodes.RETURN -> {
                stmts.add(new Ast.Return(null));
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

        Ast.Call call = new Ast.Call(target, m.name, args, m.getOpcode() == Opcodes.INVOKESTATIC);
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
        stack.push(new Ast.Binary(op, l, r));
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
        stack.push(new Ast.Unary(op, pop()));
    }

    private Ast.Expr pop() {
        if (stack.isEmpty()) return new Ast.Literal(0);
        return stack.pop();
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