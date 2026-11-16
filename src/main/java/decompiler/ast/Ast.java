package decompiler.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * AST-модель для Этапа 6: выражения и операторы Java-кода, восстанавливаемые
 * из байткода и дерева структур.
 */
public final class Ast {

    private Ast() {
    }

    /** Выражение (возвращает значение). */
    public interface Expr {
    }

    /** Целочисленный/длинный литерал. */
    public record Literal(Object value) implements Expr {
        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /** null-литерал. */
    public record NullLit() implements Expr {
        @Override
        public String toString() {
            return "null";
        }
    }

    /** Переменная (локаль или параметр). name — сгенерированное имя. */
    public record Var(String name) implements Expr {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Резолвер имен локальных переменных по слоту и типу. */
    public static final class VarNameResolver {
        private final int paramCount;
        private final int thisOffset; // 0 для static, 1 для instance (слот this)

        public VarNameResolver(int paramCount, int thisOffset) {
            this.paramCount = paramCount;
            this.thisOffset = thisOffset;
        }

        /**
         * Имя переменной слота по типу. Параметры (слот от thisOffset) получают
         * p0,p1,... по порядковому номеру; остальные — по типу: iVar/lVar/oVar...
         */
        public String name(int slot, String typeName) {
            // слот 0 в instance-методе — это this
            if (thisOffset == 1 && slot == 0) return "this";
            String base;
            if (slot >= thisOffset && slot < thisOffset + paramCount) {
                base = "p" + (slot - thisOffset);
            } else {
                char prefix;
                String t = typeName == null ? "Object" : typeName.toLowerCase();
                if (t.contains("int") || t.contains("boolean") || t.contains("byte")
                        || t.contains("short") || t.contains("char")) {
                    prefix = 'i';
                } else if (t.contains("long")) {
                    prefix = 'l';
                } else if (t.contains("double")) {
                    prefix = 'd';
                } else if (t.contains("float")) {
                    prefix = 'f';
                } else if (t.equals("object") || t.contains("string")) {
                    prefix = 'o';
                } else {
                    prefix = 'o';
                }
                base = prefix + "Var" + slot;
            }
            return base;
        }
    }

    /** this. */
    public record This() implements Expr {
        @Override
        public String toString() {
            return "this";
        }
    }

    /** Бинарный оператор: a @ b. */
    public record Binary(String op, Expr left, Expr right) implements Expr {
        @Override
        public String toString() {
            return "(" + left + " " + op + " " + right + ")";
        }
    }

    /** Унарный оператор: @ operand (например !, -). */
    public record Unary(String op, Expr operand) implements Expr {
        @Override
        public String toString() {
            return op + "(" + operand + ")";
        }
    }

    /** Вызов метода: target.name(args) или static name(args). */
    public record Call(Expr target, String name, List<Expr> args, boolean isStatic, String retDesc) implements Expr {
        /** Без типа возврата (служебные выражения вроде thrown/cast). */
        public Call(Expr target, String name, List<Expr> args, boolean isStatic) {
            this(target, name, args, isStatic, null);
        }

        @Override
        public String toString() {
            String base = (target != null ? target + "." : "") + name;
            List<String> strs = new ArrayList<>();
            for (Expr a : args) strs.add(String.valueOf(a));
            return base + "(" + String.join(", ", strs) + ")";
        }
    }

    /** Доступ к полю. */
    public record Field(Expr target, String name, boolean isStatic) implements Expr {
        @Override
        public String toString() {
            return (target != null ? target + "." : "") + name;
        }
    }

    /** new Type(args). */
    public record NewObject(String desc, List<Expr> args) implements Expr {
        @Override
        public String toString() {
            List<String> strs = new ArrayList<>();
            for (Expr a : args) strs.add(String.valueOf(a));
            return "new " + friendlyType(desc) + "(" + String.join(", ", strs) + ")";
        }
    }

    /** Оператор (не имеет значения). */
    public interface Stmt {
    }

    /** return [expr]; */
    public record Return(Expr value) implements Stmt {
    }

    /** var = expr; */
    public record Assign(Var target, Expr value) implements Stmt {
    }

    /** Выражение как оператор (вызов void-метода, постфикс и т.п.). */
    public record ExprStmt(Expr expr) implements Stmt {
    }

    /** if (cond) { then } [else { else }] */
    public record If(Expr cond, List<Stmt> thenBranch, List<Stmt> elseBranch) implements Stmt {
    }

    /** while (cond) { body } — базовый цикл. */
    public record While(Expr cond, List<Stmt> body) implements Stmt {
    }

    /** switch (key) { case v: ...; default: ... } */
    public record Switch(Expr key, List<Case> cases, List<Stmt> defaultBranch) implements Stmt {
    }

    /** Одна ветвь switch: case value: statements */
    public record Case(Integer value, List<Stmt> body) implements Stmt {
    }

    /** try { body } catch (Type e) { ... } ... — один catch на запись. */
    public record TryCatch(List<Stmt> tryBody, String catchType, Ast.Var catchVar,
                           List<Stmt> catchBody) implements Stmt {
    }

    /** Блок операторов. */
    public record Block(List<Stmt> body) implements Stmt {
        public Block() {
            this(new ArrayList<>());
        }
        public void add(Stmt s) {
            body.add(s);
        }
    }

    /** Пустой оператор (нет операции). */
    public record Nop() implements Stmt {
    }

    /** Вспомогательное: тип в дружественной форме (Lfoo/Bar; -> foo.Bar). */
    static String friendlyType(String desc) {
        if (desc == null) return "Object";
        String d = desc;
        int dims = 0;
        while (d.startsWith("[")) { d = d.substring(1); dims++; }
        String suffix = "[]".repeat(dims);
        if (d.startsWith("L") && d.endsWith(";")) {
            String internal = d.substring(1, d.length() - 1);
            String renamed = ClassRenamer.renameInternal(internal);
            return renamed.replace('/', '.') + suffix;
        }
        return (switch (d) {
            case "I" -> "int";
            case "J" -> "long";
            case "Z" -> "boolean";
            case "F" -> "float";
            case "D" -> "double";
            case "B" -> "byte";
            case "S" -> "short";
            case "C" -> "char";
            case "V" -> "void";
            default -> "Object";
        }) + suffix;
    }

    /**
     * Свёртка константного выражения (для непрозрачных предикатов обфускатора,
     * вида {@code 1 * 1 + 1 % 2 != 0}).
     *
     * @return значение (Integer/Long/Double/Boolean) либо {@code null}, если
     *         выражение не является константным
     */
    static Object constValue(Expr e) {
        if (e == null) return null;
        if (e instanceof Literal l) {
            Object v = l.value();
            return (v instanceof Number || v instanceof Boolean || v instanceof String) ? v : null;
        }
        if (e instanceof Unary u) {
            Object v = constValue(u.operand());
            if (v == null) return null;
            if (u.op().equals("!") && v instanceof Boolean b) return !b;
            if (u.op().equals("-") && v instanceof Number n) {
                if (n instanceof Double || n instanceof Float) return -n.doubleValue();
                if (n instanceof Long) return -n.longValue();
                return -n.intValue();
            }
            if (u.op().equals("~") && v instanceof Number n) return ~n.intValue();
            return null;
        }
        if (e instanceof Binary b) {
            Object l = constValue(b.left());
            Object r = constValue(b.right());
            if (l == null || r == null) return null;
            return applyConst(b.op(), l, r);
        }
        return null;
    }

    private static Object applyConst(String op, Object l, Object r) {
        if (l instanceof Boolean lb && r instanceof Boolean rb) {
            if (op.equals("&&")) return lb && rb;
            if (op.equals("||")) return lb || rb;
            if (op.equals("==")) return lb.equals(rb);
            if (op.equals("!=")) return !lb.equals(rb);
            return null;
        }
        if (!(l instanceof Number) || !(r instanceof Number)) return null;
        // сравнения
        if (op.equals("==") || op.equals("!=") || op.equals("<") || op.equals("<=")
                || op.equals(">") || op.equals(">=")) {
            double a = ((Number) l).doubleValue();
            double b = ((Number) r).doubleValue();
            return switch (op) {
                case "==" -> a == b;
                case "!=" -> a != b;
                case "<" -> a < b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                default -> a >= b;
            };
        }
        // арифметика с плавающей точкой
        if (l instanceof Double || r instanceof Double || l instanceof Float || r instanceof Float) {
            double a = ((Number) l).doubleValue();
            double b = ((Number) r).doubleValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? null : a / b;
                case "%" -> b == 0 ? null : a % b;
                default -> null;
            };
        }
        // целочисленная арифметика
        boolean useLong = l instanceof Long || r instanceof Long;
        long a = ((Number) l).longValue();
        long b = ((Number) r).longValue();
        long res;
        switch (op) {
            case "+" -> res = a + b;
            case "-" -> res = a - b;
            case "*" -> res = a * b;
            case "/" -> { if (b == 0) return null; res = a / b; }
            case "%" -> { if (b == 0) return null; res = a % b; }
            case "&" -> res = a & b;
            case "|" -> res = a | b;
            case "^" -> res = a ^ b;
            case "<<" -> res = a << b;
            case ">>" -> res = a >> b;
            case ">>>" -> res = a >>> b;
            default -> { return null; }
        }
        return useLong ? (Object) res : (Object) (int) res;
    }
}