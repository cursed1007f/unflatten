package decompiler.ast;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Автопоиск мастер-секрета {@code StringCrypto} и SVSB-блоба строк.
 *
 * <p>Источники секрета (проверяются по очереди, победитель — тот, которым
 * реально расшифровывается блоб, т.е. проходит тег AES-GCM):
 * <ol>
 *   <li>base64-константа на 32 байта в байткоде ({@code master()});</li>
 *   <li>файл ключа рядом с jar ({@code *.key}, не короче 32 байт) —
 *       так работает сборка, читающая {@code keyPath()};</li>
 *   <li>встроенные константы нативной {@code slashnative.dll} (ветка
 *       anti-debug: константа и деобфусцированная таблица).</li>
 * </ol>
 *
 * <p>Формат ключа: {@code SHA-256(secret || "strings" || 0x00 || className)}.
 */
public final class NativeKeyExtractor {

    // Реальные адреса констант в .rdata (найдены по rip-relative ссылкам в
    // sv_native_init: lea r10 -> таблица, movdqu xmm0 -> запасная константа).
    private static final int RVA_FALLBACK = 0x3660b0;
    private static final int RVA_DERIVED_TABLE = 0x366080;
    private static final int RVA_SEPARATOR = 0x366037;
    // Старые сборки, где те же данные лежали по другим адресам.
    private static final int RVA_FALLBACK_LEGACY = 0x363d58;
    private static final int RVA_DERIVED_TABLE_LEGACY = 0x363de4;

    private NativeKeyExtractor() {
    }

    /** Найденный секрет вместе с загруженным дешифратором. */
    public record Result(byte[] secret, StringDecryptor decryptor, String source) {
    }

    private record Candidate(byte[] secret, String source) {
    }

    /** Ищет секрет и SVSB-блоб в jar и возвращает готовый дешифратор. */
    public static Result discover(Path jar) {
        byte[] blob = findBlob(jar);
        if (blob == null) return null;
        Set<String> seen = new LinkedHashSet<>();
        for (Candidate c : candidates(jar)) {
            if (c.secret() == null || c.secret().length < 16) continue;
            if (!seen.add(HexFormat.of().formatHex(c.secret()))) continue;
            StringDecryptor d = new StringDecryptor(c.secret());
            try {
                d.load(blob);
            } catch (Throwable t) {
                continue;
            }
            if (validates(d)) return new Result(c.secret(), d, c.source());
        }
        return null;
    }

    private static List<Candidate> candidates(Path jar) {
        List<Candidate> out = new ArrayList<>();
        out.addAll(bytecodeSecrets(jar));
        out.addAll(keyFileSecrets(jar));
        out.addAll(dllSecrets(jar));
        return out;
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
                    try (var in = zf.getInputStream(e)) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (Throwable ignored) {
            // не jar или битый архив
        }
        return null;
    }

    /** Кандидаты из байткода: base64-константы 44 символа → 32 байта. */
    private static List<Candidate> bytecodeSecrets(Path jar) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
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

    private static void collect(ClassNode cn, Set<String> seen, List<Candidate> out) {
        if (cn.methods == null) return;
        for (var m : cn.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String s)) continue;
                if (s.length() != 44 || !seen.add(s)) continue;
                try {
                    byte[] raw = Base64.getDecoder().decode(s);
                    if (raw.length == 32) out.add(new Candidate(raw, "bytecode:base64"));
                } catch (IllegalArgumentException ignored) {
                    // не base64
                }
            }
        }
    }

    /** Кандидаты из файлов ключа рядом с jar. */
    private static List<Candidate> keyFileSecrets(Path jar) {
        List<Candidate> out = new ArrayList<>();
        Path dir = jar.toAbsolutePath().getParent();
        if (dir == null) return out;
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".key"))
                    .forEach(p -> {
                        try {
                            byte[] raw = Files.readAllBytes(p);
                            if (raw.length >= 32) out.add(new Candidate(raw, "keyfile:" + p.getFileName()));
                        } catch (IOException ignored) {
                            // нечитаемый файл
                        }
                    });
        } catch (IOException ignored) {
            // каталог недоступен
        }
        return out;
    }

    /** Кандидаты из нативной DLL: константа fallback и деобфусцированная таблица. */
    private static List<Candidate> dllSecrets(Path jar) {
        List<Candidate> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().toLowerCase().endsWith(".dll")) continue;
                if (e.getSize() <= 0 || e.getSize() > 64L * 1024 * 1024) continue;
                byte[] dll;
                try (var in = zf.getInputStream(e)) {
                    dll = in.readAllBytes();
                }
                PeView pe = PeView.parse(dll);
                if (pe == null) continue;
                if (!pe.looksLikeSlashNative()) continue;
                byte[] sep = pe.read(RVA_SEPARATOR, 7);
                if (sep == null || !"strings".equals(new String(sep, java.nio.charset.StandardCharsets.UTF_8))) {
                    continue;
                }
                byte[] fallback = pe.read(RVA_FALLBACK, 32);
                if (fallback != null) out.add(new Candidate(fallback, "dll:fallback"));
                byte[] table = pe.read(RVA_DERIVED_TABLE, 32);
                if (table != null) out.add(new Candidate(deriveSecret(table), "dll:derived"));
                byte[] tableOld = pe.read(RVA_DERIVED_TABLE_LEGACY, 32);
                if (tableOld != null) {
                    out.add(new Candidate(deriveSecret(tableOld), "dll:derived-legacy"));
                }
                byte[] fallbackOld = pe.read(RVA_FALLBACK_LEGACY, 32);
                if (fallbackOld != null) {
                    out.add(new Candidate(fallbackOld, "dll:fallback-legacy"));
                }
            }
        } catch (Throwable ignored) {
            // нет DLL/битый PE
        }
        return out;
    }

    /** Деобфускация таблицы из {@code sv_native_init} (s169). */
    private static byte[] deriveSecret(byte[] table) {
        byte[] out = new byte[32];
        int r8 = 0xfffffff5;
        int edx = 0x11;
        int ecx = 5;
        for (int i = 0; i < out.length && i < table.length; i++) {
            int al = (table[i] ^ edx) & 0xff;
            int sh = ecx & 7;
            al = ((al >>> sh) | (al << (8 - sh))) & 0xff;
            al = (al + r8) & 0xff;
            out[i] = (byte) al;
            edx += 0x1f;
            ecx += 3;
            r8 -= 7;
        }
        return out;
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

    /** Минимальный PE-читатель: RVA → байты, без внешних зависимостей. */
    private static final class PeView {
        private final byte[] data;
        private final int[][] sections; // {va, vsize, raw, rawSize}

        private PeView(byte[] data, int[][] sections) {
            this.data = data;
            this.sections = sections;
        }

        static PeView parse(byte[] d) {
            try {
                if (d.length < 0x40 || d[0] != 'M' || d[1] != 'Z') return null;
                int pe = u32(d, 0x3c);
                if (pe + 24 > d.length || d[pe] != 'P' || d[pe + 1] != 'E') return null;
                int nsec = u16(d, pe + 6);
                int optsz = u16(d, pe + 20);
                int opt = pe + 24;
                int[][] secs = new int[nsec][4];
                int s = opt + optsz;
                for (int i = 0; i < nsec; i++) {
                    if (s + 40 > d.length) return null;
                    secs[i][0] = u32(d, s + 12); // VirtualAddress
                    secs[i][1] = u32(d, s + 8);  // VirtualSize
                    secs[i][2] = u32(d, s + 20); // PointerToRawData
                    secs[i][3] = u32(d, s + 16); // SizeOfRawData
                    s += 40;
                }
                return new PeView(d, secs);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Есть ли ожидаемая секция .text/.rdata (slashnative). */
        boolean looksLikeSlashNative() {
            return data.length > 6_000_000 && sections.length > 4;
        }

        byte[] read(int rva, int len) {
            for (int[] s : sections) {
                int va = s[0];
                int size = Math.max(s[1], s[3]);
                if (rva >= va && rva + len <= va + size) {
                    int off = s[2] + (rva - va);
                    if (off < 0 || off + len > data.length) return null;
                    return Arrays.copyOfRange(data, off, off + len);
                }
            }
            return null;
        }

        private static int u16(byte[] d, int off) {
            return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8);
        }

        private static int u32(byte[] d, int off) {
            return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8)
                    | ((d[off + 2] & 0xff) << 16) | ((d[off + 3] & 0xff) << 24);
        }
    }
}
