package decompiler.ast;

/**
 * Метод невозможно корректно восстановить: эмиттер вышел за пределы модели
 * (стек под потоком управления, потерянный блок, двойная эмиссия и т.п.).
 *
 * <p>Намеренно НЕ обрабатывается фабрикацией значения: подстановка константы
 * на пустом стеке даёт тихо неверный код вместо явного отказа. Метод помечается
 * undecompilable, в вывод попадает raw-маркер, причина учитывается в отчёте.</p>
 */
public class UndecompilableException extends RuntimeException {

    /** Категория отказа — для бакетов в отчёте и сортировки работ. */
    public enum Kind {
        UNDERFLOW("underflow"),
        LOST_BLOCK("lost block"),
        DOUBLE_EMIT("double emit"),
        DEAD_CODE("dead code"),
        MISSING_RETURN("missing return"),
        JOIN_DEPTH("join depth mismatch"),
        IRREDUCIBLE("irreducible"),
        OTHER("other");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Kind kind;

    public UndecompilableException(Kind kind, String detail) {
        super(kind.label() + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.kind = kind;
    }

    public UndecompilableException(String detail) {
        this(Kind.OTHER, detail);
    }

    public Kind kind() {
        return kind;
    }
}
