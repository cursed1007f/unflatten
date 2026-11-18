package decompiler.ast;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Автопоиск мастер-секрета {@code StringCrypto} и SVSB-блоба строк.
 *
 * <p>Секрет в этих билдах лежит не в нативной части, а в байткоде: метод
 * {@code master()} возвращает base64-строку на 32 байта, которую
 * {@code embeddedSecret()} декодирует. Нативная DLL содержит другие константы
 * (ветка anti-debug), поэтому кандидаты проверяются расшифровкой реального
 * блоба: AES-GCM с неверным ключом не проходит проверку тега.
 *
 * <p>Формат ключа: {@code SHA-256(secret || "strings" || 0x00 || className)}.
 */
public final class NativeKeyExtractor {

    private NativeKeyExtractor() {
    }

    /** Найденный секрет вместе с загруженным дешифратором. */
    public record Result(byte[] secret, StringDecryptor decryptor, String source) {
    }

    /**
     * Ищет секрет и SVSB-блоб в jar и возвращает готовый дешифратор.
     *
     * @return результат или {@code null}, если секрет/блоб не найдены
     *         либо ни один кандидат не прошёл проверку тега GCM
     */
    public static Result discover(Path jar) {
        byte[] blob = findBlob(jar);
        if (blob == null) return null;
        List<byte[]> candidates = candidateSecrets(jar);
        for (byte[] secret : candidates) {
            StringDecryptor d = new StringDecryptor(secret);
            try {
                d.load(blob);
            } catch (Throwable t) {
                continue;
            }
            if (validates(d)) {
                return new Result(secret, d, "base64(" + secret.length + "B)");
            }
        }
        return null;
    }

    /** Первый ресурс с магией {@code SVSB}. */
    private static byte[] findBlob(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (e.getSize() <= 0 || e.getSize() > 64L * 1024 * 1024) continue;
                byte[] head;
                try (var in = zf.getInputStream(e)) {
                    head = in.readNBytes(4);
                }
                if (head.length == 4 && head[0] == 'S' && head[1] == 'V'
                        && head[2] == 'S' && head[3] == 'B') {
                    return zf.getInputStream(e).readAllBytes();
                }
            }
        } catch (Throwable ignored) {
            // не jar или битый архив — секрет не нужен
        }
        return null;
    }

    /** Кандидаты: base64-константы на 44 символа, декодирующиеся в 32 байта. */
    private static List<byte[]> candidateSecrets(Path jar) {
        Set<String> seen = new LinkedHashSet<>();
        List<byte[]> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getSize() <= 0) continue;
                try (var in = zf.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in.readAllBytes());
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    collect(cn, seen, out);
                } catch (Throwable ignored) {
                    // битый класс — пропускаем
                }
            }
        } catch (Throwable ignored) {
            // нечего сканировать
        }
        return out;
    }

    private static void collect(ClassNode cn, Set<String> seen, List<byte[]> out) {
        if (cn.methods == null) return;
        for (var m : cn.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String s)) continue;
                if (s.length() != 44 || !seen.add(s)) continue;
                try {
                    byte[] raw = Base64.getDecoder().decode(s);
                    if (raw.length == 32) out.add(raw);
                } catch (IllegalArgumentException ignored) {
                    // не base64
                }
            }
        }
    }

    /** Проверка кандидата: хотя бы одна строка расшифровалась в печатный текст. */
    private static boolean validates(StringDecryptor d) {
        for (String cls : d.classes()) {
            for (int i = 0; i < 3; i++) {
                String s = d.decrypt(cls, i);
                if (s != null && isText(s)) return true;
            }
        }
        return false;
    }

    private static boolean isText(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') return false;
        }
        return true;
    }
}
