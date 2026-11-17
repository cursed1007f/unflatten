package decompiler.ast;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Вывод типов локальных слотов метода.
 *
 * <p>Точнее фильтра типа, чем входной кадр BasicInterpreter: анализирует все
 * фреймы через {@link SimpleVerifier}, а тип слота берёт по месту записи
 * (store) — из кадра в точке инструкции. Параметры типизируются строго по
 * дескриптору метода.
 *
 * <p>Результат — {@code String[]} дружественных имён типов по номеру слота
 * (например {@code "int"}, {@code "java.lang.String"}, {@code "String[]"}).
 */
public final class TypeInference {

    private TypeInference() {
    }

    /**
     * Выводит типы слотов.
     *
     * @param mn       метод
     * @param isStatic флаг static (слот 0 = this у instance)
     */
    public static String[] infer(MethodNode mn, boolean isStatic, String classOwner) {
        return infer(mn, isStatic, classOwner, null);
    }

    public static String[] infer(MethodNode mn, boolean isStatic, String classOwner,
                                 ClassHierarchy hierarchy) {
        int max = mn.maxLocals;
        String[] out = new String[max];
        for (int i = 0; i < max; i++) out[i] = "Object";

        // параметры по дескриптору
        int slot = 0;
        if (!isStatic) {
            String owner = classOwner != null ? classOwner.replace('/', '.') : "Object";
            out[0] = owner;
            slot = 1;
        }
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            String nm = friendly(t);
            out[slot] = nm;
            slot += t.getSize();
        }

        // локальные: тип по store-точке из кадров.
        // SimpleVerifier плохо наследуется (его конструктор кидает
        // IllegalStateException для любого подкласса — защита от наследования),
        // поэтому используем BasicVerifier: он без рефлексии и даёт точный
        // тип ссылочного значения через getType(). Если и он падает —
        // фолбэк на BasicInterpreter (примитивы и базовые объекты).
        Frame<BasicValue>[] frames = tryAnalyze(mn, new org.objectweb.asm.tree.analysis.BasicVerifier());
        if (frames == null) frames = tryAnalyze(mn, new org.objectweb.asm.tree.analysis.BasicInterpreter());
        if (frames != null) {
            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (int i = 0; i < insns.length && i < frames.length; i++) {
                Frame<BasicValue> f = frames[i];
                AbstractInsnNode n = insns[i];
                if (f == null || !(n instanceof VarInsnNode v)) continue;
                int op = v.getOpcode();
                if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) {
                    // frames[i] — состояние ДО insns[i]; при store значение на
                    // вершине стека. Берём его тип (надёжнее, чем локаль после).
                    BasicValue val = null;
                    try {
                        int sz = f.getStackSize();
                        if (sz > 0) val = f.getStack(sz - 1);
                    } catch (Exception ignored) {
                    }
                    if (val == null || val.getType() == null) {
                        try { val = f.getLocal(v.var); } catch (Exception ignored) { }
                    }
                    if (val != null && val.getType() != null) {
                        // Конкретный тип не затираем более общим: слот переиспользуется,
                        // и последний store вполне может давать Object после слияния ветвей.
                        String t = friendly(val.getType());
                        if (!isObjectType(t) || isObjectType(out[v.var])) out[v.var] = t;
                    }
                }
            }
        }
        // После кадров: грубая классификация по STORE-опкоду, точна для примитивов
        // и окончательна. Кадры (BasicVerifier) для зафлэттенных методов падают или
        // перетирают слот 0 обратно в объект (если в методе есть и ALOAD 0 this, и
        // переиспользование слота 0 под int). Здесь примитивная классификация
        // преобладает, чтобы слот 0, куда писали ISTORE, не рендерился как "this"
        // (иначе выходит "new double[this]", "iVar29 >= this").
        classifyArrayStores(mn, out);
        classifyArrayElements(mn, out);
        classifyPrimitiveStores(mn, out);
        refineBooleans(mn, out);
        applyUsageBasedObjectTypes(mn, isStatic, out, hierarchy);
        return out;
    }

    /**
     * Usage-based вывод типа объектной локали: если слот остался {@code Object},
     * но используется как получатель члена ({@code INVOKEVIRTUAL/INVOKEINTERFACE}
     * или {@code GETFIELD}), его реальный класс — как минимум класс-владелец
     * этого члена. Такая замена делает вызов компилируемым, тогда как
     * {@code Object} — нет.
     *
     * <p>Голоса собираются по кадрам {@link SlotTrackingInterpreter}: значение,
     * загруженное {@code ALOAD n}, несёт номер слота; на вызове/чтении поля
     * смотрим получателя на стеке. Из нескольких владельцев выбирается тот, под
     * который подходит больше всего голосов (наследник покрывает методы предка).
     */
    private static void applyUsageBasedObjectTypes(MethodNode mn, boolean isStatic, String[] types,
                                                   ClassHierarchy hierarchy) {
        if (mn == null || mn.instructions == null || types == null) return;
        int paramSlots = isStatic ? 0 : 1;
        for (Type t : Type.getArgumentTypes(mn.desc)) paramSlots += t.getSize();

        Map<Integer, Map<String, Integer>> votes = collectOwnerVotes(mn);
        if (votes.isEmpty()) return;
        for (Map.Entry<Integer, Map<String, Integer>> e : votes.entrySet()) {
            int slot = e.getKey();
            if (slot < paramSlots || slot >= types.length) continue;
            if (!isObjectType(types[slot])) continue; // уже есть точный тип — не трогаем
            String owner = pickBestOwner(e.getValue(), hierarchy);
            if (owner != null) types[slot] = friendlyOwner(owner);
        }
    }

    /** Карта «слот -> (класс-владелец члена -> число использований)». */
    private static Map<Integer, Map<String, Integer>> collectOwnerVotes(MethodNode mn) {
        Map<Integer, Map<String, Integer>> votes = new HashMap<>();
        Frame<BasicValue>[] frames = tryAnalyze(mn, new SlotTrackingInterpreter());
        if (frames == null) return votes;
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length && i < frames.length; i++) {
            Frame<BasicValue> f = frames[i];
            if (f == null) continue;
            AbstractInsnNode n = insns[i];
            if (n.getOpcode() == Opcodes.INVOKEVIRTUAL || n.getOpcode() == Opcodes.INVOKEINTERFACE) {
                MethodInsnNode m = (MethodInsnNode) n;
                if ("<init>".equals(m.name)) continue;
                vote(votes, f, f.getStackSize() - 1 - argumentCount(m.desc), m.owner);
            } else if (n.getOpcode() == Opcodes.GETFIELD) {
                vote(votes, f, f.getStackSize() - 1, ((FieldInsnNode) n).owner);
            }
        }
        return votes;
    }

    private static void vote(Map<Integer, Map<String, Integer>> votes, Frame<BasicValue> f,
                             int receiverIndex, String owner) {
        if (owner == null || owner.isEmpty() || "java/lang/Object".equals(owner)) return;
        if (BOXED.contains(owner)) return;
        if (receiverIndex < 0 || receiverIndex >= f.getStackSize()) return;
        BasicValue v = f.getStack(receiverIndex);
        if (!(v instanceof SlotValue sv) || sv.slot < 0) return;
        votes.computeIfAbsent(sv.slot, k -> new LinkedHashMap<>()).merge(owner, 1, Integer::sum);
    }

    /**
     * Выбор типа слота по голосам. Кандидат «покрывает» владельца O, если он
     * наследник O — тогда методы O доступны и на кандидате. Берём кандидата с
     * наибольшим покрытием (при равенстве — с наибольшим числом собственных
     * использований, затем первый встреченный).
     */
    private static String pickBestOwner(Map<String, Integer> votes, ClassHierarchy hierarchy) {
        String best = null;
        int bestScore = -1;
        int bestOwn = -1;
        for (Map.Entry<String, Integer> cand : votes.entrySet()) {
            int score = 0;
            for (Map.Entry<String, Integer> o : votes.entrySet()) {
                if (assignable(o.getKey(), cand.getKey(), hierarchy)) score += o.getValue();
            }
            if (score > bestScore || (score == bestScore && cand.getValue() > bestOwn)) {
                best = cand.getKey();
                bestScore = score;
                bestOwn = cand.getValue();
            }
        }
        return best;
    }

    /** assignable ли {@code sub} к {@code sup}: сначала по jar, затем рефлексией (JDK/classpath). */
    private static boolean assignable(String sup, String sub, ClassHierarchy hierarchy) {
        if (sup.equals(sub) || "java/lang/Object".equals(sup)) return true;
        if (hierarchy != null && hierarchy.isAssignable(sup, sub)) return true;
        try {
            Class<?> supClass = loadClass(sup);
            Class<?> subClass = loadClass(sub);
            return supClass != null && subClass != null && supClass.isAssignableFrom(subClass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> loadClass(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, TypeInference.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String friendlyOwner(String owner) {
        return owner.startsWith("[") ? Ast.friendlyType(owner) : Ast.friendlyType("L" + owner + ";");
    }

    /**
     * Слот, в который кладут только что созданный массив, получает тип массива.
     * BasicVerifier отдаёт для NEWARRAY/ANEWARRAY обычную ссылку на Object и
     * теряет размерность, из-за чего объявление становится Object и обращение
     * {@code a[i]} в восстановленном коде перестаёт компилироваться.
     */
    private static void classifyArrayStores(MethodNode mn, String[] out) {
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (insns[i].getOpcode() != Opcodes.ASTORE) continue;
            int var = ((VarInsnNode) insns[i]).var;
            String t = arrayTypeOf(previousReal(insns, i), out);
            if (t != null && (!isObjectType(t) || isObjectType(out[var]))) out[var] = t;
        }
    }

    /** Тип массива, создаваемого инструкцией, либо null. */
    private static String arrayTypeOf(AbstractInsnNode n, String[] out) {
        try {
            return arrayTypeOfRaw(n, out);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String arrayTypeOfRaw(AbstractInsnNode n, String[] out) {
        if (n == null) return null;
        if (n instanceof MethodInsnNode mi) {
            Type r = Type.getReturnType(mi.desc);
            return r.getSort() == Type.ARRAY ? friendly(r) : null;
        }
        if (n instanceof FieldInsnNode fi) {
            Type r = Type.getType(fi.desc);
            return r.getSort() == Type.ARRAY ? friendly(r) : null;
        }
        if (n instanceof org.objectweb.asm.tree.LdcInsnNode ld && ld.cst instanceof Type ct
                && ct.getSort() == Type.ARRAY) {
            return friendly(ct);
        }
        if (n instanceof VarInsnNode av && av.getOpcode() == Opcodes.ALOAD && out != null
                && av.var >= 0 && av.var < out.length && out[av.var] != null
                && out[av.var].endsWith("[]")) {
            return out[av.var];
        }
        return switch (n.getOpcode()) {
            case Opcodes.NEWARRAY -> switch (((org.objectweb.asm.tree.IntInsnNode) n).operand) {
                case Opcodes.T_BOOLEAN -> "boolean[]";
                case Opcodes.T_CHAR -> "char[]";
                case Opcodes.T_FLOAT -> "float[]";
                case Opcodes.T_DOUBLE -> "double[]";
                case Opcodes.T_BYTE -> "byte[]";
                case Opcodes.T_SHORT -> "short[]";
                case Opcodes.T_INT -> "int[]";
                case Opcodes.T_LONG -> "long[]";
                default -> null;
            };
            case Opcodes.ANEWARRAY -> {
                String d = ((org.objectweb.asm.tree.TypeInsnNode) n).desc;
                yield Ast.friendlyType(d.startsWith("[") ? "[" + d : "[L" + d + ";");
            }
            case Opcodes.CHECKCAST -> {
                Type ct = Type.getType(((org.objectweb.asm.tree.TypeInsnNode) n).desc);
                yield ct.getSort() == Type.ARRAY ? friendly(ct) : null;
            }
            case Opcodes.MULTIANEWARRAY ->
                    Ast.friendlyType(((org.objectweb.asm.tree.MultiANewArrayInsnNode) n).desc);
            default -> null;
        };
    }

    /** Предыдущая настоящая инструкция (не label/line/frame). */
    private static AbstractInsnNode previousReal(AbstractInsnNode[] insns, int index) {
        for (int j = index - 1; j >= 0; j--) {
            if (insns[j].getOpcode() >= 0) return insns[j];
        }
        return null;
    }

    /** Слот, которому присваивают элемент массива T[], получает тип T. */
    private static void classifyArrayElements(MethodNode mn, String[] out) {
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int round = 0; round < 2; round++) {
            for (int i = 1; i + 1 < insns.length; i++) {
                if (insns[i].getOpcode() != Opcodes.AALOAD) continue;
                VarInsnNode arr = null;
                for (int k = i - 1; k >= 0 && k >= i - 4; k--) {
                    if (insns[k] instanceof VarInsnNode cand && cand.getOpcode() == Opcodes.ALOAD) {
                        arr = cand;
                        break;
                    }
                }
                if (arr == null) continue;
                if (!(insns[i + 1] instanceof VarInsnNode st && st.getOpcode() == Opcodes.ASTORE)) continue;
                int a = arr.var;
                int d = st.var;
                if (a < 0 || d < 0 || a >= out.length || d >= out.length) continue;
                String at = out[a];
                if (at == null || !at.endsWith("[]")) continue;
                String elem = at.substring(0, at.length() - 2);
                if (elem.isEmpty()) continue;
                if (isObjectType(out[d])) out[d] = elem;
            }
        }
    }
    private static boolean isObjectType(String t) {
        return "Object".equals(t) || "java.lang.Object".equals(t);
    }

    /**
     * Число значений-аргументов метода. В кадрах ASM {@code long}/{@code double}
     * занимают ОДНУ позицию стека (в отличие от слотов JVM), поэтому считаем типы,
     * а не размеры.
     */
    private static int argumentCount(String desc) {
        return Type.getArgumentTypes(desc).length;
    }

    /**
     * Значение с известным источником — загружено из слота {@code slot}. Позволяет
     * на вызове/чтении поля узнать, какой локальный слот стоит получателем.
     */
    private static final class SlotValue extends BasicValue {
        private final int slot;

        SlotValue(Type type, int slot) {
            super(type);
            this.slot = slot;
        }
    }

    /** BasicInterpreter, помечающий результаты {@code *LOAD} номером слота. */
    private static final class SlotTrackingInterpreter extends BasicInterpreter {
        SlotTrackingInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
            BasicValue base = super.copyOperation(insn, value);
            int op = insn.getOpcode();
            if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
                return new SlotValue(base == null ? null : base.getType(), ((VarInsnNode) insn).var);
            }
            return base;
        }
    }

    /** Обёртки примитивов: объявлять их типом локали вредно (boxing и префикс имени). */
    private static final Set<String> BOXED = Set.of(
            "java/lang/Boolean", "java/lang/Byte", "java/lang/Character", "java/lang/Short",
            "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double");

    /**
     * Грубая классификация типов локальных по STORE-опкоду. Не требует кадров и
     * точна для примитивов. Кадры для сильно зафлэттенных методов падают, из-за
     * чего слот 0, переиспользованный под int-временную, оставался именем класса
     * и рендерился как {@code this} (напр. {@code new double[this]}).
     */
    private static void classifyPrimitiveStores(MethodNode mn, String[] types) {
        if (mn == null || mn.instructions == null || types == null) return;
        int n = types.length;
        for (AbstractInsnNode a = mn.instructions.getFirst(); a != null; a = a.getNext()) {
            if (!(a instanceof VarInsnNode v)) continue;
            int s = v.var;
            if (s < 0 || s >= n) continue;
            switch (v.getOpcode()) {
                case Opcodes.ISTORE -> types[s] = "int";
                case Opcodes.LSTORE -> types[s] = "long";
                case Opcodes.FSTORE -> types[s] = "float";
                case Opcodes.DSTORE -> types[s] = "double";
            }
        }
    }

    /**
     * Эвристика boolean-локальных: слот int-типа считаем boolean, если он только
     * пишется литералами 0/1 и только используется как условие (ILOAD прямо перед
     * IFEQ/IFNE). Тогда {@code iVar != 0} эквивалентно {@code iVar}, и печать
     * {@code if (iVar)} корректнее. Трансформация безопасна: локальные в выводе
     * не объявляются, а значения в таких местах эквивалентны.
     */
    private static void refineBooleans(MethodNode mn, String[] types) {
        if (mn == null || mn.instructions == null || types == null) return;
        AbstractInsnNode[] insns = mn.instructions.toArray();
        int n = types.length;
        boolean[] cand = new boolean[n];
        boolean[] litStore = new boolean[n];
        boolean[] intStore = new boolean[n];
        boolean[] bad = new boolean[n];
        boolean[] hasCond = new boolean[n];

        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            if (insn instanceof org.objectweb.asm.tree.IincInsnNode inc) {
                if (inc.var >= 0 && inc.var < n) bad[inc.var] = true;
                continue;
            }
            if (!(insn instanceof VarInsnNode v)) continue;
            int s = v.var;
            if (s < 0 || s >= n) continue;
            int op = v.getOpcode();
            if (op == Opcodes.ISTORE) {
                AbstractInsnNode prev = prevReal(insns, i);
                if (isBoolSource(prev)) cand[s] = true;
                else if (isBoolLiteral(prev)) litStore[s] = true;
                else intStore[s] = true;
            } else if (op == Opcodes.ILOAD) {
                AbstractInsnNode next = nextReal(insns, i);
                if (next instanceof org.objectweb.asm.tree.JumpInsnNode j
                        && (j.getOpcode() == Opcodes.IFEQ || j.getOpcode() == Opcodes.IFNE)) {
                    hasCond[s] = true;
                } else if (next instanceof VarInsnNode nv && nv.getOpcode() == Opcodes.ISTORE) {
                    // цепочка temp -> local, решается ниже
                } else if (next instanceof org.objectweb.asm.tree.InsnNode ri
                        && ri.getOpcode() == Opcodes.IRETURN
                        && Type.getReturnType(mn.desc).getSort() == Type.BOOLEAN) {
                    hasCond[s] = true;
                } else {
                    bad[s] = true;
                }
            } else if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) {
                bad[s] = true;
            }
        }
        // цепочки ILOAD -> ISTORE: кандидат переносится в обе стороны
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ILOAD) continue;
                AbstractInsnNode next = nextReal(insns, i);
                if (!(next instanceof VarInsnNode nv) || nv.getOpcode() != Opcodes.ISTORE) continue;
                int from = v.var;
                int to = nv.var;
                if (from < 0 || to < 0 || from >= n || to >= n) continue;
                if ((cand[from] || litStore[from]) && !bad[from]) cand[to] = true;
                if (cand[to] && !bad[to]) cand[from] = true;
            }
        }
        for (int s = 0; s < n; s++) {
            boolean src = cand[s] || (litStore[s] && !intStore[s]);
            if (src && hasCond[s] && !intStore[s] && !bad[s] && "int".equals(types[s])) {
                types[s] = "boolean";
            }
        }
    }

    private static boolean isBoolSource(AbstractInsnNode prev) {
        if (prev instanceof MethodInsnNode mi) {
            return Type.getReturnType(mi.desc).getSort() == Type.BOOLEAN;
        }
        if (prev instanceof FieldInsnNode fi) {
            return "Z".equals(fi.desc);
        }
        return false;
    }

    private static boolean isBoolLiteral(AbstractInsnNode prev) {
        if (prev instanceof org.objectweb.asm.tree.InsnNode pi) {
            return pi.getOpcode() == Opcodes.ICONST_0 || pi.getOpcode() == Opcodes.ICONST_1;
        }
        if (prev instanceof org.objectweb.asm.tree.IntInsnNode ii) {
            return (ii.getOpcode() == Opcodes.BIPUSH || ii.getOpcode() == Opcodes.SIPUSH)
                    && (ii.operand == 0 || ii.operand == 1);
        }
        return false;
    }
    private static AbstractInsnNode prevReal(AbstractInsnNode[] insns, int i) {
        for (int k = i - 1; k >= 0; k--) if (insns[k].getOpcode() >= 0) return insns[k];
        return null;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode[] insns, int i) {
        for (int k = i + 1; k < insns.length; k++) if (insns[k].getOpcode() >= 0) return insns[k];
        return null;
    }

    /** Пробует анализ фреймов настоящего метода; null, если упал. */
    private static Frame<BasicValue>[] tryAnalyze(MethodNode mn,
            org.objectweb.asm.tree.analysis.Interpreter<BasicValue> interp) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(interp);
            return analyzer.analyze(mn.name, mn);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Дружественное имя типа по ASM-типу. */
    private static String friendly(Type t) {
        if (t == null) return "Object";
        if (t.getSort() == Type.ARRAY) {
            return friendly(t.getElementType()) + "[]";
        }
        if (t.getSort() == Type.OBJECT) {
            return t.getClassName();
        }
        return switch (t.getSort()) {
            case Type.INT -> "int";
            case Type.LONG -> "long";
            case Type.FLOAT -> "float";
            case Type.DOUBLE -> "double";
            case Type.BOOLEAN -> "boolean";
            case Type.BYTE -> "byte";
            case Type.SHORT -> "short";
            case Type.CHAR -> "char";
            default -> "Object";
        };
    }
}