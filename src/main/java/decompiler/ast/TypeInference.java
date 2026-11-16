package decompiler.ast;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

/**
 * Вывод типов локальных слотов метода.
 *
 * <p>Точнее фильтра типа, чем входной кадр BasicInterpreter: анализирует все
 * фреймы через {@link SimpleVerifier}, а тип слота берёт по месту записи
 * (store) — из кадра в точке инструкции. Параметры типизируются строго по
 * дескриптору метода.
 *
 * <p>Результат — {@code String[]} дружественных имён типов по номеру слота
 * (например {@code "int"}, {@code "java.lang.String"}, {@code "String[]"}).
 */
public final class TypeInference {

    private TypeInference() {
    }

    /**
     * Выводит типы слотов.
     *
     * @param mn       метод
     * @param isStatic флаг static (слот 0 = this у instance)
     */
    public static String[] infer(MethodNode mn, boolean isStatic, String classOwner) {
        int max = mn.maxLocals;
        String[] out = new String[max];
        for (int i = 0; i < max; i++) out[i] = "Object";

        // параметры по дескриптору
        int slot = 0;
        if (!isStatic) {
            String owner = classOwner != null ? classOwner.replace('/', '.') : "Object";
            out[0] = owner;
            slot = 1;
        }
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            String nm = friendly(t);
            out[slot] = nm;
            slot += t.getSize();
        }

        // локальные: тип по store-точке из кадров.
        // сначала пробуем SimpleVerifier (точные типы, но требует resolver и
        // обычно падает на незнакомых классах), затем — BasicInterpreter
        // (примитивы и базовые объекты без resolver).
        Frame<BasicValue>[] frames = tryAnalyze(mn, new SimpleVerifier());
        if (frames == null) frames = tryAnalyze(mn, new org.objectweb.asm.tree.analysis.BasicInterpreter());
        if (frames != null) {
            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (int i = 0; i < insns.length && i < frames.length; i++) {
                Frame<BasicValue> f = frames[i];
                AbstractInsnNode n = insns[i];
                if (f == null || !(n instanceof VarInsnNode v)) continue;
                int op = v.getOpcode();
                if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) {
                    // frames[i] — состояние ДО insns[i]; при store значение на
                    // вершине стека. Берём его тип (надёжнее, чем локаль после).
                    BasicValue val = null;
                    try {
                        int sz = f.getStackSize();
                        if (sz > 0) val = f.getStack(sz - 1);
                    } catch (Exception ignored) {
                    }
                    if (val == null || val.getType() == null) {
                        try { val = f.getLocal(v.var); } catch (Exception ignored) { }
                    }
                    if (val != null && val.getType() != null) {
                        out[v.var] = friendly(val.getType());
                    }
                }
            }
        }
        return out;
    }

    /** Пробует анализ фреймов настоящего метода; null, если упал. */
    private static Frame<BasicValue>[] tryAnalyze(MethodNode mn,
            org.objectweb.asm.tree.analysis.Interpreter<BasicValue> interp) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(interp);
            return analyzer.analyze(mn.name, mn);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Дружественное имя типа по ASM-типу. */
    private static String friendly(Type t) {
        if (t == null) return "Object";
        if (t.getSort() == Type.ARRAY) {
            return friendly(t.getElementType()) + "[]";
        }
        if (t.getSort() == Type.OBJECT) {
            return t.getClassName();
        }
        return switch (t.getSort()) {
            case Type.INT -> "int";
            case Type.LONG -> "long";
            case Type.FLOAT -> "float";
            case Type.DOUBLE -> "double";
            case Type.BOOLEAN -> "boolean";
            case Type.BYTE -> "byte";
            case Type.SHORT -> "short";
            case Type.CHAR -> "char";
            default -> "Object";
        };
    }
}