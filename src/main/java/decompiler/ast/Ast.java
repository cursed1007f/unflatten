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
    public record Call(Expr target, String name, List<Expr> args, boolean isStatic) implements Expr {
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
        while (d.startsWith("[")) d = d.substring(1);
        if (d.startsWith("L") && d.endsWith(";")) {
            return d.substring(1, d.length() - 1).replace('/', '.');
        }
        return switch (d) {
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
        };
    }
}