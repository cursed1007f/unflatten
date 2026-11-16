package decompiler.cfganalyzer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.List;

/**
 * Текстовый дамп CFG: блоки с порядковым номером, их содержимое (тонкие
 * сигналы для дебага — операторы/операнды), список преемников.
 *
 * <p>На этапе 7/визуализации этот же вывод можно отдавать в граф (Graphviz),
 * оставив рендер структуры отдельно.
 */
public final class CfgDumper {

    private CfgDumper() {
    }

    /**
     * Строит CFG для метода и возвращает его текстовое представление.
     *
     * @param mn         метод
     * @param withInsns  включать ли построчные инструкции блоков
     */
    public static String dump(MethodNode mn, boolean withInsns) {
        List<BasicBlock> blocks = CfgBuilder.build(mn);
        if (blocks.isEmpty()) {
            return "  (метод пуст/структуры нет)\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  CFG: ").append(blocks.size()).append(" blocks\n");
        for (BasicBlock bb : blocks) {
            sb.append("  ").append(bb).append(": preds=").append(sign(bb.getPredecessors()))
                    .append(" succs=").append(sign(bb.getSuccessors()))
                    .append(" term=").append(termName(bb.getTerminator()))
                    .append('\n');
            if (withInsns) {
                for (AbstractInsnNode insn : bb.getInstructions()) {
                    if (insn.getOpcode() < 0) continue;
                    sb.append("      ").append(describe(insn)).append('\n');
                }
            }
        }
        List<BasicBlock> exits = CfgBuilder.exits(blocks);
        sb.append("  exits: ").append(sign(exits)).append('\n');
        return sb.toString();
    }

    private static String sign(List<BasicBlock> list) {
        if (list.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (BasicBlock bb : list) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append("BB").append(bb.index());
        }
        return sb.toString();
    }

    private static String termName(AbstractInsnNode t) {
        if (t == null) return "?";
        String nm = t.getClass().getSimpleName();
        return (t.getOpcode() >= 0 ? opcode(t.getOpcode()) + "/" : "") + nm;
    }

    private static String opcode(int op) {
        try {
            return org.objectweb.asm.util.Printer.OPCODES[op];
        } catch (Exception e) {
            return "?";
        }
    }

    /** Короткое описание инструкции (оператор + ключевые операнды). */
    private static String describe(AbstractInsnNode insn) {
        String s = opcode(insn.getOpcode());
        if (insn instanceof org.objectweb.asm.tree.VarInsnNode v) {
            s += " v" + v.var;
        } else if (insn instanceof org.objectweb.asm.tree.IntInsnNode ii) {
            s += " " + ii.operand;
        } else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode l) {
            s += " " + l.cst;
        } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f) {
            s += " " + f.owner + "." + f.name + " " + f.desc;
        } else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode m) {
            s += " " + m.owner + "." + m.name + m.desc;
        } else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode t) {
            s += " " + t.desc;
        } else if (insn instanceof org.objectweb.asm.tree.JumpInsnNode j) {
            // метка без номера — покажем только класс перехода
            s += " ->label";
        } else if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode t) {
            s += " " + t.min + ".." + t.max;
        } else if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode l) {
            s += " " + l.keys;
        }
        return s;
    }
}