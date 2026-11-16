package decompiler.ast;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.structure.StructureNode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.List;

/**
 * Превращает восстановленное дерево структур ({@link StructureNode}) в
 * операторы Java-кода ({@link Ast.Stmt}), разобрав байткод блоков через
 * {@link BytecodeToExpr}.
 *
 * <p>Типы локальных переменных берутся из анализа типов ASM
 * ({@link BasicVerifier/BasicInterpreter}) — так имена переменных получают
 * префикс по типу ({@code iVar}, {@code objVar}), а параметры метода — из
 * его дескриптора.
 */
public final class StructureToAst {

    private final List<BasicBlock> blocks;
    private final MethodNode method;
    private final String[] slotTypes;     // тип каждого слота (friendly name)
    private final Ast.VarNameResolver names;
    private final boolean isStatic;
    private final java.util.Set<Integer> cffDispatch; // блоки-диспетчеры CFF (не switch)
    private final java.util.Set<Integer> switchConsumed = new java.util.HashSet<>(); // блоки case/default, уже поглощённые switch

    public StructureToAst(List<BasicBlock> blocks, MethodNode method) {
        this(blocks, method, java.util.Set.of());
    }

    public StructureToAst(List<BasicBlock> blocks, MethodNode method, java.util.Set<Integer> cffDispatch) {
        this.blocks = blocks;
        this.method = method;
        this.isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        this.slotTypes = inferSlotTypes();
        int paramCount = argCount(method.desc);
        this.names = new Ast.VarNameResolver(paramCount, isStatic ? 0 : 1);
        this.cffDispatch = cffDispatch;
    }

    /** Главная точка входа. */
    public List<Ast.Stmt> convert(StructureNode root) {
        List<Ast.Stmt> out = new ArrayList<>();
        convertNode(root, out);
        return out;
    }

    private void convertNode(StructureNode node, List<Ast.Stmt> out) {
        switch (node.kind()) {
            case SEQUENCE -> {
                for (StructureNode c : node.children()) convertNode(c, out);
            }
            case BLOCK -> {
                // если блок — switch, строим switch; его case-блоки отмечены в switchConsumed
                int idx = node.blockIndex();
                if (switchConsumed.contains(idx)) break; // уже обработан как case switch'а
                Ast.Stmt sw = tryBuildSwitch(idx);
                if (sw != null) {
                    out.add(sw);
                } else {
                    out.addAll(blockToStmt(idx));
                }
            }
            case LOOP -> {
                int header = node.loopHeader();
                Ast.Expr cond = loopCond(header);
                List<Ast.Stmt> body = new ArrayList<>();
                for (StructureNode c : node.children()) {
                    body.addAll(nodeToStmts(c));
                }
                out.add(new Ast.While(cond, body));
            }
            case IF_ELSE -> {
                int condIdx = node.ifCondIndex();
                Ast.Expr cond = condFrom(condIdx);
                List<Ast.Stmt> thenSt = node.thenBranch() != null ? nodeToStmts(node.thenBranch()) : new ArrayList<>();
                List<Ast.Stmt> elseSt = node.elseBranch() != null ? nodeToStmts(node.elseBranch()) : new ArrayList<>();
                out.add(new Ast.If(cond, thenSt, elseSt.isEmpty() ? null : elseSt));
            }
        }
    }

    /** Все операторы из узла структур (блок или последовательность). */
    private List<Ast.Stmt> nodeToStmts(StructureNode node) {
        List<Ast.Stmt> out = new ArrayList<>();
        convertNode(node, out);
        return out;
    }

    /** Если блок оканчивается switch — строит Ast.Switch; иначе null. */
    private Ast.Stmt tryBuildSwitch(int blockIndex) {
        // блок-диспетчер CFF (после дефлата) — не настоящий switch, пропускаем
        if (cffDispatch.contains(blockIndex)) return null;
        BasicBlock bb = blocks.get(blockIndex);
        AbstractInsnNode term = bb.getTerminator();
        List<Integer> keys = new ArrayList<>();
        List<Integer> caseBlocks = new ArrayList<>();
        Integer dfltBlock = null;

        if (term instanceof org.objectweb.asm.tree.TableSwitchInsnNode ts) {
            for (int i = 0; i < ts.labels.size(); i++) {
                keys.add(ts.min + i);
                BasicBlock c = bb.successorByLabel().get(ts.labels.get(i));
                caseBlocks.add(c != null ? c.index() : -1);
            }
            BasicBlock d = bb.successorByLabel().get(ts.dflt);
            dfltBlock = d != null ? d.index() : null;
        } else if (term instanceof org.objectweb.asm.tree.LookupSwitchInsnNode ls) {
            keys.addAll(ls.keys);
            for (var l : ls.labels) {
                BasicBlock c = bb.successorByLabel().get(l);
                caseBlocks.add(c != null ? c.index() : -1);
            }
            BasicBlock d = bb.successorByLabel().get(ls.dflt);
            dfltBlock = d != null ? d.index() : null;
        } else {
            return null; // не switch
        }

        // ключ — значение на стеке перед switch
        var emu = new BytecodeToExpr(names, slotTypes);
        Ast.Expr key = emu.extractSwitchKey(bb.getInstructions());
        if (key == null) return null;

        // пометить case/default-блоки как поглощённые (чтобы не печатать их снова)
        for (int cb : caseBlocks) switchConsumed.add(cb);
        if (dfltBlock != null) switchConsumed.add(dfltBlock);

        // собираем case-ветви (тела — блоки с return)
        List<Ast.Case> cases = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            int cb = caseBlocks.get(i);
            if (cb < 0 || cb >= blocks.size()) continue;
            List<Ast.Stmt> body = blockToStmt(cb);
            cases.add(new Ast.Case(keys.get(i), body));
        }
        List<Ast.Stmt> dfltBody = dfltBlock != null && dfltBlock < blocks.size()
                ? blockToStmt(dfltBlock) : new ArrayList<>();
        return new Ast.Switch(key, cases, dfltBody);
    }

    /** Операторы для одного CFG-блока: байткод блока -> (присваивания + return). */
    private List<Ast.Stmt> blockToStmt(int blockIndex) {
        BasicBlock bb = blocks.get(blockIndex);
        BytecodeToExpr emu = new BytecodeToExpr(names, slotTypes);
        List<AbstractInsnNode> all = new ArrayList<>(bb.getInstructions());
        return emu.runBlock(all, false);
    }

    /** Условие блока (до условного перехода). */
    private Ast.Expr condFrom(int blockIndex) {
        BasicBlock bb = blocks.get(blockIndex);
        BytecodeToExpr emu = new BytecodeToExpr(names, slotTypes);
        List<AbstractInsnNode> prefix = new ArrayList<>();
        for (AbstractInsnNode n : bb.getInstructions()) {
            prefix.add(n); // берём ВСЕ инструкции, включая терминальный IFxx
        }
        // прогоняем со всеми инструкциями — терминальный условный переход
        // сформирует сравнение на стеке, runExpr вернёт его вершину.
        Ast.Expr e = emu.runExpr(prefix);
        return e == null ? new Ast.Literal(true) : e;
    }

    private Ast.Expr loopCond(int header) {
        BasicBlock hdr = blocks.get(header);
        AbstractInsnNode term = hdr.getTerminator();
        if (term instanceof JumpInsnNode j && j.getOpcode() != Opcodes.GOTO) {
            return condFrom(header);
        }
        for (BasicBlock s : hdr.getSuccessors()) {
            AbstractInsnNode st = s.getTerminator();
            if (st instanceof JumpInsnNode && st.getOpcode() != Opcodes.GOTO) {
                return condFrom(s.index());
            }
        }
        return new Ast.Literal(true);
    }

    // ------------------------------------------------------------------ типы

    /** Инфер типов слотов через ASM BasicInterpreter. */
    private String[] inferSlotTypes() {
        String[] types = new String[method.maxLocals];
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(method.name, method);
            if (frames != null && frames.length > 0) {
                Frame<BasicValue> entry = frames[0];
                if (entry != null) {
                    for (int i = 0; i < types.length; i++) {
                        BasicValue v = entry.getLocal(i);
                        if (v != null && v.getType() != null) {
                            types[i] = friendly(v.getType().getDescriptor());
                        } else {
                            types[i] = "Object";
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // параметры из дескриптора (точнее входного кадра): ASM уже дал типы,
        // оставляем.
        return types;
    }

    private static String friendly(String desc) {
        if (desc == null) return "Object";
        return Ast.friendlyType(desc);
    }

    /** Число параметров метода (без this). */
    private int argCount(String desc) {
        return Type.getArgumentTypes(desc).length;
    }
}