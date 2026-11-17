package decompiler.fixtures;

import decompiler.ast.JavaPrinter;
import decompiler.ast.StructureToAst;
import decompiler.cfganalyzer.CfgBuilder;
import decompiler.passes.CffUnflattenerPass;
import decompiler.structure.StructureRecoverer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ASM-фикстуры: формы, на которых ломались инварианты. */
public class FixtureTest {

    private static String decompile(byte[] cls, String method) {
        ClassNode cn = new ClassNode();
        new ClassReader(cls).accept(cn, 0);
        MethodNode mn = null;
        for (MethodNode m : cn.methods) if (m.name.equals(method)) mn = m;
        if (mn == null) throw new IllegalStateException("no method " + method);
        var blocks = CfgBuilder.build(mn);
        var deflat = CffUnflattenerPass.run(blocks);
        int entry = blocks.isEmpty() ? -1 : blocks.get(0).index();
        if (deflat.unflattened) {
            blocks = deflat.blocks;
            entry = deflat.entryIndex;
        }
        var rec = StructureRecoverer.forGraph(blocks, entry);
        var root = rec.build(entry);
        var ast = new StructureToAst(blocks, mn, new HashSet<>(), null, cn.name, null, entry).convert(root);
        return JavaPrinter.print(ast);
    }

    @Test
    void nestedAssignAndIf() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "T1", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m", "(IZ)I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        Label end = new Label();
        Label skip = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, skip);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitLabel(skip);
        mv.visitLabel(end);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String java = decompile(cw.toByteArray(), "m");
        assertFalse(java.contains("undecompilable"), java);
        assertTrue(java.contains("= 2"), java);
    }

    @Test
    void putfieldOnLocalReceiver() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "T2", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "f", "I", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "set", "(LT2;I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, "T2", "f", "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String java = decompile(cw.toByteArray(), "set");
        assertFalse(java.contains("undecompilable"), java);
        assertTrue(java.contains(".f = ") || java.contains("f = "), java);
    }
}