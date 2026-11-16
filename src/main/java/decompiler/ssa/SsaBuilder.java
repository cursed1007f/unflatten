package decompiler.ssa;

import decompiler.cfganalyzer.BasicBlock;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Строит SSA-форму поверх CFG, не мутируя исходный байткод.
 *
 * <p>Алгоритм классический (по Сайру, Ферранте и др.):
 * <ol>
 *   <li>Находим слоты, которые вообще пишутся (store/iinc). Версии параметров
 *       ({@code 0}=this и слоты параметров) считаются определёнными на входе
 *       в версии 0.</li>
 *   <li>Вставка Phi: для каждого слота с определениями добавляем Phi-узел в
 *       каждый блок из границы доминации любого определяющего блока
 *       (worklist-алгоритм по {@link DominanceFrontier}).</li>
 *   <li>Ренейминг: DFS по дереву доминаторов. Для каждого слота ведём стек
 *       версий; при входе в блок Phi назначает новую версию, каждая store/iinc
 *       открывает новую версию, каждый load читает текущую версию. На выходе
 *       из блока стек откатывается.</li>
 * </ol>
 *
 * <p>Результат — «полная» структурная SSA без улучшения dead-Phi (не удаляются
 * тривиальные Phi) — этого достаточно для Этапа 5.
 */
public final class SsaBuilder {

    private final List<BasicBlock> blocks;
    private final int maxLocals;
    private final List<List<SsaVar.Insn>> blockInsns;
    private final List<List<SsaVar.Phi>> blockPhis;

    private SsaBuilder(List<BasicBlock> blocks, int maxLocals,
                       List<List<SsaVar.Insn>> blockInsns,
                       List<List<SsaVar.Phi>> blockPhis) {
        this.blocks = blocks;
        this.maxLocals = maxLocals;
        this.blockInsns = blockInsns;
        this.blockPhis = blockPhis;
    }

    /**
     * Строит SSA-форму для метода по его CFG.
     *
     * @param blocks    блоки CFG (индексы = позиции в списке)
     * @param maxLocals число локальных слотов метода
     */
    public static SsaBuilder build(List<BasicBlock> blocks, int maxLocals) {
        int n = blocks.size();
        List<List<SsaVar.Insn>> insns = new ArrayList<>(n);
        List<List<SsaVar.Phi>> phis = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            insns.add(new ArrayList<>());
            phis.add(new ArrayList<>());
        }

        // Какие слоты реально пишутся (кроме параметров/this).
        boolean[] written = new boolean[maxLocals];
        for (BasicBlock bb : blocks) {
            for (AbstractInsnNode insn : bb.getInstructions()) {
                Integer slot = writtenSlot(insn);
                if (slot != null) written[slot] = true;
            }
        }

        DominatorAnalyzer dom = DominatorAnalyzer.analyze(blocks);
        int[][] frontier = DominanceFrontier.compute(blocks, dom);

        // --- Вставка Phi ---
        // Для каждого пишущегося слота: Phi следует поставить в DF каждого
        // блока, где есть определение (store). Worklist по DF.
        boolean[] phiInserted = new boolean[n];
        for (int slot = 0; slot < maxLocals; slot++) {
            if (!written[slot]) continue;
            ArrayDeque<Integer> work = new ArrayDeque<>();
            for (int b = 0; b < n; b++) {
                if (defines(blocks.get(b), slot)) work.push(b);
            }
            boolean[] hasPhi = new boolean[n];
            while (!work.isEmpty()) {
                int b = work.pop();
                for (int df : frontier[b]) {
                    if (!hasPhi[df]) {
                        hasPhi[df] = true;
                        phis.get(df).add(new SsaVar.Phi(slot, predIndexes(blocks, df)));
                        work.push(df);
                    }
                }
            }
        }

        // --- Ренейминг ---
        // Стеки версий по слотам.
        List<ArrayDeque<Integer>> stacks = new ArrayList<>(maxLocals);
        for (int i = 0; i < maxLocals; i++) {
            ArrayDeque<Integer> st = new ArrayDeque<>();
            st.push(0);
            stacks.add(st);
        }

        rename(dom, blocks, phis, insns, stacks, 0);
        return new SsaBuilder(blocks, maxLocals, insns, phis);
    }

    // ------------------------------------------------------------------ data

    /** Операторы блока в порядке исполнения. */
    public List<SsaVar.Insn> insns(int blockIndex) {
        return blockInsns.get(blockIndex);
    }

    /** Phi-функции блока (в начале). */
    public List<SsaVar.Phi> phis(int blockIndex) {
        return blockPhis.get(blockIndex);
    }

    /** Число локальных слотов. */
    public int maxLocals() {
        return maxLocals;
    }

    // ------------------------------------------------------------------ ренейминг

    private static void rename(DominatorAnalyzer dom, List<BasicBlock> blocks,
                               List<List<SsaVar.Phi>> phis, List<List<SsaVar.Insn>> insns,
                               List<ArrayDeque<Integer>> stacks, int blockIndex) {
        // 1) Phi-результаты блока: каждая Phi открывает новую версию слота.
        for (SsaVar.Phi phi : phis.get(blockIndex)) {
            int slot = phi.slot();
            int newVer = top(stacks, slot) + 1;
            stacks.get(slot).push(newVer);
            phi.setResult(new SsaVar(slot, newVer));
        }

        // 2) Инструкции блока.
        for (AbstractInsnNode asm : blocks.get(blockIndex).getInstructions()) {
            if (asm instanceof VarInsnNode v) {
                int op = v.getOpcode();
                if (op == Opcodes.ISTORE || op == Opcodes.LSTORE || op == Opcodes.FSTORE
                        || op == Opcodes.DSTORE || op == Opcodes.ASTORE) {
                    int newVer = top(stacks, v.var) + 1;
                    stacks.get(v.var).push(newVer);
                    insns.get(blockIndex).add(new SsaVar.Insn(opName(op), new SsaVar(v.var, newVer), List.of()));
                } else if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
                    insns.get(blockIndex).add(new SsaVar.Insn(opName(op), null, List.of(new SsaVar(v.var, current(stacks, v.var)))));
                }
            } else if (asm instanceof IincInsnNode i) {
                int newVer = top(stacks, i.var) + 1;
                stacks.get(i.var).push(newVer);
                insns.get(blockIndex).add(new SsaVar.Insn("iinc", new SsaVar(i.var, newVer), List.of(new SsaVar(i.var, newVer - 1), intConst(i.incr))));
            }
        }

        // 3) Заполняем Phi-аргументы в преемниках текущими версиями на рёбрах.
        for (BasicBlock succ : blocks.get(blockIndex).getSuccessors()) {
            int si = succ.index();
            for (SsaVar.Phi phi : phis.get(si)) {
                int predPos = phi.preds().indexOf(blockIndex);
                if (predPos >= 0) {
                    phi.setArg(predPos, new SsaVar(phi.slot(), current(stacks, phi.slot())));
                }
            }
        }

        // 4) DFS по дереву доминаторов.
        for (int child : dom.children(blockIndex)) {
            rename(dom, blocks, phis, insns, stacks, child);
        }

        // 5) Откат версий Phi и stores блока.
        for (SsaVar.Phi phi : phis.get(blockIndex)) {
            stacks.get(phi.slot()).pop();
        }
        for (AbstractInsnNode asm : blocks.get(blockIndex).getInstructions()) {
            Integer slot = writtenSlot(asm);
            if (slot != null) stacks.get(slot).pop();
        }
    }

    private static int top(List<ArrayDeque<Integer>> stacks, int slot) {
        return stacks.get(slot).peek();
    }

    private static int current(List<ArrayDeque<Integer>> stacks, int slot) {
        return stacks.get(slot).peek();
    }

    private static String opName(int op) {
        try {
            return org.objectweb.asm.util.Printer.OPCODES[op];
        } catch (Exception e) {
            return "op_" + op;
        }
    }

    /** Индексы блоков-предшественников блока. */
    private static List<Integer> predIndexes(List<BasicBlock> blocks, int blockIndex) {
        List<Integer> preds = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            for (BasicBlock s : blocks.get(i).getSuccessors()) {
                if (s.index() == blockIndex) {
                    preds.add(i);
                    break;
                }
            }
        }
        return preds;
    }

    /** Слот, который пишет инструкция; {@code null}, если не пишет. */
    private static Integer writtenSlot(AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode v) {
            int op = v.getOpcode();
            if (op == Opcodes.ISTORE || op == Opcodes.LSTORE || op == Opcodes.FSTORE
                    || op == Opcodes.DSTORE || op == Opcodes.ASTORE) return v.var;
        } else if (insn instanceof IincInsnNode i) {
            return i.var;
        }
        return null;
    }

    /** Есть ли в блоке определение слота (store/iinc). */
    private static boolean defines(BasicBlock bb, int slot) {
        for (AbstractInsnNode insn : bb.getInstructions()) {
            Integer s = writtenSlot(insn);
            if (s != null && s == slot) return true;
        }
        return false;
    }

    private static SsaVar intConst(int v) {
        return new SsaVar(Integer.MIN_VALUE, v); // фиктивная: как литерал int
    }
}