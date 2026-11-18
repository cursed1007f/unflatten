package decompiler.ast;

import decompiler.parser.JarParser;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Пункты 1–2 универсальности: переименование обфусцированных классов в осмысленные
 * имена и раскладка по тематическим пакетам (модулям).
 *
 * <p>Проходит jar один раз (только имена/супер/интерфейсы/сигнатуры — без
 * инструкций), выводит для каждого обфусцированного класса понятное имя и
 * тематический пакет, и хранит карту {@code internalName -> новый internal}
 * (например {@code io/grpc/KAqblefBq -> ui/RainHandler}). Карту используют
 * {@link Ast#friendlyType}, печать объявлений классов и заголовков.
 *
 * <p>Тема пакета выбирается по агрегату токенов метода/полей/интерфейсов:
 * {@code ui}, {@code net}, {@code config}, {@code math}, {@code ml},
 * {@code world}, {@code audio}, {@code data}, {@code account}, {@code util};
 * если сигналов нет — {@code mod}.
 */
public final class ClassRenamer {

    /** internalName -> новый full internal (пакет/Имя), напр. "ui/RainHandler". */
    private static final Map<String, String> RENAMED = new HashMap<>();

    /** Пакеты-приманки обфускатора: классы в них переименовываем всегда. */
    private static final String[] OBF_PACKAGES = {
            "io/grpc/", "com/google/protobuf/", "com/google/re2j/",
            "com/google/j2objc/", "com/google/errorprone/",
            "org/checkerframework/", "ovr/", "zzp/"
    };

    /** Тема -> ключевые слова (подстроки, нижний регистр). Порядок = приоритет. */
    private static final String[][] THEMES = {
            {"ml", "model,weight,gradient,train,vocab,mlp,neural,embedding,dense,layer,epoch,loss,predict,distribution,char"},
            {"net", "packet,network,channel,socket,connection,protocol,send,receive,remote,client,server,request,response,http,url,host,ping,address"},
            {"config", "config,setting,option,preference,module,feature,preset,toggle"},
            {"audio", "sound,audio,music,volume,pitch,clip,song,track"},
            {"account", "account,auth,session,token,login,password,credential,user"},
            {"world", "entity,player,world,level,chunk,block,mob,spawn,target,position,coordinate,biome,camera,entity"},
            {"math", "math,vector,matrix,quaternion,angle,clamp,lerp,round,random,interpolate,normalize,distance,speed,velocity"},
            {"data", "cache,store,repository,dao,serialize,json,parse,file,load,save,read,write,stream,buffer,io"},
            {"ui", "mouse,keyboard,key,render,screen,gui,panel,popup,slider,button,hover,widget,layout,text,color,font,icon,tooltip,click,scroll,drag,window,view,menu,hud,overlay,label,title,row,column,checkbox,animation,animate,tab,entry"},
            {"util", "util,helper,string,format,sort,compare,collect,array,convert,value"},
    };

    /** «Слабые» токены, не дающие осмысленного имени. */
    private static final Set<String> STOP = Set.of(
            "get", "set", "is", "has", "on", "to", "in", "of", "do", "add", "remove",
            "put", "init", "initialize", "close", "dispose", "run", "start", "stop",
            "value", "name", "data", "list", "map", "object", "class", "type", "index",
            "size", "length", "string", "integer", "boolean", "double", "float", "long",
            "byte", "character", "char", "void", "this", "that", "super", "main", "call",
            "apply", "accept", "compare", "equals", "hash", "code", "next", "prev",
            "new", "create", "build", "make", "read", "write", "load", "save", "parse",
            "toString", "valueOf", "copy", "clear", "reset", "test", "check", "validate",
            "invoke", "handle", "process", "update", "onMouse", "onKey", "onPlayer"
    );

    private ClassRenamer() {
    }

    /** Сброс (для тестов/повторных прогонов). */
    public static void reset() {
        RENAMED.clear();
    }

    /** Построить карту переименований (имя + пакет) по всему jar. */
    public static void build(Path jar) throws Exception {
        Map<String, Signals> sigs = new LinkedHashMap<>();
        JarParser.parse(jar, (p, entry, cn) -> sigs.put(cn.name, Signals.of(cn)));

        List<String> names = new ArrayList<>(sigs.keySet());
        Collections.sort(names);

        Set<String> used = new HashSet<>();
        // Внешние классы
        for (String internal : names) {
            if (internal.contains("$")) {
                String outer = internal.substring(0, internal.indexOf('$'));
                if (sigs.containsKey(outer)) continue; // настоящий вложенный — во втором проходе
            }
            if (!shouldRename(internal)) continue;
            Signals s = sigs.get(internal);
            Map<String, Integer> score = tokenScore(s);
            String pkg = themeOf(score);
            String base = simple(internal);
            int dpos = base.indexOf('$');
            if (dpos > 0) base = base.substring(0, dpos);
            String tok = best(score);
            if (tok == null || tok.length() <= 4) {
                String sup = supertypeSuffix(s);
                if (sup != null) tok = sup;
            }
            String name = unique(sanitize(pascal(tok) + suffix(s)), used);
            RENAMED.put(internal, pkg + "/" + name);
        }
        // Вложенные: если внешний переименован — тот же пакет, ИмяВнешнего$Внутренний
        for (String internal : names) {
            if (!internal.contains("$")) continue;
            if (RENAMED.containsKey(internal)) continue;
            int d = internal.indexOf('$');
            String outer = internal.substring(0, d);
            String outerNew = RENAMED.get(outer);
            if (outerNew == null) continue;
            String pkg = outerNew.substring(0, outerNew.lastIndexOf('/'));
            String outerName = outerNew.substring(outerNew.lastIndexOf('/') + 1);
            String inner = sanitize(innerName(internal.substring(d + 1), sigs.get(internal), used, outerName));
            RENAMED.put(internal, pkg + "/" + inner);
        }
    }

    /** Переименован ли класс. */
    public static boolean isRenamed(String internal) {
        return RENAMED.containsKey(internal);
    }

    /** Простое имя класса (переименованное, если есть). */
    public static String simpleNameOf(String internal) {
        String r = RENAMED.get(internal);
        if (r == null) return simple(internal);
        return lastSegment(r);
    }

    /** Новое full internal-имя (пакет/Имя), если класс переименован; иначе исходное. */
    public static String renameInternal(String internal) {
        String r = RENAMED.get(internal);
        return r != null ? r : internal;
    }

    // ------------------------------------------------------------------ сигналы

    private static final class Signals {
        String superName;
        List<String> interfaces = List.of();
        List<String> methods = List.of();
        List<String> fields = List.of();
        List<String> strings = List.of();
        List<String> types = List.of();
        boolean allStatic = true;
        boolean hasOnHandler;

        static Signals of(ClassNode cn) {
            Signals s = new Signals();
            s.superName = cn.superName;
            s.interfaces = cn.interfaces != null ? new ArrayList<>(cn.interfaces) : List.of();
            List<String> ms = new ArrayList<>();
            if (cn.methods != null) {
                boolean any = false;
                for (MethodNode m : cn.methods) {
                    ms.add(m.name);
                    if (m.name.startsWith("on") && m.name.length() > 2) s.hasOnHandler = true;
                    if ((m.access & Opcodes.ACC_STATIC) == 0) { s.allStatic = false; any = true; }
                }
                if (!any) s.allStatic = cn.methods.isEmpty();
            }
            s.methods = ms;
            List<String> fs = new ArrayList<>();
            if (cn.fields != null) for (FieldNode f : cn.fields) fs.add(f.name);
            s.fields = fs;
            List<String> ss = new ArrayList<>();
            if (cn.methods != null) {
                for (MethodNode m : cn.methods) {
                    if (m.instructions == null) continue;
                    for (var it = m.instructions.iterator(); it.hasNext() && ss.size() < 40; ) {
                        var insn = it.next();
                        if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                                && ldc.cst instanceof String str && str.length() >= 4 && str.length() <= 24) {
                            ss.add(str);
                        }
                    }
                }
            }
            s.strings = ss;
            java.util.Set<String> ts = new java.util.LinkedHashSet<>();
            if (cn.fields != null) for (FieldNode f : cn.fields) addType(ts, f.desc);
            if (cn.methods != null) for (MethodNode m : cn.methods) {
                addType(ts, m.desc);
                addType(ts, m.signature);
            }
            addType(ts, cn.superName);
            s.types = new ArrayList<>(ts);
            return s;
        }
    }

    // ------------------------------------------------------------------ правила

    private static boolean shouldRename(String internal) {
        if (internal.startsWith("java/") || internal.startsWith("javax/")) return false;
        if (internal.startsWith("com/mojang/") || internal.startsWith("net/minecraft/")) return false;
        if (internal.startsWith("org/joml/") || internal.startsWith("org/apache/")) return false;
        if (internal.startsWith("io/netty/") || internal.startsWith("com/google/gson/")) return false;
        for (String p : OBF_PACKAGES) if (internal.startsWith(p)) return true;
        String s = simple(internal);
        // невалидный Java-идентификатор (начинается с цифры / только цифры) — точно переименовать
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) return true;
        return looksRandom(s);
    }

    /** Публичный признак обфускации класса (для детектора): пакет-приманка или случайное имя. */
    public static boolean looksObfuscated(String internal) {
        for (String p : OBF_PACKAGES) if (internal.startsWith(p)) return true;
        return looksRandom(simple(internal));
    }

    /** Похоже ли имя на случайную обфускацию (эвристика). */
    private static boolean looksRandom(String s) {
        if (s == null || s.isEmpty()) return false;
        // Не валидный Java-идентификатор (содержит ?/-/. или иной не-идентификатор) —
        // это маска обфускатора спец-символом (напр. '?'). Такой класс точно переименовать.
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return true;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return true;
        }
        String core = s;
        int dollar = core.indexOf('$');
        if (dollar > 0) core = core.substring(0, dollar);
        if (core.length() < 5) return false;
        int transitions = 0;
        for (int i = 1; i < core.length(); i++) {
            char a = core.charAt(i - 1), b = core.charAt(i);
            if (Character.isLowerCase(a) && Character.isUpperCase(b)) transitions++;
        }
        int uppers = 0;
        for (int i = 1; i < core.length(); i++) if (Character.isUpperCase(core.charAt(i))) uppers++;
        if (core.length() >= 6 && uppers >= 2) return true;
        if (core.length() >= 6 && transitions >= 3) return true;
        if (core.length() >= 8 && transitions >= 2 && core.indexOf('a') < 0 && core.indexOf('e') < 0) return true;
        // «спам» из похожих символов I/l/1/O/0 (IllIlIIIIIIlII) — типичный обфускатор
        int confusable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'I' || c == 'l' || c == '1' || c == 'O' || c == '0') confusable++;
        }
        if (s.length() >= 6 && (double) confusable / s.length() >= 0.6) return true;
        int upper = 0, lower = 0, digits = 0, vowels = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) upper++;
            else if (Character.isLowerCase(c)) lower++;
            else if (Character.isDigit(c)) digits++;
            if ("aeiouyAEIOUY".indexOf(c) >= 0) vowels++;
        }
        double vowelRatio = (double) vowels / s.length();
        return (upper >= 2 && lower >= 2 && vowelRatio < 0.28)
                || (digits >= 1 && upper >= 1 && lower >= 1 && vowelRatio < 0.3);
    }

    private static Map<String, Integer> tokenScore(Signals s) {
        Map<String, Integer> score = new LinkedHashMap<>();
        for (String i : s.interfaces) tokens(score, simple(i), 3);
        if (s.superName != null && !s.superName.equals("java/lang/Object")) {
            tokens(score, simple(s.superName), 3);
        }
        for (String m : s.methods) tokens(score, m, 2);
        for (String f : s.fields) tokens(score, f, 1);
        for (String str : s.strings) tokens(score, str, 1);
        for (String ty : s.types) tokens(score, simple(ty), 2);
        return score;
    }

    /** Тема пакета по агрегату токенов. */
    private static String themeOf(Map<String, Integer> score) {
        Map<String, Integer> themes = new LinkedHashMap<>();
        for (var e : score.entrySet()) {
            String th = themeOfWord(e.getKey());
            if (th != null) themes.merge(th, e.getValue(), Integer::sum);
        }
        String bestTheme = "mod";
        int bestScore = 0;
        for (var e : themes.entrySet()) {
            if (e.getValue() > bestScore) { bestScore = e.getValue(); bestTheme = e.getKey(); }
        }
        return bestTheme;
    }

    private static String themeOfWord(String w) {
        String lw = w.toLowerCase();
        for (String[] t : THEMES) {
            for (String kw : t[1].split(",")) {
                if (!kw.isEmpty() && lw.contains(kw)) return t[0];
            }
        }
        return null;
    }

    /** Оставляет только символы, допустимые в Java-идентификаторе. */
    private static String sanitize(String s) {
        if (s == null) return "Cls";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        if (sb.length() == 0) return "Cls";
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) sb.insert(0, 'C');
        String r = sb.toString();
        if (KEYWORDS.contains(r)) r = r + "Cls";
        return r;
    }

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null");
    private static String suffix(Signals s) {
        String bySuper = supertypeSuffix(s);
        if (bySuper != null) return bySuper;
        if (implementsAny(s, "java/lang/Runnable", "java/util/concurrent/Callable",
                "java/lang/Thread")) return "Task";
        if (s.hasOnHandler) return "Handler";
        if (isGetterSetterOnly(s)) return "Data";
        if (s.allStatic) return "Util";
        return "";
    }

    /** Суффикс по суперклассу/интерфейсу (Screen, Listener, Command, Exception...). */
    private static String supertypeSuffix(Signals s) {
        String[] known = {"Screen", "Panel", "Widget", "Listener", "Handler", "Command",
                "Event", "Mixin", "Module", "Manager", "Provider", "Factory", "Exception", "Error"};
        List<String> supers = new ArrayList<>();
        if (s.superName != null) supers.add(simple(s.superName));
        for (String i : s.interfaces) supers.add(simple(i));
        for (String sup : supers) {
            for (String k : known) {
                if (sup.endsWith(k) && sup.length() > k.length()) return k;
            }
        }
        return null;
    }
    private static boolean implementsAny(Signals s, String... names) {
        for (String n : names) {
            if (s.interfaces.contains(n)) return true;
            if (s.superName != null && s.superName.equals(n)) return true;
        }
        return false;
    }

    private static boolean isGetterSetterOnly(Signals s) {
        for (String m : s.methods) {
            if (m.startsWith("get") || m.startsWith("set") || m.startsWith("is")) continue;
            if (m.equals("<init>") || m.equals("<clinit>")) continue;
            return false;
        }
        return !s.methods.isEmpty();
    }

    /** Добавляет в набор простое имя типа из дескриптора/сигнатуры. */
    private static void addType(java.util.Set<String> out, String desc) {
        if (desc == null) return;
        try {
            org.objectweb.asm.Type t = org.objectweb.asm.Type.getType(desc);
            if (t.getSort() == org.objectweb.asm.Type.ARRAY) t = t.getElementType();
            if (t.getSort() == org.objectweb.asm.Type.OBJECT) out.add(t.getInternalName());
        } catch (RuntimeException ignored) {
        }
    }
    private static String best(Map<String, Integer> score) {
        String best = null;
        int bestScore = 0;
        for (var e : score.entrySet()) {
            int sc = e.getValue();
            if (sc > bestScore || (sc == bestScore && best != null
                    && e.getKey().length() > best.length())) {
                best = e.getKey();
                bestScore = sc;
            }
        }
        return best;
    }

    /** Добавляет токены из строки (camelCase/«_»/«$») с весом. */
    private static void tokens(Map<String, Integer> score, String src, int weight) {
        if (src == null) return;
        for (String w : splitWords(src)) {
            String lw = w.toLowerCase();
            if (w.length() < 3 || STOP.contains(lw)) continue;
            if (looksRandom(w)) continue; // обфусцированный токен — не даёт смысла
            score.merge(w, weight, Integer::sum);
        }
    }

    private static List<String> splitWords(String src) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '_' || c == '$' || c == '<' || c == '>') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            if (Character.isUpperCase(c) && cur.length() > 0
                    && Character.isLowerCase(cur.charAt(cur.length() - 1))) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String pascal(String w) {
        if (w == null || w.isEmpty()) return "Cls";
        // гарантируем валидный Java-идентификатор: буква в начале
        if (!Character.isLetter(w.charAt(0))) return "Cls" + w;
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    private static String unique(String base, Set<String> used) {
        String name = base;
        int n = 2;
        while (used.contains(name)) name = base + n++;
        used.add(name);
        return name;
    }

    private static String innerName(String innerRaw, Signals s, Set<String> used, String outerNew) {
        String candidate = null;
        if (!innerRaw.matches("\\d+") && s != null) {
            Map<String, Integer> score = new LinkedHashMap<>();
            for (String m : s.methods) tokens(score, m, 2);
            for (String f : s.fields) tokens(score, f, 1);
        for (String str : s.strings) tokens(score, str, 1);
        for (String ty : s.types) tokens(score, simple(ty), 2);
            candidate = best(score);
        }
        String base = (candidate != null ? pascal(candidate) : "Inner");
        String name = outerNew + "$" + base;
        int n = 2;
        while (used.contains(name)) name = outerNew + "$" + base + (n++);
        used.add(name);
        return name;
    }

    private static String simple(String internal) {
        int slash = internal.lastIndexOf('/');
        String s = slash >= 0 ? internal.substring(slash + 1) : internal;
        int d = s.lastIndexOf('$');
        return d >= 0 ? s.substring(d + 1) : s;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
