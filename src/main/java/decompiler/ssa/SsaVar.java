package decompiler.ssa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Версия переменной в SSA-форме.
 *
 * <p>Одна байткод-локаль (слот {@link #slot}) может иметь несколько версий:
 * каждая точка присваивания (store/iinc) и каждый Phi-узел открывает новую
 * версию. Каждый читатель (load) ссылается на версию, активную в своей точке.
 * Это и есть суть SSA: «одна переменная — одно присваивание».
 */
public final class SsaVar {

    private final int slot;
    private final int version;

    public SsaVar(int slot, int version) {
        this.slot = slot;
        this.version = version;
    }

    public int slot() {
        return slot;
    }

    public int version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SsaVar s)) return false;
        return slot == s.slot && version == s.version;
    }

    @Override
    public int hashCode() {
        return 31 * slot + version;
    }

    @Override
    public String toString() {
        return "v" + slot + "." + version;
    }

    /**
     * Оператор SSA-формы. {@code op} — мнемоника (например {@code istore} или
     * {@code aload}), {@code args} — входные версии, {@code result} — выходная
     * версия (называет слот, что записывается), может быть {@code null} для
     * инструкций без результата задания локальной переменной.
     */
    public static final class Insn {
        private final String op;
        private final List<SsaVar> args;
        private final SsaVar result;

        public Insn(String op, SsaVar result, List<SsaVar> args) {
            this.op = op;
            this.result = result;
            this.args = List.copyOf(args);
        }

        public String op() {
            return op;
        }

        public SsaVar result() {
            return result;
        }

        public List<SsaVar> args() {
            return args;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (result != null) sb.append(result).append(" = ");
            sb.append(op);
            if (!args.isEmpty()) {
                sb.append('(');
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(args.get(i));
                }
                sb.append(')');
            }
            return sb.toString();
        }
    }

    /**
     * Phi-функция — выбирает версию в зависимости от того, из какого
     * предшественника пришёл поток. Аргументы соответствуют предшественникам
     * по индексу; заполняются в процессе ренейминга.
     */
    public static final class Phi {
        private final int slot;
        private final List<SsaVar> args;
        private final List<Integer> preds;
        private SsaVar result;

        public Phi(int slot, List<Integer> preds) {
            this.slot = slot;
            this.preds = new ArrayList<>(preds);
            this.args = new ArrayList<>(Collections.nCopies(preds.size(), null));
        }

        public int slot() {
            return slot;
        }

        public SsaVar result() {
            return result;
        }

        /** Назначает result-версию Phi (при первичной обработке блока). */
        public void setResult(SsaVar result) {
            this.result = result;
        }

        /** Индексы блоков-предшественников (соответствуют args по индексу). */
        public List<Integer> preds() {
            return Collections.unmodifiableList(preds);
        }

        /** Аргументы Phi (могут содержать {@code null}, пока не заполнены). */
        public List<SsaVar> args() {
            return Collections.unmodifiableList(args);
        }

        public void setArg(int predIndex, SsaVar v) {
            args.set(predIndex, v);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(result != null ? result.toString() : "v" + slot + ".?")
                    .append(" = φ(");
            for (int i = 0; i < preds.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i) == null ? "—" : args.get(i)).append("@BB").append(preds.get(i));
            }
            sb.append(')');
            return sb.toString();
        }
    }
}