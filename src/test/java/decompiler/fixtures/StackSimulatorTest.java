package decompiler.fixtures;

import decompiler.ast.StackSimulator;
import decompiler.ast.StackValue;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StackSimulatorTest {

    private static MethodNode method(byte[] cls, String name) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cls).accept(cn, 0);
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        throw new IllegalStateException("no " + name);
    }

    @Test
    void longConstantLeavesCategory2() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "S1", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m", "()J", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitInsn(Opcodes.LRETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        MethodNode mn = method(cw.toByteArray(), "m");
        List<AbstractInsnNode> prefix = new ArrayList<>();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.LRETURN) break;
            prefix.add(n);
        }
        List<StackValue> st = StackSimulator.exit(prefix);
        assertEquals(1, st.size());
        assertEquals("long", st.get(0).type());
        assertEquals(2, st.get(0).size());
    }

    @Test
    void staticCallPushesReturnType() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "S2", null, "java/lang/Object", null);
        MethodVisitor callee = cw.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, "x", "()I", null, null);
        callee.visitCode();
        callee.visitInsn(Opcodes.ICONST_0);
        callee.visitInsn(Opcodes.IRETURN);
        callee.visitMaxs(1, 0);
        callee.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m", "()I", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "S2", "x", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        MethodNode mn = method(cw.toByteArray(), "m");
        List<AbstractInsnNode> prefix = new ArrayList<>();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.IRETURN) break;
            prefix.add(n);
        }
        List<StackValue> st = StackSimulator.exit(prefix);
        assertEquals(1, st.size());
        assertEquals("int", st.get(0).type());
    }

    @Test
    void underflowBails() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "S3", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        MethodNode mn = method(cw.toByteArray(), "m");
        List<AbstractInsnNode> prefix = new ArrayList<>();
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.RETURN) break;
            prefix.add(n);
        }
        assertThrows(IllegalStateException.class, () -> StackSimulator.exit(prefix));
    }
}