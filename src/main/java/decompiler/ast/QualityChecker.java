package decompiler.ast;

import java.util.regex.Pattern;

/**
 * Автономный чекер качества декомпиляции ({@code --ast}).
 *
 * <p>Считает по готовому Java-выводу маркеры несовершенств из BUGREPORT.md и
 * считает балл качества (0 = идеально, выше = хуже). Предназначен для регресс-контроля:
 * гонять на тест-джарке после каждой правки эмиттера и сравнивать счётчики.</p>
 */
public final class QualityChecker {

    /** Результат прогона по одному классу. */
    public static final class Report {
        public int contextThisCompare = 0;       // >= this, this OP x
        public int thisIndex = 0;                // [this], new X[this]
        public int dumbThrow = 0;                // throw(0), throw(oVarN)
        public int castObject = 0;               // (Object)x, x.cast(Object)
        public int newObject = 0;                // new Object(
        public int deadIVarConst = 0;            // iVarN = const после return/недостижим
        public int returnZero = 0;               // return 0;
        public int undecompilable = 0;           // методов с явным отказом (raw-маркер)
        public int undecompilableUnderflow = 0;
        public int undecompilableLostBlock = 0;
        public int undecompilableDoubleEmit = 0;
        public int undecompilableDeadCode = 0;
        public int undecompilableMissingReturn = 0;
        public int undecompilableOther = 0;
        public int undecompilableJoinDepth = 0;
        public int undecompilableIrreducible = 0;
        public int classErrors = 0;              // класс целиком упал при печати
        public int undecompilableClinit = 0;     // отказы в <clinit> (ExceptionInInitializerError)
        public int ctorInit = 0;                 // this.<init>(, oVar0.<init>(
        public int questionMark = 0;             // /* ? */
        public int unsupportedOps = 0;           // UnsupportedOperationException
        public int emptyBodies = 0;              // пустые тела { }
        public int methods = 0;
        public int lineCount = 0;
        /** Имя класса (для диагностики мест). Заполняется вызывающим кодом перед scan. */
        public String className = "";
        /** Первые места с {@code >= this} (для точечной диагностики). */
        public final java.util.List<CmpThisSite> cmpThisSites = new java.util.ArrayList<>();

        /** Печатает места с {@code >= this} для этого класса (если есть). Пишет в stdout. */
        public void printCmpThisSites() {
            if (cmpThisSites.isEmpty()) return;
            for (CmpThisSite s : cmpThisSites) {
                System.out.println("// QSITE [" + className + "] >= this @ строка " + (s.line + 1) + ": " + s.text);
            }
        }

        /** Балл 0-100 (100 = чисто), нормирован на число методов. */
        public int score100() {
            int m = methods > 0 ? methods : 1;
            double defects = score() + emptyBodies + unsupportedOps;
            double per = defects / (double) m;
            int s = (int) Math.round(100 - Math.min(100.0, per * 100.0));
            return Math.max(0, s);
        }

        public int score() {
            // веса: грубые типы важнее косметики
            return contextThisCompare * 5
                    + thisIndex * 4
                    + dumbThrow * 2
                    + castObject * 2
                    + newObject * 2
                    + deadIVarConst * 2
                    + returnZero
                    + ctorInit
                    + questionMark;
        }

        public void merge(Report o) {
            this.contextThisCompare += o.contextThisCompare;
            this.thisIndex += o.thisIndex;
            this.dumbThrow += o.dumbThrow;
            this.castObject += o.castObject;
            this.newObject += o.newObject;
            this.deadIVarConst += o.deadIVarConst;
            this.returnZero += o.returnZero;
            this.undecompilable += o.undecompilable;
            this.undecompilableUnderflow += o.undecompilableUnderflow;
            this.undecompilableLostBlock += o.undecompilableLostBlock;
            this.undecompilableDoubleEmit += o.undecompilableDoubleEmit;
            this.undecompilableDeadCode += o.undecompilableDeadCode;
            this.undecompilableMissingReturn += o.undecompilableMissingReturn;
            this.undecompilableOther += o.undecompilableOther;
            this.undecompilableJoinDepth += o.undecompilableJoinDepth;
            this.undecompilableIrreducible += o.undecompilableIrreducible;
            this.classErrors += o.classErrors;
            this.undecompilableClinit += o.undecompilableClinit;
            this.ctorInit += o.ctorInit;
            this.questionMark += o.questionMark;
            this.unsupportedOps += o.unsupportedOps;
            this.emptyBodies += o.emptyBodies;
            this.methods += o.methods;
            this.lineCount += o.lineCount;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("строк:               %d%n", lineCount));
            sb.append(String.format(">= this:             %d%n", contextThisCompare));
            sb.append(String.format("[this] (индексы):    %d%n", thisIndex));
            sb.append(String.format("throw(число/var):    %d%n", dumbThrow));
            sb.append(String.format(".cast(Object):       %d%n", castObject));
            sb.append(String.format("new Object(:         %d%n", newObject));
            sb.append(String.format("мёртвые iVar=const:  %d%n", deadIVarConst));
            sb.append(String.format("return 0:            %d%n", returnZero));
            sb.append(String.format("undecompilable:      %d%n", undecompilable));
            if (undecompilable > 0) {
                sb.append(String.format("  underflow:         %d%n", undecompilableUnderflow));
                sb.append(String.format("  lost block:        %d%n", undecompilableLostBlock));
                sb.append(String.format("  double emit:       %d%n", undecompilableDoubleEmit));
                sb.append(String.format("  dead code:         %d%n", undecompilableDeadCode));
                sb.append(String.format("  missing return:    %d%n", undecompilableMissingReturn));
                sb.append(String.format("  other:             %d%n", undecompilableOther));
                sb.append(String.format("  join depth:        %d%n", undecompilableJoinDepth));
                sb.append(String.format("  irreducible:       %d%n", undecompilableIrreducible));
                // Правило: ровно одна primary reason на метод; сумма бакетов
                // обязана совпадать с total. clinit — secondary, в сумму не входит.
                int primary = undecompilableUnderflow + undecompilableLostBlock
                        + undecompilableDoubleEmit + undecompilableDeadCode
                        + undecompilableMissingReturn + undecompilableOther
                        + undecompilableJoinDepth + undecompilableIrreducible;
                if (primary != undecompilable) {
                    sb.append(String.format("  BUCKET MISMATCH:   primary=%d total=%d%n",
                            primary, undecompilable));
                }
                sb.append(String.format("  (secondary) clinit: %d%n", undecompilableClinit));
            }
            sb.append(String.format("class-level errors:  %d%n", classErrors));
            sb.append(String.format("this.<init>:         %d%n", ctorInit));
            sb.append(String.format("/* ? */:             %d%n", questionMark));
            sb.append(String.format("score parts: ctx=%d idx=%d dumb=%d cast=%d new=%d dead=%d ret0=%d ctor=%d qm=%d%n",
                    contextThisCompare, thisIndex, dumbThrow, castObject, newObject,
                    deadIVarConst, returnZero, ctorInit, questionMark));
            sb.append(String.format("БАЛЛ качества:       %d (ниже = лучше)%n", score()));
            sb.append(String.format("БАЛЛ 0-100:          %d%n", score100()));
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ маркеры

    /** Одно найденное место с {@code >= this} (для точечной диагностики). */
    public static final class CmpThisSite {
        public final int line;   // номер строки в Java-выводе (с нуля)
        public final String text; // фрагмент строки с маркером
        public CmpThisSite(int line, String text) { this.line = line; this.text = text; }
    }

    // "iVar29 >= this", "this >= x" — реляционные сравнения с голым this (артефакт:
// объект нельзя упорядочивать). `== this`/`!= this` сюда НЕ входят — это обычно
// валидная identity-проверка (`o == this` в equals) или проверка null.
// `this.field < x` тоже не флагаем: после this идёт '.' (доступ к члену).
    private static final Pattern CMP_THIS =
            Pattern.compile("\\bthis\\b\\s*[<>]=?\\s*[\\w(]|"
                          + "[\\w)\\]]\\s*[<>]=?\\s*\\bthis\\b(?!\\.)");

    // arr[this], new double[this], foo[this] — но НЕ [this.field] (валидный this.member-индекс).
    // Границы [ ] не пересекают перевод строки (иначе ложные склейки через несколько строк).
    private static final Pattern THIS_INDEX =
            Pattern.compile("\\[[^\\]\\r\\n]*\\bthis\\b(?!\\.)[^\\]\\r\\n]*\\]");

    // throw(0); throw(7);
    private static final Pattern THROW_NUM =
            Pattern.compile("throw\\s+-?\\d+\\b");
    // throw(iVar12) — примитив вместо Throwable (стек испорчен); oVar — валидная ссылка, не считаем
    private static final Pattern THROW_PRIM_VAR =
            Pattern.compile("throw\\s+(iVar|lVar|fVar|dVar)\\d+\\b");

    // (Object)x-cast
    private static final Pattern CAST_OBJECT =
            Pattern.compile("\\(Object\\)\\s*\\S|\\bcast\\s*\\(Object\\)");

    // new Object( - пустой конструктор-заглушка
    /** "Мёртвое" присваивание: имя переменной больше нигде в классе не встречается. */
    static int DEBUG_DEAD;
    /** return 0; считается дефектом только там, где метод возвращает не int. */
    static int RZ_DEBUG;
    private static int countReturnZero(String java) {
        Pattern rz = Pattern.compile("\\breturn 0;");
        var m = rz.matcher(java);
        int bad = 0;
        while (m.find()) {
            String head = enclosingSignature(java, m.start());
            if (head == null || head.contains(" int ")) continue; // законный возврат int
            bad++;
        }
        return bad;
    }

    private static boolean isControlLine(String t) {
        return t.startsWith("if ") || t.startsWith("if(") || t.startsWith("else")
                || t.startsWith("while ") || t.startsWith("while(") || t.startsWith("for ")
                || t.startsWith("for(") || t.startsWith("try") || t.startsWith("catch")
                || t.startsWith("switch") || t.startsWith("synchronized") || t.startsWith("do ");
    }
    private static String enclosingSignature(String java, int pos) {
        for (int i = pos; i > 0; ) {
            i = java.lastIndexOf('\n', i - 1);
            if (i < 0) break;
            int end = java.indexOf('\n', i + 1);
            String line = java.substring(i + 1, end < 0 ? java.length() : end);
            String t = line.trim();
            if (t.endsWith("{") && t.contains("(") && !isControlLine(t)) {
                int paren = t.indexOf('(');
                int sp = t.lastIndexOf(' ', paren);
                return sp > 0 ? t.substring(0, sp) + " " + t.substring(sp + 1) : t;
            }
        }
        return null;
    }
    private static int countDeadIvarConst(String java) {
        Pattern p2 = Pattern.compile("\\b([ilfdo]Var\\d+)\\s*=\\s*-?\\d+(?:\\.\\d*)?[LlFfDd]?;");
        var m = p2.matcher(java);
        java.util.Map<String, Boolean> checked = new java.util.HashMap<>();
        int dead = 0;
        while (m.find()) {
            String name = m.group(1);
            Boolean d = checked.get(name);
            if (d == null) {
                Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
                int occ = 0;
                var mm = p.matcher(java);
                while (mm.find() && occ < 2) occ++;
                d = occ <= 1;
                checked.put(name, d);
            }
            if (d) dead++;
        }
        return dead;
    }
    private static int countEmptyBodies(String java) {
        int n = 0;
        int i = 0;
        while ((i = java.indexOf('{', i)) >= 0) {
            int j = i + 1;
            while (j < java.length() && Character.isWhitespace(java.charAt(j))) j++;
            if (j < java.length() && java.charAt(j) == '}') n++;
            i++;
        }
        return n;
    }
    private static final Pattern NEW_OBJECT =
            Pattern.compile("new\\s+Object\\s*\\(");

    // <var>N = <константа>; — мёртвая константа-спилл любого типа, не только iVar
    // (lVar/fVar/dVar/oVar тоже пишутся и не читаются).
    private static final Pattern DEAD_IVAR_CONST =
            Pattern.compile("\\b(iVar|lVar|fVar|dVar|oVar)\\d+\\s*=\\s*-?\\d+(?:\\.\\d*)?[LlFfDd]?;");

    // return 0; return 0L; return 0.0;
    private static final Pattern RETURN_ZERO =
            Pattern.compile("return\\s+0[Ll]?;|return\\s+0\\.0\\w*;");

    // // undecompilable: <label>[: detail] @ <метод>
    private static final Pattern UNDEC_MARKER =
            Pattern.compile("// undecompilable: ([^@\\r\\n]+?) @");

    // this.<init>( или oVar0.<init>(
    private static final Pattern CTOR_INIT =
            Pattern.compile("\\b(this|oVar\\d+)\\s*\\.\\s*<init>\\s*\\(");

    /** Считает маркеры в уже сгенерированном Java-коде одного класса/метода. */
    public static Report scan(String java) {
        Report r = new Report();
        if (java == null || java.isEmpty()) return r;
        r.lineCount = countLines(java);
        r.contextThisCompare = count(CMP_THIS, java);
        if (r.contextThisCompare > 0) collectCmpThis(java, r);
        r.thisIndex = count(THIS_INDEX, java);
        r.dumbThrow = count(THROW_NUM, java) + count(THROW_PRIM_VAR, java);
        r.castObject = count(CAST_OBJECT, java);
        r.newObject = count(NEW_OBJECT, java);
        r.deadIVarConst = countDeadIvarConst(java);
        r.returnZero = countReturnZero(java);
        r.undecompilable = countAll("// undecompilable:", java);
        // Независимый учёт по точной метке причины: other — это только
        // нераспознанные метки, а не «total минус известные». Иначе guard
        // BUCKET MISMATCH не сработает никогда.
        var um = UNDEC_MARKER.matcher(java);
        while (um.find()) {
            String reason = um.group(1).trim();
            int colon = reason.indexOf(':');
            String label = (colon >= 0 ? reason.substring(0, colon) : reason).trim();
            switch (label) {
                case "underflow" -> r.undecompilableUnderflow++;
                case "lost block" -> r.undecompilableLostBlock++;
                case "double emit" -> r.undecompilableDoubleEmit++;
                case "dead code" -> r.undecompilableDeadCode++;
                case "missing return" -> r.undecompilableMissingReturn++;
                case "join depth mismatch" -> r.undecompilableJoinDepth++;
                case "irreducible" -> r.undecompilableIrreducible++;
                default -> r.undecompilableOther++;
            }
        }
        r.undecompilableClinit = countAll(" @ <clinit>", java);
        r.ctorInit = count(CTOR_INIT, java);
        r.questionMark = java.contains("/* ? */") ? countAll("/* ? */", java) : 0;
        r.unsupportedOps = countAll("UnsupportedOperationException", java);
        r.emptyBodies = countEmptyBodies(java);

        return r;
    }

    /** Собирает первые места с {@code >= this} (строка + фрагмент) для диагностики. */
    private static void collectCmpThis(String java, Report r) {
        String[] lines = java.split("\n", -1);
        var m = CMP_THIS.matcher(java);
        int budget = 8;
        while (m.find() && budget-- > 0) {
            int lineNo = 0, count = 0;
            while (count <= m.start() && lineNo < lines.length) {
                count += lines[lineNo].length() + 1;
                lineNo++;
            }
            String text = lines[Math.max(0, lineNo - 1)].trim();
            r.cmpThisSites.add(new CmpThisSite(lineNo - 1, text.length() > 90 ? text.substring(0, 90) : text));
        }
    }

    private static int count(Pattern p, String s) {
        int n = 0;
        var m = p.matcher(s);
        while (m.find()) n++;
        return n;
    }

    private static int countAll(String sub, String s) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    private static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private QualityChecker() {
    }
}