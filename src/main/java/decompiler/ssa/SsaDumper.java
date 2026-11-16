package decompiler.ssa;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.cfganalyzer.CfgBuilder;

import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/**
 * Вывод SSA-формы метода в текстовом виде — для проверки на терминалах/GUI
 * и для отладки трансформаций.
 */
public final class SsaDumper {

    private SsaDumper() {
    }

    /** Строит CFG и SSA метода, возвращает текст с блоками и инструкциями. */
    public static String dump(MethodNode mn) {
        List<BasicBlock> blocks = CfgBuilder.build(mn);
        if (blocks.isEmpty()) {
            return "  (метод пуст)\n";
        }
        SsaBuilder ssa = SsaBuilder.build(blocks, mn.maxLocals);

        StringBuilder sb = new StringBuilder();
        sb.append("  SSA for ").append(mn.name).append(mn.desc)
                .append(" (").append(blocks.size()).append(" blocks, ")
                .append(ssa.maxLocals()).append(" locals)\n");
        for (BasicBlock bb : blocks) {
            int i = bb.index();
            List<SsaVar.Phi> phis = ssa.phis(i);
            List<SsaVar.Insn> insns = ssa.insns(i);
            sb.append("  BB").append(i)
                    .append(" preds=").append(predStr(bb))
                    .append(" succs=").append(succStr(bb)).append('\n');
            for (SsaVar.Phi phi : phis) {
                sb.append("    ").append(phi).append('\n');
            }
            for (SsaVar.Insn in : insns) {
                sb.append("    ").append(in).append('\n');
            }
            if (phis.isEmpty() && insns.isEmpty()) {
                sb.append("    (нет инструкций SSA)\n");
            }
        }
        return sb.toString();
    }

    private static String predStr(BasicBlock bb) {
        return join(bb.getPredecessors());
    }

    private static String succStr(BasicBlock bb) {
        return join(bb.getSuccessors());
    }

    private static String join(List<BasicBlock> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("BB").append(list.get(i).index());
        }
        sb.append("]");
        return sb.toString();
    }
}