package decompiler.ast;

import decompiler.cfganalyzer.BasicBlock;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Версионно-точная типизация слота 0 instance-метода.
 *
 * <p>Корень {@code >= this} (см. BUGREPORT.md №1): обфускатор (CFF spill/
 * де-флаттенинг) переиспользует слот 0 под временное значение наряду с
 * {@code this}. Статическая таблица {@code slotTypes[0]} неоднозначна — в одной
 * точке слот 0 может быть объектом-this, в другой — спилл-значением. Из-за этого
 * {@code VarNameResolver} звал слот 0 «this» и в числовом сравнении, и в
 * {@code == null}, и в индексе массива.</p>
 *
 * <p>Решение — прямой dataflow-анализ по (де-флаттенированному) CFG: слот 0
 * стартует как {@link #THIS} (вход в instance-метод), а любой store в слот 0
 * меняет состояние в этой точке и ниже по потоку. Читатель ({@code *LOAD 0})
 * получает тип по текущему состоянию: {@code THIS} — только если по ВСЕМ
 * достигающим путям слот 0 не был перезаписан. Слияние в join-точке с разными
 * состояниями даёт {@link #MIXED} — «точно не this», чтобы никогда не назвать
 * спилл-значение «this».</p>
 */
public final class SlotZeroResolver {

    /** Слот 0 ещё несёт изначальный this (не перезаписан по достигающему пути). */
    public static final int THIS = 0;
    /** Слот 0 перезаписан примитивом. */
    public static final int INT = 1;
    /** Слот 0 перезаписан объектом (не this). */
    public static final int OBJ = 2;
    /** Слияние различных состояний — спилл, точно не this. */
    public static final int MIXED = 3;
    /** Не посещён (нет достигающего определения). */
    private static final int UNKNOWN = 4;

    private final boolean isInstanceMethod;
    private final Map<AbstractInsnNode, Integer> loadKinds;

    private SlotZeroResolver(boolean isInstanceMethod, Map<AbstractInsnNode, Integer> loadKinds) {
        this.isInstanceMethod = isInstanceMethod;
        this.loadKinds = loadKinds;
    }

    /** Пустой резолвер (для статических методов или когда трекать нечего). */
    public static SlotZeroResolver none() {
        return new SlotZeroResolver(false, java.util.Map.of());
    }

    /**
     * Строит резолвер по (де-флаттенированному) CFG метода.
     *
     * @param blocks    блоки CFG в том виде, в каком их видит StructureToAst
     * @param isStatic  признак static-метода (у static слот 0 не this — резолвер пуст)
     * @param isInstance признак instance-метода (иначе не трекаем)
     */
    public static SlotZeroResolver build(List<BasicBlock> blocks, boolean isInstance) {
        if (!isInstance || blocks.isEmpty()) return none();

        int n = blocks.size();
        int[] in = new int[n];
        java.util.Arrays.fill(in, UNKNOWN);
        boolean[] queued = new boolean[n];

        int entry = blocks.get(0).index();
        in[entry] = THIS;

        // поточечные типы чтений слота 0
        Map<AbstractInsnNode, Integer> loadKinds = new HashMap<>();

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.push(entry);
        queued[entry] = true;

        while (!queue.isEmpty()) {
            int b = queue.pop();
            queued[b] = false;
            int cur = in[b];
            int after = cur;
            for (AbstractInsnNode insn : blocks.get(b).getInstructions()) {
                if (insn instanceof VarInsnNode v) {
                    int op = v.getOpcode();
                    if (v.var == 0) {
                        if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
                            loadKinds.put(insn, after);
                        } else if (op == Opcodes.ISTORE || op == Opcodes.LSTORE
                                || op == Opcodes.FSTORE || op == Opcodes.DSTORE) {
                            after = INT;
                        } else if (op == Opcodes.ASTORE) {
                            after = OBJ;
                        }
                    }
                }
            }
            if (after != cur) {
                // store в слот 0 меняет состояние и для преемников
                in[b] = after;
            }
            for (BasicBlock succ : blocks.get(b).getSuccessors()) {
                int si = succ.index();
                int merged = merge(in[si], after);
                if (merged != in[si]) {
                    in[si] = merged;
                    if (!queued[si]) { queued[si] = true; queue.push(si); }
                }
            }
        }

        return new SlotZeroResolver(true, loadKinds);
    }

    /**
     * Имя слота 0 в точке загрузки {@code loadInsn}.
     * Возвращает {@code null}, если слот 0 — не этот случай (обычный слот/статический).
     */
    public String nameForSlot0Load(VarInsnNode loadInsn) {
        if (!isInstanceMethod || loadInsn == null || loadInsn.var != 0) return null;
        Integer kind = loadKinds.get(loadInsn);
        // нет достигающего определения или совпадает с entry-this — имя по типу
        if (kind == null || kind == THIS) {
            // слот 0 — this (объект) либо нет данных; объектный доступ -> this
            if (loadInsn.getOpcode() == Opcodes.ALOAD) return "this";
            // числовой load без def/u-точность — спилл (число), не this
            return numericName(loadInsn);
        }
        // слот 0 точно перезаписан — это спилл, а не this
        if (loadInsn.getOpcode() == Opcodes.ALOAD) return "objVar0";
        return numericName(loadInsn);
    }

    private static String numericName(VarInsnNode v) {
        return switch (v.getOpcode()) {
            case Opcodes.LLOAD -> "lVar0";
            case Opcodes.FLOAD -> "fVar0";
            case Opcodes.DLOAD -> "dVar0";
            default -> "iVar0";
        };
    }

    private static int merge(int a, int b) {
        if (a == UNKNOWN) return b;
        if (b == UNKNOWN) return a;
        if (a == b) return a;
        return MIXED;
    }
}