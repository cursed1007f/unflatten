package decompiler.ssa;

import decompiler.cfganalyzer.BasicBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Вычисляет границы доминации (dominance frontiers) для CFG.
 *
 * <p>{@code DF(b)} — множество узлов {@code x}, таких что x НЕ доминируется b,
 * но существует предшественник x, который доминируется b. Иными словами, это
 * «граница», где контроль выходит из-под доминирования b снаружи — там
 * значение, определённое в b, может сойтись со значением из другого пути.
 *
 * <p>Именно на DF опирается вставка Phi-узлов: переменная, определённая в b,
 * требует Phi-функции в каждом блоке из DF(b).
 *
 * <p>Алгоритм — Купер-Харви-Кеннеди, работает за O(|блоков|^2) в худшем
 * случае, для методов обфускатора (сотни блоков) достаточно.
 */
public final class DominanceFrontier {

    private DominanceFrontier() {
    }

    /**
     * Считает DF всех блоков.
     *
     * @param blocks блоки CFG (порядок — индексы)
     * @param dom    уже построенный доминаторный анализ
     * @return {@code result[i]} — отсортированные индексы блоков в DF блока i
     */
    public static int[][] compute(List<BasicBlock> blocks, DominatorAnalyzer dom) {
        int n = blocks.size();
        @SuppressWarnings("unchecked")
        Set<Integer>[] frontier = new Set[n];
        for (int i = 0; i < n; i++) frontier[i] = new HashSet<>();

        // Предшественники — из succs блоков.
        @SuppressWarnings("unchecked")
        Set<Integer>[] predSet = new Set[n];
        for (int i = 0; i < n; i++) predSet[i] = new HashSet<>();
        for (int i = 0; i < n; i++) {
            for (BasicBlock s : blocks.get(i).getSuccessors()) {
                predSet[s.index()].add(i);
            }
        }

        for (int b = 0; b < n; b++) {
            if (predSet[b].size() < 2) continue; // с одним предком DF пуст
            int idomB = dom.idom(b);
            for (int p : predSet[b]) {
                int runner = p;
                while (runner != idomB && runner >= 0) {
                    frontier[runner].add(b);
                    runner = dom.idom(runner);
                }
            }
        }

        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = frontier[i].stream().mapToInt(Integer::intValue).sorted().toArray();
        }
        return out;
    }
}