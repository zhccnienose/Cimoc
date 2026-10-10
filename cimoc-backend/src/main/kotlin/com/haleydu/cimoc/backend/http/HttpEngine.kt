package com.haleydu.cimoc.backend.http

import com.haleydu.cimoc.core.Manga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path

/**
 * 网络请求失败。
 *
 * 继承 [Manga.NetworkErrorException] 是刻意的：`Parser.parseImages` 等方法声明的就是
 * 这个受检异常，图源内部也可能自己抛出它（如 `Manhuatai` 直接用 `Manga.getResponseBody`）。
 * 让本异常成为它的子类，调用方就只需要 `catch (Manga.NetworkErrorException)` 一处，
 * 不会漏掉某条路径——同时还能带上 URL 和底层原因用于定位。
 *
 * 基类只有无参构造，所以细节走自己的字段与 [message] 覆写。
 */
class HttpException(
    val url: String,
    val detail: String,
    cause: Throwable? = null,
) : Manga.NetworkErrorException() {

    init {
        if (cause != null) {
            initCause(cause)
        }
    }

    override val message: String get() = "$detail [$url]"
}

/**
 * HTTP 取数。搜索、详情、更新检查、下载都从这里走。
 *
 * 方法都是 `suspend` 的：底层是 OkHttp 的阻塞 API，必须落在 `Dispatchers.IO` 上，
 * 而把这件事收在引擎内部，调用方就不必每处都记得包 `withContext`。
 */
class HttpEngine(private val client: OkHttpClient) {

    /**
     * 取页面 HTML。
     *
     * **直接复用 cimoc-core 的 [Manga.getResponseBody]**，而不是自己再写一遍。
     * 那个方法带有图源依赖的两个行为：失败重试一次、以及从页面声明的 `charset=`
     * 反推编码（很多中文站的页面是 GBK，用 UTF-8 解出来是乱码，图源的正则就全不匹配了）。
     * 这些图源的正则就是照着那份行为写出来的，重写一份等于引入一个静默的行为差异。
     */
    suspend fun getBody(request: Request): String = withContext(Dispatchers.IO) {
        try {
            Manga.getResponseBody(client, request)
        } catch (e: Manga.NetworkErrorException) {
            // 基类不带 message，直接抛上去调用方只能看到「网络错误」四个字，没法定位是哪个源
            throw HttpException(request.url().toString(), "读取响应体失败", e)
        }
    }

    /**
     * 单次尝试把响应体写进 [target]。**不做重试**——重试要配合退避，而退避需要
     * `delay()` 才能不占着线程，那是协程的事，放在 [com.haleydu.cimoc.backend.download.DownloadManager] 里做。
     *
     * 失败时会把写了一半的 [target] 删掉，避免留下残文件被后续判断当成「已下载」。
     *
     * @return 写入的字节数
     */
    suspend fun downloadTo(request: Request, target: Path): Long = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        // 协程被取消时顺手取消 OkHttp 的调用。不这么做的话，暂停下载要等当前这张图
        // 自己超时（最长 30 秒）才会真的停下来。
        val registration = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                // 必须写 response.code() 而不是属性语法 response.code：
                // OkHttp 3.x 的 Response 里同时有一个包私有的 int 字段 code 和一个 public int code()，
                // Kotlin 的属性语法会优先解析到那个不可见的字段，直接报「无法访问」。
                if (!response.isSuccessful()) {
                    throw HttpException(request.url().toString(), "HTTP ${response.code()}")
                }
                val body = response.body()
                    ?: throw HttpException(request.url().toString(), "响应体为空")

                val absolute = target.toAbsolutePath()
                absolute.parent?.let { Files.createDirectories(it) }
                body.byteStream().use { input ->
                    Files.newOutputStream(absolute).use { output -> input.copyTo(output) }
                }
            }
            Files.size(target.toAbsolutePath())
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(target) }
            // 被取消时 OkHttp 抛的是 IOException("Canceled")。必须还原成 CancellationException，
            // 否则上层会把它当网络故障去重试一个已经取消的下载。
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("下载已取消: ${request.url()}")
            }
            if (e is HttpException) throw e
            throw HttpException(request.url().toString(), "下载失败: ${e.message}", e)
        } finally {
            registration?.dispose()
        }
    }

    /** 底层客户端。下载引擎需要它来做按图源派生（如 JMTT 的响应解码）。 */
    fun client(): OkHttpClient = client

    /** 换一套超时，其余策略（含 TLS 选择）不变。 */
    fun withTimeouts(connectTimeoutMs: Long, readTimeoutMs: Long): HttpEngine =
        HttpEngine(HttpClientFactory.derive(client, connectTimeoutMs, readTimeoutMs))
}
