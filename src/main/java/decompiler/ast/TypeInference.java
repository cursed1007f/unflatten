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
        return infer(mn, isStatic, classOwner, null);
    }

    public static String[] infer(MethodNode mn, boolean isStatic, String classOwner,
                                 ClassHierarchy hierarchy) {
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
        // SimpleVerifier плохо наследуется (его конструктор кидает
        // IllegalStateException для любого подкласса — защита от наследования),
        // поэтому используем BasicVerifier: он без рефлексии и даёт точный
        // тип ссылочного значения через getType(). Если и он падает —
        // фолбэк на BasicInterpreter (примитивы и базовые объекты).
        Frame<BasicValue>[] frames = tryAnalyze(mn, new org.objectweb.asm.tree.analysis.BasicVerifier());
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
        refineBooleans(mn, out);
        return out;
    }

    /**
     * Эвристика boolean-локальных: слот int-типа считаем boolean, если он только
     * пишется литералами 0/1 и только используется как условие (ILOAD прямо перед
     * IFEQ/IFNE). Тогда {@code iVar != 0} эквивалентно {@code iVar}, и печать
     * {@code if (iVar)} корректнее. Трансформация безопасна: локальные в выводе
     * не объявляются, а значения в таких местах эквивалентны.
     */
    private static void refineBooleans(MethodNode mn, String[] types) {
        if (mn == null || mn.instructions == null || types == null) return;
        AbstractInsnNode[] insns = mn.instructions.toArray();
        int n = types.length;
        boolean[] ok = new boolean[n];
        boolean[] hasCond = new boolean[n];
        boolean[] bad = new boolean[n];
        java.util.Arrays.fill(ok, true);

        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            if (insn instanceof org.objectweb.asm.tree.IincInsnNode inc) {
                if (inc.var >= 0 && inc.var < n) bad[inc.var] = true;
                continue;
            }
            if (!(insn instanceof VarInsnNode v)) continue;
            int s = v.var;
            if (s < 0 || s >= n) continue;
            int op = v.getOpcode();
            if (op == Opcodes.ISTORE) {
                AbstractInsnNode prev = prevReal(insns, i);
                boolean lit = prev instanceof org.objectweb.asm.tree.InsnNode pi
                        && (pi.getOpcode() == Opcodes.ICONST_0 || pi.getOpcode() == Opcodes.ICONST_1);
                if (!lit && prev instanceof org.objectweb.asm.tree.IntInsnNode ii
                        && (ii.getOpcode() == Opcodes.BIPUSH || ii.getOpcode() == Opcodes.SIPUSH)
                        && (ii.operand == 0 || ii.operand == 1)) {
                    lit = true;
                }
                if (!lit) ok[s] = false;
            } else if (op == Opcodes.ILOAD) {
                AbstractInsnNode next = nextReal(insns, i);
                if (next instanceof org.objectweb.asm.tree.JumpInsnNode j
                        && (j.getOpcode() == Opcodes.IFEQ || j.getOpcode() == Opcodes.IFNE)) {
                    hasCond[s] = true;
                } else {
                    bad[s] = true;
                }
            } else if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) {
                ok[s] = false;
            }
        }
        for (int s = 0; s < n; s++) {
            if (ok[s] && hasCond[s] && !bad[s] && "int".equals(types[s])) {
                types[s] = "boolean";
            }
        }
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode[] insns, int i) {
        for (int k = i - 1; k >= 0; k--) if (insns[k].getOpcode() >= 0) return insns[k];
        return null;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode[] insns, int i) {
        for (int k = i + 1; k < insns.length; k++) if (insns[k].getOpcode() >= 0) return insns[k];
        return null;
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