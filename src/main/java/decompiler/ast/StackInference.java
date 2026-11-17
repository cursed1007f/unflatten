package decompiler.ast;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Входные стеки блоков (entry-stack). Источник — кадры верификатора ASM:
 * он уже считает join по правилам JVM, включая handler-входы (стек = [exc]).
 * Своя fixpoint-реализация не нужна; при провале анализа карта пуста.
 */
public final class StackInference {

    private StackInference() {
    }

    /** Входной стек по индексу инструкции (начало блока), снизу вверх. */
    public static Map<Integer, List<StackValue>> byInstruction(MethodNode mn) {
        Map<Integer, List<StackValue>> out = new HashMap<>();
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicVerifier()).analyze(mn.name, mn);
        } catch (Throwable t) {
            return out;
        }
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length && i < frames.length; i++) {
            Frame<BasicValue> f = frames[i];
            if (f == null) continue;
            List<StackValue> st = new ArrayList<>();
            boolean ok = true;
            for (int k = 0; k < f.getStackSize(); k++) {
                BasicValue v = f.getStack(k);
                Type t = v == null ? null : v.getType();
                if (t == null || t.getSort() == Type.VOID) {
                    ok = false;
                    break;
                }
                st.add(new StackValue(StackValue.Kind.EXPR,
                        Ast.friendlyType(t.getDescriptor()), -1, t.getSize()));
            }
            if (ok) out.put(i, st);
        }
        return out;
    }

    /** Входной стек блока по его первой инструкции; null — кадр недоступен. */
    public static List<StackValue> entryOf(Map<Integer, List<StackValue>> byInsn,
                                           MethodNode mn, AbstractInsnNode first) {
        if (first == null) return null;
        return byInsn.get(mn.instructions.indexOf(first));
    }
}