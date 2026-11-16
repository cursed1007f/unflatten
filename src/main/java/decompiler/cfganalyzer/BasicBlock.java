package decompiler.cfganalyzer;

import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.List;
import java.util.Map;

/**
 * Базовый блок CFG — линейная последовательность инструкций, выполняемая
 * без ответвлений: ветвление возможно только на первой или последней
 * инструкции блока.
 *
 * <p>Хранит как сами узлы ASM ({@link #getInstructions()}), так и граф:
 * {@link #getPredecessors()} — кто входит в блок, {@link #getSuccessors()} —
 * куда поток может выйти. Терминатор блока может быть переходом, switch'ем,
 * return'ом или throw.
 */
public final class BasicBlock {

    private final int index;
    private final List<AbstractInsnNode> instructions;
    private final List<BasicBlock> predecessors;
    private final List<BasicBlock> successors;

    /** Карта преемников по операнду; для switch'ей — для случая switchInsn.labels[i]. */
    private final Map<AbstractInsnNode, BasicBlock> successorByLabel;

    BasicBlock(int index, List<AbstractInsnNode> instructions,
               List<BasicBlock> predecessors, List<BasicBlock> successors,
               Map<AbstractInsnNode, BasicBlock> successorByLabel) {
        this.index = index;
        this.instructions = instructions;
        this.predecessors = predecessors;
        this.successors = successors;
        this.successorByLabel = successorByLabel;
    }

    /** Порядковый номер блока в методе. */
    public int index() {
        return index;
    }

    /** Инструкции блока в порядке выполнения. */
    public List<AbstractInsnNode> getInstructions() {
        return instructions;
    }

    /** Блоки, из которых можно попасть в этот. */
    public List<BasicBlock> getPredecessors() {
        return predecessors;
    }

    /** Блоки, в которые можно выйти из этого. */
    public List<BasicBlock> getSuccessors() {
        return successors;
    }

    /** Запоминает преемника для конкретной метки (для switch'ей). */
    void linkLabel(AbstractInsnNode label, BasicBlock target) {
        successorByLabel.put(label, target);
    }

    /** Карта «метка switch → блок-преемник» (у пустых/обычных блоков пуста). */
    public java.util.Map<AbstractInsnNode, BasicBlock> successorByLabel() {
        return successorByLabel;
    }

    /**
     * Перестраивает граф по списку рёбер {@code (from, to)} — индексы блоков.
     * Используется после де-флаттенинга CFF, чтобы рековер структур и
     * анализаторы доминаторов видели развёрнутый (плоский) граф, а не исходный
     * зацикленный с диспетчером. Блоки, не затронутые рёбрами, изолируются.
     */
    public static void rebuildEdges(List<BasicBlock> blocks, List<int[]> edges) {
        for (BasicBlock b : blocks) {
            b.successors.clear();
            b.predecessors.clear();
        }
        for (int[] e : edges) {
            BasicBlock src = blocks.get(e[0]);
            BasicBlock dst = blocks.get(e[1]);
            src.successors.add(dst);
            dst.predecessors.add(src);
        }
    }

    /** Первая (не-метка/не-line) инструкция блока; {@code null}, если блок пуст. */
    public AbstractInsnNode getFirstReal() {
        for (AbstractInsnNode n : instructions) {
            if (n.getOpcode() >= 0) return n;
        }
        return null;
    }

    /** Последняя инструкция блока (терминатор); {@code null}, если блок пуст. */
    public AbstractInsnNode getTerminator() {
        return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1);
    }

    @Override
    public String toString() {
        return "BB" + index;
    }
}