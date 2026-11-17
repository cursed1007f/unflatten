package decompiler.fixtures;

import decompiler.ast.StackInference;
import decompiler.ast.StackValue;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StackInferenceTest {

    private static MethodNode method(byte[] cls, String name) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cls).accept(cn, 0);
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        throw new IllegalStateException("no " + name);
    }

    @Test
    void diamondJoinHasIntOnStack() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "D1", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m", "(I)I", null, null);
        mv.visitCode();
        Label l1 = new Label(), l2 = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFEQ, l1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, l2);
        mv.visitLabel(l1);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitLabel(l2);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        MethodNode mn = method(cw.toByteArray(), "m");
        Map<Integer, List<StackValue>> byInsn = StackInference.byInstruction(mn);
        int join = mn.instructions.indexOf(mn.instructions.toArray()[mn.instructions.size() - 2]);
        List<StackValue> st = byInsn.get(join);
        assertNotNull(st, "no frame at join");
        assertEquals(1, st.size());
        assertEquals("int", st.get(0).type());
    }

    @Test
    void handlerEntryHasReference() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "D2", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "h", "()V", null, null);
        mv.visitCode();
        Label start = new Label(), end = new Label(), handler = new Label();
        mv.visitLabel(start);
        mv.visitInsn(Opcodes.NOP);
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        MethodNode mn = method(cw.toByteArray(), "h");
        Map<Integer, List<StackValue>> byInsn = StackInference.byInstruction(mn);
        int h = -1, seen = 0;
        for (int i = 0; i < mn.instructions.size(); i++) {
            if (mn.instructions.get(i) instanceof org.objectweb.asm.tree.LabelNode) {
                seen++;
                if (seen == 2) { h = i; break; }
            }
        }
        while (h >= 0 && h < mn.instructions.size() && mn.instructions.get(h).getOpcode() < 0) h++;
        List<StackValue> st = byInsn.get(h);
        assertNotNull(st, "no frame at handler");
        assertEquals(1, st.size());
        assertNotNull(st.get(0).type());
        assertFalse(StackValue.isPrimitive(st.get(0).type()), st.get(0).type());
    }
}