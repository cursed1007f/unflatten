package decompiler.ast;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.cfganalyzer.CfgBuilder;
import decompiler.parser.JarParser;
import decompiler.passes.CffUnflattenerPass;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.nio.file.Path;
import java.util.List;

/**
 * Детектор обфускации: до декомпиляции определяет, есть ли обфускация и какого
 * типа — обычная (имена/строки/анти-тампер), CFF tableswitch (state-машина),
 * CFF switch-gate, непрозрачные предикаты или смешанный «кастомный» CFF.
 * Также определяет случай «обфускации нет».
 *
 * <p>Работает по байткоду без полной декомпиляции: CFG на метод, прогон
 * {@link CffUnflattenerPass} для классификации CFF, свёртка условий блоков
 * ({@link Ast#constValue}) для поиска непрозрачных предикатов.
 */
public final class ObfuscationDetector {

    /** Профиль обфускации jar. */
    public static final class Profile {
        public int classes;
        public int methods;
        public int cffTableswitch;
        public int cffGate;
        public int opaquePredicates;
        public int conditionalBlocks;
        public int encryptedStringCalls;
        public int antiTamperHandlers;
        public int obfuscatedClassNames;
        public String verdict = "";

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Детектор обфускации ===\n");
            sb.append("вердикт: ").append(verdict).append('\n');
            sb.append("классов: ").append(classes)
              .append(", обфусцированных имён: ").append(obfuscatedClassNames).append('\n');
            sb.append("методов: ").append(methods).append('\n');
            sb.append("CFF tableswitch: ").append(cffTableswitch)
              .append(", CFF switch-gate: ").append(cffGate).append('\n');
            sb.append("непрозрачных предикатов: ").append(opaquePredicates)
              .append(" из ").append(conditionalBlocks).append(" условных блоков")
              .append(", шифрованных строк-вызовов: ").append(encryptedStringCalls)
              .append(", анти-тампер обработчиков: ").append(antiTamperHandlers).append('\n');
            return sb.toString();
        }
    }

    private ObfuscationDetector() {
    }

    /** Просканировать jar и вернуть профиль обфускации. */
    public static Profile analyze(Path jar) throws Exception {
        Profile p = new Profile();
        JarParser.parse(jar, (jp, entry, cn) -> {
            p.classes++;
            if (cn.name != null && ClassRenamer.looksObfuscated(cn.name)) p.obfuscatedClassNames++;
            if (cn.methods == null) return;
            for (MethodNode mn : cn.methods) {
                p.methods++;
                if (mn.tryCatchBlocks != null) {
                    for (TryCatchBlockNode t : mn.tryCatchBlocks) {
                        if ("java/lang/Throwable".equals(t.type)) p.antiTamperHandlers++;
                    }
                }
                if (mn.instructions != null) {
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof MethodInsnNode m
                                && m.getOpcode() == Opcodes.INVOKESTATIC
                                && m.name.equals("a")
                                && "(Ljava/lang/String;I)Ljava/lang/String;".equals(m.desc)) {
                            p.encryptedStringCalls++;
                        }
                    }
                }
                try {
                    List<BasicBlock> blocks = CfgBuilder.build(mn);
                    CffUnflattenerPass.Result res = CffUnflattenerPass.run(blocks);
                    if (res.unflattened) {
                        if ("gate".equals(res.kind)) p.cffGate++;
                        else p.cffTableswitch++;
                    }
                    // непрозрачные предикаты: условие блока свёртывается в константу
                    for (BasicBlock b : blocks) {
                        AbstractInsnNode term = b.getTerminator();
                        if (term instanceof JumpInsnNode j && j.getOpcode() != Opcodes.GOTO) {
                            p.conditionalBlocks++;
                            BytecodeToExpr emu = new BytecodeToExpr(new Ast.VarNameResolver(0, 0));
                            Ast.Expr cond = emu.runExpr(b.getInstructions());
                            if (Ast.constValue(cond) instanceof Boolean) p.opaquePredicates++;
                        }
                    }
                } catch (Exception ignored) {
                    // нечитаемый метод пропускаем — детектор не должен падать
                }
            }
        });
        p.verdict = verdict(p);
        return p;
    }

    /** Вердикт по собранным сигналам. */
    private static String verdict(Profile p) {
        int cff = p.cffTableswitch + p.cffGate;
        // Пороги: одиночные совпадения не считаем обфускацией (в обычном коде тоже
        // бывают константные условия и catch(Throwable)).
        boolean enc = p.encryptedStringCalls >= 5;
        boolean tamper = p.antiTamperHandlers >= Math.max(10, (int) (p.methods * 0.01));
        double opaqueShare = p.conditionalBlocks == 0 ? 0 : (double) p.opaquePredicates / p.conditionalBlocks;
        boolean opaque = p.opaquePredicates >= 10 && opaqueShare >= 0.05;
        double nameShare = p.classes == 0 ? 0 : (double) p.obfuscatedClassNames / p.classes;
        boolean names = p.obfuscatedClassNames > 0 && nameShare >= 0.2;

        if (cff == 0 && !enc && !tamper && !opaque && !names) {
            return "NONE — обфускации нет";
        }
        boolean ts = p.cffTableswitch > 0;
        boolean ga = p.cffGate > 0;
        if (ts && ga) {
            return "MIXED_CFF — кастомный/смешанный CFF (tableswitch + switch-gate)";
        }
        if (ts) {
            return (opaque || enc || tamper)
                    ? "CFF_TABLESWITCH — state-машина + доп. защиты"
                    : "CFF_TABLESWITCH — state-машина";
        }
        if (ga) {
            return "CFF_SWITCH_GATE — switch-gate";
        }
        if (opaque) {
            return "OPAQUE_PREDICATES — непрозрачные предикаты без state-машины";
        }
        return "REGULAR — обычная (имена/строки/анти-тампер, без CFF)";
    }
}
