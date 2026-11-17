package decompiler.fixtures;

import decompiler.ast.StackValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class StackValueTest {

    @Test
    void sameValuesMerge() {
        StackValue a = StackValue.literal("int", 1);
        assertSame(a, StackValue.merge(a, a));
    }

    @Test
    void differentPrimitivesBail() {
        assertThrows(StackValue.MergeFailure.class,
                () -> StackValue.merge(StackValue.literal("int", 1), StackValue.literal("long", 2)));
    }

    @Test
    void referencesMergeToObject() {
        StackValue r = StackValue.merge(StackValue.literal("java.lang.String", 1),
                StackValue.literal("java.lang.Integer", 1));
        assertEquals("Object", r.type());
    }

    @Test
    void sizeMismatchBails() {
        assertThrows(StackValue.MergeFailure.class,
                () -> StackValue.merge(StackValue.literal("int", 1), StackValue.literal("long", 2)));
    }
}