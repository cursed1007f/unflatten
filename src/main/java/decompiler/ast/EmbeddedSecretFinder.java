package decompiler.ast;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Printer;

import java.nio.file.Path;
import java.util.Base64;

/**
 * Извлекает мастер-секрет, вшитый в байткод класса-дешифратора.
 *
 * <p>Схема non-native билда: класс-дешифратор объявляет метод
 * {@code a(String,int)String} и содержит {@code master()}, возвращающий
 * захардкоженную base64-строку секрета (32 байта после декодирования).
 * {@code secret()}/base64-ветка в {@code embeddedSecret()} декодирует её.
 *
 * <p>Здесь мы находим такой класс и читаем литерал из {@code master()}
 * напрямую по байткоду — без выполнения.
 */
public final class EmbeddedSecretFinder {

    private EmbeddedSecretFinder() {
    }

    /**
     * Возвращает мастер-секрет, зашитый в байткод jar, или {@code null} если
     * дешифратор не найден.
     */
    public static byte[] findSecret(Path jar) throws Exception {
        final byte[][] result = {null};
        decompiler.parser.JarParser.parse(jar, (p, entry, cn) -> {
            if (result[0] != null) return;
            // ищем класс-дешифратор: объявляет a(String,I)String
            boolean isDec = false;
            for (MethodNode mn : cn.methods == null ? java.util.List.<MethodNode>of() : cn.methods) {
                if (mn.name.equals("a") && mn.desc.equals("(Ljava/lang/String;I)Ljava/lang/String;")) {
                    isDec = true;
                    break;
                }
            }
            if (!isDec) return;
            // читаем master() -> литерал
            for (MethodNode mn : cn.methods == null ? java.util.List.<MethodNode>of() : cn.methods) {
                if (mn.name.equals("master") && mn.instructions != null) {
                    String sec = extractBase64Literal(mn);
                    if (sec != null) {
                        try {
                            result[0] = Base64.getDecoder().decode(sec.trim());
                        } catch (Exception ignored) {
                            result[0] = null;
                        }
                        if (result[0] != null) return;
                    }
                }
            }
        });
        return result[0];
    }

    /** Ищет первый большой base64-литерал в методе (секрет). */
    private static String extractBase64Literal(MethodNode mn) {
        for (AbstractInsnNode n : mn.instructions) {
            if (n instanceof LdcInsnNode l && l.cst instanceof String s) {
                if (s.length() >= 40 && s.indexOf('=') > 0) {
                    return s;
                }
            }
        }
        // fallback: первый строковый литерал из всех
        for (AbstractInsnNode n : mn.instructions) {
            if (n instanceof LdcInsnNode l && l.cst instanceof String s && s.length() >= 32) {
                return s;
            }
        }
        return null;
    }
}