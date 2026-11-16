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
    private final java.util.List<org.objectweb.asm.tree.TryCatchBlockNode> tryCatch;
    private final java.util.Map<org.objectweb.asm.tree.LabelNode, Integer> labelToBlock; // метка -> индекс блока

    /** Вычисляет маппинг LabelNode -> индекс блока. Метка может быть в любой
     *  позиции блока (не только первой), т.к. try-range часто начинается внутри. */
    private static java.util.Map<org.objectweb.asm.tree.LabelNode, Integer> buildLabelMap(List<BasicBlock> bl) {
        java.util.Map<org.objectweb.asm.tree.LabelNode, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < bl.size(); i++) {
            for (var insn : bl.get(i).getInstructions()) {
                if (insn instanceof org.objectweb.asm.tree.LabelNode ln && !m.containsKey(ln)) {
                    m.put(ln, i);
                }
            }
        }
        return m;
    }

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
        this.tryCatch = method.tryCatchBlocks != null ? method.tryCatchBlocks : java.util.List.of();
        this.labelToBlock = buildLabelMap(blocks);
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
                Ast.Stmt tc = tryBuildTryCatch(idx);
                if (tc != null) {
                    out.add(tc);
                    break;
                }
                Ast.Stmt sw = tryBuildSwitch(idx);
                if (sw != null) {
                    out.add(sw);
                } else {
                    out.addAll(blockToStmt(idx));
                }
            }
            case LOOP -> {
                int header = node.loopHeader();
                java.util.Set<Integer> bodyIdx = new java.util.HashSet<>(node.loopBody());
                Ast.Expr cond = loopCond(header, bodyIdx);
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

    /** Если блок — начало try-range, строит Ast.TryCatch с первым catch'ем. */
    private Ast.Stmt tryBuildTryCatch(int blockIndex) {
        // найти tryCatchBlock, чей start-блок == этот блок
        org.objectweb.asm.tree.TryCatchBlockNode match = null;
        for (var tcb : tryCatch) {
            Integer startBlock = labelToBlock.get(tcb.start);
            if (startBlock != null && startBlock == blockIndex) {
                match = tcb;
                break;
            }
        }
        if (match == null) return null;

        // catch-блок (handler)
        Integer handlerBlock = labelToBlock.get(match.handler);
        if (handlerBlock == null) return null;

        // try-body: тело нашего блока + тело handler'а как catch
        // (упрощённо: try-body — текущий блок, catch — блок handler'а)
        List<Ast.Stmt> tryBody = blockToStmt(blockIndex);
        List<Ast.Stmt> catchBody = handlerBlock < blocks.size() ? blockToStmt(handlerBlock) : new ArrayList<>();
        // пометить handler-блок потреблённым, чтобы не печатался повторно
        switchConsumed.add(handlerBlock);

        String type = match.type; // может быть null (finally) — на этом этапе просто дефолт
        Ast.Var cv = new Ast.Var("e");
        return new Ast.TryCatch(tryBody, type, cv, catchBody);
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

    private Ast.Expr loopCond(int header, java.util.Set<Integer> bodyIdx) {
        BasicBlock hdr = blocks.get(header);
        AbstractInsnNode term = hdr.getTerminator();
        if (term instanceof JumpInsnNode j && j.getOpcode() != Opcodes.GOTO) {
            Ast.Expr c = condFrom(header);
            // Байткод цикла: header оканчивается условным переходом. Если его цель
            // (куда прыгаем по j.label) НЕ лежит внутри тела — значит переход ведёт
            // на выход из цикла при НЕвыполнении условия -> условие Java надо
            // инвертировать (Java while крутится при истинном).
            int jmpTarget = headerSuccessorByJump(hdr, j);
            if (jmpTarget >= 0 && !bodyIdx.contains(jmpTarget)) {
                return negate(c);
            }
            return c;
        }
        for (BasicBlock s : hdr.getSuccessors()) {
            AbstractInsnNode st = s.getTerminator();
            if (st instanceof JumpInsnNode && st.getOpcode() != Opcodes.GOTO) {
                return condFrom(s.index());
            }
        }
        return new Ast.Literal(true);
    }

    /** Преемник блока по условному переходу j (десериализация метки в индекс). */
    private int headerSuccessorByJump(BasicBlock hdr, JumpInsnNode j) {
        var byLabel = hdr.successorByLabel();
        // successorByLabel ключи — метки switch; для jump — иначе
        // просто находим преемник, чей первый label совпадает с j.label
        for (BasicBlock s : hdr.getSuccessors()) {
            if (s != null && !s.getInstructions().isEmpty()) {
                // блок-преемник начинается с метки; сравниваем с j.label
                if (s.getInstructions().get(0) == j.label) return s.index();
            }
        }
        return -1;
    }

    /** Отрицание выражения-условия (инверсия оператора сравнения/&&/||). */
    private Ast.Expr negate(Ast.Expr e) {
        if (e instanceof Ast.Binary b) {
            String op = switch (b.op()) {
                case "==" -> "!=";
                case "!=" -> "==";
                case "<" -> ">=";
                case ">" -> "<=";
                case "<=" -> ">";
                case ">=" -> "<";
                case "&&" -> "||";
                case "||" -> "&&";
                default -> null;
            };
            if (op != null) return new Ast.Binary(op, b.left(), b.right());
        }
        return new Ast.Unary("!", e);
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