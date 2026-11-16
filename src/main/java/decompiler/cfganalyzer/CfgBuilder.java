package decompiler.cfganalyzer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Этап 3 пайплайна декомпилятора: строит Control Flow Graph (CFG) из
 * линейного байткода {@link MethodNode}.
 *
 * <p>Алгоритм — классический «лидеры + рёбра»:
 * <ol>
 *   <li>Лидер — первая инструкция метода, цель любого перехода, первая после
 *       любого перехода. Между лидерами — базовые блоки (они же границы
 *       твоего CFF-диспетчера: каждый case — отдельный блок).</li>
 *   <li>Рёбра — по терминатору: безусловный/условный переход, switch и
 *       fall-through (падение на следующий блок). Предшественники считаются
 *       обратным проходом после построения преемников.</li>
 *   <li>Выход ({@code true}) — блок, который заканчивается return или throw,
 *       либо «висит» (нет перехода).</li>
 * </ol>
 *
 * <p>Обработка {@code try/catch}: пока рёбра строятся по обычному потоку
 * (handler'ы как преемники не учитываются) — для Этапа 6 структуру
 * try/catch придётся восстанавливать отдельно из {@code tryCatchBlocks};
 * CFG остаётся корректным для безысключительной части.
 *
 * <p>Этот CFG — основа для Этапа 4 (SSA): блок — единица слияния локальных
 * переменных, рёбра — точки вставки Phi-инструкций.
 */
public final class CfgBuilder {

    private CfgBuilder() {
    }

    /**
     * Строит CFG для метода.
     *
     * @param mn метод
     * @return список блоков в порядке их появления в байткоде (индекс блока
     *         совпадает с позицией в списке)
     */
    public static List<BasicBlock> build(MethodNode mn) {
        InsnList list = mn.instructions;
        AbstractInsnNode[] all = list.toArray();
        int n = all.length;

        // --- 1. лидеры ---
        boolean[] leader = new boolean[n];
        if (n > 0) {
            leader[0] = true;
        }
        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = all[i];
            // Инструкции-метки (LABEL/LINE/FRAME) кода не выполняют — блок
            // продолжается сквозь них, они лишь часть списка. Ничего не делаем.
            if (insn.getOpcode() < 0) {
                continue;
            }
            int op = insn.getOpcode();

            // return/throw завершают блок
            if (op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                    || op == Opcodes.DRETURN || op == Opcodes.ARETURN || op == Opcodes.RETURN
                    || op == Opcodes.ATHROW) {
                continue;
            }
            if (insn instanceof JumpInsnNode j) {
                leader[indexOfLabel(all, j.label)] = true;
                if (op != Opcodes.GOTO && i + 1 < n) {
                    // условный переход: после него возможен fall-through
                    leader[i + 1] = true;
                }
                continue;
            }
            if (insn instanceof TableSwitchInsnNode ts) {
                leader[indexOfLabel(all, ts.dflt)] = true;
                for (LabelNode l : ts.labels) leader[indexOfLabel(all, l)] = true;
                // switch всегда переходит (default покрывает все пути) — fall-through нет
                continue;
            }
            if (insn instanceof LookupSwitchInsnNode ls) {
                leader[indexOfLabel(all, ls.dflt)] = true;
                for (LabelNode l : ls.labels) leader[indexOfLabel(all, l)] = true;
                continue;
            }
            // обычная инструкция: блок продолжается, следующая НЕ становится лидером
        }

        // --- 2. границы блоков (лидеры -> [from, to)) ---
        List<Integer> leaderIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (leader[i]) leaderIdx.add(i);
        }
        if (leaderIdx.isEmpty()) {
            return List.of();
        }

        int blockCount = leaderIdx.size();
        int[] blockOf = new int[n];
        for (int b = 0; b < blockCount; b++) {
            int from = leaderIdx.get(b);
            int to = (b + 1 < blockCount) ? leaderIdx.get(b + 1) : n;
            for (int k = from; k < to; k++) blockOf[k] = b;
        }

        List<BasicBlock> blocks = new ArrayList<>(blockCount);
        for (int b = 0; b < blockCount; b++) {
            int from = leaderIdx.get(b);
            int to = (b + 1 < blockCount) ? leaderIdx.get(b + 1) : n;
            List<AbstractInsnNode> insns = new ArrayList<>(to - from);
            for (int k = from; k < to; k++) insns.add(all[k]);
            blocks.add(new BasicBlock(b, insns, new ArrayList<>(), new ArrayList<>(), new HashMap<>()));
        }

        // --- 3. преемники ---
        for (int b = 0; b < blockCount; b++) {
            int from = leaderIdx.get(b);
            int to = (b + 1 < blockCount) ? leaderIdx.get(b + 1) : n;
            int lastIdx = to - 1;
            if (lastIdx < from) continue;
            AbstractInsnNode term = all[lastIdx];
            int op = term.getOpcode();

            if (op == Opcodes.GOTO) {
                int target = blockOf[indexOfLabel(all, ((JumpInsnNode) term).label)];
                addSuccessor(blocks.get(b), blocks.get(target));
            } else if (term instanceof JumpInsnNode) {
                int target = blockOf[indexOfLabel(all, ((JumpInsnNode) term).label)];
                addSuccessor(blocks.get(b), blocks.get(target));
                addFallthrough(blocks, b, lastIdx, blockOf);
            } else if (term instanceof TableSwitchInsnNode ts) {
                BasicBlock dflt = blocks.get(blockOf[indexOfLabel(all, ts.dflt)]);
                for (LabelNode l : ts.labels) {
                    BasicBlock tgt = blocks.get(blockOf[indexOfLabel(all, l)]);
                    addSuccessor(blocks.get(b), tgt);
                    blocks.get(b).linkLabel(l, tgt);
                }
                addSuccessor(blocks.get(b), dflt);
                blocks.get(b).linkLabel(ts.dflt, dflt);
            } else if (term instanceof LookupSwitchInsnNode ls) {
                BasicBlock dflt = blocks.get(blockOf[indexOfLabel(all, ls.dflt)]);
                for (LabelNode l : ls.labels) {
                    BasicBlock tgt = blocks.get(blockOf[indexOfLabel(all, l)]);
                    addSuccessor(blocks.get(b), tgt);
                    blocks.get(b).linkLabel(l, tgt);
                }
                addSuccessor(blocks.get(b), dflt);
                blocks.get(b).linkLabel(ls.dflt, dflt);
            } else {
                // не-терминатор блока (lastIdx не переход): падение
                addFallthrough(blocks, b, lastIdx, blockOf);
            }
        }

        // --- 4. предшественники ---
        for (BasicBlock bb : blocks) {
            for (BasicBlock succ : bb.getSuccessors()) {
                succ.getPredecessors().add(bb);
            }
        }

        return blocks;
    }

    /** Выходные блоки метода (заканчивающиеся return/throw или висячие). */
    public static List<BasicBlock> exits(List<BasicBlock> blocks) {
        List<BasicBlock> out = new ArrayList<>();
        for (BasicBlock bb : blocks) {
            AbstractInsnNode term = bb.getTerminator();
            if (term == null) continue;
            int op = term.getOpcode();
            boolean isExit = op == Opcodes.IRETURN || op == Opcodes.LRETURN
                    || op == Opcodes.FRETURN || op == Opcodes.DRETURN
                    || op == Opcodes.ARETURN || op == Opcodes.RETURN
                    || op == Opcodes.ATHROW;
            if (isExit || bb.getSuccessors().isEmpty()) {
                out.add(bb);
            }
        }
        return out;
    }

    private static void addSuccessor(BasicBlock from, BasicBlock to) {
        if (!from.getSuccessors().contains(to)) {
            from.getSuccessors().add(to);
        }
    }

    /** Падение с блока b на блок b+1 (если терминатор позволяет). */
    private static void addFallthrough(List<BasicBlock> blocks, int b, int lastIdx, int[] blockOf) {
        if (lastIdx + 1 < blockOf.length) {
            int next = blockOf[lastIdx + 1];
            addSuccessor(blocks.get(b), blocks.get(next));
        }
    }

    private static int indexOfLabel(AbstractInsnNode[] all, LabelNode label) {
        for (int i = 0; i < all.length; i++) {
            if (all[i] == label) return i;
        }
        return -1;
    }
}