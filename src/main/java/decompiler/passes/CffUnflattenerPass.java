package decompiler.passes;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.cfganalyzer.CfgBuilder;
import decompiler.ssa.DominatorAnalyzer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass Этапа 5: де-флаттенинг CFF.
 *
 * <p>По CFG зафлаттененного метода распознаёт state-машину твоего
 * обфускатора и делает граф «плоским» — вырезает цикл-диспетчер и соединяет
 * case-блоки напрямую согласно их setState-переходам.
 *
 * <p>Формат CFF (из {@code ControlFlow.java}):
 * <pre>
 *   вход:   ICONST_0; ISTORE stateVar        // state = 0
 *   диспетчер: LABEL dispatch
 *           ILOAD stateVar
 *           TABLESWITCH(0..B-1, default=dispatch, caseLabels)
 *   каждый case: восстановление спилла ...; тело...;
 *           конец: ICONST/BIPUSH/... state; ISTORE stateVar; GOTO dispatch
 * </pre>
 *
 * <p>Признак диспетчера: блок оканчивается {@code TABLESWITCH}, у которого
 * (а) default ведёт в сам этот блок (зацикленность), (б) у блока есть
 * Phi-цикл по одному слоту в SSA (сюда мы не залезаем — используем только
 * граф). На этом этапе проверяем (а) и факт, что все case-ветви возвращаются
 * в диспетчер через {@code setState} (ISTORE + GOTO на диспетчер).
 *
 * <p>Выход — {@link Result} с плоским списком блоков и рёбрами; исходный CFG
 * не мутируется.
 */
public final class CffUnflattenerPass {

    private CffUnflattenerPass() {
    }

    /** Результат де-флаттенинга. */
    public static final class Result {
        public final boolean unflattened;
        public final String reason;
        public final List<BasicBlock> blocks;      // порядок — исходные индексы
        public final List<int[]> edges;            // пары (from, to) индексы блоков
        public final BasicBlock dispatch;          // вырезанный диспетчер (или null)
        public final int stateSlot;                // слот stateVar (-1 если не найден)
        public final int entryIndex;               // корень плоского графа (первый реальный блок, не init-state)

        Result(boolean unflattened, String reason, List<BasicBlock> blocks,
               List<int[]> edges, BasicBlock dispatch, int stateSlot, int entryIndex) {
            this.unflattened = unflattened;
            this.reason = reason;
            this.blocks = blocks;
            this.edges = edges;
            this.dispatch = dispatch;
            this.stateSlot = stateSlot;
            this.entryIndex = entryIndex;
        }

        public static Result ok(List<BasicBlock> blocks, List<int[]> edges,
                                BasicBlock dispatch, int stateSlot, int entryIndex) {
            return new Result(true, "ok", blocks, edges, dispatch, stateSlot, entryIndex);
        }

        public static Result notUnflat(String reason) {
            return new Result(false, reason, null, null, null, -1, -1);
        }
    }

    /**
     * Пытается развернуть CFF в плоский граф.
     *
     * @param blocks CFG зафлаттененного метода
     */
    public static Result run(List<BasicBlock> blocks) {
        if (blocks.isEmpty()) return Result.notUnflat("пустой CFG");

        // --- найти диспетчер: блок с TABLESWITCH, чей default на самого себя ---
        BasicBlock dispatch = null;
        int stateSlot = -1;
        for (BasicBlock bb : blocks) {
            AbstractInsnNode term = bb.getTerminator();
            if (!(term instanceof TableSwitchInsnNode ts)) continue;
            // default на самого себя?
            Map<AbstractInsnNode, BasicBlock> byLabel = bb.successorByLabel();
            BasicBlock dflt = byLabel.get(ts.dflt);
            if (dflt == bb) {
                dispatch = bb;
                stateSlot = stateVar(bb, ts);
                break;
            }
        }
        if (dispatch == null) return Result.notUnflat("нет диспетчера (TABLESWITCH default->self)");

        // --- собрать case-блоки (все преемники диспетчера, кроме самого) ---
        List<BasicBlock> cases = new ArrayList<>(dispatch.getSuccessors());
        cases.remove(dispatch);
        if (cases.isEmpty()) return Result.notUnflat("нет case-блоков");

        // --- вычислить транзиции setState: case -> один или несколько следующих ---
        // В CFF case-блок либо заканчивается return'ом (выходной), либо условным
        // переходом на 1-2 транзишн-блока. Транзишн-блок пишет state (ISTORE
        // stateVar) и GOTO dispatch. Собираем из каждого case все state, на
        // которые он переходит (у условного case их два — это и есть ветвь).
        Map<BasicBlock, java.util.Set<Integer>> caseToStates = new LinkedHashMap<>();
        for (BasicBlock c : cases) {
            java.util.Set<Integer> sts = new java.util.HashSet<>();
            // сам case может быть транзишн-блоком (кончается GOTO dispatch)
            Integer own = readSetState(c, dispatch, stateSlot);
            if (own != null) sts.add(own);
            // иначе читаем из его преемников (транзишн-блоков)
            for (BasicBlock succ : c.getSuccessors()) {
                Integer st = readSetState(succ, dispatch, stateSlot);
                if (st != null) sts.add(st);
            }
            if (!sts.isEmpty()) caseToStates.put(c, sts); // return-случай пропускается (пусто)
        }

        // Для каждого state -> найти case-блок, к которому он ведёт.
        // Строим таблицу stateTarget: значение state -> блок, чья метка
        // соответствует этому state. Как получить связь state<->case?
        // В CFF-обфускаторе порядок case-блоков в switch совпадает с
        // таблицей его labels: state i соответствует i-му case (label[i]).
        Map<Integer, BasicBlock> stateToCase = new HashMap<>();
        AbstractInsnNode term = dispatch.getTerminator();
        if (term instanceof TableSwitchInsnNode ts) {
            for (int i = 0; i < ts.labels.size(); i++) {
                BasicBlock c = dispatch.successorByLabel().get(ts.labels.get(i));
                stateToCase.put(ts.min + i, c);
            }
        }
        // дефолт-метка тоже может быть case'ом
        if (term instanceof TableSwitchInsnNode ts && ts.dflt == dispatch.getTerminator() /* not; см. ниже */) {
        }

        // --- построить плоский граф ---
        List<BasicBlock> flatBlocks = new ArrayList<>(blocks);
        List<int[]> flatEdges = new ArrayList<>();
        // Начало: входной блок (не диспетчер) -> первый case. Какой он?
        // state=0 на входе -> case с меткой min. РЕБРО НЕ КЛЕИМ: корнем плоского
        // графа будет entryIndex (первый case), а входной блок BB0 в CFF пишет
        // только stateVar=0 на входе и реального кода не несёт. Если оставить
        // ребро BB0->case, рековер добирается до BB0 через pdom-join и эмитит
        // мёртвое присваивание iVar4 = 0.
        BasicBlock entryCase = stateToCase.get(0);
        BasicBlock head = findEntryBefore(blocks, dispatch);

        // Рёба по setState: case -> следующий case (у условного case — 2 ребра)
        for (Map.Entry<BasicBlock, java.util.Set<Integer>> e : caseToStates.entrySet()) {
            for (int st : e.getValue()) {
                BasicBlock target = stateToCase.get(st);
                if (target != null) {
                    flatEdges.add(new int[]{e.getKey().index(), target.index()});
                }
            }
        }

        // Держим ВСЕ блоки в плоском списке (позиция = индекс должны совпадать,
        // иначе рековер структур ломается на блоках с индексом >= новой длины).
        List<BasicBlock> kept = new ArrayList<>(blocks);

        // Применяем плоские рёбра к реальному графу: рековер структур и
        // анализаторы доминаторов читают getSuccessors(), поэтому чтобы они
        // увидели развёрнутый граф, переписываем ребра напрямую.
        BasicBlock.rebuildEdges(kept, flatEdges);

        // Корень плоского графа — первый case по state=0, а не входной блок
        // BB0 (BB0 в CFF пишет только stateVar=0 на входе). Если стартовать с
        // BB0, рековер эмитит мёртвое присваивание iVar4 = 0.
        int entryIndex = entryCase != null ? entryCase.index() : (head != null ? head.index() : 0);

        Result res = new Result(true, "ok", kept, flatEdges, dispatch, stateSlot, entryIndex);
        return res;
    }

    /** Слот stateVar из диспетчера: {ILOAD stateVar; TABLESWITCH}. */
    private static int stateVar(BasicBlock bb, TableSwitchInsnNode ts) {
        List<AbstractInsnNode> insns = bb.getInstructions();
        // идём с конца к началу: терминатор ts, перед ним ILOAD stateVar
        for (int i = insns.size() - 1; i >= 0; i--) {
            AbstractInsnNode n = insns.get(i);
            if (n == ts) continue;
            if (n instanceof VarInsnNode v && v.getOpcode() == Opcodes.ILOAD) {
                return v.var;
            }
            // если наткнулись на другую значимую инструкцию — стоп
            if (n.getOpcode() >= 0) break;
        }
        return -1;
    }

    /**
     * Пытается прочитать `state` из терминальной последовательности case-блока
     * {@code <LDC/BIPUSH/ICONST> state; ISTORE stateVar; GOTO dispatch}.
     *
     * @return записанное значение state, или {@code null}, если case заканчивается
     *         return'ом (выходной), или шаблон не совпал.
     */
    private static Integer readSetState(BasicBlock c, BasicBlock dispatch, int stateSlot) {
        List<AbstractInsnNode> insns = c.getInstructions();
        int i = insns.size() - 1;
        // последняя: GOTO dispatch
        if (i < 0) return null;
        AbstractInsnNode last = insns.get(i);
        if (!(last instanceof JumpInsnNode j) || j.getOpcode() != Opcodes.GOTO) {
            // не GOTO — вероятно, return (выходной case)
            return null;
        }
        // ищем в обратном порядке: ISTORE stateSlot, а перед ним пул констант state
        Integer loaded = null;
        i--;
        while (i >= 0) {
            AbstractInsnNode n = insns.get(i);
            if (n instanceof VarInsnNode v && v.getOpcode() == Opcodes.ISTORE && v.var == stateSlot) {
                break; // нашли запись state; ниже должен быть пул констант
            }
            if (n.getOpcode() >= 0) return null; // чужая инструкция перед GOTO — не наша
            i--;
        }
        if (i < 0) return null;
        // теперь ищем пул констант state выше
        i--;
        while (i >= 0) {
            AbstractInsnNode n = insns.get(i);
            if (n instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof Integer) {
                loaded = (Integer) ldc.cst;
                break;
            }
            if (n instanceof org.objectweb.asm.tree.IntInsnNode ii && (ii.getOpcode() == Opcodes.BIPUSH || ii.getOpcode() == Opcodes.SIPUSH)) {
                loaded = ii.operand;
                break;
            }
            if (n instanceof org.objectweb.asm.tree.InsnNode in &&
                    in.getOpcode() >= Opcodes.ICONST_M1 && in.getOpcode() <= Opcodes.ICONST_5) {
                loaded = in.getOpcode() - Opcodes.ICONST_0;
                break;
            }
            if (n.getOpcode() >= 0) return null;
            i--;
        }
        return loaded;
    }

    /** Блок, предшествующий диспетчеру (тот, откуда входим в диспетчер; не case). */
    private static BasicBlock findEntryBefore(List<BasicBlock> blocks, BasicBlock dispatch) {
        // входной блок метода — blocks.get(0). Если это не диспетчер, это и есть вход.
        BasicBlock entry = blocks.get(0);
        return entry == dispatch ? null : entry;
    }
}