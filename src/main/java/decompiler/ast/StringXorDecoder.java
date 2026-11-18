package decompiler.ast;

/**
 * Декодер строк обфускатора slash: {@code obf/rt/RT.dStr(Ljava/lang/String;)Ljava/lang/String;}.
 *
 * <p>Алгоритм снят с байткода {@code RT.dStr}: последний символ строки — seed,
 * далее побайтовый XOR с мультипликативным обновлением состояния. Ключа нет —
 * декодирование целиком на стороне Java, поэтому строки можно вернуть в
 * читаемый вид без нативной части.
 */
public final class StringXorDecoder {

    private static final int SEED_XOR = 90;
    private static final int K_OR = 10368889;
    private static final int K_MUL = -2048144789;
    private static final int K_ADD = 43;

    private StringXorDecoder() {
    }

    /** Имя метода-декодера в обфусцированном рантайме. */
    public static final String DECODER_NAME = "dStr";
    public static final String DECODER_DESC = "(Ljava/lang/String;)Ljava/lang/String;";

    /** Расшифровывает строку; {@code null}, если вход пуст. */
    public static String decode(String s) {
        if (s == null || s.isEmpty()) return null;
        int n = s.length();
        int last = s.charAt(n - 1) & 0xFF;
        int k = (last ^ SEED_XOR) | K_OR;
        byte[] out = new byte[n - 1];
        for (int i = 0; i < n - 1; i++) {
            int c = s.charAt(i) & 0xFF;
            out[i] = (byte) ((c ^ ((k >>> 8) ^ (k & 0xFF))) & 0xFF);
            k = k * K_MUL + K_ADD;
        }
        return new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * Декодирует строку, только если она похожа на шифртекст (есть непечатные
     * байты) и результат похож на текст. Обычные строки возвращаются как есть —
     * ложных переименований из-за случайного совпадения не будет.
     */
    public static String decodeIfCipher(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean asciiOnly = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c >= 127) {
                asciiOnly = false;
                break;
            }
        }
        if (asciiOnly) return s;
        String d = decode(s);
        return d != null && looksLikeText(d) ? d : s;
    }

    /**
     * Похож ли результат на осмысленный текст: печатный ASCII, переводы строк
     * или не-латинские буквы. Защита от ложной свёртки чужого метода dStr.
     */
    public static boolean looksLikeText(String s) {
        if (s == null || s.isEmpty()) return false;
        int printable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 32 && c < 127) || c == '\n' || c == '\r' || c == '\t' || c >= 0x400) {
                printable++;
            }
        }
        return printable * 10 >= s.length() * 9;
    }
}
