package android.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;

/**
 * 桌面端兼容 shim，替代 Android 平台的 {@code android.util.Base64}。
 *
 * <p><b>这是本次迁移最容易出错的地方。</b> Android 与 {@code java.util.Base64}
 * 的 flag 语义并不一一对应，如果简单地用 {@code getEncoder()/getDecoder()} 顶替，
 * 会导致图源解密静默失败（不抛异常，只是解出垃圾数据）。对应关系：
 *
 * <table>
 *   <tr><th>Android</th><th>java.util.Base64</th></tr>
 *   <tr><td>{@code DEFAULT} 编码</td><td>{@code getMimeEncoder(76, "\r\n")} —— 76 字符换行</td></tr>
 *   <tr><td>{@code NO_WRAP} 编码</td><td>{@code getEncoder()} —— 不换行</td></tr>
 *   <tr><td>{@code DEFAULT} 解码</td><td>{@code getMimeDecoder()} —— 容忍换行</td></tr>
 *   <tr><td>{@code NO_WRAP} 解码</td><td>{@code getDecoder()}，且需自行补 padding</td></tr>
 * </table>
 *
 * <p>另外 Android 的解码器接受「未补齐 padding」的输入，而 JDK 的严格解码器会抛
 * {@code IllegalArgumentException}，所以这里统一先去除空白、再补齐 {@code =}。
 *
 * <p>本类只在桌面 JVM 上编译，<b>不可</b>打进 Android 产物。
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {
    }

    public static byte[] decode(String str, int flags) {
        if (str == null) {
            return null;
        }
        return decode(str.getBytes(StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        if (input == null) {
            return null;
        }
        String s = new String(input, StandardCharsets.US_ASCII);
        // Android 的解码器容忍任意空白（换行/空格/制表符）
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        s = sb.toString();

        if ((flags & URL_SAFE) != 0) {
            s = s.replace('-', '+').replace('_', '/');
        }

        // JDK 的严格解码器要求长度是 4 的倍数，Android 不要求，这里补齐
        int remainder = s.length() % 4;
        if (remainder == 2) {
            s = s + "==";
        } else if (remainder == 3) {
            s = s + "=";
        } else if (remainder == 1) {
            // 非法长度，交给下面的解码器抛错或尽力而为
            s = s + "===";
        }

        Decoder decoder = ((flags & URL_SAFE) != 0)
                ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getMimeDecoder();
        return decoder.decode(s);
    }

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) {
            return null;
        }
        Encoder encoder;
        if ((flags & URL_SAFE) != 0) {
            encoder = java.util.Base64.getUrlEncoder();
        } else if ((flags & NO_WRAP) != 0) {
            encoder = java.util.Base64.getEncoder();
        } else {
            @SuppressWarnings("checkstyle:MagicNumber")
            byte[] lineSeparator = "\r\n".getBytes(StandardCharsets.US_ASCII);
            @SuppressWarnings("checkstyle:MagicNumber")
            int lineLength = 76;
            encoder = java.util.Base64.getMimeEncoder(lineLength, lineSeparator);
        }
        if ((flags & NO_PADDING) != 0) {
            encoder = encoder.withoutPadding();
        }
        return encoder.encodeToString(input);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encodeToString(input, flags).getBytes(StandardCharsets.US_ASCII);
    }

    /** 与 Android 一致：只编码 {@code input[offset, offset+len)} 这一段。 */
    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        if (input == null) {
            return null;
        }
        byte[] slice = new byte[len];
        System.arraycopy(input, offset, slice, 0, len);
        return encodeToString(slice, flags);
    }
}
