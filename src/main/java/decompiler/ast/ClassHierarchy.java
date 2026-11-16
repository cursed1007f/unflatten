package decompiler.ast;

import decompiler.parser.JarParser;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Иерархия классов входного jar: карта {@code имя -> суперкласс} и
 * {@code имя -> интерфейсы}. Используется как resolver для
 * {@link org.objectweb.asm.tree.analysis.SimpleVerifier}, чтобы анализ типов
 * знал assignability между классами архива (без загрузки внешнего classpath).
 */
public final class ClassHierarchy {

    private final Map<String, String> superName = new HashMap<>();
    private final Map<String, List<String>> interfaces = new HashMap<>();
    private final Map<String, byte[]> classBytes = new HashMap<>();

    /** Собирает иерархию из всех классов jar. */
    public static ClassHierarchy fromJar(Path jar) throws Exception {
        ClassHierarchy h = new ClassHierarchy();
        JarParser.parse(jar, (p, entry, cn) -> h.add(cn));
        return h;
    }

    public void add(ClassNode cn) {
        superName.put(cn.name, cn.superName);
        interfaces.put(cn.name, cn.interfaces != null
                ? new ArrayList<>(cn.interfaces) : List.of());
        classBytes.put(cn.name, new byte[0]); // placeholder; байты добавим отдельно если нужно
    }

    /** Байты класса (если известны), иначе null. */
    public byte[] bytesOf(String internalName) {
        return classBytes.getOrDefault(internalName, null);
    }

    public String superClass(String internalName) {
        return superName.getOrDefault(internalName, internalName);
    }

    public List<String> interfaceList(String internalName) {
        return interfaces.getOrDefault(internalName, List.of());
    }

    /** Реально ли класс в jar (иначе неизвестен -> Object). */
    public boolean known(String internalName) {
        return superName.containsKey(internalName);
    }

    /** Есть ли путь по superName от {@code sub} к {@code sup}. */
    public boolean isAssignable(String supInternal, String subInternal) {
        if (supInternal.equals(subInternal)) return true;
        if (supInternal.equals("java/lang/Object") || supInternal.equals("Object")) return true;
        String cur = subInternal;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            if (cur.equals(supInternal)) return true;
            // проверим и интерфейсы на пути
            for (String iface : interfaceList(cur)) {
                if (iface.equals(supInternal) || isAssignable(supInternal, iface)) return true;
            }
            String next = superName.get(cur);
            if (next == null || next.equals(cur)) break;
            cur = next;
        }
        return false;
    }

    /** Интерфейсы суперкласса: транзитивно, или все, у кого нет суперкласса. */
    public List<String> allInterfacesUpTo(String internal) {
        List<String> out = new ArrayList<>();
        String cur = internal;
        int guard = 0;
        while (cur != null && guard++ < 64) {
            for (String i : interfaceList(cur)) out.add(i);
            String next = superName.get(cur);
            if (next == null || next.equals(cur)) break;
            cur = next;
        }
        return out;
    }
}