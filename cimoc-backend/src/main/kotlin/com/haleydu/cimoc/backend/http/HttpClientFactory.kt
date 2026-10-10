package com.haleydu.cimoc.backend.http

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * `OkHttpClient` 的构造。
 *
 * ### 关于 TLS 默认值
 *
 * **默认 `strictTls = false`，即不验证服务器证书。**
 *
 * 这沿用了原 Android 版 `App.getHttpClient()` 的行为（trust-all 的 `X509TrustManager`
 * 加一个恒真的 `HostnameVerifier`）。做出这个选择的原因是现实：相当一部分中文漫画站的
 * 证书是坏的（自签、过期、域名不匹配），严格校验会让这些图源直接不可用，
 * 而它们正是这个项目的价值所在。
 *
 * 代价必须说清楚：**在这个默认值下，下载到的内容可以被网络中间人篡改而不被察觉。**
 * 对漫画图片而言风险有限，但如果你在公共 Wi-Fi 或公司网络下使用，建议把
 * `setting` 表的 `http.strict_tls` 置为 true。
 *
 * 那个开关**不是摆设**——见 [build]，它决定是否往 builder 里塞 trust-all 的
 * `sslSocketFactory` 与 `hostnameVerifier`；关掉后 OkHttp 就用 JDK 的默认校验。
 */
object HttpClientFactory {

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
    private const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    private const val DEFAULT_WRITE_TIMEOUT_MS = 15_000L

    /**
     * @param strictTls true 时使用 JDK 默认的证书校验；false 时不校验（见类注释）
     */
    fun create(
        strictTls: Boolean,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
        writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
    ): OkHttpClient = build(strictTls, connectTimeoutMs, readTimeoutMs, writeTimeoutMs)

    /** 在已有客户端上改超时，其余策略（含 TLS 选择）保持不变。 */
    fun derive(
        base: OkHttpClient,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
    ): OkHttpClient = base.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private fun build(
        strictTls: Boolean,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        writeTimeoutMs: Long,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            // 重定向与连接失败重试沿用原版设置。不少图源的图片会 302 到 CDN。
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)

        if (!strictTls) {
            val trustManager = TrustAllManager()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        // 刻意不设置 User-Agent。
        //
        // 直觉上设一个浏览器 UA 更「像样」，但那会改变部分图源拿到的 HTML——
        // 很多中文漫画站按 UA 分发不同模板。原 Android 版用的是 OkHttp 的默认 UA，
        // 图源的解析规则就是照着那个模板写的。换 UA 等于把 42 个图源的行为全部赌一遍。
        // 如果将来确实需要，应当做成按图源配置，而不是全局。

        return builder.build()
    }

    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
