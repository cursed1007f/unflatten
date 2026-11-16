package decompiler.structure;

import decompiler.cfganalyzer.BasicBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Анализ пост-доминаторов поверх CFG.
 *
 * <p>{@code p} пост-доминирует {@code q}, если любой путь от {@code q} до
 * выхода метода проходит через {@code p}. Это те же доминаторы, но на
 * перевёрнутом графе: рёбра берутся в обратную сторону, а «входом» служит
 * виртуальный узел выхода (super-exit), из которого достигаются все
 * терминальные блоки.
 *
 * <p>Отдельный рендер перевёрнутого графа не нужен — достаточно при обходе
 * менять роли:  «предшественники в перевёрнутом графе» — это преемники в
 * оригинале, «преемники в перевёрнутом» — предшественники в оригинале.
 *
 * <p>Используется для:
 * <ul>
 *   <li>точек слияния ветвей (join): общий пост-доминатор двух ветвей if/else;</li>
 *   <li>back-edges циклов: {m → h}, где h пост-доминирует m не в обычном,
 *       а в исходном направлении — фактически для natural loop уже есть у
 *       {@link StructureAnalyzer}; пост-доминаторы дополняют анализ reversibility.</li>
 * </ul>
 */
public final class PostDominatorAnalyzer {

    private final List<BasicBlock> blocks;
    private final List<Integer> exits;             // терминальные блоки
    private final int[] ipdom;                     // immediate post-dominator
    private final List<List<Integer>> children;    // дерево пост-доминаторов

    private PostDominatorAnalyzer(List<BasicBlock> blocks, List<Integer> exits,
                                  int[] ipdom, List<List<Integer>> children) {
        this.blocks = blocks;
        this.exits = exits;
        this.ipdom = ipdom;
        this.children = children;
    }

    /**
     * Строит пост-доминаторный анализ.
     *
     * @param blocks блоки CFG
     * @param exits  терминальные блоки (обычно {@code CfgBuilder.exits(blocks)})
     */
    public static PostDominatorAnalyzer analyze(List<BasicBlock> blocks, List<Integer> exits) {
        int n = blocks.size();
        if (n == 0) {
            return new PostDominatorAnalyzer(blocks, exits, new int[0], List.of());
        }

        // entry в перевёрнутом графе — виртуальный супер-exit с индексом n.
        // Все реальные выходные блоки из него достижимы. Добавим его как
        // искусственный корень.
        List<Integer> allBlocks = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) allBlocks.add(i);
        allBlocks.add(n); // виртуальный супер-exit

        // Строим «предшественников в перевёрнутом графе» для каждого узла,
        // включая супер-exit: его «предки» в перевёрнутом = терминальные блоки.
        List<List<Integer>> revPreds = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) revPreds.add(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            // предшественники в перевёрнутом графе = преемники в оригинале
            for (BasicBlock s : blocks.get(i).getSuccessors()) {
                revPreds.get(i).add(s.index());
            }
        }
        // супер-exit: его «предки» — все выходные блоки
        for (int e : exits) {
            revPreds.get(n).add(e);
        }

        // Купер-Харви-Кеннеди на перевёрнутом графе.
        List<Set<Integer>> dom = new ArrayList<>(n + 1);
        Set<Integer> all = new HashSet<>(allBlocks);
        Set<Integer> entryOnly = new HashSet<>();
        entryOnly.add(n);
        for (int i = 0; i <= n; i++) {
            dom.add(i == n ? new HashSet<>(entryOnly) : new HashSet<>(all));
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int b = 0; b <= n; b++) {
                if (b == n) continue; // корень (супер-exit) не обновляем
                Set<Integer> newDom = new HashSet<>(all);
                boolean first = true;
                for (int p : revPreds.get(b)) {
                    if (first) {
                        newDom = new HashSet<>(dom.get(p));
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

        // immediate post-dominator: единственный строгий доминатор в rev
        int[] ipdomArr = new int[n];
        List<List<Integer>> ch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ch.add(new ArrayList<>());
        for (int b = 0; b < n; b++) {
            Set<Integer> strict = new HashSet<>(dom.get(b));
            strict.remove(b);
            int ip = -1;
            for (int cand : strict) {
                if (cand == n) { ip = cand; break; } // супер-exit — корень
                boolean ok = true;
                for (int other : strict) {
                    if (other != cand && !dom.get(cand).contains(other)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) { ip = cand; break; }
            }
            ipdomArr[b] = ip;
        }
        // дерево пост-доминаторов: дети (для обхода)
        for (int b = 0; b < n; b++) {
            int p = ipdomArr[b];
            if (p >= 0 && p < n) ch.get(p).add(b);
        }

        return new PostDominatorAnalyzer(blocks, exits, ipdomArr, ch);
    }

    /** Immediate post-dominator блока; n (супер-exit) означает «метод завершается». */
    public int ipdom(int blockIndex) {
        return ipdom[blockIndex];
    }

    /** Мгновенный пост-доминатор блока как объект (null, если это супер-exit). */
    public BasicBlock ipdomBlock(int blockIndex) {
        int i = ipdom[blockIndex];
        return (i >= 0 && i < blocks.size()) ? blocks.get(i) : null;
    }

    /** Дети в дереве пост-доминаторов. */
    public List<Integer> children(int blockIndex) {
        return children.get(blockIndex);
    }

    /** Пост-доминирует ли {@code a} блок {@code b} (a = пост-доминатор b)? */
    public boolean postDominates(int a, int b) {
        int cur = b;
        Set<Integer> seen = new LinkedHashSet<>();
        while (cur >= 0 && seen.add(cur)) {
            if (cur == a) return true;
            cur = ipdom[cur];
        }
        return false;
    }

    /**
     * Общий пост-доминатор двух блоков (точка слияния ветвей join), либо -1,
     * если его нет (например, ветвь ведёт прямо к exit по разным путям).
     */
    public int joinPoint(int blockA, int blockB) {
        Set<Integer> pathA = new LinkedHashSet<>();
        int cur = blockA;
        while (cur >= 0 && cur < blocks.size() && pathA.add(cur)) {
            cur = ipdom[cur];
        }
        cur = blockB;
        Set<Integer> seen = new LinkedHashSet<>();
        while (cur >= 0 && cur < blocks.size() && seen.add(cur)) {
            if (pathA.contains(cur)) return cur;
            cur = ipdom[cur];
        }
        return -1;
    }

    /** Терминальные блоки анализа (переданные на вход). */
    public List<Integer> exits() {
        return Collections.unmodifiableList(exits);
    }
}