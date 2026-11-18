package decompiler.ast;

import java.util.List;

/**
 * Печатает AST-операторы в Java-код с отступами (для вывода полного тела
 * метода). Простое текстовое представление, достаточное для превью-вывода.
 */
public final class JavaPrinter {
    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
        "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue",
        "default","do","double","else","enum","extends","final","finally","float","for","goto","if",
        "implements","import","instanceof","int","interface","long","native","new","package","private",
        "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
        "throw","throws","transient","try","void","volatile","while","_","true","false","null");

    static String id(String n) {
        if (n == null || n.isEmpty()) return n;
        if ("this".equals(n) || "super".equals(n) || n.contains(".")) return n;
        boolean ok = Character.isJavaIdentifierStart(n.charAt(0));
        for (int i = 1; ok && i < n.length(); i++) ok = Character.isJavaIdentifierPart(n.charAt(i));
        if (ok && !KEYWORDS.contains(n)) return n;
        return n + "$";
    }

    private final StringBuilder sb = new StringBuilder();
    private int indent = 0;

    private JavaPrinter() {
    }

    public static String print(List<Ast.Stmt> stmts) {
        JavaPrinter p = new JavaPrinter();
        for (Ast.Stmt s : stmts) {
            p.stmt(s);
        }
        return p.sb.toString();
    }

    private void stmt(Ast.Stmt s) {
        if (s instanceof Ast.Return r) {
            pad();
            if (r.value() == null) {
                sb.append("return;\n");
            } else {
                sb.append("return ").append(expr(r.value())).append(";\n");
            }
        } else if (s instanceof Ast.Assign a) {
            pad(); sb.append(expr(a.target())).append(" = ").append(expr(a.value())).append(";\n");
        } else if (s instanceof Ast.ExprStmt es && es.expr() instanceof Ast.Call c
                && "throw".equals(c.name()) && !c.args().isEmpty()) {
            // throw(e) — невалидный Java; печатаем throw <expr>;
            pad(); sb.append("throw ").append(expr(c.args().get(0))).append(";\n");
        } else if (s instanceof Ast.ExprStmt es) {
            pad(); sb.append(expr(es.expr())).append(";\n");
        } else if (s instanceof Ast.If i) {
            pad(); sb.append("if (").append(expr(i.cond())).append(") {\n");
            indent++;
            for (Ast.Stmt t : i.thenBranch()) stmt(t);
            indent--;
            if (i.elseBranch() != null && !isEffectivelyEmpty(i.elseBranch())) {
                pad(); sb.append("} else {\n");
                indent++;
                for (Ast.Stmt e : i.elseBranch()) stmt(e);
                indent--;
            }
            pad(); sb.append("}\n");
        } else if (s instanceof Ast.While w) {
            pad(); sb.append("while (").append(expr(w.cond())).append(") {\n");
            indent++;
            for (Ast.Stmt b : w.body()) stmt(b);
            indent--;
            pad(); sb.append("}\n");
        } else if (s instanceof Ast.TryCatch tc) {
            pad(); sb.append("try {\n");
            indent++;
            for (Ast.Stmt b : tc.tryBody()) stmt(b);
            indent--;
            pad(); sb.append("} catch (");
            sb.append(Ast.friendlyType("L" + tc.catchType() + ";")).append(' ')
                    .append(tc.catchVar() != null ? tc.catchVar().name() : "e").append(") {\n");
            indent++;
            for (Ast.Stmt b : tc.catchBody()) stmt(b);
            indent--;
            pad(); sb.append("}\n");
        } else if (s instanceof Ast.Switch sw) {
            pad(); sb.append("switch (").append(expr(sw.key())).append(") {\n");
            indent++;
            for (Ast.Case c : sw.cases()) {
                pad(); sb.append("case ").append(c.value()).append(":\n");
                indent++;
                for (Ast.Stmt cs : c.body()) stmt(cs);
                indent--;
            }
            if (sw.defaultBranch() != null && !sw.defaultBranch().isEmpty()) {
                pad(); sb.append("default:\n");
                indent++;
                for (Ast.Stmt ds : sw.defaultBranch()) stmt(ds);
                indent--;
            }
            indent--;
            pad(); sb.append("}\n");
        } else if (s instanceof Ast.Block bl) {
            for (Ast.Stmt b : bl.body()) stmt(b);
        } else if (s instanceof Ast.VarDecl vd) {
            pad(); sb.append(vd.type()).append(' ').append(id(vd.name())).append(";\n");
        } else if (s instanceof Ast.Raw raw) {
            pad(); sb.append(raw.text()).append('\n');
        } else if (s instanceof Ast.Case ca) {
            // case встречается только внутри Switch — не должен печататься сам
            for (Ast.Stmt cs : ca.body()) stmt(cs);
        } else {
            pad(); sb.append("/* ? */\n");
        }
    }

    /** Экранирование строкового литерала: кавычки, слэши, контролы (unicode-эскейп не используем). */
    private static String escapeString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\%03o", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** Ветвь без видимых операторов: Nop, пустые блоки. */
    static boolean isEffectivelyEmpty(java.util.List<Ast.Stmt> body) {
        if (body == null || body.isEmpty()) return true;
        for (Ast.Stmt s : body) {
            if (s instanceof Ast.Nop) continue;
            if (s instanceof Ast.Block b) {
                if (!isEffectivelyEmpty(b.body())) return false;
                continue;
            }
            return false;
        }
        return true;
    }
    private String expr(Ast.Expr e) {
        if (e == null) return "?";
        if (e instanceof Ast.Literal l) {
            Object v = l.value();
            if (v instanceof String s) return "\"" + escapeString(s) + "\"";
            if (v instanceof Boolean b) return b ? "true" : "false";
            if (v instanceof Long x) return x + "L";
            if (v instanceof Float f) return f + "f";
            return String.valueOf(v);
        }
        if (e instanceof Ast.Var v) return id(v.name());
        if (e instanceof Ast.This t) return "this";
        if (e instanceof Ast.NullLit) return "null";
        if (e instanceof Ast.Binary b) {
            // arr[idx] — чтение из массива: без пробелов вокруг [ ]
            if (b.op().equals("[]")) return expr(b.left()) + "[" + expr(b.right()) + "]";
            // arr[idx] = val — запись в массив. Структура: Binary("[]=", arr, Binary("[]", idx, val))
            if (b.op().equals("[]=")) {
                if (b.right() instanceof Ast.Binary rb && rb.op().equals("[]")) {
                    return expr(b.left()) + "[" + expr(rb.left()) + "] = " + expr(rb.right());
                }
                return expr(b.left()) + " = " + expr(b.right());
            }
            return "(" + expr(b.left()) + " " + b.op() + " " + expr(b.right()) + ")";
        }
        if (e instanceof Ast.Unary u) {
            // Операнд-бинарник обязан быть в скобках: !(a == b), (long)(a + b),
            // -(a + b). Иначе приоритет Java меняет смысл (тихо неверный код).
            String op = u.op();
            Ast.Expr operand = u.operand();
            boolean needParens = operand instanceof Ast.Binary
                    || (operand instanceof Ast.Unary && !"!".equals(op));
            return op + (needParens ? "(" + expr(operand) + ")" : expr(operand));
        }
        if (e instanceof Ast.Cast c) {
            return "((" + c.type() + ")" + expr(c.value()) + ")";
        }
        if (e instanceof Ast.Call c) {
            StringBuilder sb = new StringBuilder();
            if (c.target() != null) sb.append(expr(c.target())).append(".");
            sb.append(id(c.name())).append("(");
            for (int i = 0; i < c.args().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(expr(c.args().get(i)));
            }
            return sb.append(")").toString();
        }
        if (e instanceof Ast.Field f) {
            return (f.target() != null ? expr(f.target()) + "." : "") + id(f.name());
        }
        if (e instanceof Ast.NewObject no) {
            String desc = no.desc();
            // Создание массива: дескриптор начинается с '[' или заканчивается "[]"
            // (например "[I" / "[D" / "Lcom/foo;[]"). Выводим new T[size1][size2]...
            if (desc != null && (desc.startsWith("[") || desc.endsWith("[]"))) {
                StringBuilder sb = new StringBuilder("new ");
                int dims = 0;
                String elem;
                if (desc.startsWith("[")) {
                    int d = 0;
                    while (d < desc.length() && desc.charAt(d) == '[') d++;
                    dims = d;
                    elem = Ast.friendlyType(desc.substring(d));
                } else {
                    // Lfoo;[] / Lfoo;[][] — считаем ХВОСТОВЫЕ пары "[]" (иначе [0][] — баг)
                    dims = 0;
                    String base = desc;
                    while (base.endsWith("[]")) {
                        dims++;
                        base = base.substring(0, base.length() - 2);
                    }
                    if (dims == 0) dims = 1;
                    elem = Ast.friendlyType(base);
                }
                sb.append(elem);
                int idx = 0;
                for (int i = 0; i < dims; i++) {
                    sb.append("[");
                    if (idx < no.args().size()) {
                        sb.append(expr(no.args().get(idx++)));
                    }
                    sb.append("]");
                }
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder("new " + Ast.friendlyType(desc) + "(");
            for (int i = 0; i < no.args().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(expr(no.args().get(i)));
            }
            return sb.append(")").toString();
        }
        return String.valueOf(e);
    }

    private void pad() {
        sb.append("  ".repeat(indent));
    }
}