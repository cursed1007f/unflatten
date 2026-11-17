package decompiler.ast;

import java.util.Objects;

/**
 * Абстрактное значение стека для entry-stack (см. ENTRY-STACK.md).
 * Не AST-узел: описывает, что лежит на входе блока.
 */
public record StackValue(Kind kind, String type, int slot, int size) {

    public enum Kind { LITERAL, VAR, NEW, EXPR, EXCEPTION }

    /** Слияние недопустимо: стек испорчен или тип не выводится. */
    public static final class MergeFailure extends RuntimeException {
        public MergeFailure(String message) {
            super(message);
        }
    }

    public static StackValue literal(String type, int size) {
        return new StackValue(Kind.LITERAL, type, -1, size);
    }

    public static StackValue var(String type, int slot, int size) {
        return new StackValue(Kind.VAR, type, slot, size);
    }

    public static StackValue exception() {
        return new StackValue(Kind.EXCEPTION, "java.lang.Throwable", -1, 1);
    }

    /**
     * Слияние значений на join. Одинаковые — как есть. Разные примитивы
     * (int/long/float/double, boolean vs число) — {@link MergeFailure}:
     * JVM-верификатор такого join не допустит. Разные ссылки — общий предок
     * (Object, если не известен).
     */
    public static StackValue merge(StackValue a, StackValue b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.size != b.size) {
            throw new MergeFailure("size mismatch: " + a.type + "/" + b.type);
        }
        if (Objects.equals(a.kind, b.kind) && Objects.equals(a.type, b.type)
                && a.slot == b.slot) {
            return a;
        }
        if (isPrimitive(a.type) || isPrimitive(b.type)) {
            if (!a.type.equals(b.type)) {
                throw new MergeFailure("primitive merge " + a.type + "/" + b.type);
            }
            return a;
        }
        String t = a.type.equals(b.type) ? a.type : "Object";
        return new StackValue(Kind.EXPR, t, -1, 1);
    }

    public static boolean isPrimitive(String type) {
        return switch (type) {
            case "int", "long", "float", "double", "boolean", "byte", "short", "char" -> true;
            default -> false;
        };
    }
}