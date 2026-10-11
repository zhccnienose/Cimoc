package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * 封面宽高比（宽 / 高）。2:3 是漫画封面的常见比例。
 *
 * 搜索结果的格子和详情页的头图都用它——不给固定比例的话，同一行每格的高度会随图片
 * 原始尺寸变，看起来参差不齐。
 */
internal const val COVER_ASPECT_RATIO = 0.68f

/**
 * 异步封面：内存缓存 → 磁盘缓存 → 网络，三级都走
 * [com.haleydu.cimoc.backend.http.CoverLoader.load]。
 *
 * 取不到图时画一个占位块并写明原因，**不抛异常**：列表里几十张封面，只要有一张网络失败
 * 就把整个界面崩掉是不可接受的（而当前有 26 个图源在本机连不上，失败是常态）。
 *
 * ### 为什么还要一层内存缓存
 *
 * 后端那层是**磁盘**缓存，命中一次仍要读文件 + 解码。列表滚动时 [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * 会把划出屏幕的格子丢掉，划回来就重新走一次「读文件 + Skia 解码」——一屏 8 张、
 * 来回滚几下就是上百次解码。内存这层把解码结果留着，滚动才不掉帧。
 *
 * ### 缓存键是原始封面地址
 *
 * [Comic.getCover] 可能是空串（构造函数把 null 归一成了 ""），不是 null。
 */
@Composable
fun CoverImage(comic: Comic, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    // 用原始地址当键：CoverLoader 内部的归一化（补 https:）是它的纯函数，
    // 一个原始地址只对应一个归一化结果，拿原始值当键不会撞。
    val key = comic.cover ?: ""

    var image by remember(key) { mutableStateOf(CoverCache.get(key)) }
    // 「已经有结论了」：内存命中、或者确定没有封面地址，两种情况都跳过加载
    var settled by remember(key) { mutableStateOf(key.isBlank() || image != null) }

    LaunchedEffect(key) {
        if (settled) return@LaunchedEffect

        val bitmap = withContext(Dispatchers.IO) {
            // 不能图省事用 runCatching：它连 CancellationException 一起吞。
            // 滚动时这个 effect 会被取消，吞掉之后协程会继续往下跑。
            val bytes = try {
                app.backend.covers.load(comic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 网络/HTTP 失败（超时、证书、源站返回 HTML）。CoverLoader 刻意不吞这个异常，
                // 这里就是它注释里说的「调用方自己降级成占位图」。
                null
            }
            bytes?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
        }

        if (bitmap != null) CoverCache.put(key, bitmap)
        image = bitmap
        settled = true
    }

    Box(
        modifier = modifier.background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = comic.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 还没出结果时不写字：转瞬即逝的「加载中」比空白更刺眼
            settled -> Text(
                text = if (key.isBlank()) "无封面" else "加载失败",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/**
 * 已解码封面的 LRU 内存缓存，进程级。
 *
 * 键是原始封面地址（见 [CoverImage]）。[LinkedHashMap] 的 accessOrder=true 让
 * `get` 也把条目挪到队尾，于是 [removeEldestEntry] 淘汰的就是「最久没用过的」。
 *
 * 容量按「一张解码后的封面约 200KB」估，128 张≈25MB，对桌面端可以接受；
 * 再多就该考虑按像素数而非条数限流了。
 */
private object CoverCache {

    private const val MAX_ENTRIES = 128

    private val entries = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > MAX_ENTRIES
    }

    /** 从主线程（remember 的初始化）与 IO 协程两边调用，必须同步。 */
    @Synchronized
    fun get(key: String): ImageBitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        entries[key] = bitmap
    }
}
