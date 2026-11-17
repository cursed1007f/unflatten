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
    private final String classOwner; // внутреннее имя текущего класса (для super()/this())
    /** Точка входа CFG: от неё считается reachability (а не от корней). */
    private final int entryIndex;

    /** Метод не удалось восстановить корректно — вывод заменён raw-маркером. */
    private boolean undecompilable;
    private String undecompilableReason;
    /** Эмиссии блоков по роли: normal-flow и catch-роль (handler). */
    private final java.util.Map<Integer, Integer> normalEmits = new java.util.HashMap<>();
    /** Списки операторов по блокам — основа двухфазной эмиссии (phi-инъекции). */
    private final java.util.Map<Integer, List<List<Ast.Stmt>>> blockStmts = new java.util.HashMap<>();
    /** Exit-выражения блоков (вершина стека на выходе) — вход для phi. */
    private final java.util.Map<Integer, List<Ast.Expr>> blockResults = new java.util.HashMap<>();
    private final java.util.Map<String, PhiSite> phiSites = new java.util.LinkedHashMap<>();
    private final java.util.Map<Integer, List<Ast.Expr>> entryCache = new java.util.HashMap<>();
    public static final java.util.concurrent.atomic.AtomicInteger PHI_SITES = new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger PHI_INJECTED = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<Ast.VarDecl> phiDecls = new java.util.LinkedHashSet<>();
    private int phiCounter;
    public static final java.util.List<String> PHI_DEBUG = java.util.Collections.synchronizedList(new java.util.ArrayList<>());    private final java.util.Map<Integer, Integer> catchEmits = new java.util.HashMap<>();
    private int catchCounter;

    /** Диагностика прогона (сбрасывается Main). */
    public static final java.util.concurrent.atomic.AtomicInteger HANDLER_DUPLICATES =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger EMPTY_ROOTS =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Методы с пустым корнем структуры (для диагностики). */
    public static final java.util.List<String> EMPTY_ROOT_METHODS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** Разбивка multi-block try по rethrowSlot: -2 / >=0 / -1. */
    public static final java.util.concurrent.atomic.AtomicInteger RETHROW_SLOT_MINUS2 =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger RETHROW_SLOT_GTE0 =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger RETHROW_SLOT_MINUS1 =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger PRUNED_STATEMENTS =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger PRUNED_METHODS =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger RETHROW_DROPPED =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger CONST_FOLDED =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Удалённые DeadStores-присваивания (общий счётчик для отчёта). */
    public static final java.util.concurrent.atomic.AtomicInteger DEAD_STORES =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Handler'ы, легально снятые (пустой try / чистый rethrow), с причиной. */
    private final java.util.Set<Integer> droppedHandlers = new java.util.HashSet<>();
    /** Литералы, созданные эмиттерами из байткода/свёрток (provenance-реестр). */
    private final java.util.Set<Ast.Expr> sourcedLiterals =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private Ast.Literal sourced(Ast.Literal l) {
        sourcedLiterals.add(l);
        return l;
    }

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
        this(blocks, method, cffDispatch, decryptor, classOwner, hierarchy,
                blocks.isEmpty() ? -1 : blocks.get(0).index());
    }

    public StructureToAst(List<BasicBlock> blocks, MethodNode method, java.util.Set<Integer> cffDispatch,
                          StringDecryptor decryptor, String classOwner, ClassHierarchy hierarchy,
                          int entryIndex) {
        this.entryIndex = entryIndex;
        this.blocks = blocks;
        this.method = method;
        this.isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        this.slotTypes = TypeInference.infer(method, isStatic, classOwner, hierarchy);
        int paramCount = argCount(method.desc);
        this.names = new Ast.VarNameResolver(paramCount, isStatic ? 0 : 1);
        this.cffDispatch = cffDispatch;
        this.tryCatch = method.tryCatchBlocks != null ? method.tryCatchBlocks : java.util.List.of();
        this.labelToBlock = buildLabelMap(blocks);
        // Диагностика: если корней нет (entry — заголовок цикла с back-edge),
        // старый reachability от корней был пустым и инварианты ничего не проверяли.
        boolean hasRoot = false;
        for (BasicBlock b : blocks) {
            if (b.getPredecessors().isEmpty()) {
                hasRoot = true;
                break;
            }
        }
        if (!hasRoot) {
            EMPTY_ROOTS.incrementAndGet();
            EMPTY_ROOT_METHODS.add(classOwner + "." + method.name + method.desc);
        }
        this.decryptor = decryptor;
        this.hierarchy = hierarchy;
        this.classOwner = classOwner;
        // Версионно-точный резолвер слота 0 для instance-методов: по CFG решает,
        // является ли слот 0 в точке *LOAD this'ом или переиспользованным спилл-значением.
        this.slotZero = SlotZeroResolver.build(blocks, (method.access & Opcodes.ACC_STATIC) == 0);
    }

    /** Р“Р»Р°РІРЅР°СЏ С‚РѕС‡РєР° РІС…РѕРґР°. */
    public List<Ast.Stmt> convert(StructureNode root) {
        try {
            return convertBody(root);
        } catch (UndecompilableException e) {
            // Явный отказ вместо тихо неверного кода: метод помечается
            // undecompilable, в тело пишется raw-маркер (без выдуманных значений).
            this.undecompilable = true;
            this.undecompilableReason = e.getMessage();
            return fallbackBody();
        }
    }

    /** Метод не удалось восстановить корректно (стек/граф вышли за модель). */
    public boolean isUndecompilable() {
        return undecompilable;
    }

    public String undecompilableReason() {
        return undecompilableReason;
    }

    /** Fallback-тело: raw-маркер и громкий throw для любого типа возврата. */
    private List<Ast.Stmt> fallbackBody() {
        List<Ast.Stmt> out = new ArrayList<>();
        String reason = undecompilableReason == null ? "other" : undecompilableReason;
        out.add(new Ast.Raw("// undecompilable: " + reason + " @ " + method.name + method.desc));
        // Громко и одинаково для void/non-void: тело не притворяется рабочим.
        // В <init> javac сам вставит super() перед первым оператором, в <clinit>
        // это даст ExceptionInInitializerError (учитывается в отчёте).
        out.add(new Ast.ExprStmt(new Ast.Call(null, "throw",
                List.of(new Ast.NewObject("Ljava/lang/UnsupportedOperationException;",
                        List.of(new Ast.Literal("undecompilable")))), false)));
        return out;
    }

    /**
     * Hard invariant: emitted ⊇ reachable — ни один достижимый блок не потерян —
     * и ровно одна эмиссия для блоков с сайд-эффектами. Потерянный блок может
     * нести return/throw/вызов/запись, то есть давать молча неверный код;
     * двойная эмиссия — тот же баг с другой стороны.
     */
    private void verifyEmittedBlocks() {
        // Exception-замыкание обязательно: handler, достижимый только из
        // catch-ребра, иначе не проверяется и теряется молча.
        java.util.Set<Integer> reachable = reachableWithHandlers();
        java.util.Set<Integer> normalReach = reachableBlocks();
        for (BasicBlock b : blocks) {
            int idx = b.index();
            int normal = normalEmits.getOrDefault(idx, 0);
            int catchCnt = catchEmits.getOrDefault(idx, 0);
            if (catchCnt > 1) HANDLER_DUPLICATES.addAndGet(catchCnt - 1);
            if (!reachable.contains(idx)) continue;
            if (!hasRealInstruction(b)) continue; // пустой блок-граница
            if (normal > 1 && hasSideEffect(b)) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.DOUBLE_EMIT, "BB" + idx);
            }
            if (normalReach.contains(idx)) {
                if (normal == 0) {
                    // Достижимый normal-flow блок не эмитирован нормально: либо потерян,
                    // либо (дыра обфускатора) в него прыгают normal-flow'ом в handler —
                    // тогда одного catch-эмита недостаточно.
                    throw new UndecompilableException(
                            UndecompilableException.Kind.LOST_BLOCK, "BB" + idx);
                }
            } else if (catchCnt == 0 && !droppedHandlers.contains(idx)) {
                // Достижим только через exception: закрывается catch-эмитом или
                // легальным снятием (пустой try/чистый rethrow). Иначе catch-код
                // исчез молча.
                throw new UndecompilableException(
                        UndecompilableException.Kind.LOST_BLOCK,
                        "handler BB" + idx + " emitted without catch");
            }
        }
    }

    /** Блоки-handler'ы: дублирование catch-кода легально и безопасно. */

    /** Есть ли в блоке наблюдаемый сайд-эффект (вызов, запись, монитор, throw). */
    private static boolean hasSideEffect(BasicBlock b) {
        for (AbstractInsnNode n : b.getInstructions()) {
            switch (n.getOpcode()) {
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC,
                     Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC,
                     Opcodes.PUTFIELD, Opcodes.PUTSTATIC,
                     Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                     Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE,
                     Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                     Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY,
                     Opcodes.ATHROW -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * Hard invariant (non-void): из entry нет пути к «выходу без значения».
     * Ловит missing return statement до javac.
     */
    /**
     * Hard invariant (non-void): из entry нет пути к «выходу без значения».
     * Ловит missing return statement до javac. Пути через catch-ребро тоже
     * проверяются: handler — такой же путь исполнения.
     */
    private void verifyAllPathsTerminate() {
        String ret = method.desc.substring(method.desc.indexOf(')') + 1);
        if ("V".equals(ret)) return;
        for (int idx : reachableWithHandlers()) {
            BasicBlock b = blocks.get(idx);
            if (!hasRealInstruction(b)) continue; // пустой label-блок — не точка выхода
            if (b.getSuccessors().isEmpty() && !terminatesExecution(b)) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.MISSING_RETURN, "BB" + idx);
            }
        }
    }

    /** Есть ли в блоке настоящая инструкция (не только label/line/frame). */
    private static boolean hasRealInstruction(BasicBlock b) {
        for (AbstractInsnNode n : b.getInstructions()) {
            if (n.getOpcode() >= 0) return true;
        }
        return false;
    }

    private static boolean terminatesExecution(BasicBlock b) {
        // Терминатор блока может быть хвостовым label/frame после return/throw —
        // getTerminator() вернёт их, и возврат «потеряется». Ищем последнюю
        // настоящую инструкцию.
        List<AbstractInsnNode> insns = b.getInstructions();
        for (int i = insns.size() - 1; i >= 0; i--) {
            int op = insns.get(i).getOpcode();
            if (op < 0) continue;
            return (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || op == Opcodes.ATHROW;
        }
        return false;
    }

    /**
     * DCE: обрезает список после первого return/throw, рекурсивно по вложенным.
     * Режем только чистые хвосты (присваивания локалям, чистые выражения). Хвост
     * с сайд-эффектом — DEAD_CODE bail: если структура криво линеаризовала
     * достижимый код, тихое удаление было бы laundering'ом.
     */
    private void pruneDeadStatements(List<Ast.Stmt> stmts) {
        if (stmts == null) return;
        for (int i = 0; i < stmts.size(); i++) {
            Ast.Stmt s = stmts.get(i);
            if (s instanceof Ast.If f) {
                pruneDeadStatements(f.thenBranch());
                pruneDeadStatements(f.elseBranch());
            } else if (s instanceof Ast.While w) {
                pruneDeadStatements(w.body());
            } else if (s instanceof Ast.Switch sw) {
                for (Ast.Case c : sw.cases()) pruneDeadStatements(c.body());
                pruneDeadStatements(sw.defaultBranch());
            } else if (s instanceof Ast.TryCatch tc) {
                pruneDeadStatements(tc.tryBody());
                pruneDeadStatements(tc.catchBody());
            } else if (s instanceof Ast.Block b) {
                pruneDeadStatements(b.body());
            }
            if (isTerminator(s) && i + 1 < stmts.size()) {
                // «Чистый» не значит «мёртвый»: return x; x = 2; при уже
                // инициализированном x меняет результат. CFG-доказуемости для
                // хвоста нет — значит это bail, а не DCE.
                throw new UndecompilableException(
                        UndecompilableException.Kind.DEAD_CODE, "tail after terminator");
            }
        }
    }

    /** Чистый (без сайд-эффектов и без бросающих операций) оператор. */
    private static boolean isClean(Ast.Stmt s) {
        if (s instanceof Ast.VarDecl) return true;
        if (s instanceof Ast.Return r) return isPureExpr(r.value());
        if (s instanceof Ast.Assign a) {
            return a.target() instanceof Ast.Var tv
                    && isLocalName(tv.name()) && isPureExpr(a.value());
        }
        if (s instanceof Ast.ExprStmt es) return isPureExpr(es.expr());
        return false;
    }

    private static boolean isLocalName(String n) {
        return n != null && n.matches("([iofd]Var\\d+|p\\d+)");
    }

    /** Выражение без вызовов/аллокаций/полей/кастов деления (не бросает и не пишет). */
    private static boolean isPureExpr(Ast.Expr e) {
        if (e == null || e instanceof Ast.Literal || e instanceof Ast.NullLit || e instanceof Ast.Var) {
            return true;
        }
        if (e instanceof Ast.Unary u) {
            // конверсии (long)/(int)/... — чистые; "!"/"-"/"~" — тоже
            return isPureExpr(u.operand());
        }
        if (e instanceof Ast.Binary b) {
            if ("/".equals(b.op()) || "%".equals(b.op())) return false;
            return isPureExpr(b.left()) && isPureExpr(b.right());
        }
        return false;
    }

    /** Hard invariant: в одном списке операторов нет кода после return/throw. */
    private static void verifyNoDeadStatements(List<Ast.Stmt> stmts) {
        if (stmts == null) return;
        boolean terminated = false;
        for (Ast.Stmt s : stmts) {
            if (terminated) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.DEAD_CODE, "statement after terminator");
            }
            if (s instanceof Ast.If i) {
                verifyNoDeadStatements(i.thenBranch());
                verifyNoDeadStatements(i.elseBranch());
            } else if (s instanceof Ast.While w) {
                verifyNoDeadStatements(w.body());
            } else if (s instanceof Ast.Switch sw) {
                for (Ast.Case c : sw.cases()) verifyNoDeadStatements(c.body());
                verifyNoDeadStatements(sw.defaultBranch());
            } else if (s instanceof Ast.TryCatch tc) {
                verifyNoDeadStatements(tc.tryBody());
                verifyNoDeadStatements(tc.catchBody());
            } else if (s instanceof Ast.Block b) {
                verifyNoDeadStatements(b.body());
            }
            terminated = isTerminator(s);
        }
    }

    /**
     * Hard invariant provenance: каждый литерал в выхлопе создан эмиттером из
     * байткодной инструкции или свёртки констант. Литерал вне реестра = скрытая
     * фабрикация (0/null «из воздуха») — метод помечается undecompilable.
     */
    private void verifyLiteralProvenance(List<Ast.Stmt> stmts) {
        checkProvenance(stmts);
    }

    private void checkProvenance(List<Ast.Stmt> stmts) {
        if (stmts == null) return;
        for (Ast.Stmt s : stmts) {
            if (s instanceof Ast.Return r) {
                checkProvenance(r.value());
            } else if (s instanceof Ast.Assign a) {
                checkProvenance(a.target());
                checkProvenance(a.value());
            } else if (s instanceof Ast.ExprStmt es) {
                checkProvenance(es.expr());
            } else if (s instanceof Ast.If i) {
                checkProvenance(i.cond());
                checkProvenance(i.thenBranch());
                checkProvenance(i.elseBranch());
            } else if (s instanceof Ast.While w) {
                checkProvenance(w.cond());
                checkProvenance(w.body());
            } else if (s instanceof Ast.Switch sw) {
                checkProvenance(sw.key());
                for (Ast.Case c : sw.cases()) checkProvenance(c.body());
                checkProvenance(sw.defaultBranch());
            } else if (s instanceof Ast.TryCatch tc) {
                checkProvenance(tc.tryBody());
                checkProvenance(tc.catchBody());
            } else if (s instanceof Ast.Block b) {
                checkProvenance(b.body());
            }
        }
    }

    private void checkProvenance(Ast.Expr e) {
        if (e == null) return;
        if (e instanceof Ast.NullLit n) {
            if (!sourcedLiterals.contains(n)) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.OTHER, "null without provenance");
            }
            return;
        }
        if (e instanceof Ast.Literal l) {
            if (!sourcedLiterals.contains(l)) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.OTHER, "literal without provenance");
            }
            return;
        }
        if (e instanceof Ast.Binary b) {
            checkProvenance(b.left());
            checkProvenance(b.right());
        } else if (e instanceof Ast.Unary u) {
            checkProvenance(u.operand());
        } else if (e instanceof Ast.Cast c) {
            checkProvenance(c.value());
        } else if (e instanceof Ast.Call c) {
            checkProvenance(c.target());
            for (Ast.Expr a : c.args()) checkProvenance(a);
        } else if (e instanceof Ast.Field f) {
            checkProvenance(f.target());
        } else if (e instanceof Ast.NewObject no) {
            for (Ast.Expr a : no.args()) checkProvenance(a);
        }
    }

    private static boolean isTerminator(Ast.Stmt s) {
        if (s instanceof Ast.Return) return true;
        return s instanceof Ast.ExprStmt es
                && es.expr() instanceof Ast.Call c && "throw".equals(c.name());
    }

    /**
     * Assert нового сплиттера: handler-метка обязана открывать блок (перед ней
     * только метки/line/frame). Иначе catch-блок снова слипнется с кодом.
     */
    private void verifyHandlerBoundaries() {
        for (var tcb : tryCatch) {
            Integer hi = labelToBlock.get(tcb.handler);
            if (hi == null || hi < 0 || hi >= blocks.size()) continue;
            boolean seen = false;
            boolean bad = false;
            for (AbstractInsnNode n : blocks.get(hi).getInstructions()) {
                if (n == tcb.handler) {
                    seen = true;
                    break;
                }
                if (n.getOpcode() >= 0) {
                    bad = true;
                    break;
                }
            }
            if (!seen || bad) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.OTHER, "handler label not a block start");
            }
        }
    }

    /** Достижимые от entry (а не от корней: entry может быть заголовком цикла). */
    private java.util.Set<Integer> reachableBlocks() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        int start = (entryIndex >= 0 && entryIndex < blocks.size()) ? entryIndex : firstRoot();
        if (start >= 0 && seen.add(start)) queue.add(start);
        while (!queue.isEmpty()) {
            BasicBlock b = blocks.get(queue.poll());
            for (BasicBlock s : b.getSuccessors()) {
                if (seen.add(s.index())) queue.add(s.index());
            }
        }
        return seen;
    }

    private int firstRoot() {
        for (BasicBlock b : blocks) {
            if (b.getPredecessors().isEmpty()) return b.index();
        }
        return -1;
    }

    /**
     * Reachability с exception-замыканием: CfgBuilder не строит exception-рёбра,
     * поэтому handler'ы добавляем сами для достижимых try-start'ов (транзитивно —
     * вложенный try внутри handler'а тоже попадает в замыкание).
     */
    private java.util.Set<Integer> reachableWithHandlers() {
        java.util.Map<Integer, java.util.List<Integer>> startHandlers = new java.util.HashMap<>();
        for (var tcb : tryCatch) {
            Integer s = labelToBlock.get(tcb.start);
            Integer h = labelToBlock.get(tcb.handler);
            if (s != null && h != null) {
                startHandlers.computeIfAbsent(s, k -> new ArrayList<>()).add(h);
            }
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        int start = (entryIndex >= 0 && entryIndex < blocks.size()) ? entryIndex : firstRoot();
        if (start >= 0 && seen.add(start)) queue.add(start);
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            BasicBlock b = blocks.get(idx);
            for (BasicBlock s : b.getSuccessors()) {
                if (seen.add(s.index())) queue.add(s.index());
            }
            for (int h : startHandlers.getOrDefault(idx, java.util.List.of())) {
                if (seen.add(h)) queue.add(h);
            }
        }
        return seen;
    }

    private static void restoreCounts(java.util.Map<Integer, Integer> m,
                                      java.util.Map<Integer, Integer> snap) {
        m.clear();
        m.putAll(snap);
    }

    private static void addDelta(java.util.Map<Integer, Integer> dst,
                                 java.util.Map<Integer, Integer> snap,
                                 java.util.Map<Integer, Integer> base) {
        for (var e : snap.entrySet()) {
            int d = e.getValue() - base.getOrDefault(e.getKey(), 0);
            if (d > 0) dst.merge(e.getKey(), d, Integer::sum);
        }
    }

    private static void restoreSet(java.util.Set<Integer> s, java.util.Set<Integer> snap) {
        s.clear();
        s.addAll(snap);
    }

    private static void addSetDelta(java.util.Set<Integer> dst, java.util.Set<Integer> snap,
                                    java.util.Set<Integer> base) {
        for (int v : snap) if (!base.contains(v)) dst.add(v);
    }

    /**
     * Hard invariant: у каждого достижимого try-start'а handler обязан быть
     * покрыт — эмитирован как catch, легально снят (пустой try/чистый rethrow)
     * или эмитирован нормально. Иначе catch-код исчез молча.
     */
    private void verifyHandlerCoverage() {
        java.util.Set<Integer> reachable = reachableWithHandlers();
        for (var tcb : tryCatch) {
            Integer start = labelToBlock.get(tcb.start);
            Integer handler = labelToBlock.get(tcb.handler);
            if (start == null || handler == null) continue;
            if (!reachable.contains(start)) continue;
            if (catchEmits.containsKey(handler)) continue;
            if (droppedHandlers.contains(handler)) continue;
            // normal-эмиссия handler'а НЕ закрывает try: catch-структуры нет,
            // исключение уйдёт мимо. Handler обязан быть catch-эмитом или снят.
            throw new UndecompilableException(
                    UndecompilableException.Kind.LOST_BLOCK, "uncovered handler BB" + handler);
        }
    }

    private List<Ast.Stmt> convertBody(StructureNode root) {
        List<Ast.Stmt> out = new ArrayList<>();
        convertNode(root, out);
        verifyEmittedBlocks();
        verifyHandlerCoverage();
        verifyHandlerBoundaries();
        verifyAllPathsTerminate();
        // Убираем мёртвые присваивания примитив-локалям (iVarN = const, нечитаемые).
        lowerPhi();
        List<Ast.Stmt> res = DeadStores.eliminate(out);
        // DCE: доказуемо недостижимый код после терминатора выкидываем, а не
        // роняем метод (это не потеря значения, а мусор структуры).
        pruneDeadStatements(res);
        verifyNoDeadStatements(res);
        verifyLiteralProvenance(res);
        // Гарантия валидного Java: non-void метод, в котором восстановитель структуры
        // потерял return (diamond-тернарники и т.п.), получает завершающий return <default>.
        String ret = method.desc.substring(method.desc.indexOf(')') + 1);
        if (!"V".equals(ret) && !containsReturn(res) && !cannotCompleteNormally(res)) {
            // Раньше здесь фабриковался return 0/null. Теперь — явный отказ:
            // литерал обязан иметь байткодный provenance.
            throw new UndecompilableException(
                    UndecompilableException.Kind.MISSING_RETURN, "no return emitted");
        }
        // Объявляем локальные переменные в начале метода (тип из slotTypes по слоту).
        java.util.Map<Integer, String> locals = new java.util.TreeMap<>();
        for (Ast.Stmt s : res) collectLocals(s, locals);
        if (!locals.isEmpty() || !phiDecls.isEmpty()) {
            List<Ast.Stmt> decls = new ArrayList<>();
            for (Ast.VarDecl d : phiDecls) decls.add(d);
            for (var e : locals.entrySet()) {
                int slot = e.getKey();
                String type = (slot >= 0 && slot < slotTypes.length && validType(slotTypes[slot]))
                        ? slotTypes[slot] : "Object";
                decls.add(new Ast.VarDecl(type, e.getValue()));
            }
            // В конструкторе super()/this() обязан идти первым — вставляем после него.
            int at = (!res.isEmpty() && isCtorDelegation(res.get(0))) ? 1 : 0;
            res.addAll(at, decls);
            normalizeBooleanLiterals(res);
            normalizeArrayTemps(res);
        }
        return res;
    }

    /** boolean-локалы и phi-темпы: тип из присваиваний, литералы 0/1 -> false/true. */
    private static void normalizeBooleanLiterals(java.util.List<Ast.Stmt> res) {
        java.util.Map<String, String> decl = new java.util.HashMap<>();
        walkStmts(res, l -> {
            for (Ast.Stmt s : l) if (s instanceof Ast.VarDecl d) decl.put(d.name(), d.type());
        });
        java.util.Set<String> bools = new java.util.HashSet<>();
        for (var e : decl.entrySet()) if ("boolean".equals(e.getValue())) bools.add(e.getKey());
        for (int round = 0; round < 3; round++) {
            java.util.List<String> promote = new java.util.ArrayList<>();
            walkStmts(res, l -> {
                for (Ast.Stmt s : l) {
                    if (!(s instanceof Ast.Assign a)) continue;
                    if (!(a.target() instanceof Ast.Var t) || !(a.value() instanceof Ast.Var v)) continue;
                    if (bools.contains(t.name()) && "int".equals(decl.get(v.name())) && !bools.contains(v.name())) {
                        promote.add(v.name());
                    }
                }
            });
            if (promote.isEmpty()) break;
            bools.addAll(promote);
        }
        walkStmts(res, l -> {
            for (int i = 0; i < l.size(); i++) {
                Ast.Stmt s = l.get(i);
                if (s instanceof Ast.VarDecl d && bools.contains(d.name()) && !"boolean".equals(d.type())) {
                    l.set(i, new Ast.VarDecl("boolean", d.name()));
                } else if (s instanceof Ast.Assign a && a.target() instanceof Ast.Var t
                        && bools.contains(t.name()) && a.value() instanceof Ast.Literal lit
                        && lit.value() instanceof Integer k && (k == 0 || k == 1)) {
                    l.set(i, new Ast.Assign(a.target(), new Ast.Literal(Boolean.valueOf(k == 1))));
                }
            }
        });
    }

    /** Тип массива для локалов/phi-темпов: из вызова с дескриптором-массивом и из соседних локалов. */
    private static void normalizeArrayTemps(java.util.List<Ast.Stmt> res) {
        java.util.Map<String, String> decl = new java.util.HashMap<>();
        walkStmts(res, l -> {
            for (Ast.Stmt s : l) if (s instanceof Ast.VarDecl d) decl.put(d.name(), d.type());
        });
        java.util.Set<String> changed = new java.util.HashSet<>();
        for (int round = 0; round < 3; round++) {
            final boolean[] any = {false};
            walkStmts(res, l -> {
                for (Ast.Stmt s : l) {
                    if (!(s instanceof Ast.Assign a) || !(a.target() instanceof Ast.Var t)) continue;
                    String cur = decl.get(t.name());
                    String src = null;
                    String srcType = null;
                    if (a.value() instanceof Ast.Var v) {
                        src = v.name();
                        srcType = decl.get(v.name());
                    }
                    if (isObjectish(cur) && srcType != null && srcType.endsWith("[]")) {
                        decl.put(t.name(), srcType);
                        changed.add(t.name());
                        any[0] = true;
                    } else if (cur != null && cur.endsWith("[]") && src != null && isObjectish(srcType)) {
                        decl.put(src, cur);
                        changed.add(src);
                        any[0] = true;
                    } else if (isObjectish(cur) && a.value() instanceof Ast.Call c
                            && c.retDesc() != null && c.retDesc().startsWith("[")) {
                        decl.put(t.name(), Ast.friendlyType(c.retDesc()));
                        changed.add(t.name());
                        any[0] = true;
                    }
                }
            });
            if (!any[0]) break;
        }
        java.util.Set<String> usedAsArray = new java.util.HashSet<>();
        java.util.Set<String> primitiveWrite = new java.util.HashSet<>();
        walkStmts(res, l -> {
            for (Ast.Stmt st : l) {
                if (st instanceof Ast.Assign as2 && as2.target() instanceof Ast.Binary bt
                        && "[]=".equals(bt.op()) && bt.left() instanceof Ast.Var tv) {
                    usedAsArray.add(tv.name());
                    if (as2.value() instanceof Ast.Literal li && !(li.value() instanceof Boolean)
                            && !(li.value() instanceof String)) {
                        primitiveWrite.add(tv.name());
                    }
                }
            }
        });
        for (String nm : usedAsArray) {
            if (!primitiveWrite.contains(nm) && isObjectish(decl.get(nm)) && !"boolean".equals(decl.get(nm))) {
                decl.put(nm, "Object[]");
                changed.add(nm);
            }
        }        if (changed.isEmpty()) return;
        walkStmts(res, l -> {
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i) instanceof Ast.VarDecl d && changed.contains(d.name())
                        && !"boolean".equals(d.type())) {
                    l.set(i, new Ast.VarDecl(decl.get(d.name()), d.name()));
                }
            }
        });
    }

    /** Тип массива из дескриптора NewObject: "[I" или "Ljava/lang/Object;;[]". */
    private static String arrayTypeOfNew(String d) {
        if (d == null) return null;
        if (d.startsWith("[")) {
            try {
                return Ast.friendlyType(d);
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (!d.endsWith("[]")) return null;
        String base = d.substring(0, d.length() - 2);
        if (base.startsWith("L") && base.endsWith(";")) {
            return base.substring(1, base.length() - 1).replace('/', '.') + "[]";
        }
        if (base.length() == 1) return Ast.friendlyType("[" + base);
        return base.replace('/', '.') + "[]";
    }
    private static boolean isObjectish(String t) {
        return t == null || "Object".equals(t) || "java.lang.Object".equals(t);
    }
    private static void walkStmts(java.util.List<Ast.Stmt> list, java.util.function.Consumer<java.util.List<Ast.Stmt>> visit) {
        visit.accept(list);
        for (Ast.Stmt s : list) {
            if (s instanceof Ast.Block b) {
                walkStmts(b.body(), visit);
            } else if (s instanceof Ast.If f) {
                walkStmts(f.thenBranch(), visit);
                if (f.elseBranch() != null) walkStmts(f.elseBranch(), visit);
            } else if (s instanceof Ast.While w) {
                walkStmts(w.body(), visit);
            } else if (s instanceof Ast.Switch sw) {
                for (Ast.Case c : sw.cases()) walkStmts(c.body(), visit);
                if (sw.defaultBranch() != null) walkStmts(sw.defaultBranch(), visit);
            } else if (s instanceof Ast.TryCatch tc) {
                walkStmts(tc.tryBody(), visit);
                walkStmts(tc.catchBody(), visit);
            }
        }
    }
    private static boolean validType(String t) {
        return t != null && !t.isEmpty() && !"?".equals(t);
    }

    private static boolean isCtorDelegation(Ast.Stmt s) {
        return s instanceof Ast.ExprStmt es && es.expr() instanceof Ast.Call c
                && ("super".equals(c.name()) || "this".equals(c.name()));
    }

    /** Собирает имена локальных переменных (iVarN/oVarN/…) по номеру слота. */
    private static void collectLocals(Ast.Stmt s, java.util.Map<Integer, String> out) {
        if (s == null) return;
        if (s instanceof Ast.Assign a) { collectLocals(a.target(), out); collectLocals(a.value(), out); }
        else if (s instanceof Ast.Return r) collectLocals(r.value(), out);
        else if (s instanceof Ast.ExprStmt es) collectLocals(es.expr(), out);
        else if (s instanceof Ast.VarDecl) { /* уже объявление */ }
        else if (s instanceof Ast.If i) {
            collectLocals(i.cond(), out);
            for (Ast.Stmt t : i.thenBranch()) collectLocals(t, out);
            if (i.elseBranch() != null) for (Ast.Stmt t : i.elseBranch()) collectLocals(t, out);
        } else if (s instanceof Ast.While w) {
            collectLocals(w.cond(), out);
            for (Ast.Stmt t : w.body()) collectLocals(t, out);
        } else if (s instanceof Ast.Switch sw) {
            collectLocals(sw.key(), out);
            for (Ast.Case c : sw.cases()) for (Ast.Stmt t : c.body()) collectLocals(t, out);
            if (sw.defaultBranch() != null) for (Ast.Stmt t : sw.defaultBranch()) collectLocals(t, out);
        } else if (s instanceof Ast.TryCatch tc) {
            for (Ast.Stmt t : tc.tryBody()) collectLocals(t, out);
            for (Ast.Stmt t : tc.catchBody()) collectLocals(t, out);
        } else if (s instanceof Ast.Block b) {
            for (Ast.Stmt t : b.body()) collectLocals(t, out);
        }
    }

    private static void collectLocals(Ast.Expr e, java.util.Map<Integer, String> out) {
        if (e == null) return;
        if (e instanceof Ast.Var v) {
            var m = LOCAL.matcher(v.name());
            if (m.matches()) out.putIfAbsent(Integer.parseInt(m.group(2)), v.name());
        } else if (e instanceof Ast.Binary b) {
            collectLocals(b.left(), out); collectLocals(b.right(), out);
        } else if (e instanceof Ast.Unary u) {
            collectLocals(u.operand(), out);
        } else if (e instanceof Ast.Cast cast) {
            collectLocals(cast.value(), out);
        } else if (e instanceof Ast.Call c) {
            collectLocals(c.target(), out);
            for (Ast.Expr a : c.args()) collectLocals(a, out);
        } else if (e instanceof Ast.Field f) {
            collectLocals(f.target(), out);
        } else if (e instanceof Ast.NewObject no) {
            for (Ast.Expr a : no.args()) collectLocals(a, out);
        }
    }

    private static final java.util.regex.Pattern LOCAL =
            java.util.regex.Pattern.compile("([ilfdo]Var)(\\d+)");

    private static boolean containsReturn(List<Ast.Stmt> stmts) {
        if (stmts == null) return false;
        for (Ast.Stmt s : stmts) if (containsReturn(s)) return true;
        return false;
    }

    private static boolean containsReturn(Ast.Stmt s) {
        if (s instanceof Ast.Return) return true;
        if (s instanceof Ast.If i) return containsReturn(i.thenBranch()) || containsReturn(i.elseBranch());
        if (s instanceof Ast.While w) return containsReturn(w.body());
        if (s instanceof Ast.Switch sw) {
            for (Ast.Case c : sw.cases()) if (containsReturn(c.body())) return true;
            return containsReturn(sw.defaultBranch());
        }
        if (s instanceof Ast.TryCatch tc) return containsReturn(tc.tryBody()) || containsReturn(tc.catchBody());
        if (s instanceof Ast.Block b) return containsReturn(b.body());
        return false;
    }

    private static boolean endsWithThrow(List<Ast.Stmt> stmts) {
        if (stmts == null || stmts.isEmpty()) return false;
        Ast.Stmt last = stmts.get(stmts.size() - 1);
        return last instanceof Ast.ExprStmt es && es.expr() instanceof Ast.Call c && "throw".equals(c.name());
    }

    /**
     * Не может ли поток исполнения дойти до конца списка. Нужен для проверки
     * non-void метода без return: метод, все пути которого завершаются throw
     * (в т.ч. try/catch с throw в обеих ветвях), компилируется без return.
     */
    private static boolean cannotCompleteNormally(List<Ast.Stmt> stmts) {
        if (stmts == null || stmts.isEmpty()) return false;
        return cannotCompleteNormally(stmts.get(stmts.size() - 1));
    }

    private static boolean cannotCompleteNormally(Ast.Stmt s) {
        if (s instanceof Ast.Return) return true;
        if (s instanceof Ast.ExprStmt es && es.expr() instanceof Ast.Call c
                && "throw".equals(c.name())) {
            return true;
        }
        if (s instanceof Ast.If i) {
            return cannotCompleteNormally(i.thenBranch()) && cannotCompleteNormally(i.elseBranch());
        }
        if (s instanceof Ast.Block b) return cannotCompleteNormally(b.body());
        if (s instanceof Ast.TryCatch tc) {
            return cannotCompleteNormally(tc.tryBody()) && cannotCompleteNormally(tc.catchBody());
        }
        return false;
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
                    out.add(new Ast.Block(blockToStmt(idx)));
                }
            }
            case LOOP -> {
                int header = node.loopHeader();
                java.util.Set<Integer> bodyIdx = new java.util.HashSet<>(node.loopBody());
                Ast.Expr cond = loopCond(header, bodyIdx);
                if (!lastCondStatements.isEmpty()) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.OTHER, "loop header prelude");
                }
                normalEmits.merge(header, 1, Integer::sum);
                java.util.Map<Integer, Integer> nBefore = new java.util.HashMap<>(normalEmits);
                java.util.Map<Integer, Integer> cBefore = new java.util.HashMap<>(catchEmits);
                java.util.Set<Integer> dBefore = new java.util.HashSet<>(droppedHandlers);
                java.util.Set<Integer> sBefore = new java.util.HashSet<>(switchConsumed);
                List<Ast.Stmt> body = new ArrayList<>();
                for (StructureNode c : node.children()) {
                    if (outTerminated(body)) break; // недостижимые после return/throw узлы тела цикла
                    body.addAll(nodeToStmts(c));
                }
                // Свёртка непрозрачного предиката в УСЛОВИИ цикла:
                // while (1*1+1%2 != 0) -> while(true); while(false) -> тело мёртвое, не эмитим.
                Object cv = Ast.constValue(cond);
                if (cv instanceof Boolean b) {
                    CONST_FOLDED.incrementAndGet();
                    if (b) {
                        out.add(new Ast.While(sourced(new Ast.Literal(true)), body));
                    } else {
                        // while(false): тело мёртвое — откатываем его счётчики
                        // и съеденные handler'ы/switch-блоки.
                        restoreCounts(normalEmits, nBefore);
                        restoreCounts(catchEmits, cBefore);
                        restoreSet(droppedHandlers, dBefore);
                        restoreSet(switchConsumed, sBefore);
                    }
                } else {
                    out.add(new Ast.While(cond, body));
                }
            }
            case IF_ELSE -> {
                int condIdx = node.ifCondIndex();
                Ast.Expr cond = condFrom(condIdx);
                java.util.Map<Integer, Integer> nBefore = new java.util.HashMap<>(normalEmits);
                java.util.Map<Integer, Integer> cBefore = new java.util.HashMap<>(catchEmits);
                java.util.Set<Integer> dBefore = new java.util.HashSet<>(droppedHandlers);
                java.util.Set<Integer> sBefore = new java.util.HashSet<>(switchConsumed);
                List<Ast.Stmt> thenSt = node.thenBranch() != null ? nodeToStmts(node.thenBranch()) : new ArrayList<>();
                java.util.Map<Integer, Integer> nThen = new java.util.HashMap<>(normalEmits);
                java.util.Map<Integer, Integer> cThen = new java.util.HashMap<>(catchEmits);
                java.util.Set<Integer> dThen = new java.util.HashSet<>(droppedHandlers);
                java.util.Set<Integer> sThen = new java.util.HashSet<>(switchConsumed);
                restoreCounts(normalEmits, nBefore);
                restoreCounts(catchEmits, cBefore);
                restoreSet(droppedHandlers, dBefore);
                restoreSet(switchConsumed, sBefore);
                List<Ast.Stmt> elseSt = node.elseBranch() != null ? nodeToStmts(node.elseBranch()) : new ArrayList<>();
                java.util.Map<Integer, Integer> nElse = new java.util.HashMap<>(normalEmits);
                java.util.Map<Integer, Integer> cElse = new java.util.HashMap<>(catchEmits);
                java.util.Set<Integer> dElse = new java.util.HashSet<>(droppedHandlers);
                java.util.Set<Integer> sElse = new java.util.HashSet<>(switchConsumed);
                // Свёртка непрозрачных предикатов обфускатора (1*1+1%2 != 0 и т.п.).
                // Если условие константно, подставляем взятую ветвь. False-ветвь
                // (else) — это ложный путь = продолжение/эпилог, поэтому дописываем
                // её после взятой ветви только если та не терминирует (return/throw).
                Object cv = Ast.constValue(cond);
                if (cv instanceof Boolean b) {
                    // Условие константно: живая ровно одна ветвь; мёртвую не эмитим
                    // (иначе исполнятся её сайд-эффекты). Join — после if.
                    CONST_FOLDED.incrementAndGet();
                    // record→commit: считаем только живую ветвь, мёртвую не эмитим.
                    restoreCounts(normalEmits, b ? nThen : nElse);
                    restoreCounts(catchEmits, b ? cThen : cElse);
                    restoreSet(droppedHandlers, b ? dThen : dElse);
                    restoreSet(switchConsumed, b ? sThen : sElse);
                    out.addAll(b ? thenSt : elseSt);
                } else {
                    // Обе ветви реально эмитятся — union дельт от базовой точки.
                    restoreCounts(normalEmits, nBefore);
                    addDelta(normalEmits, nThen, nBefore);
                    addDelta(normalEmits, nElse, nBefore);
                    restoreCounts(catchEmits, cBefore);
                    addDelta(catchEmits, cThen, cBefore);
                    addDelta(catchEmits, cElse, cBefore);
                    restoreSet(droppedHandlers, dBefore);
                    addSetDelta(droppedHandlers, dThen, dBefore);
                    addSetDelta(droppedHandlers, dElse, dBefore);
                    restoreSet(switchConsumed, sBefore);
                    addSetDelta(switchConsumed, sThen, sBefore);
                    addSetDelta(switchConsumed, sElse, sBefore);
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
    /** Начинается ли блок с try-range (его start-метка). */
    private boolean startsTry(int idx) {
        for (var tcb : tryCatch) {
            Integer s = labelToBlock.get(tcb.start);
            if (s != null && s == idx) return true;
        }
        return false;
    }

    private Ast.Stmt tryBuildTryCatch(int blockIndex) {
        java.util.List<org.objectweb.asm.tree.TryCatchBlockNode> matches = new ArrayList<>();
        for (var tcb : tryCatch) {
            Integer startBlock = labelToBlock.get(tcb.start);
            if (startBlock != null && startBlock == blockIndex) matches.add(tcb);
        }
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) {
            // Несколько handler'ов на один try (multi-catch/anti-tamper): модель
            // Ast.TryCatch хранит один catch — не притворяемся, что покрыли все.
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER, "multiple handlers at try start");
        }
        var match = matches.get(0);

        // catch-блок (handler)
        Integer handlerBlock = labelToBlock.get(match.handler);
        if (handlerBlock == null) return null;

        // Модель try-body = один стартовый блок. Многоблочный range она не
        // покрывает: чистый rethrow-handler — пустая обёртка (снимаем), иначе
        // честный отказ, пока нет регионов.
        if (!rangeIsSingleBlock(blockIndex, match)) {
            int rethrowSlot = rethrowSlot(handlerBlock);
            if (rethrowSlot == -2) RETHROW_SLOT_MINUS2.incrementAndGet();
            else if (rethrowSlot >= 0) RETHROW_SLOT_GTE0.incrementAndGet();
            else RETHROW_SLOT_MINUS1.incrementAndGet();
            // Снимаем обёртку только если ASTORE-слот после rethrow'а больше не
            // читается (иначе внешний handler может зависеть от записи).
            if (rethrowSlot == -2 || (rethrowSlot >= 0 && !slotReadAnywhere(rethrowSlot))) {
                droppedHandlers.add(handlerBlock);
                RETHROW_DROPPED.incrementAndGet();
                switchConsumed.add(handlerBlock);
                return null;
            }
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER, "multi-block try range");
        }

        // try-body: С‚РµР»Рѕ РЅР°С€РµРіРѕ Р±Р»РѕРєР° + С‚РµР»Рѕ handler'Р° РєР°Рє catch
        // (СѓРїСЂРѕС‰С‘РЅРЅРѕ: try-body вЂ” С‚РµРєСѓС‰РёР№ Р±Р»РѕРє, catch вЂ” Р±Р»РѕРє handler'Р°)
        List<Ast.Stmt> tryBody = blockToStmt(blockIndex, false);
        // Анти-тампер обфускатора: `try { } catch (Throwable e) { return <dead>; throw(0); }`
        // при ПУСТОМ try — весь конструкт мёртв (catch недостижим). Выкидываем его,
        // пометив handler потреблённым, чтобы он не напечатался отдельным блоком.
        if (tryBody.isEmpty()) {
            droppedHandlers.add(handlerBlock);
            switchConsumed.add(handlerBlock);
            return null;
        }
        // try-body реально попадёт в вывод — теперь эмиссию можно считать.
        normalEmits.merge(blockIndex, 1, Integer::sum);
        String cvName = catchCounter == 0 ? "e" : "e" + catchCounter;
        catchCounter++;
        Ast.Var cv = new Ast.Var(cvName);
        List<Ast.Stmt> catchBody = handlerBlock < blocks.size()
                ? runHandlerBlock(handlerBlock, cvName) : new ArrayList<>();
        if (handlerBlock < blocks.size()) {
            for (BasicBlock succ : blocks.get(handlerBlock).getSuccessors()) {
                if (!startsTry(succ.index())) continue;
                if (normalEmits.getOrDefault(succ.index(), 0) > 0) continue;
                switchConsumed.add(succ.index());
                Ast.Stmt nested = tryBuildTryCatch(succ.index());
                if (nested != null) catchBody.add(nested);
            }
        }
        // РїРѕРјРµС‚РёС‚СЊ handler-Р±Р»РѕРє РїРѕС‚СЂРµР±Р»С‘РЅРЅС‹Рј, С‡С‚РѕР±С‹ РЅРµ РїРµС‡Р°С‚Р°Р»СЃСЏ РїРѕРІС‚РѕСЂРЅРѕ
        switchConsumed.add(handlerBlock);

        String type = match.type; // РјРѕР¶РµС‚ Р±С‹С‚СЊ null (finally) вЂ” РЅР° СЌС‚РѕРј СЌС‚Р°РїРµ РїСЂРѕСЃС‚Рѕ РґРµС„РѕР»С‚
        return new Ast.TryCatch(tryBody, type, cv, catchBody);
    }

    /** Покрывает ли try-range ровно стартовый блок (end — на его границе). */
    private boolean rangeIsSingleBlock(int blockIndex, org.objectweb.asm.tree.TryCatchBlockNode tcb) {
        Integer endBlock = labelToBlock.get(tcb.end);
        if (endBlock == null) return false;
        if (endBlock == blockIndex) return true;
        if (endBlock != blockIndex + 1) return false;
        for (AbstractInsnNode n : blocks.get(endBlock).getInstructions()) {
            if (n == tcb.end) return true;
            if (n.getOpcode() >= 0) return false;
        }
        return false;
    }

    /**
     * Handler — чистый rethrow: ATHROW (-2) или ASTORE v; ALOAD v; ATHROW (v >= 0).
     * Любая другая форма — -1 (не снимаем).
     */
    private int rethrowSlot(int handlerBlock) {
        if (handlerBlock < 0 || handlerBlock >= blocks.size()) return -1;
        int state = 0;
        int slot = -1;
        for (AbstractInsnNode n : blocks.get(handlerBlock).getInstructions()) {
            int op = n.getOpcode();
            if (op < 0) continue;
            if (state == 0) {
                if (op == Opcodes.ATHROW) return -2;
                if (op == Opcodes.ASTORE) {
                    slot = ((org.objectweb.asm.tree.VarInsnNode) n).var;
                    state = 1;
                } else {
                    return -1;
                }
            } else if (state == 1) {
                if (op == Opcodes.ALOAD
                        && ((org.objectweb.asm.tree.VarInsnNode) n).var == slot) {
                    state = 2;
                } else {
                    return -1;
                }
            } else {
                return op == Opcodes.ATHROW ? slot : -1;
            }
        }
        return -1;
    }

    /**
     * Читается ли слот где-либо ещё в методе, кроме собственного ALOAD rethrow'а.
     * Консервативно по всему методу: block-shuffle может положить внешний handler
     * в байткоде раньше, и линейный скан «после athrow» его не увидит.
     */
    private boolean slotReadAnywhere(int slot) {
        int reads = 0;
        for (AbstractInsnNode n : method.instructions) {
            int op = n.getOpcode();
            if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD
                    && ((org.objectweb.asm.tree.VarInsnNode) n).var == slot) {
                reads++;
            }
        }
        // один ALOAD — сам rethrow; больше — слот читается (в т.ч. внешним handler'ом)
        return reads > 1;
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
        if (!emu.lastStatements().isEmpty()) {
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER, "switch key prelude");
        }
        // Диспетчер эмитится как заголовок switch — учитываем как normal-эмиссию.
        normalEmits.merge(blockIndex, 1, Integer::sum);

        // РїРѕРјРµС‚РёС‚СЊ case/default-Р±Р»РѕРєРё РєР°Рє РїРѕРіР»РѕС‰С‘РЅРЅС‹Рµ (С‡С‚РѕР±С‹ РЅРµ РїРµС‡Р°С‚Р°С‚СЊ РёС… СЃРЅРѕРІР°)
        for (int cb : caseBlocks) switchConsumed.add(cb);
        if (dfltBlock != null) switchConsumed.add(dfltBlock);

        // СЃРѕР±РёСЂР°РµРј case-РІРµС‚РІРё (С‚РµР»Р° вЂ” Р±Р»РѕРєРё СЃ return)
        // Несколько ключей могут делить одно тело (javac: case 1: case 2: body).
        // Тело эмитим ровно один раз — для последнего ключа группы, остальные
        // получают пустое тело (fallthrough). Иначе блок с сайд-эффектом
        // эмитится на каждый ключ и ловится DOUBLE_EMIT.
        java.util.Map<Integer, Integer> occurrences = new java.util.HashMap<>();
        for (int cb : caseBlocks) {
            if (cb >= 0 && cb < blocks.size()) occurrences.merge(cb, 1, Integer::sum);
        }
        if (dfltBlock != null && occurrences.containsKey(dfltBlock)) {
            // default делит тело с case: печатать его пустым нельзя (уйдёт
            // fallthrough не туда), дублировать тело — тоже.
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER, "switch default shares case body");
        }
        List<Ast.Case> cases = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            int cb = caseBlocks.get(i);
            if (cb < 0 || cb >= blocks.size()) continue;
            int left = occurrences.merge(cb, -1, Integer::sum);
            List<Ast.Stmt> body = left == 0 ? blockToStmt(cb) : new ArrayList<>();
            cases.add(new Ast.Case(keys.get(i), body));
        }
        List<Ast.Stmt> dfltBody = dfltBlock != null && dfltBlock < blocks.size()
                ? blockToStmt(dfltBlock) : new ArrayList<>();
        return new Ast.Switch(key, cases, dfltBody);
    }

/** РћРїРµСЂР°С‚РѕСЂС‹ РґР»СЏ РѕРґРЅРѕРіРѕ CFG-Р±Р»РѕРєР°: Р±Р°Р№С‚РєРѕРґ Р±Р»РѕРєР° -> (РїСЂРёСЃРІР°РёРІР°РЅРёСЏ + return). */
    /**
     * Эмиссия catch-блока: на входе handler'а стек ровно [exception], поэтому
     * стартуем с {@code e} — иначе {@code ATHROW} (rethrow) терялся молча.
     */
    private List<Ast.Stmt> runHandlerBlock(int blockIndex, String varName) {
        catchEmits.merge(blockIndex, 1, Integer::sum);
        BasicBlock bb = blocks.get(blockIndex);
        BytecodeToExpr emu = newEmu();
        List<AbstractInsnNode> all = new ArrayList<>(bb.getInstructions());
        return emu.runBlock(all, false, List.of(new Ast.Var(varName)));
    }

    private List<Ast.Stmt> blockToStmt(int blockIndex) {
        return blockToStmt(blockIndex, true);
    }

    /**
     * @param count false — пробная эмиссия (try-body): её нельзя считать в
     *              emitCounts, иначе пустой try даёт ложный double emit.
     */
    /** phi-сайт: входное значение блока, материализуемое временной. */
    private record PhiSite(int block, int pos, String type, Ast.Var temp) {
    }

    private java.util.Map<Integer, List<StackValue>> entryStacksCache;

    private java.util.Map<Integer, List<StackValue>> entryStacks() {
        if (entryStacksCache == null) entryStacksCache = StackInference.byInstruction(method);
        return entryStacksCache;
    }

    /** Входные значения блока, если он стартует с непустым стеком (tN). */
    private List<Ast.Expr> entryExprs(int blockIndex) {
        List<Ast.Expr> cached = entryCache.get(blockIndex);
        if (cached != null) return cached;
        BasicBlock b = blocks.get(blockIndex);
        AbstractInsnNode first = b.getFirstReal();
        if (first == null) return List.of();
        List<StackValue> want = entryStacks().get(method.instructions.indexOf(first));
        if (want == null || want.isEmpty()) return List.of();
        List<Ast.Expr> out = new ArrayList<>();
        for (int i = 0; i < want.size(); i++) {
            StackValue v = want.get(i);
            String vt = v.type();
            if (("Object".equals(vt) || "java.lang.Object".equals(vt)) && v.slot() >= 0
                    && v.slot() < slotTypes.length && slotTypes[v.slot()] != null
                    && slotTypes[v.slot()].endsWith("[]")) {
                vt = slotTypes[v.slot()];
            }
            Ast.Var t = new Ast.Var("t" + blockIndex + "_" + i);
            phiDecls.add(new Ast.VarDecl(vt, t.name()));
            phiSites.put(blockIndex + "_" + i, new PhiSite(blockIndex, i, vt, t));
            PHI_SITES.incrementAndGet();
            out.add(t);
        }
        entryCache.put(blockIndex, out);
        return out;
    }

    /**
     * Post-pass phi: после финальной структуры присвоить tN в предшественниках.
     * Спекулятивные ветви к этому моменту отброшены; нет значения — отказ.
     */
    private void lowerPhi() {
        for (PhiSite s : phiSites.values()) {
            BasicBlock b = blocks.get(s.block);
            List<BasicBlock> preds = b.getPredecessors();            if (preds.isEmpty()) {
                throw new UndecompilableException(
                        UndecompilableException.Kind.JOIN_DEPTH, "phi without predecessors");
            }
            List<Ast.Expr> vals = new ArrayList<>();
            for (BasicBlock p : preds) {
                List<Ast.Expr> r = blockResults.get(p.index());
                if (r == null || r.size() <= s.pos) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.JOIN_DEPTH, "phi without predecessor value");
                }
                vals.add(r.get(s.pos));
            }
            for (int k = 0; k < preds.size(); k++) {
                List<List<Ast.Stmt>> lists = blockStmts.get(preds.get(k).index());
                if (lists == null || lists.isEmpty()) {
                    throw new UndecompilableException(
                            UndecompilableException.Kind.JOIN_DEPTH, "phi without block stmts");
                }
                for (List<Ast.Stmt> pl : lists) {
                    pl.add(new Ast.Assign(s.temp, vals.get(k)));
                    PHI_INJECTED.incrementAndGet();
                }
                PHI_INJECTED.incrementAndGet();
            }
        }
    }
    private List<Ast.Stmt> blockToStmt(int blockIndex, boolean count) {
        if (count) normalEmits.merge(blockIndex, 1, Integer::sum);
        BasicBlock bb = blocks.get(blockIndex);
        BytecodeToExpr emu = newEmu();
        List<AbstractInsnNode> all = new ArrayList<>(bb.getInstructions());
        List<Ast.Expr> entry = entryExprs(blockIndex);
        List<Ast.Stmt> stmts;
        try {
            stmts = emu.runBlock(all, false, entry);
        } catch (UndecompilableException ex) {
            if (ex.kind() == UndecompilableException.Kind.UNDERFLOW
                    && "underflow".equals(ex.getMessage())) {
                throw new UndecompilableException(UndecompilableException.Kind.UNDERFLOW,
                        "b=" + blockIndex + " entry=" + entry.size());
            }
            throw ex;
        }
        blockStmts.computeIfAbsent(blockIndex, k -> new ArrayList<>()).add(stmts);
        blockResults.put(blockIndex, emu.stackSnapshot());
        return stmts;
    }

    /** Эмулятор с привязанным версионным резолвером слота 0 и признаком boolean-возврата. */
    private BytecodeToExpr newEmu() {
        return new BytecodeToExpr(names, slotTypes, decryptor)
                .withSlotZero(slotZero)
                .booleanReturning(isBooleanReturn(method.desc))
                .currentOwner(classOwner)
                .withLiteralSink(sourcedLiterals)
                .withSlotReads(this::slotReadAnywhere)
                .staticMethod(isStatic);
    }

    /** РЈСЃР»РѕРІРёРµ Р±Р»РѕРєР° (РґРѕ СѓСЃР»РѕРІРЅРѕРіРѕ РїРµСЂРµС…РѕРґР°). */
    /** Операторы prelude из последнего condFrom (для проверки потерь). */
    private List<Ast.Stmt> lastCondStatements = List.of();

    private Ast.Expr condFrom(int blockIndex) {
        lastCondStatements = java.util.List.of();
        BasicBlock bb = blocks.get(blockIndex);
        BytecodeToExpr emu = newEmu();
        List<AbstractInsnNode> prefix = new ArrayList<>();
        for (AbstractInsnNode n : bb.getInstructions()) {
            prefix.add(n); // Р±РµСЂС‘Рј Р’РЎР• РёРЅСЃС‚СЂСѓРєС†РёРё, РІРєР»СЋС‡Р°СЏ С‚РµСЂРјРёРЅР°Р»СЊРЅС‹Р№ IFxx
        }
        // РїСЂРѕРіРѕРЅСЏРµРј СЃРѕ РІСЃРµРјРё РёРЅСЃС‚СЂСѓРєС†РёСЏРјРё вЂ” С‚РµСЂРјРёРЅР°Р»СЊРЅС‹Р№ СѓСЃР»РѕРІРЅС‹Р№ РїРµСЂРµС…РѕРґ
        // СЃС„РѕСЂРјРёСЂСѓРµС‚ СЃСЂР°РІРЅРµРЅРёРµ РЅР° СЃС‚РµРєРµ, runExpr РІРµСЂРЅС‘С‚ РµРіРѕ РІРµСЂС€РёРЅСѓ.
        Ast.Expr e;
        try {
            e = emu.runExpr(prefix, entryExprs(blockIndex));
        } catch (UndecompilableException ex) {
            if (ex.kind() == UndecompilableException.Kind.UNDERFLOW
                    && "underflow".equals(ex.getMessage())) {
                throw new UndecompilableException(UndecompilableException.Kind.UNDERFLOW, "cond");
            }
            throw ex;
        }
        lastCondStatements = emu.lastStatements();
        if (e == null) {
            // Условие не построено: фабриковать true нельзя (provenance).
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER, "condition lost");
        }
        return e;
    }

    private Ast.Expr loopCond(int header, java.util.Set<Integer> bodyIdx) {
        lastCondStatements = java.util.List.of();
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
        return sourced(new Ast.Literal(true));
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

    /** Тип возврата метода из дескриптора — boolean (»)Z). */
    private static boolean isBooleanReturn(String desc) {
        int rp = desc.lastIndexOf(')');
        return rp >= 0 && rp + 1 < desc.length() && desc.charAt(rp + 1) == 'Z';
    }
}