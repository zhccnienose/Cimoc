package com.haleydu.cimoc.core;

import com.haleydu.cimoc.core.CimocCore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 图源解析的公共支撑。
 *
 * <p>迁移说明：原先 {@code core/Manga} 既做 HTTP 调度又承载两个异常类型，导致
 * {@code parser/Parser} 接口反向依赖 {@code core} 包。这里暂时保留同名类以维持
 * 零改动迁移，但只保留被解析层真正引用的部分：
 * <ul>
 *   <li>{@link NetworkErrorException} / {@link ParseErrorException} —— 被 Parser 接口
 *       和 8 个图源的方法签名引用</li>
 *   <li>{@link #getResponseBody} —— 唯一被图源直接调用的 HTTP 工具方法
 *       （{@code source/Manhuatai.java}）</li>
 * </ul>
 *
 * <p>后续把 HTTP 调度引擎写进 {@code core} 包后，这里的方法会自然成为引擎的一部分。
 */
public final class Manga {

    /** 字符集探测：优先使用响应体里声明的 charset，否则按 UTF-8 处理。 */
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w\\-]+)");

    private Manga() {
    }

    /**
     * 发起请求并读取响应体，自动按页面声明的字符集解码。
     *
     * <p>与 Android 版行为一致：失败时重试一次，仍失败则抛 {@link NetworkErrorException}。
     */
    public static String getResponseBody(OkHttpClient client, Request request)
            throws NetworkErrorException {
        return getResponseBody(client, request, true);
    }

    private static String getResponseBody(OkHttpClient client, Request request, boolean retry)
            throws NetworkErrorException {
        if (client == null) {
            // 对应 Android 版「仅 WiFi 联网」时 App.getHttpClient() 返回 null 的情形
            throw new NetworkErrorException();
        }
        Response response = null;
        try {
            response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                byte[] bodyBytes = response.body().bytes();
                String body = new String(bodyBytes);
                Matcher matcher = CHARSET.matcher(body);
                if (matcher.find()) {
                    body = new String(bodyBytes, matcher.group(1));
                }
                return body;
            }
            if (retry) {
                return getResponseBody(client, request, false);
            }
        } catch (Exception e) {
            if (retry) {
                return getResponseBody(client, request, false);
            }
        } finally {
            if (response != null) {
                response.close();
            }
        }
        throw new NetworkErrorException();
    }

    /** 网络请求失败。 */
    public static class NetworkErrorException extends Exception {
    }

    /** 页面解析失败。 */
    public static class ParseErrorException extends Exception {
    }
}
