package decompiler.fixtures;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.cfganalyzer.CfgBuilder;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CfgLeadersTest {

    private static MethodNode method(byte[] cls, String name) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cls).accept(cn, 0);
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        throw new IllegalStateException("no " + name);
    }

    private static boolean startsBlock(List<BasicBlock> blocks, AbstractInsnNode first) {
        for (BasicBlock b : blocks) {
            if (!b.getInstructions().isEmpty() && b.getInstructions().get(0) == first) return true;
        }
        return false;
    }

    @Test
    void tryBoundariesAreBlockLeaders() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "L1", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m", "()V", null, null);
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

        MethodNode mn = method(cw.toByteArray(), "m");
        List<BasicBlock> blocks = CfgBuilder.build(mn);
        TryCatchBlockNode tcb = mn.tryCatchBlocks.get(0);
        assertTrue(startsBlock(blocks, tcb.start), "start не лидер");
        assertTrue(startsBlock(blocks, tcb.end), "end не лидер");
        assertTrue(startsBlock(blocks, tcb.handler), "handler не лидер");
    }
}