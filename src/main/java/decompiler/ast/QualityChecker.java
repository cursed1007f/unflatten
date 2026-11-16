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
        public int ctorInit = 0;                 // this.<init>(, oVar0.<init>(
        public int questionMark = 0;             // /* ? */
        public int lineCount = 0;

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
            this.ctorInit += o.ctorInit;
            this.questionMark += o.questionMark;
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
            sb.append(String.format("this.<init>:         %d%n", ctorInit));
            sb.append(String.format("/* ? */:             %d%n", questionMark));
            sb.append(String.format("БАЛЛ качества:       %d (ниже = лучше)%n", score()));
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ маркеры

    // "iVar29 >= this", "this >= x" — но НЕ "this.size()", "this.length" (валидный
    // доступ к члену). Смотрим на "this" как голый операнд сравнения: после "this"
    // не должно идти '.' (member access) и до него не должно быть '.' (член справа).
    private static final Pattern CMP_THIS =
            Pattern.compile("(?:^|[^\\w.])this\\s*([<>]=?|==|!=)\\s*(?!this\\.)[^\n.]?|"
                          + "[^\\w.]\\S*\\s*([<>]=?|==|!=)\\s*this(?!\\.)");

    // arr[this], new double[this], foo[this]
    private static final Pattern THIS_INDEX =
            Pattern.compile("\\[[^\\]]*\\bthis\\b[^\\]]*\\]");

    // throw(0); throw(7);
    private static final Pattern THROW_NUM =
            Pattern.compile("throw\\s*\\(\\s*-?\\d+\\s*\\)");
    // throw(oVar12);
    private static final Pattern THROW_VAR =
            Pattern.compile("throw\\s*\\(\\s*(oVar|lVar|fVar|dVar|iVar)\\d+\\s*\\)");

    // (Object)x-cast
    private static final Pattern CAST_OBJECT =
            Pattern.compile("\\(Object\\)\\s*\\S|\\bcast\\s*\\(Object\\)");

    // new Object( - пустой конструктор-заглушка
    private static final Pattern NEW_OBJECT =
            Pattern.compile("new\\s+Object\\s*\\(");

    // iVarN = 0/1/... (мёртвая константа, спилл)
    private static final Pattern DEAD_IVAR_CONST =
            Pattern.compile("\\biVar\\d+\\s*=\\s*-?\\d+;");

    // return 0; return 0L; return 0.0;
    private static final Pattern RETURN_ZERO =
            Pattern.compile("return\\s+0[Ll]?;|return\\s+0\\.0\\w*;");

    // this.<init>( или oVar0.<init>(
    private static final Pattern CTOR_INIT =
            Pattern.compile("\\b(this|oVar\\d+)\\s*\\.\\s*<init>\\s*\\(");

    /** Считает маркеры в уже сгенерированном Java-коде одного класса/метода. */
    public static Report scan(String java) {
        Report r = new Report();
        if (java == null || java.isEmpty()) return r;
        r.lineCount = countLines(java);
        r.contextThisCompare = count(CMP_THIS, java);
        r.thisIndex = count(THIS_INDEX, java);
        r.dumbThrow = count(THROW_NUM, java) + count(THROW_VAR, java);
        r.castObject = count(CAST_OBJECT, java);
        r.newObject = count(NEW_OBJECT, java);
        r.deadIVarConst = count(DEAD_IVAR_CONST, java);
        r.returnZero = count(RETURN_ZERO, java);
        r.ctorInit = count(CTOR_INIT, java);
        r.questionMark = java.contains("/* ? */") ? countAll("/* ? */", java) : 0;
        return r;
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