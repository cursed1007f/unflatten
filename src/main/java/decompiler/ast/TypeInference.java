package decompiler.ast;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
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
        SPLIT_SLOTS.clear();
        collectSlotSegments(mn);
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
    /** Символический проход типов по базовым блокам с merge-стеком. */
    private static void symbolicTypes(MethodNode mn, String[] out) {
        if (mn == null || mn.instructions == null || mn.instructions.size() == 0) return;
        AbstractInsnNode[] insns = mn.instructions.toArray();
        // лидеры блоков: первый, цели переходов, и инструкции после переходов/бросков
        java.util.Set<AbstractInsnNode> leaders = new java.util.HashSet<>();
        leaders.add(insns[0]);
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode n = insns[i];
            int op = n.getOpcode();
            if (n instanceof JumpInsnNode j) {
                if (op != Opcodes.GOTO) {
                    int t = insns.length;
                }
                leaders.add(j.label);
                if (op != Opcodes.GOTO) markNext(leaders, insns, i);
            } else if (n instanceof org.objectweb.asm.tree.TableSwitchInsnNode ts) {
                leaders.addAll(ts.labels);
                leaders.add(ts.dflt);
                markNext(leaders, insns, i);
            } else if (n instanceof org.objectweb.asm.tree.LookupSwitchInsnNode ls) {
                leaders.addAll(ls.labels);
                leaders.add(ls.dflt);
                markNext(leaders, insns, i);
            } else if (isTerminatorOp(op)) {
                markNext(leaders, insns, i);
            }
        }
        // сопоставление инстр -> номер блока; блок = лидер и последующие до след. лидера
        java.util.List<java.util.List<AbstractInsnNode>> blocks = new java.util.ArrayList<>();
        java.util.Map<AbstractInsnNode, Integer> insnToBlock = new java.util.HashMap<>();
        java.util.List<AbstractInsnNode> cur = null;
        for (int i = 0; i < insns.length; i++) {
            if (leaders.contains(insns[i])) {
                cur = new java.util.ArrayList<>();
                blocks.add(cur);
            }
            if (cur != null) {
                cur.add(insns[i]);
                insnToBlock.put(insns[i], blocks.size() - 1);
            }
        }
        int m = blocks.size();
        // входные стеки блоков
        java.util.Deque<String>[] inputs = new java.util.Deque[m];
        boolean[] done = new boolean[m];
        boolean[] primStore = new boolean[out.length];
        inputs[0] = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        work.add(0);
        while (!work.isEmpty()) {
            int b = work.poll();
            if (done[b]) continue;
            java.util.Deque<String> st = new java.util.ArrayDeque<>();
            if (inputs[b] != null) st.addAll(inputs[b]);
            // выполнить блок
            boolean ok = true;
            for (AbstractInsnNode insn : blocks.get(b)) {
                ok = step(st, insn, out, primStore);
                if (!ok) break;
            }
            done[b] = true;
            if (!ok) continue; // блок нельзя разобрать; не распространяем дальше
            // распространить выход в succ
            AbstractInsnNode last = blocks.get(b).get(blocks.get(b).size() - 1);
            if (last instanceof JumpInsnNode j && j.getOpcode() == Opcodes.GOTO) {
                int t = insnToBlock.getOrDefault(j.label, -1);
                if (t >= 0) inputs[t] = merge(inputs[t], st);
                work.add(t);
            } else if (last instanceof org.objectweb.asm.tree.TableSwitchInsnNode ts) {
                java.util.HashSet<AbstractInsnNode> targets = new java.util.HashSet<>();
                targets.addAll(ts.labels);
                targets.add(ts.dflt);
                for (AbstractInsnNode t2 : targets) {
                    int t = insnToBlock.getOrDefault(t2, -1);
                    if (t >= 0) { inputs[t] = merge(inputs[t], st); work.add(t); }
                }
            } else if (last instanceof org.objectweb.asm.tree.LookupSwitchInsnNode ls) {
                java.util.HashSet<AbstractInsnNode> targets = new java.util.HashSet<>();
                targets.addAll(ls.labels);
                targets.add(ls.dflt);
                for (AbstractInsnNode t2 : targets) {
                    int t = insnToBlock.getOrDefault(t2, -1);
                    if (t >= 0) { inputs[t] = merge(inputs[t], st); work.add(t); }
                }
            } else if (last instanceof JumpInsnNode jj) {
                int t = insnToBlock.getOrDefault(jj.label, -1);
                if (t >= 0) { inputs[t] = merge(inputs[t], st); work.add(t); }
                int next = insnToBlock.getOrDefault(insns[Math.min(insns.length - 1, indexOf(insns, jj) + 1)], -1);
                if (next >= 0 && next != t) { inputs[next] = merge(inputs[next], st); work.add(next); }
            } else if (isTerminatorOp(last.getOpcode())) {
                // return/throw на копию стека — ничего не распространяем
            }
        }
    }

    private static int indexOf(AbstractInsnNode[] arr, AbstractInsnNode target) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == target) return i;
        return -1;
    }

    private static boolean isTerminatorOp(int op) {
        return op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                || op == Opcodes.DRETURN || op == Opcodes.ARETURN || op == Opcodes.RETURN
                || op == Opcodes.ATHROW;
    }

    private static void markNext(java.util.Set<AbstractInsnNode> leaders, AbstractInsnNode[] insns, int i) {
        if (i + 1 < insns.length) leaders.add(insns[i + 1]);
    }

    /** Слияние входных стеков блока: выравнивание по низу, джойн попарно. */
    private static java.util.Deque<String> merge(java.util.Deque<String> a, java.util.Deque<String> b) {
        if (a == null) {
            java.util.Deque<String> r = new java.util.ArrayDeque<>();
            r.addAll(b);
            return r;
        }
        java.util.List<String> la = new java.util.ArrayList<>(a);
        java.util.List<String> lb = new java.util.ArrayList<>(b);
        int d = Math.min(la.size(), lb.size());
        if (la.size() > d) la = la.subList(la.size() - d, la.size());
        if (lb.size() > d) lb = lb.subList(lb.size() - d, lb.size());
        java.util.ArrayDeque<String> r = new java.util.ArrayDeque<>();
        for (int i = 0; i < d; i++) {
            String x = la.get(i), y = lb.get(i);
            if (x.equals(y)) {
                if (i == d - 1) r.addLast(x); // верх — так же
            }
            r.addLast(java.util.Objects.equals(x, y) ? x : "Object");
        }
        // внимание: порядок — сохраняем исходный (низ->верх) побайтово
        return r;
    }

    /** Один шаг машины. Возвращает false, если инструкцию нельзя обработать. */
    private static boolean step(java.util.Deque<String> st, AbstractInsnNode insn, String[] out, boolean[] primStore) {
int n = out.length;
        int op = insn.getOpcode();
        try {
            switch (op) {
                case -1, Opcodes.NOP -> { }
                case Opcodes.ACONST_NULL -> st.push("Object");
                case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
                     Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.ARRAYLENGTH,
                     Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                     Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                     Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
                     Opcodes.INEG, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                     Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
                     Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR -> st.push("int");
                case Opcodes.LCONST_0, Opcodes.LCONST_1,
                     Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                     Opcodes.LNEG, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                     Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR,
                     Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> st.push("long");
                case Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL,
                     Opcodes.DDIV, Opcodes.DREM, Opcodes.DNEG, Opcodes.D2I, Opcodes.D2L,
                     Opcodes.D2F -> st.push("double");
                case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
                     Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
                     Opcodes.FNEG, Opcodes.F2I, Opcodes.F2L, Opcodes.F2D -> st.push("float");
                case Opcodes.LDC -> {
                    Object c = ((LdcInsnNode) insn).cst;
                    if (c instanceof Type t) st.push(friendly(t));
                    else if (c instanceof Long) st.push("long");
                    else if (c instanceof Integer) st.push("int");
                    else if (c instanceof Float) st.push("float");
                    else if (c instanceof Double) st.push("double");
                    else st.push("String");
                }
                case Opcodes.ALOAD -> {
                    int var = ((VarInsnNode) insn).var;
                    st.push(var >= 0 && var < n && out[var] != null ? out[var] : "Object");
                }
                case Opcodes.ILOAD -> st.push("int");
                case Opcodes.LLOAD -> st.push("long");
                case Opcodes.FLOAD -> st.push("float");
                case Opcodes.DLOAD -> st.push("double");
                case Opcodes.ASTORE -> {
                    int var = ((VarInsnNode) insn).var;
                    String top = popStr(st);
                    if (top != null && var >= 0 && var < n && isObjectType(out[var]) && !primStore[var] && top.endsWith("[]")) out[var] = top;
                }
                case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> {
                    int v2 = ((VarInsnNode) insn).var;
                    if (v2 >= 0 && v2 < n) primStore[v2] = true;
                    popStr(st);
                }
                case Opcodes.AALOAD -> {
                    popStr(st);
                    String arr = popStr(st);
                    if (arr != null && arr.endsWith("[]")) st.push(arr.substring(0, arr.length() - 2));
                    else st.push("Object");
                }
                case Opcodes.AASTORE, Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE,
                     Opcodes.DASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                    popStr(st); popStr(st); popStr(st);
                }
                case Opcodes.DUP -> st.push(st.peek());
                case Opcodes.DUP_X1 -> { String a = popStr(st); String b = popStr(st); st.push(a); st.push(b); st.push(a); }
                case Opcodes.POP -> popStr(st);
                case Opcodes.POP2 -> { popStr(st); popStr(st); }
                case Opcodes.NEW -> st.push(Ast.friendlyType("L" + ((TypeInsnNode) insn).desc + ";"));
                case Opcodes.NEWARRAY -> {
                    popStr(st);
                    st.push(arrayOfType(atypeName(((IntInsnNode) insn).operand)));
                }
                case Opcodes.ANEWARRAY -> {
                    popStr(st);
                    String d = ((TypeInsnNode) insn).desc;
                    st.push(arrayOfType(d.startsWith("[") ? d : "L" + d + ";"));
                }
                case Opcodes.MULTIANEWARRAY -> {
                    int dims = ((MultiANewArrayInsnNode) insn).dims;
                    for (int k = 0; k < dims; k++) popStr(st);
                    st.push(Ast.friendlyType(((MultiANewArrayInsnNode) insn).desc));
                }
                case Opcodes.CHECKCAST -> {
                    popStr(st);
                    st.push(Ast.friendlyType("L" + ((TypeInsnNode) insn).desc + ";"));
                }
                case Opcodes.INSTANCEOF -> { popStr(st); st.push("boolean"); }
                case Opcodes.GETSTATIC -> st.push(Ast.friendlyType(((FieldInsnNode) insn).desc));
                case Opcodes.GETFIELD -> { popStr(st); st.push(Ast.friendlyType(((FieldInsnNode) insn).desc)); }
                case Opcodes.PUTSTATIC -> popStr(st);
                case Opcodes.PUTFIELD -> { popStr(st); popStr(st); }
                case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL,
                     Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    Type[] args = Type.getArgumentTypes(mi.desc);
                    for (int k = 0; k < args.length; k++) popStr(st);
                    if (mi.getOpcode() != Opcodes.INVOKESTATIC) popStr(st);
                    Type ret = Type.getReturnType(mi.desc);
                    if (ret.getSort() != Type.VOID) st.push(friendly(ret));
                }
                case Opcodes.INVOKEDYNAMIC -> {
                    org.objectweb.asm.tree.InvokeDynamicInsnNode id =
                            (org.objectweb.asm.tree.InvokeDynamicInsnNode) insn;
                    Type[] args = Type.getArgumentTypes(id.desc);
                    for (int k = 0; k < args.length; k++) popStr(st);
                    Type ret = Type.getReturnType(id.desc);
                    if (ret.getSort() != Type.VOID) st.push(friendly(ret));
                }
                case Opcodes.GOTO, Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
                     Opcodes.IFGT, Opcodes.IFLE, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                     Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                     Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL,
                     Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH, Opcodes.IINC -> { }
                case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN,
                     Opcodes.ARETURN, Opcodes.RETURN, Opcodes.ATHROW -> { }
                case Opcodes.JSR, Opcodes.RET -> { return false; }
                case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> { }
                default -> { return false; }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }
    /** Слот -> (индекс инструкции -> тип): сегменты для slot splitting. */
    public static final java.util.Map<Integer, java.util.TreeMap<Integer, String>> SLOT_SEGMENTS =
            new java.util.HashMap<>();
    public static int SLOT_CONFLICTS;
    public static final java.util.Map<Integer, String> SPLIT_SLOTS = new java.util.HashMap<>();

    /** Собирает сегменты для слотов, в которые пишут и массив, и примитив. */
    private static void collectSlotSegments(MethodNode mn) {
        AbstractInsnNode[] insns = mn.instructions.toArray();
        java.util.Map<Integer, java.util.TreeMap<Integer, String>> all = new java.util.HashMap<>();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof VarInsnNode v)) continue;
            int op = v.getOpcode();
            String t = switch (op) {
                case Opcodes.ISTORE -> "int";
                case Opcodes.LSTORE -> "long";
                case Opcodes.FSTORE -> "float";
                case Opcodes.DSTORE -> "double";
                case Opcodes.ASTORE -> {
                    String at = null;
                    try {
                        at = arrayTypeOf(previousReal(insns, i), null);
                    } catch (RuntimeException ignored) {
                    }
                    yield at != null ? at : "ref";
                }
                default -> null;
            };
            if (t == null) continue;
            all.computeIfAbsent(v.var, k -> new java.util.TreeMap<>()).put(i, t);
        }
        for (var e : all.entrySet()) {
            boolean prim = false, arr = false;
            for (String t : e.getValue().values()) {
                if (t.endsWith("[]")) arr = true;
                else if (java.util.Set.of("int", "long", "float", "double").contains(t)) prim = true;
            }
            if (arr && prim) {
                SLOT_CONFLICTS++;
                SLOT_SEGMENTS.put(e.getKey(), e.getValue());
                String at = null;
                for (String t : e.getValue().values()) if (t.endsWith("[]")) at = t;
                if (at != null) SPLIT_SLOTS.put(e.getKey(), at);
            }
        }
    }
    private static String popStr(java.util.Deque<String> st) {
        if (st.isEmpty()) return null;
        return st.pop();
    }

    private static String arrayOfType(String elem) {
        return (elem == null || elem.isEmpty()) ? "Object[]" : elem + "[]";
    }

    private static String atypeName(int atype) {
        return switch (atype) {
            case Opcodes.T_BOOLEAN -> "boolean";
            case Opcodes.T_CHAR -> "char";
            case Opcodes.T_FLOAT -> "float";
            case Opcodes.T_DOUBLE -> "double";
            case Opcodes.T_BYTE -> "byte";
            case Opcodes.T_SHORT -> "short";
            case Opcodes.T_INT -> "int";
            case Opcodes.T_LONG -> "long";
            default -> "Object";
        };
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