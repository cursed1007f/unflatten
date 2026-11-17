package decompiler.ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Удаление мёртвых присваиваний примитивным локалям ({@code iVar3 = 0;} и т.п.).
 *
 * <p>Обфускатор щедро рассыпает {@code iVarN = const;}, которые затем ни разу не
 * читаются (write-only) либо перезаписываются до чтения. Такие присваивания не
 * имеют побочных эффектов и безопасно удаляются. Это снижает маркер
 * {@code мёртвые iVar=const} и убирает шум из вывода.</p>
 *
 * <p>Анализ консервативный: удаляем присваивание примитив-локалу только если это
 * имя НИ РАЗУ не встречается как чтение во всём методе (иначе не трогаем).</p>
 */
final class DeadStores {

    private DeadStores() {
    }

    /** Возвращает копию списка операторов без мёртвых присваиваний примитив-локалям. */
    static List<Ast.Stmt> eliminate(List<Ast.Stmt> stmts) {
        if (stmts == null || stmts.isEmpty()) return stmts;
        // Чтения собираем ОДИН раз на весь метод — живая переменная, прочитанная
        // в любой ветви/блоке, не должна считаться мёртвой.
        Set<String> reads = new HashSet<>();
        for (Ast.Stmt s : stmts) collectReads(s, reads);
        return filterList(stmts, reads);
    }

    private static List<Ast.Stmt> filterList(List<Ast.Stmt> stmts, Set<String> reads) {
        if (stmts == null) return null;
        List<Ast.Stmt> out = new ArrayList<>(stmts.size());
        for (Ast.Stmt s : stmts) {
            Ast.Stmt cleaned = filter(s, reads);
            if (cleaned != null) out.add(cleaned);
        }
        return out;
    }

    // ------------------------------------------------------------------ чтения

    private static void collectReads(Ast.Stmt s, Set<String> reads) {
        if (s instanceof Ast.Return r) {
            collectReads(r.value(), reads);
        } else if (s instanceof Ast.Assign a) {
            collectReads(a.value(), reads); // цель — запись, не чтение
        } else if (s instanceof Ast.ExprStmt es) {
            collectReads(es.expr(), reads);
        } else if (s instanceof Ast.If i) {
            collectReads(i.cond(), reads);
            for (Ast.Stmt t : i.thenBranch()) collectReads(t, reads);
            if (i.elseBranch() != null) for (Ast.Stmt t : i.elseBranch()) collectReads(t, reads);
        } else if (s instanceof Ast.While w) {
            collectReads(w.cond(), reads);
            for (Ast.Stmt t : w.body()) collectReads(t, reads);
        } else if (s instanceof Ast.Switch sw) {
            collectReads(sw.key(), reads);
            for (Ast.Case c : sw.cases()) for (Ast.Stmt t : c.body()) collectReads(t, reads);
            if (sw.defaultBranch() != null) for (Ast.Stmt t : sw.defaultBranch()) collectReads(t, reads);
        } else if (s instanceof Ast.TryCatch tc) {
            for (Ast.Stmt t : tc.tryBody()) collectReads(t, reads);
            for (Ast.Stmt t : tc.catchBody()) collectReads(t, reads);
        } else if (s instanceof Ast.Block b) {
            for (Ast.Stmt t : b.body()) collectReads(t, reads);
        }
    }

    private static void collectReads(Ast.Expr e, Set<String> reads) {
        if (e == null) return;
        if (e instanceof Ast.Var v) {
            reads.add(v.name());
        } else if (e instanceof Ast.Binary b) {
            collectReads(b.left(), reads);
            collectReads(b.right(), reads);
        } else if (e instanceof Ast.Unary u) {
            collectReads(u.operand(), reads);
        } else if (e instanceof Ast.Call c) {
            collectReads(c.target(), reads);
            for (Ast.Expr a : c.args()) collectReads(a, reads);
        } else if (e instanceof Ast.Field f) {
            collectReads(f.target(), reads);
        } else if (e instanceof Ast.NewObject no) {
            for (Ast.Expr a : no.args()) collectReads(a, reads);
        }
    }

    // ------------------------------------------------------------------ фильтр

    /** Убирает мёртвые присваивания, рекурсивно очищая вложенные блоки. */
    private static Ast.Stmt filter(Ast.Stmt s, Set<String> reads) {
        if (s instanceof Ast.Assign a) {
            if (isPrimLocal(a.target().name())
                    && a.value() instanceof Ast.Literal
                    && !reads.contains(a.target().name())) {
                return null; // write-only константа — мёртвая
            }
            return a;
        }
        if (s instanceof Ast.If i) {
            return new Ast.If(i.cond(), filterList(i.thenBranch(), reads), filterList(i.elseBranch(), reads));
        }
        if (s instanceof Ast.While w) {
            return new Ast.While(w.cond(), filterList(w.body(), reads));
        }
        if (s instanceof Ast.Switch sw) {
            List<Ast.Case> cases = new ArrayList<>();
            for (Ast.Case c : sw.cases()) cases.add(new Ast.Case(c.value(), filterList(c.body(), reads)));
            return new Ast.Switch(sw.key(), cases, filterList(sw.defaultBranch(), reads));
        }
        if (s instanceof Ast.TryCatch tc) {
            return new Ast.TryCatch(filterList(tc.tryBody(), reads), tc.catchType(), tc.catchVar(),
                    filterList(tc.catchBody(), reads));
        }
        if (s instanceof Ast.Block b) {
            return new Ast.Block(filterList(b.body(), reads));
        }
        return s;
    }

    private static boolean isPrimLocal(String n) {
        return n.startsWith("iVar") || n.startsWith("fVar")
                || n.startsWith("lVar") || n.startsWith("dVar");
    }
}