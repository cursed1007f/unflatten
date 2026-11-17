package decompiler.ast;

import decompiler.cfganalyzer.BasicBlock;
import decompiler.cfganalyzer.CfgBuilder;
import decompiler.passes.CffUnflattenerPass;
import decompiler.structure.StructureRecoverer;
import decompiler.structure.StructureNode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Печатает полный класс как Java-код: объявление (модификаторы, extends,
 * implements), поля, конструкторы, методы с сигнатурами и телами.
 *
 * Тела методов генерятся существующим пайплайном: CFG -> де-флаттенинг (если
 * CFF) -> структуры -> AST -> Java.
 */
public final class JavaClassPrinter {

    /** Снятые хвостовые return; в static-инициализаторах (omit, не bail). */
    public static final java.util.concurrent.atomic.AtomicInteger CLINIT_RETURN_STRIPPED =
            new java.util.concurrent.atomic.AtomicInteger();

    private JavaClassPrinter() {
    }

    /** Печатает класс целиком (без расшифровки строк). */
    public static String printClass(ClassNode cn) {
        return printClass(cn, null, null);
    }

    public static String printClass(ClassNode cn, StringDecryptor decryptor) {
        return printClass(cn, decryptor, null);
    }

    /** Печатает класс целиком; дешифратор и иерархия могут быть null. */
    public static String printClass(ClassNode cn, StringDecryptor decryptor, ClassHierarchy hierarchy) {
        StringBuilder sb = new StringBuilder();
        // заголовок
        sb.append(classDeclaration(cn)).append(" {\n\n");

        // поля
        if (cn.fields != null) {
            for (FieldNode f : cn.fields) {
                if (!isEnumConstant(f)) {
                    sb.append("    ").append(fieldModifiers(f)).append(' ')
                            .append(Ast.friendlyType(f.desc)).append(' ')
                            .append(JavaPrinter.id(f.name)).append(";\n");
                }
            }
            if (!cn.fields.isEmpty()) sb.append("\n");
        }

        // методы
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                sb.append(methodToJava(cn, mn, decryptor, hierarchy));
                sb.append("\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Модификаторы + type + name для одного метода + тело. */
    static String methodToJava(ClassNode cn, MethodNode mn) {
        return methodToJava(cn, mn, null);
    }

    static String methodToJava(ClassNode cn, MethodNode mn, StringDecryptor decryptor) {
        return methodToJava(cn, mn, decryptor, null);
    }

    static String methodToJava(ClassNode cn, MethodNode mn, StringDecryptor decryptor,
                               ClassHierarchy hierarchy) {
        StringBuilder sb = new StringBuilder("    ");
        // модификаторы
        if ((mn.access & Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        else if ((mn.access & Opcodes.ACC_PRIVATE) != 0) sb.append("private ");
        else if ((mn.access & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        if ((mn.access & Opcodes.ACC_STATIC) != 0) sb.append("static ");
        if ((mn.access & Opcodes.ACC_FINAL) != 0) sb.append("final ");
        if ((mn.access & Opcodes.ACC_ABSTRACT) != 0) sb.append("abstract ");

        Type[] args = Type.getArgumentTypes(mn.desc);
        Type ret = Type.getReturnType(mn.desc);

        // имя: конструктор — имя класса, иначе methodName
        String name = mn.name.equals("<init>") ? simpleName(cn.name)
                : mn.name.equals("<clinit>") ? null : mn.name;
        // clinit (static init) печатаем как блок
        if (mn.name.equals("<clinit>")) {
            return staticInitBlock(mn, decryptor, cn.name, hierarchy);
        }

        // у конструктора нет возвращаемого типа: печатаем просто Имя(params)
        if (mn.name.equals("<init>")) {
            sb.append(JavaPrinter.id(name));
        } else {
            sb.append(name == null ? "" : ret(desc(ret)) + " " + JavaPrinter.id(name));
        }
        // параметры
        List<String> params = params(args, mn);
        sb.append('(').append(String.join(", ", params)).append(")");

        // abstract — без тела
        if ((mn.access & Opcodes.ACC_ABSTRACT) != 0 || (mn.access & Opcodes.ACC_NATIVE) != 0) {
            sb.append(";\n");
            return sb.toString();
        }
        sb.append(" {\n");
        sb.append(bodyOf(mn, decryptor, cn.name, hierarchy));
        sb.append("    }\n");
        return sb.toString();
    }

    /** Тело метода (пусто, если без байткода). */
    private static String bodyOf(MethodNode mn, StringDecryptor decryptor, String classOwner,
                                 ClassHierarchy hierarchy) {
        if (mn.instructions == null || mn.instructions.size() == 0) return "        \n";
        var blocks = CfgBuilder.build(mn);
        var deflat = CffUnflattenerPass.run(blocks);
        Set<Integer> cffDispatch = new java.util.HashSet<>();
        int entry = blocks.isEmpty() ? -1 : blocks.get(0).index();
        if (deflat.unflattened) {
            blocks = deflat.blocks;
            cffDispatch.add(deflat.dispatch.index());
            entry = deflat.entryIndex;
        }
        var rec = StructureRecoverer.forGraph(blocks, entry);
        var root = rec.build(entry);
        var ast = new StructureToAst(blocks, mn, cffDispatch, decryptor, classOwner, hierarchy, entry).convert(root);
        // печатаем с отступами (4 пробела базово)
        String body = JavaPrinter.print(ast);
        return indent(body, 8);
    }

    private static String staticInitBlock(MethodNode mn, StringDecryptor decryptor, String classOwner,
                                          ClassHierarchy hierarchy) {
        String body = stripTrailingReturn(bodyOf(mn, decryptor, classOwner, hierarchy));
        // Хвостовой return; эквивалентен выходу из инициализатора и снят.
        // return в середине clinit (обфускация) снимать нельзя — это реальный
        // выход, после которого код не должен исполняться: честный отказ.
        if (body.contains("return;")) {
            throw new UndecompilableException(
                    UndecompilableException.Kind.OTHER, "return inside <clinit>");
        }
        return "    static {\n" + body + "    }\n";
    }

    /** Убирает хвостовой return; из static-блока: в Java он невалиден. */
    private static String stripTrailingReturn(String body) {
        String[] lines = body.split("\\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isBlank()) continue;
            if ("        return;".equals(lines[i].replace("\r", ""))) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < lines.length; j++) {
                    if (j == i) continue;
                    if (sb.length() > 0 || j > 0) sb.append("\n");
                    sb.append(lines[j]);
                }
                CLINIT_RETURN_STRIPPED.incrementAndGet();
                return sb.toString();
            }
            break;
        }
        return body;
    }

    private static String indent(String text, int spaces) {
        if (text.isEmpty()) return "";
        String pad = " ".repeat(spaces);
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) { sb.append('\n'); continue; }
            sb.append(pad).append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static List<String> params(Type[] args, MethodNode mn) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            out.add(Ast.friendlyType(args[i].getDescriptor()) + " p" + i);
        }
        return out;
    }

    private static String desc(Type t) {
        return t.getDescriptor();
    }

    private static String ret(String desc) {
        return desc.equals("V") ? "void" : Ast.friendlyType(desc);
    }

    private static String simpleName(String internal) {
        // если класс переименован ClassRenamer'ом — используем осмысленное имя
        if (ClassRenamer.isRenamed(internal)) return ClassRenamer.simpleNameOf(internal);
        int i = internal.lastIndexOf('/');
        return i >= 0 ? internal.substring(i + 1) : internal;
    }

    private static boolean isEnumConstant(FieldNode f) {
        return (f.access & (Opcodes.ACC_ENUM | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                == (Opcodes.ACC_ENUM | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
    }

    private static String fieldModifiers(FieldNode f) {
        StringBuilder sb = new StringBuilder();
        if ((f.access & Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        else if ((f.access & Opcodes.ACC_PRIVATE) != 0) sb.append("private ");
        else if ((f.access & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        if ((f.access & Opcodes.ACC_STATIC) != 0) sb.append("static ");
        if ((f.access & Opcodes.ACC_FINAL) != 0) sb.append("final ");
        return sb.toString().trim();
    }

    /** Объявление класса: модификаторы + type + name (+ extends/implements). */
    static String classDeclaration(ClassNode cn) {
        StringBuilder sb = new StringBuilder();
        if ((cn.access & Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        if ((cn.access & Opcodes.ACC_ABSTRACT) != 0) sb.append("abstract ");
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0) {
            sb.append("interface ");
        } else if ((cn.access & Opcodes.ACC_ENUM) != 0) {
            sb.append("enum ");
        } else if ((cn.access & Opcodes.ACC_ANNOTATION) != 0) {
            sb.append("@interface ");
        } else {
            sb.append("class ");
        }
        sb.append(simpleName(cn.name));
        // Неявные суперклассы: enum всегда extends java.lang.Enum, record — java.lang.Record.
        // В Java явный extends у enum/record недопустим — не печатаем.
        boolean implicitSuper = "java/lang/Enum".equals(cn.superName)
                || "java/lang/Record".equals(cn.superName);
        if (cn.superName != null && !cn.superName.equals("java/lang/Object") && !implicitSuper
                && (cn.access & Opcodes.ACC_INTERFACE) == 0) {
            sb.append(" extends ").append(Ast.friendlyType("L" + cn.superName + ";"));
        }
        if (cn.interfaces != null && !cn.interfaces.isEmpty()) {
            List<String> ifaces = new ArrayList<>();
            for (String i : cn.interfaces) ifaces.add(Ast.friendlyType("L" + i + ";"));
            // interface extends интерфейсы; класс implements интерфейсы (независимо от числа).
            boolean isIface = (cn.access & Opcodes.ACC_INTERFACE) != 0;
            sb.append(isIface ? " extends " : " implements ")
                    .append(String.join(", ", ifaces));
        }
        return sb.toString();
    }
}