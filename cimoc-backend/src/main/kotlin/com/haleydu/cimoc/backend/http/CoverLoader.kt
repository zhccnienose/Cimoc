package com.haleydu.cimoc.backend.http

import com.haleydu.cimoc.model.Comic
import com.haleydu.cimoc.parser.MangaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 封面图片的取用与磁盘缓存。
 *
 * 前端渲染列表只需要 `covers.load(comic)`，缓存对调用方透明。
 *
 * ### 为什么这件事必须由后端做
 *
 * 客户端必须与其余请求**共用同一条 `OkHttpClient`**。TLS 策略只在
 * [com.haleydu.cimoc.backend.CimocBackend.open] 启动时装配一次：默认
 * trust-all（`http.strict_tls` 为 true 时才换成 JDK 默认校验）。前端自己 new 一个
 * 客户端，用的就是 JDK 默认校验，结果恰好是「证书坏掉的那批图源的封面全都加载不出来」
 * ——而那正是 trust-all 要解决的问题。同理，封面在各自图源的 CDN 上，往往需要该图源
 * 的 Referer/UA（[MangaParser.getHeader]），这条也只有后端拿得到。
 *
 * ### 缓存键是 URL，不是 (source, cid)
 *
 * - `cid` 不一定能当文件名：有的图源 cid 里带 `/`（见 [com.haleydu.cimoc.backend.download.FileNames]
 *   要处理的那一堆），清洗之后还可能撞名
 * - 图源换了封面地址就自动失效，不需要任何额外判断
 * - 代价：换过封面的漫画会把旧文件留在磁盘上，只能靠 [clear] 回收。这是缓存不是数据，
 *   可以接受
 *
 * 文件名取 `<sha256(url)>.cover`，**没有扩展名**——图片格式靠内容嗅探，不靠文件名，
 * 而为了拿到扩展名去依赖 `download` 包的 `FileNames.extensionOf` 会让 `http` 包反过来
 * 依赖 `download` 包，不值得。SHA-256 在这里只作缓存键，不涉及任何安全用途。
 *
 * ### 缓存内容是不可信数据
 *
 * 图源站点返回「HTTP 200 + 一页 HTML 错误页」是常态（拦截、需要验证、源站挂了）。
 * 这类响应如果被当成图片写进缓存，就**永久**污染了这张封面——之后每次命中缓存拿到的
 * 都是 HTML，而且看不出来为什么。所以写缓存前一律先嗅探魔数，不是图片就抛
 * [HttpException]，宁可不缓存。
 */
class CoverLoader(
    private val http: HttpEngine,
    val cacheRoot: Path,
    private val parserOf: (Int) -> MangaParser?,
) {

    /**
     * 封面用的客户端换了一套更短的超时，**连接池与线程池仍与主客户端共用**
     * （`OkHttpClient.newBuilder()` 是共享的，不是新建一套）。
     *
     * 用主客户端的 15s/30s 不行：列表里一次就是几十张封面，而当前相当一部分图源
     * 是本机连不上的（见 README「图源现状一览」），30 秒的读超时会让这些占位图
     * 一直转圈。
     */
    private val imageHttp = http.withTimeouts(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)

    /**
     * 为该漫画构造封面请求。**地址不合法或没有封面地址时返回 null。**
     *
     * 低层入口，普通调用方用 [load] 即可；需要在后端之外自己加载图片（换一套图片库）时
     * 才用它，这样能拿到该图源的请求头而不必自己拼。
     */
    fun request(comic: Comic): Request? {
        val url = coverUrl(comic) ?: return null
        val headers = headersFor(comic.source, url)
        // 地址非法（`Request.Builder.url` 抛 IllegalArgumentException）和缺封面地址
        // 都归到 null：对调用方来说处理方式相同——显示占位图
        return runCatching {
            Request.Builder().url(url).apply { if (headers != null) headers(headers) }.build()
        }.getOrNull()
    }

    /**
     * 取封面字节，带磁盘缓存。
     *
     * @return **null 表示这张漫画没有可用的封面地址**（字段为空、是猜不出主机名的相对
     *   路径、或地址非法）。**网络/HTTP 失败抛 [HttpException]，不吞掉**——封面加载
     *   失败的原因很多（超时、证书、源站拦截），吞掉之后前端只剩一个永远转圈的占位图，
     *   没法排查。列表场景建议调用方自己 `runCatching` 降级成占位图
     */
    suspend fun load(comic: Comic): ByteArray? {
        val request = request(comic) ?: return null
        val file = cacheFile(request.url().toString())

        if (Files.isRegularFile(file)) {
            // 读失败（外部删了、权限变了）当作没缓存，重新下，不因此报错
            runCatching { Files.readAllBytes(file) }.getOrNull()?.let { return it }
        }

        val data = imageHttp.getBytes(request)
        if (!looksLikeImage(data)) {
            throw HttpException(request.url().toString(), "响应不是图片（${data.size} 字节）")
        }

        // 写缓存失败不影响本次返回：图已经拿到手了，缓存只是让下次快一点
        runCatching { writeCache(file, data) }
        return data
    }

    /**
     * 清空封面缓存。
     *
     * @return 删掉的文件数
     */
    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(cacheRoot)) return@withContext 0
        var removed = 0
        Files.newDirectoryStream(cacheRoot).use { stream ->
            for (entry in stream) {
                if (runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)) removed++
            }
        }
        removed
    }

    // ---------------------------------------------------------------- 内部

    /** 归一化成绝对地址；无法归一化时返回 null。 */
    private fun coverUrl(comic: Comic): String? {
        val raw = comic.cover.trim()
        if (raw.isEmpty()) return null
        // 协议相对地址。漫画柜的搜索结果就是这个形状：`//cf.mhgui.com/cpic/m/60452.jpg`
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
        // 相对路径：各图源的 baseurl 不同，没有可补的主机名，只能放弃
        return null
    }

    /**
     * 该图源在请求这个地址时要带的头。
     *
     * 包了 `runCatching` 是刻意的：某个图源的 [MangaParser.getHeader] 抛异常，不该让
     * 这张封面连试都不试就失败——退化成不带额外请求头，最多是图片取不到。
     */
    private fun headersFor(source: Int, url: String): Headers? =
        runCatching { parserOf(source)?.getHeader(url) }.getOrNull()

    private fun cacheFile(url: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val name = buildString(digest.size * 2) {
            for (byte in digest) append("%02x".format(byte.toInt() and 0xFF))
        }
        return cacheRoot.resolve("$name.cover")
    }

    /**
     * 先写临时文件再原子改名。**临时文件名交给 `createTempFile` 生成**，不能用固定的
     * `<名字>.part`：同一张封面被并发请求时，两个协程会互相截断对方正在写的文件，
     * 最终改名的可能是个半截文件。
     */
    private fun writeCache(file: Path, data: ByteArray) {
        Files.createDirectories(cacheRoot)
        val tmp = Files.createTempFile(cacheRoot, "cover", PART_SUFFIX)
        try {
            Files.write(tmp, data)
            move(tmp, file)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /**
     * 与 `CbzWriter.move` 是同一套逻辑。那边是私有实现，不为了这 6 行去拓宽它的可见性。
     */
    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 魔数嗅探。只认这几种静态格式，与 [com.haleydu.cimoc.backend.download.DownloadLayout.extensionOf]
     * 认的那一组一致。
     *
     * 不去信任 `Content-Type`：漫画站上的图片响应头经常是错的（一水儿的 `image/jpeg`
     * 配 PNG 内容），而 HTML 错误页反倒经常老老实实报 `text/html`——所以判断只能看内容。
     */
    private fun looksLikeImage(data: ByteArray): Boolean {
        if (data.size < 12) return false

        fun startsAt(offset: Int, vararg signature: Int): Boolean =
            signature.withIndex().all { (i, expected) -> data[offset + i].toInt() and 0xFF == expected }

        return startsAt(0, 0xFF, 0xD8, 0xFF) ||                                    // JPEG
            startsAt(0, 0x89, 0x50, 0x4E, 0x47) ||                                 // PNG
            startsAt(0, 0x47, 0x49, 0x46, 0x38) ||                                 // GIF8
            startsAt(0, 0x42, 0x4D) ||                                             // BMP
            (startsAt(0, 0x52, 0x49, 0x46, 0x46) && startsAt(8, 0x57, 0x45, 0x42, 0x50)) || // RIFF….WEBP
            startsAt(4, 0x66, 0x74, 0x79, 0x70)                                    // ISO-BMFF（avif）
    }

    companion object {

        /** 缓存目录名，与 `<下载根>/.partial/` 同一层级、同样以点开头。 */
        const val DIR = ".cover"

        /** 临时文件后缀，与 [com.haleydu.cimoc.backend.download.CbzWriter.PART_SUFFIX] 一致。 */
        private const val PART_SUFFIX = ".part"

        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 10_000L
    }
}
