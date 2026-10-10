package android.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/**
 * 桌面端兼容 shim，替代 Android 平台的 {@code android.net.Uri}。
 *
 * <p>迁移说明：解析层用到 {@link #encode(String, String)} 与 {@code uri.getHost()} /
 * {@code uri.getPath()}。Android 的 {@code Uri.parse} 极其宽容（永不抛异常），
 * 而 {@link java.net.URI} 会对畸形输入抛 {@link URISyntaxException}，因此这里
 * 自己做降级解析，保持行为一致。
 *
 * <p>本类只在桌面 JVM 上编译，<b>不可</b>打进 Android 产物。
 */
public class Uri {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final String raw;
    private String host;
    private String path;
    private boolean parsed;

    private Uri(String raw) {
        this.raw = raw == null ? "" : raw;
    }

    public static Uri parse(String uriString) {
        return new Uri(uriString);
    }

    /** 与 Android 一致：host 取不到时返回 null。 */
    public String getHost() {
        ensureParsed();
        return host;
    }

    /** 与 Android 一致：路径取不到时返回空串（而非 null）。 */
    public String getPath() {
        ensureParsed();
        return path == null ? "" : path;
    }

    public String getScheme() {
        ensureParsed();
        int i = raw.indexOf("://");
        return i > 0 ? raw.substring(0, i) : null;
    }

    @Override
    public String toString() {
        return raw;
    }

    private void ensureParsed() {
        if (parsed) {
            return;
        }
        parsed = true;
        try {
            URI u = new URI(raw);
            host = u.getHost();
            path = u.getPath();
            return;
        } catch (URISyntaxException ignored) {
            // 落到下面的降级解析
        }
        // scheme://authority[/path]
        int schemeEnd = raw.indexOf("://");
        int authorityStart = (schemeEnd >= 0) ? schemeEnd + 3 : 0;
        int pathStart = raw.indexOf('/', authorityStart);
        String authority = (pathStart >= 0)
                ? raw.substring(authorityStart, pathStart)
                : raw.substring(authorityStart);
        path = (pathStart >= 0) ? raw.substring(pathStart) : "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        host = authority.isEmpty() ? null : authority;
    }

    /**
     * 与 Android {@code Uri.encode(String, String)} 语义一致：
     * {@code A-Za-z0-9} 与 {@code _-!.~'()*} 以及 {@code allow} 中的字符保持原样，
     * 其余字符按 UTF-8 逐字节百分号编码（大写十六进制）。
     *
     * <p>{@code allow} 中通常包含 {@code '%'}，这样已经编码过的序列不会被二次编码。
     */
    public static String encode(String s, String allow) {
        if (s == null) {
            return null;
        }
        StringBuilder encoded = null;
        int len = s.length();
        for (int i = 0; i < len; ) {
            int cp = s.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (isAllowed(cp, allow)) {
                if (encoded != null) {
                    encoded.appendCodePoint(cp);
                }
            } else {
                if (encoded == null) {
                    encoded = new StringBuilder(len + 16);
                    encoded.append(s, 0, i);
                }
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    encoded.append('%')
                            .append(HEX[(b >> 4) & 0xF])
                            .append(HEX[b & 0xF]);
                }
            }
            i += charCount;
        }
        return (encoded == null) ? s : encoded.toString();
    }

    public static String encode(String s) {
        return encode(s, null);
    }

    private static boolean isAllowed(int cp, String allow) {
        if (cp > 127) {
            return false;
        }
        char c = (char) cp;
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            return true;
        }
        if ("_-!.~'()*".indexOf(c) != -1) {
            return true;
        }
        return allow != null && allow.indexOf(c) != -1;
    }
}
