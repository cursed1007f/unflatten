package decompiler.fixtures;

import decompiler.cfganalyzer.CfgBuilder;
import decompiler.structure.StructureNode;
import decompiler.structure.StructureRecoverer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LoopBodyTest {

    private static MethodNode method(byte[] cls, String name) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cls).accept(cn, 0);
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        throw new IllegalStateException("no " + name);
    }

    private static void collectLoops(StructureNode n, List<StructureNode> out) {
        if (n == null) return;
        if (n.kind() == StructureNode.Kind.LOOP) out.add(n);
        if (n.children() != null) for (StructureNode c : n.children()) collectLoops(c, out);
    }

    @Test
    void twoLatchesStayInLoopBody() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "L2", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m", "(IZ)I", null, null);
        mv.visitCode();
        Label h = new Label(), els = new Label(), end = new Label();
        mv.visitLabel(h);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, end);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IFEQ, els);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 0);
        mv.visitJumpInsn(Opcodes.GOTO, h);
        mv.visitLabel(els);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 0);
        mv.visitJumpInsn(Opcodes.GOTO, h);
        mv.visitLabel(end);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();

        MethodNode mn = method(cw.toByteArray(), "m");
        var blocks = CfgBuilder.build(mn);
        var rec = StructureRecoverer.forGraph(blocks, 0);
        var root = rec.build(0);
        List<StructureNode> loops = new ArrayList<>();
        collectLoops(root, loops);
        assertFalse(loops.isEmpty(), "цикл не найден");
        List<Integer> body = loops.get(0).loopBody();
        assertTrue(body.size() >= 2, "тело цикла потеряло блоки: " + body);
    }
}