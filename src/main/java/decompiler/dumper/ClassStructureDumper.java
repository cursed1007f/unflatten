package decompiler.dumper;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Этап 2 пайплайна декомпилятора.
 *
 * Принимает {@link ClassNode} (построенный JarParser'ом на Этапе 1) и выдаёт
 * базовую информацию о структуре класса: имя, список полей, список методов
 * с дескрипторами и количеством инструкций в каждом.
 *
 * Настройки вывода ({@link Options}) позволяют фильтровать: только имена
 * классов, поля, методы, счётчик инструкций. Один и тот же класс используется
 * и CLI, и GUI — результат собирается в {@code StringBuilder}.
 *
 * Этап 3 (CFG) заменит это на построение графа по базовым блокам; рендер
 * структуры останется полезен как «скелет» для навигации по деобфусцированным
 * классам (сравни с hot-фильтром CFF в обфускаторе по именам/признакам).
 */
public final class ClassStructureDumper {

    /** Настройки вывода структуры. */
    public static final class Options {
        public boolean fields = true;
        public boolean methods = true;
        public boolean methodCounts = true;
        public boolean accessFlags = false;
        public boolean showInterfaces = true;
    }

    private final Options options;
    private final List<String> lines = new ArrayList<>();
    private int classCount = 0;

    public ClassStructureDumper() {
        this(new Options());
    }

    public ClassStructureDumper(Options options) {
        this.options = options;
    }

    /** Обрабатывает один класс и сохраняет его строку. */
    public void dump(Path jarPath, String entryName, ClassNode cls) {
        lines.add(render(entryName, cls));
        classCount++;
    }

    /**
     * Печатает весь накопленный вывод о структуре классов.
     *
     * @return количество обработанных классов
     */
    public int printSummary() {
        for (String line : lines) {
            System.out.print(line);
        }
        System.out.println();
        System.out.println("Classes dumped: " + classCount);
        return classCount;
    }

    /** Возвращает весь накопленный вывод как одну строку. */
    public String result() {
        return String.join("", lines);
    }

    /** Возвращает строку дампа одного класса. */
    public String render(String entryName, ClassNode cls) {
        StringBuilder sb = new StringBuilder();

        sb.append("== ").append(entryName).append('\n');
        sb.append("class ").append(cls.name).append(classtypeSuffix(cls)).append('\n');

        if (cls.superName != null) {
            sb.append("  extends ").append(cls.superName).append('\n');
        }
        if (cls.interfaces != null && !cls.interfaces.isEmpty() && options.showInterfaces) {
            sb.append("  implements: ").append(String.join(", ", cls.interfaces)).append('\n');
        }
        int major = cls.version & 0xFFFF;
        sb.append("  classfile version: ").append(major);
        if (options.accessFlags) {
            sb.append(" (access=0x").append(Integer.toHexString(cls.access)).append(')');
        }
        sb.append('\n');

        if (options.fields) {
            sb.append("  fields (").append(cls.fields == null ? 0 : cls.fields.size()).append("):\n");
            if (cls.fields != null) {
                for (FieldNode f : cls.fields) {
                    sb.append("    - ").append(f.name).append(" : ").append(f.desc);
                    if (options.accessFlags) {
                        sb.append(" access=0x").append(Integer.toHexString(f.access));
                    }
                    sb.append('\n');
                }
            }
        }

        if (options.methods) {
            sb.append("  methods (").append(cls.methods == null ? 0 : cls.methods.size()).append("):\n");
            if (cls.methods != null) {
                for (MethodNode m : cls.methods) {
                    sb.append("    - ").append(m.name).append(m.desc);
                    if (options.accessFlags) {
                        sb.append(" access=0x").append(Integer.toHexString(m.access));
                    }
                    if (options.methodCounts) {
                        sb.append(" instructions=").append(countInstructions(m));
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Сколько байткод-инструкций в методе (без учёта label/line/frame). */
    private static int countInstructions(MethodNode m) {
        if (m.instructions == null) {
            return 0;
        }
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            switch (insn.getType()) {
                case AbstractInsnNode.LABEL, AbstractInsnNode.LINE, AbstractInsnNode.FRAME -> {
                    // не инструкции
                }
                default -> n++;
            }
        }
        return n;
    }

    private static String classtypeSuffix(ClassNode cls) {
        if ((cls.access & Opcodes.ACC_INTERFACE) != 0) {
            return " (interface)";
        }
        if ((cls.access & Opcodes.ACC_ENUM) != 0) {
            return " (enum)";
        }
        if ((cls.access & Opcodes.ACC_ANNOTATION) != 0) {
            return " (annotation)";
        }
        if ((cls.access & Opcodes.ACC_RECORD) != 0) {
            return " (record)";
        }
        return "";
    }
}