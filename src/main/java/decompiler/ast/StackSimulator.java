package decompiler.ast;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Абстрактный симулятор стека блока: push/pop/dup с категориями по
 * семантике {@code BytecodeToExpr.consume}, но без создания AST.
 * Нужен для exit-stack (phi-материализация на join).
 * Неизвестный опкод/недостача стека — IllegalStateException (честный отказ).
 */
public final class StackSimulator {

    private StackSimulator() {
    }

    public static List<StackValue> exit(List<AbstractInsnNode> insns) {
        Deque<StackValue> st = new ArrayDeque<>();
        for (AbstractInsnNode n : insns) {
            int op = n.getOpcode();
            if (op < 0) continue;
            switch (op) {
                case Opcodes.NOP -> { }
                case Opcodes.ACONST_NULL -> st.push(new StackValue(StackValue.Kind.LITERAL, "Object", -1, 1));
                case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH ->
                        st.push(new StackValue(StackValue.Kind.LITERAL, "int", -1, 1));
                case Opcodes.LCONST_0, Opcodes.LCONST_1 -> st.push(new StackValue(StackValue.Kind.LITERAL, "long", -1, 2));
                case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> st.push(new StackValue(StackValue.Kind.LITERAL, "float", -1, 1));
                case Opcodes.DCONST_0, Opcodes.DCONST_1 -> st.push(new StackValue(StackValue.Kind.LITERAL, "double", -1, 2));
                case Opcodes.LDC -> st.push(new StackValue(StackValue.Kind.LITERAL,
                        friendly(((LdcInsnNode) n).cst), -1, sizeOf(friendly(((LdcInsnNode) n).cst))));
                case Opcodes.ILOAD -> st.push(new StackValue(StackValue.Kind.VAR, "int", ((org.objectweb.asm.tree.VarInsnNode) n).var, 1));
                case Opcodes.LLOAD -> st.push(new StackValue(StackValue.Kind.VAR, "long", ((org.objectweb.asm.tree.VarInsnNode) n).var, 2));
                case Opcodes.FLOAD -> st.push(new StackValue(StackValue.Kind.VAR, "float", ((org.objectweb.asm.tree.VarInsnNode) n).var, 1));
                case Opcodes.DLOAD -> st.push(new StackValue(StackValue.Kind.VAR, "double", ((org.objectweb.asm.tree.VarInsnNode) n).var, 2));
                case Opcodes.ALOAD -> st.push(new StackValue(StackValue.Kind.VAR, "Object", ((org.objectweb.asm.tree.VarInsnNode) n).var, 1));
                case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> pop(st);
                case Opcodes.LSTORE, Opcodes.DSTORE -> pop(st);
                case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
                     Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR ->
                        bin(st, "int", 1);
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                     Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR ->
                        bin(st, "long", 2);
                case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM -> bin(st, "float", 1);
                case Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> bin(st, "double", 2);
                case Opcodes.INEG, Opcodes.FNEG -> { pop(st); st.push(new StackValue(StackValue.Kind.EXPR, op == Opcodes.INEG ? "int" : "float", -1, 1)); }
                case Opcodes.LNEG, Opcodes.DNEG -> { pop(st); st.push(new StackValue(StackValue.Kind.EXPR, op == Opcodes.LNEG ? "long" : "double", -1, 2)); }
                case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> conv(st, "long", 2);
                case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> conv(st, "float", 1);
                case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> conv(st, "double", 2);
                case Opcodes.L2I, Opcodes.F2I, Opcodes.D2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> conv(st, "int", 1);
                case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> { pop(st); pop(st); st.push(new StackValue(StackValue.Kind.EXPR, "int", -1, 1)); }
                case Opcodes.GETSTATIC -> {
                    FieldInsnNode f = (FieldInsnNode) n;
                    String t = Ast.friendlyType(f.desc);
                    st.push(new StackValue(StackValue.Kind.EXPR, t, -1, sizeOf(t)));
                }
                case Opcodes.GETFIELD -> { pop(st); FieldInsnNode f = (FieldInsnNode) n; String t = Ast.friendlyType(f.desc); st.push(new StackValue(StackValue.Kind.EXPR, t, -1, sizeOf(t))); }
                case Opcodes.PUTSTATIC -> pop(st);
                case Opcodes.PUTFIELD -> { pop(st); pop(st); }
                case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL,
                     Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC -> call(st, n);
                case Opcodes.NEW -> {
                    String t = Ast.friendlyType("L" + ((org.objectweb.asm.tree.TypeInsnNode) n).desc + ";");
                    st.push(new StackValue(StackValue.Kind.NEW, t, -1, 1));
                }
                case Opcodes.DUP -> st.push(st.peek());
                case Opcodes.DUP_X1 -> { StackValue a = popv(st), b = popv(st); st.push(a); st.push(b); st.push(a); }
                case Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2 ->
                        throw new IllegalStateException("dup2 form unsupported in simulator");
                case Opcodes.POP -> pop(st);
                case Opcodes.POP2 -> pop(st);
                case Opcodes.SWAP -> { StackValue a = popv(st), b = popv(st); st.push(a); st.push(b); }
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                     Opcodes.IFNULL, Opcodes.IFNONNULL -> pop(st);
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                     Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> { pop(st); pop(st); }
                case Opcodes.GOTO -> { }
                case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> pop(st);
                case Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN -> { pop(st); st.clear(); }
                case Opcodes.LRETURN, Opcodes.DRETURN -> { pop(st); st.clear(); }
                case Opcodes.RETURN -> st.clear();
                case Opcodes.ATHROW -> { pop(st); st.clear(); }
                case Opcodes.CHECKCAST -> { }
                case Opcodes.INSTANCEOF -> { pop(st); st.push(new StackValue(StackValue.Kind.EXPR, "boolean", -1, 1)); }
                case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD ->
                        { pop(st); pop(st); st.push(new StackValue(StackValue.Kind.EXPR, "int", -1, 1)); }
                case Opcodes.LALOAD, Opcodes.DALOAD -> { pop(st); pop(st); st.push(new StackValue(StackValue.Kind.EXPR, op == Opcodes.LALOAD ? "long" : "double", -1, 2)); }
                case Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> { pop(st); pop(st); pop(st); }
                case Opcodes.LASTORE, Opcodes.DASTORE -> { pop(st); pop(st); pop(st); }
                case Opcodes.ARRAYLENGTH -> { pop(st); st.push(new StackValue(StackValue.Kind.EXPR, "int", -1, 1)); }
                case Opcodes.NEWARRAY -> { pop(st); st.push(new StackValue(StackValue.Kind.NEW, "Object[]", -1, 1)); }
                case Opcodes.ANEWARRAY -> { pop(st); st.push(new StackValue(StackValue.Kind.NEW, "Object[]", -1, 1)); }
                case Opcodes.MULTIANEWARRAY -> { pop(st); st.push(new StackValue(StackValue.Kind.NEW, "Object[]", -1, 1)); }
                default -> throw new IllegalStateException("unsupported opcode " + op);
            }
        }
        List<StackValue> out = new ArrayList<>(st);
        java.util.Collections.reverse(out);
        return out;
    }

    private static void call(Deque<StackValue> st, AbstractInsnNode n) {
        String desc;
        if (n instanceof MethodInsnNode m) desc = m.desc;
        else desc = ((org.objectweb.asm.tree.InvokeDynamicInsnNode) n).desc;
        int args = argsCount(desc);
        for (int i = 0; i < args; i++) pop(st);
        if (!(n instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKESTATIC) pop(st);
        String ret = desc.substring(desc.indexOf(')') + 1);
        if (!"V".equals(ret)) {
            String t = Ast.friendlyType(ret);
            st.push(new StackValue(StackValue.Kind.EXPR, t, -1, sizeOf(t)));
        }
    }

    private static int argsCount(String desc) {
        int args = 0;
        for (Type t : Type.getArgumentTypes(desc)) args++;
        return args;
    }

    private static void bin(Deque<StackValue> st, String type, int size) {
        pop(st);
        pop(st);
        st.push(new StackValue(StackValue.Kind.EXPR, type, -1, size));
    }

    private static void conv(Deque<StackValue> st, String type, int size) {
        pop(st);
        st.push(new StackValue(StackValue.Kind.EXPR, type, -1, size));
    }

    private static StackValue popv(Deque<StackValue> st) {
        if (st.isEmpty()) throw new IllegalStateException("stack underflow");
        return st.pop();
    }

    private static void pop(Deque<StackValue> st) {
        popv(st);
    }

    private static int sizeOf(String type) {
        return switch (type) {
            case "long", "double" -> 2;
            default -> 1;
        };
    }

    private static String friendly(Object cst) {
        if (cst instanceof Integer) return "int";
        if (cst instanceof Long) return "long";
        if (cst instanceof Float) return "float";
        if (cst instanceof Double) return "double";
        if (cst instanceof String) return "java.lang.String";
        if (cst instanceof Type t) return Ast.friendlyType(t.getDescriptor());
        return "Object";
    }
}