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
 * РџСЂРµРІСЂР°С‰Р°РµС‚ РІРѕСЃСЃС‚Р°РЅРѕРІР»РµРЅРЅРѕРµ РґРµСЂРµРІРѕ СЃС‚СЂСѓРєС‚СѓСЂ ({@link StructureNode}) РІ
 * РѕРїРµСЂР°С‚РѕСЂС‹ Java-РєРѕРґР° ({@link Ast.Stmt}), СЂР°Р·РѕР±СЂР°РІ Р±Р°Р№С‚РєРѕРґ Р±Р»РѕРєРѕРІ С‡РµСЂРµР·
 * {@link BytecodeToExpr}.
 *
 * <p>РўРёРїС‹ Р»РѕРєР°Р»СЊРЅС‹С… РїРµСЂРµРјРµРЅРЅС‹С… Р±РµСЂСѓС‚СЃСЏ РёР· Р°РЅР°Р»РёР·Р° С‚РёРїРѕРІ ASM
 * ({@link BasicVerifier/BasicInterpreter}) вЂ” С‚Р°Рє РёРјРµРЅР° РїРµСЂРµРјРµРЅРЅС‹С… РїРѕР»СѓС‡Р°СЋС‚
 * РїСЂРµС„РёРєСЃ РїРѕ С‚РёРїСѓ ({@code iVar}, {@code objVar}), Р° РїР°СЂР°РјРµС‚СЂС‹ РјРµС‚РѕРґР° вЂ” РёР·
 * РµРіРѕ РґРµСЃРєСЂРёРїС‚РѕСЂР°.
 */
public final class StructureToAst {

    private final List<BasicBlock> blocks;
    private final MethodNode method;
    private final String[] slotTypes;     // С‚РёРї РєР°Р¶РґРѕРіРѕ СЃР»РѕС‚Р° (friendly name)
    private final Ast.VarNameResolver names;
    private final boolean isStatic;
    private final java.util.Set<Integer> cffDispatch; // Р±Р»РѕРєРё-РґРёСЃРїРµС‚С‡РµСЂС‹ CFF (РЅРµ switch)
    private final java.util.Set<Integer> switchConsumed = new java.util.HashSet<>(); // Р±Р»РѕРєРё case/default, СѓР¶Рµ РїРѕРіР»РѕС‰С‘РЅРЅС‹Рµ switch
    private final java.util.List<org.objectweb.asm.tree.TryCatchBlockNode> tryCatch;
    private final java.util.Map<org.objectweb.asm.tree.LabelNode, Integer> labelToBlock; // РјРµС‚РєР° -> РёРЅРґРµРєСЃ Р±Р»РѕРєР°
    private final StringDecryptor decryptor;
    private final ClassHierarchy hierarchy;
    private final SlotZeroResolver slotZero;

    /** Р’С‹С‡РёСЃР»СЏРµС‚ РјР°РїРїРёРЅРі LabelNode -> РёРЅРґРµРєСЃ Р±Р»РѕРєР°. РњРµС‚РєР° РјРѕР¶РµС‚ Р±С‹С‚СЊ РІ Р»СЋР±РѕР№
     *  РїРѕР·РёС†РёРё Р±Р»РѕРєР° (РЅРµ С‚РѕР»СЊРєРѕ РїРµСЂРІРѕР№), С‚.Рє. try-range С‡Р°СЃС‚Рѕ РЅР°С‡РёРЅР°РµС‚СЃСЏ РІРЅСѓС‚СЂРё. */
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
        this(blocks, method, java.util.Set.of(), null);
    }

    public StructureToAst(List<BasicBlock> blocks, MethodNode method, java.util.Set<Integer> cffDispatch) {
        this(blocks, method, cffDispatch, null);
    }

    public StructureToAst(List<BasicBlock> blocks, MethodNode method, java.util.Set<Integer> cffDispatch,
                          StringDecryptor decryptor) {
        this(blocks, method, cffDispatch, decryptor, null);
    }

    public StructureToAst(List<BasicBlock> blocks, MethodNode method, java.util.Set<Integer> cffDispatch,
                          StringDecryptor decryptor, String classOwner) {
        this(blocks, method, cffDispatch, decryptor, classOwner, null);
    }

    public StructureToAst(List<BasicBlock> blocks, MethodNode method, java.util.Set<Integer> cffDispatch,
                          StringDecryptor decryptor, String classOwner, ClassHierarchy hierarchy) {
        this.blocks = blocks;
        this.method = method;
        this.isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        this.slotTypes = TypeInference.infer(method, isStatic, classOwner, hierarchy);
        int paramCount = argCount(method.desc);
        this.names = new Ast.VarNameResolver(paramCount, isStatic ? 0 : 1);
        this.cffDispatch = cffDispatch;
        this.tryCatch = method.tryCatchBlocks != null ? method.tryCatchBlocks : java.util.List.of();
        this.labelToBlock = buildLabelMap(blocks);
        this.decryptor = decryptor;
        this.hierarchy = hierarchy;
        // Версионно-точный резолвер слота 0 для instance-методов: по CFG решает,
        // является ли слот 0 в точке *LOAD this'ом или переиспользованным спилл-значением.
        this.slotZero = SlotZeroResolver.build(blocks, (method.access & Opcodes.ACC_STATIC) == 0);
    }

    /** Р“Р»Р°РІРЅР°СЏ С‚РѕС‡РєР° РІС…РѕРґР°. */
    public List<Ast.Stmt> convert(StructureNode root) {
        List<Ast.Stmt> out = new ArrayList<>();
        convertNode(root, out);
        return out;
    }

    private void convertNode(StructureNode node, List<Ast.Stmt> out) {
        switch (node.kind()) {
            case SEQUENCE -> {
                for (StructureNode c : node.children()) {
                    if (outTerminated(out)) return; // после return/throw дальнейшие узлы недостижимы
                    convertNode(c, out);
                }
            }
            case BLOCK -> {
                // РµСЃР»Рё Р±Р»РѕРє вЂ” switch, СЃС‚СЂРѕРёРј switch; РµРіРѕ case-Р±Р»РѕРєРё РѕС‚РјРµС‡РµРЅС‹ РІ switchConsumed
                int idx = node.blockIndex();
                if (switchConsumed.contains(idx)) break; // СѓР¶Рµ РѕР±СЂР°Р±РѕС‚Р°РЅ РєР°Рє case switch'Р°
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
                    if (outTerminated(body)) break; // недостижимые после return/throw узлы тела цикла
                    body.addAll(nodeToStmts(c));
                }
                // Свёртка непрозрачного предиката в УСЛОВИИ цикла:
                // while (1*1+1%2 != 0) -> while(true); while(false) -> тело мёртвое, не эмитим.
                Object cv = Ast.constValue(cond);
                if (cv instanceof Boolean b) {
                    if (b) out.add(new Ast.While(new Ast.Literal(true), body));
                    // else: всегда-ложь — цикл не выполняется
                } else {
                    out.add(new Ast.While(cond, body));
                }
            }
            case IF_ELSE -> {
                int condIdx = node.ifCondIndex();
                Ast.Expr cond = condFrom(condIdx);
                List<Ast.Stmt> thenSt = node.thenBranch() != null ? nodeToStmts(node.thenBranch()) : new ArrayList<>();
                List<Ast.Stmt> elseSt = node.elseBranch() != null ? nodeToStmts(node.elseBranch()) : new ArrayList<>();
                // Свёртка непрозрачных предикатов обфускатора (1*1+1%2 != 0 и т.п.).
                // Если условие константно, подставляем взятую ветвь. False-ветвь
                // (else) — это ложный путь = продолжение/эпилог, поэтому дописываем
                // её после взятой ветви только если та не терминирует (return/throw).
                Object cv = Ast.constValue(cond);
                if (cv instanceof Boolean b) {
                    if (b) {
                        // терминирующую then-ветвь эмитим (вместе с её return),
                        // а недостижимый else — нет (мёртвый код не выводим)
                        out.addAll(thenSt);
                        if (!elseSt.isEmpty() && !outTerminated(thenSt)) out.addAll(elseSt);
                    } else {
                        out.addAll(elseSt);
                    }
                } else {
                    out.add(new Ast.If(cond, thenSt, elseSt.isEmpty() ? null : elseSt));
                }
            }
        }
    }

    /** Р’СЃРµ РѕРїРµСЂР°С‚РѕСЂС‹ РёР· СѓР·Р»Р° СЃС‚СЂСѓРєС‚СѓСЂ (Р±Р»РѕРє РёР»Рё РїРѕСЃР»РµРґРѕРІР°С‚РµР»СЊРЅРѕСЃС‚СЊ). */
    private List<Ast.Stmt> nodeToStmts(StructureNode node) {
        List<Ast.Stmt> out = new ArrayList<>();
        convertNode(node, out);
        return out;
    }

    /** Истинно, если список операторов уже завершается return или throw (дальше недостижимо). */
    private static boolean outTerminated(List<Ast.Stmt> stmts) {
        if (stmts.isEmpty()) return false;
        Ast.Stmt last = stmts.get(stmts.size() - 1);
        if (last instanceof Ast.Return) return true;
        return last instanceof Ast.ExprStmt es && es.expr() instanceof Ast.Call c && "throw".equals(c.name());
    }

    /** Р•СЃР»Рё Р±Р»РѕРє вЂ” РЅР°С‡Р°Р»Рѕ try-range, СЃС‚СЂРѕРёС‚ Ast.TryCatch СЃ РїРµСЂРІС‹Рј catch'РµРј. */
    private Ast.Stmt tryBuildTryCatch(int blockIndex) {
        // РЅР°Р№С‚Рё tryCatchBlock, С‡РµР№ start-Р±Р»РѕРє == СЌС‚РѕС‚ Р±Р»РѕРє
        org.objectweb.asm.tree.TryCatchBlockNode match = null;
        for (var tcb : tryCatch) {
            Integer startBlock = labelToBlock.get(tcb.start);
            if (startBlock != null && startBlock == blockIndex) {
                match = tcb;
                break;
            }
        }
        if (match == null) return null;

        // catch-Р±Р»РѕРє (handler)
        Integer handlerBlock = labelToBlock.get(match.handler);
        if (handlerBlock == null) return null;

        // try-body: С‚РµР»Рѕ РЅР°С€РµРіРѕ Р±Р»РѕРєР° + С‚РµР»Рѕ handler'Р° РєР°Рє catch
        // (СѓРїСЂРѕС‰С‘РЅРЅРѕ: try-body вЂ” С‚РµРєСѓС‰РёР№ Р±Р»РѕРє, catch вЂ” Р±Р»РѕРє handler'Р°)
        List<Ast.Stmt> tryBody = blockToStmt(blockIndex);
        // Анти-тампер обфускатора: `try { } catch (Throwable e) { return <dead>; throw(0); }`
        // при ПУСТОМ try — весь конструкт мёртв (catch недостижим). Выкидываем его,
        // пометив handler потреблённым, чтобы он не напечатался отдельным блоком.
        if (tryBody.isEmpty()) {
            switchConsumed.add(handlerBlock);
            return null;
        }
        List<Ast.Stmt> catchBody = handlerBlock < blocks.size() ? blockToStmt(handlerBlock) : new ArrayList<>();
        // РїРѕРјРµС‚РёС‚СЊ handler-Р±Р»РѕРє РїРѕС‚СЂРµР±Р»С‘РЅРЅС‹Рј, С‡С‚РѕР±С‹ РЅРµ РїРµС‡Р°С‚Р°Р»СЃСЏ РїРѕРІС‚РѕСЂРЅРѕ
        switchConsumed.add(handlerBlock);

        String type = match.type; // РјРѕР¶РµС‚ Р±С‹С‚СЊ null (finally) вЂ” РЅР° СЌС‚РѕРј СЌС‚Р°РїРµ РїСЂРѕСЃС‚Рѕ РґРµС„РѕР»С‚
        Ast.Var cv = new Ast.Var("e");
        return new Ast.TryCatch(tryBody, type, cv, catchBody);
    }

    /** Р•СЃР»Рё Р±Р»РѕРє РѕРєР°РЅС‡РёРІР°РµС‚СЃСЏ switch вЂ” СЃС‚СЂРѕРёС‚ Ast.Switch; РёРЅР°С‡Рµ null. */
    private Ast.Stmt tryBuildSwitch(int blockIndex) {
        // Р±Р»РѕРє-РґРёСЃРїРµС‚С‡РµСЂ CFF (РїРѕСЃР»Рµ РґРµС„Р»Р°С‚Р°) вЂ” РЅРµ РЅР°СЃС‚РѕСЏС‰РёР№ switch, РїСЂРѕРїСѓСЃРєР°РµРј
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
            return null; // РЅРµ switch
        }

        // РєР»СЋС‡ вЂ” Р·РЅР°С‡РµРЅРёРµ РЅР° СЃС‚РµРєРµ РїРµСЂРµРґ switch
        var emu = newEmu();
        Ast.Expr key = emu.extractSwitchKey(bb.getInstructions());
        if (key == null) return null;

        // РїРѕРјРµС‚РёС‚СЊ case/default-Р±Р»РѕРєРё РєР°Рє РїРѕРіР»РѕС‰С‘РЅРЅС‹Рµ (С‡С‚РѕР±С‹ РЅРµ РїРµС‡Р°С‚Р°С‚СЊ РёС… СЃРЅРѕРІР°)
        for (int cb : caseBlocks) switchConsumed.add(cb);
        if (dfltBlock != null) switchConsumed.add(dfltBlock);

        // СЃРѕР±РёСЂР°РµРј case-РІРµС‚РІРё (С‚РµР»Р° вЂ” Р±Р»РѕРєРё СЃ return)
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

/** РћРїРµСЂР°С‚РѕСЂС‹ РґР»СЏ РѕРґРЅРѕРіРѕ CFG-Р±Р»РѕРєР°: Р±Р°Р№С‚РєРѕРґ Р±Р»РѕРєР° -> (РїСЂРёСЃРІР°РёРІР°РЅРёСЏ + return). */
    private List<Ast.Stmt> blockToStmt(int blockIndex) {
        BasicBlock bb = blocks.get(blockIndex);
        BytecodeToExpr emu = newEmu();
        List<AbstractInsnNode> all = new ArrayList<>(bb.getInstructions());
        return emu.runBlock(all, false);
    }

    /** Эмулятор с привязанным версионным резолвером слота 0. */
    private BytecodeToExpr newEmu() {
        return new BytecodeToExpr(names, slotTypes, decryptor).withSlotZero(slotZero);
    }

    /** РЈСЃР»РѕРІРёРµ Р±Р»РѕРєР° (РґРѕ СѓСЃР»РѕРІРЅРѕРіРѕ РїРµСЂРµС…РѕРґР°). */
    private Ast.Expr condFrom(int blockIndex) {
        BasicBlock bb = blocks.get(blockIndex);
        BytecodeToExpr emu = newEmu();
        List<AbstractInsnNode> prefix = new ArrayList<>();
        for (AbstractInsnNode n : bb.getInstructions()) {
            prefix.add(n); // Р±РµСЂС‘Рј Р’РЎР• РёРЅСЃС‚СЂСѓРєС†РёРё, РІРєР»СЋС‡Р°СЏ С‚РµСЂРјРёРЅР°Р»СЊРЅС‹Р№ IFxx
        }
        // РїСЂРѕРіРѕРЅСЏРµРј СЃРѕ РІСЃРµРјРё РёРЅСЃС‚СЂСѓРєС†РёСЏРјРё вЂ” С‚РµСЂРјРёРЅР°Р»СЊРЅС‹Р№ СѓСЃР»РѕРІРЅС‹Р№ РїРµСЂРµС…РѕРґ
        // СЃС„РѕСЂРјРёСЂСѓРµС‚ СЃСЂР°РІРЅРµРЅРёРµ РЅР° СЃС‚РµРєРµ, runExpr РІРµСЂРЅС‘С‚ РµРіРѕ РІРµСЂС€РёРЅСѓ.
        Ast.Expr e = emu.runExpr(prefix);
        return e == null ? new Ast.Literal(true) : e;
    }

    private Ast.Expr loopCond(int header, java.util.Set<Integer> bodyIdx) {
        BasicBlock hdr = blocks.get(header);
        AbstractInsnNode term = hdr.getTerminator();
        if (term instanceof JumpInsnNode j && j.getOpcode() != Opcodes.GOTO) {
            Ast.Expr c = condFrom(header);
            // Р‘Р°Р№С‚РєРѕРґ С†РёРєР»Р°: header РѕРєР°РЅС‡РёРІР°РµС‚СЃСЏ СѓСЃР»РѕРІРЅС‹Рј РїРµСЂРµС…РѕРґРѕРј. Р•СЃР»Рё РµРіРѕ С†РµР»СЊ
            // (РєСѓРґР° РїСЂС‹РіР°РµРј РїРѕ j.label) РќР• Р»РµР¶РёС‚ РІРЅСѓС‚СЂРё С‚РµР»Р° вЂ” Р·РЅР°С‡РёС‚ РїРµСЂРµС…РѕРґ РІРµРґС‘С‚
            // РЅР° РІС‹С…РѕРґ РёР· С†РёРєР»Р° РїСЂРё РќР•РІС‹РїРѕР»РЅРµРЅРёРё СѓСЃР»РѕРІРёСЏ -> СѓСЃР»РѕРІРёРµ Java РЅР°РґРѕ
            // РёРЅРІРµСЂС‚РёСЂРѕРІР°С‚СЊ (Java while РєСЂСѓС‚РёС‚СЃСЏ РїСЂРё РёСЃС‚РёРЅРЅРѕРј).
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

    /** РџСЂРµРµРјРЅРёРє Р±Р»РѕРєР° РїРѕ СѓСЃР»РѕРІРЅРѕРјСѓ РїРµСЂРµС…РѕРґСѓ j (РґРµСЃРµСЂРёР°Р»РёР·Р°С†РёСЏ РјРµС‚РєРё РІ РёРЅРґРµРєСЃ). */
    private int headerSuccessorByJump(BasicBlock hdr, JumpInsnNode j) {
        var byLabel = hdr.successorByLabel();
        // successorByLabel РєР»СЋС‡Рё вЂ” РјРµС‚РєРё switch; РґР»СЏ jump вЂ” РёРЅР°С‡Рµ
        // РїСЂРѕСЃС‚Рѕ РЅР°С…РѕРґРёРј РїСЂРµРµРјРЅРёРє, С‡РµР№ РїРµСЂРІС‹Р№ label СЃРѕРІРїР°РґР°РµС‚ СЃ j.label
        for (BasicBlock s : hdr.getSuccessors()) {
            if (s != null && !s.getInstructions().isEmpty()) {
                // Р±Р»РѕРє-РїСЂРµРµРјРЅРёРє РЅР°С‡РёРЅР°РµС‚СЃСЏ СЃ РјРµС‚РєРё; СЃСЂР°РІРЅРёРІР°РµРј СЃ j.label
                if (s.getInstructions().get(0) == j.label) return s.index();
            }
        }
        return -1;
    }

    /** РћС‚СЂРёС†Р°РЅРёРµ РІС‹СЂР°Р¶РµРЅРёСЏ-СѓСЃР»РѕРІРёСЏ (РёРЅРІРµСЂСЃРёСЏ РѕРїРµСЂР°С‚РѕСЂР° СЃСЂР°РІРЅРµРЅРёСЏ/&&/||). */
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

    private static String friendly(String desc) {
        if (desc == null) return "Object";
        return Ast.friendlyType(desc);
    }

    /** Р§РёСЃР»Рѕ РїР°СЂР°РјРµС‚СЂРѕРІ РјРµС‚РѕРґР° (Р±РµР· this). */
    private int argCount(String desc) {
        return Type.getArgumentTypes(desc).length;
    }
}