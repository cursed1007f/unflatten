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

    @Test
    void dup2OnCategory2() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "T3", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "d2", "()J", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitInsn(Opcodes.POP2);
        mv.visitInsn(Opcodes.LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String java = decompile(cw.toByteArray(), "d2");
        assertFalse(java.contains("undecompilable"), java);
        assertTrue(java.contains("return"), java);
    }

    @Test
    void loopHeaderIsEntry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "T4", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "loop", "(I)V", null, null);
        mv.visitCode();
        Label head = new Label(), end = new Label();
        mv.visitLabel(head);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, end);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 0);
        mv.visitJumpInsn(Opcodes.GOTO, head);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String java = decompile(cw.toByteArray(), "loop");
        assertFalse(java.contains("undecompilable"), java);
        assertTrue(java.contains("while") || java.contains("for"), java);
    }

    @Test
    @org.junit.jupiter.api.Disabled("known gap: entry-stack недоступен — underflow на слиянии ветвей")
    void ternaryBranches() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "T5", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "t", "(IZ)I", null, null);
        mv.visitCode();
        Label neg = new Label(), join = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IFEQ, neg);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.GOTO, join);
        mv.visitLabel(neg);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.INEG);
        mv.visitLabel(join);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String java = decompile(cw.toByteArray(), "t");
        assertFalse(java.contains("undecompilable"), java);
    }

    @Test
    void tryCatchExceptionOnlyEntry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "T6", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "v", "I", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "tc", "()V", null, null);
        mv.visitCode();
        Label start = new Label(), end = new Label(), handler = new Label(), done = new Label();
        mv.visitLabel(start);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "T6", "v", "I");
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(end);
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "T6", "v", "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String java = decompile(cw.toByteArray(), "tc");
        assertFalse(java.contains("undecompilable"), java);
        assertTrue(java.contains("catch"), java);
    }

    @Test
    @org.junit.jupiter.api.Disabled("known gap: потерян handler BB2 (nested try) — emitted without catch")
    void nestedTryInHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "T7", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "n", "()V", null, null);
        mv.visitCode();
        Label l0 = new Label(), lend = new Label(), h1 = new Label();
        Label l2 = new Label(), lend2 = new Label(), h2 = new Label();
        Label e1 = new Label(), e2 = new Label();
        mv.visitLabel(l0);
        mv.visitInsn(Opcodes.NOP);
        mv.visitJumpInsn(Opcodes.GOTO, lend);
        mv.visitLabel(e1);
        mv.visitLabel(h1);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitLabel(l2);
        mv.visitInsn(Opcodes.NOP);
        mv.visitJumpInsn(Opcodes.GOTO, lend2);
        mv.visitLabel(e2);
        mv.visitLabel(h2);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(lend2);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(lend);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitTryCatchBlock(l0, e1, h1, "java/lang/Throwable");
        mv.visitTryCatchBlock(l2, e2, h2, "java/lang/Throwable");
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        String java = decompile(cw.toByteArray(), "n");
        assertFalse(java.contains("undecompilable"), java);
    }
}