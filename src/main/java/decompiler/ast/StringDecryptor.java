package decompiler.ast;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Дешифратор строк обфускатора (AES-256-GCM), совместимый с {@code StringCrypto}.
 *
 * Формат (из StringCrypto.java / OBFUSCATOR-HANDOFF §6.1):
 * <ul>
 *   <li>ресурс: {@code magic 0x53565342} (4B), версия (1B), число классов (int),
 *       по классу: длина имени (u16) + UTF-8 имя, число блобов (int),
 *       по блобу: длина (int) + {@code [nonce 12B][cipher+tag GCM]}.</li>
 *   <li>ключ класса: {@code SHA-256(secret || "strings" || 0x00 || <classname>)}.</li>
 *   <li>call-site: {@code LDC <class>; LDC <idx>; INVOKESTATIC a(String,int)String}.</li>
 * </ul>
 *
 * Без мастер-секрета строки расшифровать нельзя; тогда вызывающий решает,
 * расшифровывать (реализуя свой способ получения ключа) или просто помечать.
 */
public final class StringDecryptor {

    private static final int MAGIC = 0x53565342;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] secret;
    private final Map<String, byte[][]> data = new HashMap<>();
    private final Map<String, SecretKeySpec> keyCache = new HashMap<>();

    /** Создаёт дешифратор с готовым секретом (переданным ключом). */
    public StringDecryptor(byte[] secret) {
        this.secret = secret;
    }

    /** Создаёт дешифратор из файла ключа (прочитает байты). */
    public static StringDecryptor fromKeyFile(java.io.File keyFile) throws Exception {
        byte[] raw = java.nio.file.Files.readAllBytes(keyFile.toPath());
        return new StringDecryptor(raw);
    }

    /** Парсит .dat-ресурс (байты) и регистрирует блобы по классам. */
    public void load(byte[] dat) throws Exception {
        data.clear(); keyCache.clear();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(dat));
        if (dis.readInt() != MAGIC) throw new IllegalArgumentException("bad magic");
        dis.readByte(); // version
        int classes = dis.readInt();
        for (int c = 0; c < classes; c++) {
            byte[] nameBytes = new byte[dis.readUnsignedShort()];
            dis.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            int count = dis.readInt();
            byte[][] blobs = new byte[count][];
            for (int i = 0; i < count; i++) {
                int len = dis.readInt();
                byte[] blob = new byte[len];
                dis.readFully(blob);
                blobs[i] = blob;
            }
            data.put(name, blobs);
        }
        // ресурс считан целиком; если остались байты — игнорируем
    }

    /** Есть ли блобы для класса (и вообще загружены ли). */
    public boolean hasClass(String className) {
        return data.containsKey(className);
    }

    /** Имена классов, для которых загружены блобы (для автопроверки ключа). */
    public java.util.Set<String> classes() {
        return data.keySet();
    }

    /** Число блобов (строк) класса; 0 — класса нет в блобе. */
    public int count(String className) {
        byte[][] blobs = data.get(className);
        return blobs == null ? 0 : blobs.length;
    }

    /**
     * Расшифровывает строку класса по индексу.
     *
     * @return расшифрованная строка, или {@code null}, если нет данных/ключа.
     */
    public String decrypt(String className, int index) {
        byte[][] blobs = data.get(className);
        if (blobs == null || index < 0 || index >= blobs.length) return null;
        byte[] blob = blobs[index];
        if (blob.length < NONCE_BYTES + 16) return null;
        try {
            byte[] nonce = java.util.Arrays.copyOfRange(blob, 0, NONCE_BYTES);
            byte[] ct = java.util.Arrays.copyOfRange(blob, NONCE_BYTES, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyFor(className), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private SecretKeySpec keyFor(String className) {
        SecretKeySpec k = keyCache.get(className);
        if (k != null) return k;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(secret);
            md.update("strings".getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(className.getBytes(StandardCharsets.UTF_8));
            k = new SecretKeySpec(md.digest(), "AES");
            keyCache.put(className, k);
        } catch (Exception e) {
            throw new IllegalStateException("no key", e);
        }
        return k;
    }
}