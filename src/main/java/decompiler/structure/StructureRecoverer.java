package decompiler.structure;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.ssa.DominatorAnalyzer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Восстанавливает структуру потока управления (циклы, if/else) поверх плоского
 * графа. Работает на CFG, который уже прошёл {@code CffUnflattenerPass} (или на
 * обычном CFG) — использова проходит по блокам, находя циклы по back-edge и
 * ветвления по условным переходам с двумя преемниками.
 *
 * <p>Упрощённый, но корректный для плоского графа алгоритм:
 * <ol>
 *   <li>Найти все natural loops (back-edge, где header доминирует latch).</li>
 *   <li>Разделить блоки на loop-множества (самые внешние циклы) и прочие.</li>
 *   <li>Для обычных блоков с двумя преемниками (условный переход) и обоими
 *       преемниками вне цикла — построить if/else (или if без else).</li>
 * </ol>
 * Результат — дерево {@link StructureNode}.
 */
public final class StructureRecoverer {

    private final List<BasicBlock> blocks;
    private final DominatorAnalyzer dom;
    private final PostDominatorAnalyzer pdom;

    private StructureRecoverer(List<BasicBlock> blocks, DominatorAnalyzer dom) {
        this.blocks = blocks;
        this.dom = dom;
        this.pdom = PostDominatorAnalyzer.analyze(blocks, exitsOf(blocks));
    }

    /** Терминальные блоки графа как список индексов. */
    private static java.util.List<Integer> exitsOf(List<BasicBlock> blocks) {
        java.util.List<Integer> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            AbstractInsnNode term = blocks.get(i).getTerminator();
            if (term == null) continue;
            int op = term.getOpcode();
            if (op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                    || op == Opcodes.DRETURN || op == Opcodes.ARETURN || op == Opcodes.RETURN
                    || op == Opcodes.ATHROW) {
                out.add(i);
            }
        }
        return out;
    }

    public static StructureRecoverer forGraph(List<BasicBlock> blocks, DominatorAnalyzer dom) {
        return new StructureRecoverer(blocks, dom);
    }

    public static StructureRecoverer forGraph(List<BasicBlock> blocks) {
        return forGraph(blocks, DominatorAnalyzer.analyze(blocks));
    }

    /**
     * Как {@link #forGraph(List)}, но с явным входным блоком. Нужно после
     * CFF-де-флаттенинга, где реальный вход — не блок 0 (блок 0 часто мёртвая
     * инициализация stateVar), иначе доминаторы вырождаются и циклы не находятся.
     */
    public static StructureRecoverer forGraph(List<BasicBlock> blocks, int entry) {
        return new StructureRecoverer(blocks, DominatorAnalyzer.analyze(blocks, entry));
    }

    /**
     * Строит полное дерево структуры для входного блока.
     *
     * @param entry индекс стартового блока
     */
    public StructureNode build(int entry) {
        // Исключаем недостижимые изолированные блоки (без преемников И без
        // предшественников, не вход) — например, оставшийся после де-флаттенинга
        // CFF блок инициализации stateVar (ICONST; ISTORE; GOTO). Такой блок не
        // должен ни появляться в дереве как join/продолжение, ни эмитить
        // мёртвые присваивания iVarN = state.
        Set<Integer> finalAll = new LinkedHashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            boolean isolated = blocks.get(i).getPredecessors().isEmpty()
                    && blocks.get(i).getSuccessors().isEmpty();
            if (i == entry || !isolated) finalAll.add(i);
        }
        return buildFrom(entry, finalAll, new LinkedHashSet<>());
    }

    /**
     * Рекурсивная сборка: строит последовательность структур для подмножества
     * блоков, начиная с {@code head}. Общее множество {@code visited} защищает
     * от повторного захода в блок (и бесконечной рекурсии на циклах).
     */
    private StructureNode buildFrom(int head, Set<Integer> available, Set<Integer> visited) {
        StructureNode seq = StructureNode.sequence();
        int cur = head;
        Set<Integer> covered = new LinkedHashSet<>(); // локальные посещённые для линейных переходов
        // защита от зацикливания в самом рекурсивном построении
        while (available.contains(cur) && !visited.contains(cur) && covered.add(cur)) {

            // --- если блок — header natural loop, строим цикл ---
            int loopHdr = findLoopHeaderContaining(cur);
            if (loopHdr >= 0 && loopHdr == cur) {
                StructureAnalyzer.Loop lp = loopFor(loopHdr);
                StructureNode loopNode = StructureNode.loop(lp.header, lp.body);
                // Помечаем header как посещённый ДО рекурсии в тело: иначе тело,
                // идущее через header (предикат), снова опознает цикл и создаст
                // вложенный дубль while.
                visited.add(lp.header);
                covered.add(lp.header);
                int bodyStart = bodyStartAfterHeader(lp);
                Set<Integer> inBody = new LinkedHashSet<>(lp.covered);
                inBody.remove(lp.header);
                StructureNode bodyNode = buildFrom(bodyStart, inBody, visited);
                loopNode.addChild(bodyNode);
                seq.addChild(loopNode);
                visited.addAll(lp.covered);
                covered.addAll(lp.covered);
                cur = exitAfterLoop(lp);
                continue;
            }

            // --- обычный блок: идём по рёбрам ---
            visited.add(cur);
            seq.addChild(StructureNode.block(cur));
            BasicBlock bb = blocks.get(cur);
            // достижимые преемники в рамках available
            List<Integer> succs = new ArrayList<>();
            for (BasicBlock s : bb.getSuccessors()) {
                if (available.contains(s.index()) && !visited.contains(s.index())) succs.add(s.index());
            }
            if (succs.isEmpty()) break;                  // выход
            if (succs.size() == 1) {                     // линейный переход
                cur = succs.get(0);
            } else {
                // if/else: два преемника (условный переход)
                boolean isCond = bb.getTerminator() instanceof JumpInsnNode;
                if (succs.size() == 2 && isCond) {
                    int thenIdx = succs.get(0);
                    int elseIdx = succs.get(1);
                    // если один ведёт в цикл (back-edge), не считаем ветвлением
                    if (isBackEdge(cur, thenIdx) || isBackEdge(cur, elseIdx)) {
                        cur = succs.get(0);
                        continue;
                    }
                    StructureNode thenN = buildFrom(thenIdx, available, visited);
                    StructureNode elseN = buildFrom(elseIdx, available, visited);
                    seq.addChild(StructureNode.ifElse(cur, thenN, elseN));
                    // точка слияния ветвей — общий пост-доминатор (join); это
                    // корректнее, чем «общий преемник», т.к. рёбра могут быть
                    // непрямыми (ветка ведёт дальше по нескольким блокам).
                    visited.addAll(thenN.coveredBlocks());
                    visited.addAll(elseN.coveredBlocks());
                    int join = pdom.joinPoint(thenIdx, elseIdx);
                    cur = join >= 0 ? join : exitAfterBranch(thenIdx, elseIdx);
                } else {
                    // больше 2 преемников или не-условие: просто идём в первый
                    cur = succs.get(0);
                }
            }
        }
        return seq;
    }

    /** Есть ли в графе natural loop с header'ом, содержащим cur? */
    private int findLoopHeaderContaining(int cur) {
        List<StructureNode> loops = findAllLoopNodes();
        for (StructureNode n : loops) {
            if (isCffDispatch(n.loopHeader())) continue; // не считаем диспетчер циклом
            if (n.loopHeader() != null && n.loopBody().contains(cur)) {
                return n.loopHeader();
            }
            if (n.loopHeader() != null && n.loopHeader() == cur) {
                return n.loopHeader();
            }
        }
        return -1;
    }

    /** Признак вырезанного CFF-диспетчера: блок кончается TABLESWITCH с default на себя. */
    private boolean isCffDispatch(Integer blockIdx) {
        if (blockIdx == null || blockIdx < 0 || blockIdx >= blocks.size()) return false;
        var term = blocks.get(blockIdx).getTerminator();
        if (term instanceof org.objectweb.asm.tree.TableSwitchInsnNode ts) {
            var byLabel = blocks.get(blockIdx).successorByLabel();
            return byLabel.get(ts.dflt) != null && byLabel.get(ts.dflt).index() == blockIdx;
        }
        return false;
    }

    private List<StructureNode> findAllLoopNodes() {
        return new StructureAnalyzer(blocks, dom).findAllLoops();
    }

    private StructureAnalyzer.Loop loopFor(int header) {
        StructureAnalyzer sa = new StructureAnalyzer(blocks, dom);
        for (int[] be : sa.backEdges()) {
            if (be[1] == header) {
                return sa.naturalLoop(be[0], header);
            }
        }
        return new StructureAnalyzer.Loop(header, List.of(), List.of());
    }

    /** Точка входа в тело цикла: преемник header'а, лежащий в теле цикла
     *  (и не являющийся back-edge to header). Это блок, куда header пускает
     *  поток внутрь тела (предикат/первая операция), а не случайный элемент body. */
    private int bodyStartAfterHeader(StructureAnalyzer.Loop lp) {
        BasicBlock hdr = blocks.get(lp.header);
        for (BasicBlock s : hdr.getSuccessors()) {
            // преемник header внутри тела цикла, который ведёт назад на header
            if (lp.covered.contains(s.index()) && s.index() != lp.header) {
                return s.index();
            }
        }
        // fallback: первый элемент body
        List<Integer> body = lp.body;
        if (!body.isEmpty()) return body.get(0);
        return lp.header;
    }

    /** Блок, куда уходит поток из цикла (первый преемник header'а вне тела). */
    private int exitAfterLoop(StructureAnalyzer.Loop lp) {
        BasicBlock hdr = blocks.get(lp.header);
        for (BasicBlock s : hdr.getSuccessors()) {
            if (!lp.covered.contains(s.index())) return s.index();
        }
        return -1;
    }

    /** Ребро back-edge (to доминирует from при from!=to)? */
    private boolean isBackEdge(int from, int to) {
        return from != to && dominates(to, from);
    }

    private boolean dominates(int a, int b) {
        int cur = b;
        Set<Integer> seen = new LinkedHashSet<>();
        while (cur >= 0 && seen.add(cur)) {
            if (cur == a) return true;
            cur = dom.idom(cur);
        }
        return false;
    }

    /** Фолл-через после двух ветвей (если обе сходятся в одном выходе). */
    private int exitAfterBranch(int a, int b) {
        // простейший: преемник обеих, не принадлежащий ни одной ветви
        for (BasicBlock s : blocks.get(a).getSuccessors()) {
            if (s.index() != a && s.index() != b) return s.index();
        }
        return -1;
    }
}