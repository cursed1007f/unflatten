package decompiler.ssa;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.cfganalyzer.CfgBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Доминаторный анализ для Этапа 4 (SSA).
 *
 * <p>Определения на графе {@code block i доминирует block j} (i dom j), если
 * любой путь от входа до j проходит через i. {@code i} — immediate dominator
 * (idom) узла {@code j}, если i доминирует j и не существует никакого другого
 * узла d (отличного от i и j), который доминирует j и сам доминируется i.
 *
 * <p>Используется классический алгоритм Купера-Харви-Кеннеди (итеративное
 * уточнение dom-множеств). Это корректно и достаточно быстро для методов
 * уровня CFF твоего обфускатора (диспетчер + случаи), где число блоков
 * ограничено сотнями.
 *
 * <p>Выходы: дерево доминаторов ({@link #idom()}), мгновенные доминаторы,
 * множество детей в дереве ({@link #children()}) для обхода в глубину.
 */
public final class DominatorAnalyzer {

    private final List<BasicBlock> blocks;
    private final BasicBlock entry;
    private final int[] idom;             // idom[i] = индекс immediate dominator, -1 для entry
    private final List<List<Integer>> children; // дети в дереве доминаторов

    private DominatorAnalyzer(List<BasicBlock> blocks, BasicBlock entry,
                              int[] idom, List<List<Integer>> children) {
        this.blocks = blocks;
        this.entry = entry;
        this.idom = idom;
        this.children = children;
    }

    /** Строит доминаторный анализ на CFG метода (вход — блок 0). */
    public static DominatorAnalyzer analyze(List<BasicBlock> blocks) {
        return analyze(blocks, 0);
    }

    /**
     * Строит доминаторный анализ с явным входным блоком. Важно после CFF-де-флаттенинга:
     * реальный вход может быть не блоком 0 (блок 0 — мёртвая инициализация state).
     */
    public static DominatorAnalyzer analyze(List<BasicBlock> blocks, int entryIdx) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("CFG пуст");
        }
        int n = blocks.size();
        if (entryIdx < 0 || entryIdx >= n) entryIdx = 0;
        BasicBlock entry = blocks.get(entryIdx);

        // preds[i] и succs[i] по индексам блоков
        List<List<Integer>> preds = new ArrayList<>(n);
        List<List<Integer>> succs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            preds.add(new ArrayList<>());
            succs.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (BasicBlock s : blocks.get(i).getSuccessors()) {
                succs.get(i).add(s.index());
                preds.get(s.index()).add(i);
            }
        }

        // Итеративное уточнение dom-множеств (Cooper-Harvey-Kennedy).
        // dom[i] — множество индексов узлов, доминирующих i.
        List<Set<Integer>> dom = new ArrayList<>(n);
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < n; i++) all.add(i);
        for (int i = 0; i < n; i++) {
            dom.add(new HashSet<>(i == entryIdx ? Collections.singleton(entryIdx) : all));
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int b = 0; b < n; b++) {
                if (b == entryIdx) continue;
                Set<Integer> newDom = new HashSet<>(all);
                boolean first = true;
                for (int p : preds.get(b)) {
                    if (first) {
                        newDom.retainAll(dom.get(p));
                        first = false;
                    } else {
                        newDom.retainAll(dom.get(p));
                    }
                }
                if (newDom.isEmpty()) newDom.add(b);
                newDom.add(b);
                if (!newDom.equals(dom.get(b))) {
                    dom.set(b, newDom);
                    changed = true;
                }
            }
        }

        // idom: единственный строгий доминатор b в dom[b]\{b}, который доминирует все остальные.
        int[] idomArr = new int[n];
        idomArr[entryIdx] = -1;
        for (int b = 0; b < n; b++) {
            if (b == entryIdx) continue;
            Set<Integer> strict = new HashSet<>(dom.get(b));
            strict.remove(b);
            int id = -1;
            for (int cand : strict) {
                // cand — кандидат: проверим, что он доминирует каждую другую вершину из strict
                boolean ok = true;
                for (int other : strict) {
                    if (other != cand && !dom.get(cand).contains(other)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    id = cand;
                    break;
                }
            }
            idomArr[b] = id;
        }

        // дерево детей
        List<List<Integer>> ch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ch.add(new ArrayList<>());
        for (int b = 0; b < n; b++) {
            if (idomArr[b] >= 0) ch.get(idomArr[b]).add(b);
        }

        return new DominatorAnalyzer(blocks, entry, idomArr, ch);
    }

    /** Индекс immediate dominator блока; -1 для входного блока. */
    public int idom(int blockIndex) {
        return idom[blockIndex];
    }

    /** Мгновенный доминатор блока; {@code null} для входного блока. */
    public BasicBlock idomBlock(int blockIndex) {
        int i = idom[blockIndex];
        return i < 0 ? null : blocks.get(i);
    }

    /** Дети блока в дереве доминаторов. */
    public List<Integer> children(int blockIndex) {
        return children.get(blockIndex);
    }

    /** Входной блок CFG. */
    public BasicBlock entryBlock() {
        return entry;
    }

    /**
     * Возвращает блоки в порядке предобхода дерева доминаторов (parent прежде
     * всех descendants) — удобно для обхода, где надо обработать доминатор раньше
     * зависящих от него узлов.
     */
    public List<BasicBlock> dominatorTreePreorder() {
        List<BasicBlock> out = new ArrayList<>(blocks.size());
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        stack.push(0); // entry — корень дерева
        while (!stack.isEmpty()) {
            int v = stack.pop();
            out.add(blocks.get(v));
            List<Integer> kids = children.get(v);
            for (int k = kids.size() - 1; k >= 0; k--) {
                stack.push(kids.get(k));
            }
        }
        return out;
    }
}