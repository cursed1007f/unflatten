package decompiler.fixtures;

import decompiler.ast.StringDecryptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Вектор из реального SVSB-блоба (slashvisuals-1.0-SNAPSHOT-obf.jar):
 * ключ {@code SHA-256(secret || "strings" || 0x00 || class)}, AES-256-GCM,
 * полезная нагрузка {@code nonce(12) || ciphertext || tag(16)}.
 */
class StringDecryptorTest {

    private static final String MASTER = "x5Nsa8rGfniq368y/yNAKpYd0KMz7b9GfaiHpbWdqRM=";
    private static final String CLASS_NAME = "com/google/errorprone/ldtSKTczu0";

    /** Первая строка класса (32 байта), расшифровывается в "LEFT". */
    private static final String PAYLOAD_LEFT =
            "79b9208028e6ed99e7d04bdc073d27a16bbf90e9de521aee6c3a84162ac26415";

    private static byte[] blob(String className, String... payloadHex) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x53565342); // SVSB
        out.writeByte(1);         // version
        out.writeInt(1);          // classes
        byte[] name = className.getBytes(StandardCharsets.UTF_8);
        out.writeShort(name.length);
        out.write(name);
        out.writeInt(payloadHex.length);
        for (String hex : payloadHex) {
            byte[] b = java.util.HexFormat.of().parseHex(hex);
            out.writeInt(b.length);
            out.write(b);
        }
        out.flush();
        return bos.toByteArray();
    }

    @Test
    void decryptsKnownVector() throws Exception {
        StringDecryptor d = new StringDecryptor(Base64.getDecoder().decode(MASTER));
        d.load(blob(CLASS_NAME, PAYLOAD_LEFT));
        assertEquals("LEFT", d.decrypt(CLASS_NAME, 0));
        assertTrue(d.hasClass(CLASS_NAME));
    }

    @Test
    void wrongSecretFailsGcmTag() throws Exception {
        byte[] wrong = new byte[32];
        StringDecryptor d = new StringDecryptor(wrong);
        d.load(blob(CLASS_NAME, PAYLOAD_LEFT));
        assertEquals(null, d.decrypt(CLASS_NAME, 0));
    }
}
