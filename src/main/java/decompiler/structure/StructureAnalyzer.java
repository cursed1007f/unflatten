package decompiler.structure;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.ssa.DominatorAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Анализ natural loops (Этап 5, заход 2).
 *
 * <p>Для плоского CFG находит естественные циклы: ребро {@code m -> h}, где
 * {@code h} доминирует {@code m} (back-edge). Тело цикла = {@code h} плюс все
 * узлы, из которых достижим {@code m} без прохода через {@code h} (по Теореме
 * естественного цикла: natural loop с header {@code h} и edge {@code m->h} —
 * это {@code h} + множество узлов, из которых есть путь к {@code m} в обход
 * {@code h}).
 *
 * <p>Вход принимается как список {@link BasicBlock} + предварительно построенный
 * {@link DominatorAnalyzer}; так pass'ы могут подавать плоский граф после
 * {@code CffUnflattenerPass}.
 */
public final class StructureAnalyzer {

    private final List<BasicBlock> blocks;
    private final DominatorAnalyzer dom;

    /** Создаёт анализатор для графа (используется рековером и тестами). */
    StructureAnalyzer(List<BasicBlock> blocks, DominatorAnalyzer dom) {
        this.blocks = blocks;
        this.dom = dom;
    }

    /** Результат анализа цикла. */
    public static final class Loop {
        public final int header;
        public final List<Integer> body;      // без header
        public final List<Integer> latches;   // блоки, ведущие на header мультипликативно
        public final Set<Integer> covered;    // header + body

        Loop(int header, List<Integer> body, List<Integer> latches) {
        System.err.println("LOOP h=" + header + " body=" + body + " latches=" + latches);
            this.header = header;
            this.body = body;
            this.latches = latches;
            this.covered = new LinkedHashSet<>();
            this.covered.add(header);
            this.covered.addAll(body);
        }
    }

    /**
     * Строит анализ для графа.
     *
     * @param blocks блоки (индексы = позиции в списке)
     * @param dom    доминаторный анализ этого CFG
     */
    public static StructureAnalyzer analyze(List<BasicBlock> blocks, DominatorAnalyzer dom) {
        return new StructureAnalyzer(blocks, dom);
    }

    /** Все back-edge рёбра CFG: список пар (from, to), где to доминирует from. */
    public List<int[]> backEdges() {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            int from = i;
            for (BasicBlock succ : blocks.get(i).getSuccessors()) {
                int to = succ.index();
                // to доминирует from? (строгая доминация: from != to допускаем — это само-петля)
                if (dominates(to, from)) {
                    out.add(new int[]{from, to});
                }
            }
        }
        return out;
    }

    private boolean dominates(int a, int b) {
        if (a == b) return true;
        // b доминируется a, если a находится на пути к b в dom-дереве.
        int cur = b;
        // поднимаемся от b к корню; это O(глубина) — нормально для метода.
        Set<Integer> seen = new LinkedHashSet<>();
        while (cur >= 0 && seen.add(cur)) {
            if (cur == a) return true;
            cur = dom.idom(cur);
        }
        return false;
    }

    /**
     * Находит natural loop для заданного back-edge {@code m -> h} по
     * классической теореме: body = h + все вершины, из которых достижим m
     * без прохода через h.
     */
    public Loop naturalLoop(int m, int h) {
        Set<Integer> body = new LinkedHashSet<>();
        body.add(h);
        // BFS/DFS от m в обратном направлении (по предшественникам) до h.
        // Идём по рёбрам графа от m к началу, пока не упрёмся в h.
        List<Integer> latchSeeds = new ArrayList<>();
        for (BasicBlock sb : blocks.get(h).getPredecessors()) {
            int p = sb.index();
            if (p != h && dominates(h, p)) latchSeeds.add(p);
        }
        if (!latchSeeds.contains(m)) latchSeeds.add(m);
        List<Integer> work = new ArrayList<>(latchSeeds);
        work.add(m);
        while (!work.isEmpty()) {
            int v = work.remove(work.size() - 1);
            if (v == h) continue; // не выходим за h
            if (!body.add(v)) continue;
            for (BasicBlock pred : blocks.get(v).getPredecessors()) {
                int p = pred.index();
                // включаем предшественников v, если они не за пределами h
                if (p == h) continue;
                work.add(p);
            }
        }
        body.remove(h);
        List<Integer> bodyList = new ArrayList<>(body);
        // latches — предки header'а среди body
        List<Integer> latches = new ArrayList<>();
        for (BasicBlock sb : blocks.get(h).getPredecessors()) {
            if (body.contains(sb.index())) latches.add(sb.index());
        }
        return new Loop(h, bodyList, latches);
    }

    /**
     * Возвращает все natural loops графа (по каждому back-edge), заворачивая
     * в {@link StructureNode#loop(header, body)}. Убираются дубли (один header
     * с многократными latches).
     */
    public List<StructureNode> findAllLoops() {
        List<StructureNode> loops = new ArrayList<>();
        Set<Integer> seenHeaders = new LinkedHashSet<>();
        for (int[] be : backEdges()) {
            int m = be[0], h = be[1];
            if (!seenHeaders.add(h)) continue; // один header = один цикл
            Loop lp = naturalLoop(m, h);
            loops.add(StructureNode.loop(lp.header, lp.body));
        }
        return loops;
    }
}