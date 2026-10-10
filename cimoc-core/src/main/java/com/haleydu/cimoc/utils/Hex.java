package com.haleydu.cimoc.utils;

/**
 * 十六进制编解码。
 *
 * <p>迁移说明：原先 {@code source/CopyMH.java} 用的是 Fresco 的
 * {@code com.facebook.common.util.Hex}。Fresco 是 Android 图片加载库，本次重构整体
 * 弃用；为了一个十六进制编解码器而把它整个拉进核心库显然不合理，因此本地实现。
 *
 * <p>方法签名与语义对齐 Fresco 版本，保证调用方行为不变。
 */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    /** 把字节数组编码为小写十六进制字符串。 */
    public static String encodeHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = DIGITS[v >>> 4];
            out[i * 2 + 1] = DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 把十六进制字符串解码为字节数组。
     *
     * @throws IllegalArgumentException 长度为奇数或含非法十六进制字符
     */
    public static byte[] decodeHex(String hex) {
        if (hex == null) {
            return null;
        }
        int len = hex.length();
        if ((len & 0x01) != 0) {
            throw new IllegalArgumentException("Odd number of characters: " + len);
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = digit(hex.charAt(i));
            int lo = digit(hex.charAt(i + 1));
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("Illegal hexadecimal character: " + c);
    }
}
