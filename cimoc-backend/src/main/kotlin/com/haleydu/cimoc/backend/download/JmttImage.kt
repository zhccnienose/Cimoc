package com.haleydu.cimoc.backend.download

import com.haleydu.cimoc.source.JMTT
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * JMTT（`source/JMTT.java`，type=72）的图片还原。
 *
 * ### 为什么需要这个
 *
 * JMTT 站把一部分图片**切割后重排**再发出来，页面上的 JS 负责还原。原 Android 版
 * 是在下载时挂一个 OkHttp 拦截器，用 `Bitmap`/`Canvas` 还原后再落盘（`app/` 里的
 * `utils/JMTTUtil.kt`）——那个文件随 `app/` 一起删了，所以这段逻辑在这里用
 * `java.awt.image.BufferedImage` 重写。**不还原的话，下到的是乱序的图片**，
 * 而且不会报任何错，打开才发现。
 *
 * ### 还原规则
 *
 * 把图片等高切成 10 条横带，然后**上下颠倒**（第 1 条与第 10 条互换）。
 * 高度除不尽时余数并入第 1 条。原实现（`JMTTUtil.decodeImage`）数格子用的就是这套。
 *
 * ### 触发条件
 *
 * 只有**一部分**图片被处理过，原版的判断是：URL 含 `media/photos`，且路径里
 * 那张图的编号 ≥ 220980。其余图片原样使用。这个阈值是硬编码在旧代码里的，
 * 无法从原理推导，只能照搬——站方如果改了阈值，表现是「新章节的图变成乱序」。
 *
 * AWT 只用 `BufferedImage` / `Graphics2D`，不需要显示器，headless 环境可用。
 */
object JmttImage {

    /** 分带数量。原实现硬编码 10。 */
    private const val ROWS = 10

    /** 编号阈值。原实现的硬编码值，低于它的图片没有被打乱。 */
    private const val SCRAMBLED_FROM = 220_980

    /** 只有 JMTT 需要还原。 */
    fun applies(source: Int, url: String): Boolean =
        source == JMTT.TYPE && needsDecode(url)

    /**
     * 就地还原 [target]。失败时**保留原文件**并返回 false——图片可能本来就是
     * 没被打乱的（阈值判断只是启发式），或者 JDK 解不开这个格式（AVIF/WebP）。
     * 这两种情况下保持原样都比让整章下载失败要好。
     *
     * @return 是否真的替换了文件
     */
    fun process(target: Path): Boolean {
        val original = runCatching { Files.readAllBytes(target) }.getOrNull() ?: return false
        val decoded = runCatching { descramble(original) }.getOrNull() ?: return false
        return runCatching { Files.write(target, decoded) }.isSuccess
    }

    /** 纯函数形式，便于单独验证。 */
    fun descramble(bytes: ByteArray): ByteArray {
        val input = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("JDK 无法解码这张图片（可能是 AVIF/WebP）")
        if (input.height < ROWS) throw IllegalArgumentException("图片太矮，不像是被打乱过的")

        val width = input.width
        val height = input.height
        val band = height / ROWS
        val remainder = height % ROWS

        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = result.createGraphics()
        try {
            for (x in 0 until ROWS) {
                var bandHeight = band
                var destY = band * x
                val srcY = height - band * (x + 1) - remainder

                if (x == 0) {
                    // 第 1 条多担余数，这样 10 条正好铺满整幅图
                    bandHeight += remainder
                } else {
                    destY += remainder
                }

                graphics.drawImage(
                    input,
                    0, destY, width, destY + bandHeight,
                    0, srcY, width, srcY + bandHeight,
                    null,
                )
            }
        } finally {
            graphics.dispose()
        }

        return ByteArrayOutputStream().also { ImageIO.write(result, "jpg", it) }.toByteArray()
    }

    /**
     * 判断这张图是否需要还原。照搬原拦截器的逻辑：URL 里找 `photos/<编号>/`。
     */
    private fun needsDecode(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.contains("media/photos")) return false

        val start = lower.indexOf("photos/")
        val end = lower.lastIndexOf('/')
        if (start < 0 || end <= start + 7) return false

        val raw = lower.substring(start + 7, end).substringBefore('-')
        val number = raw.toIntOrNull() ?: return false
        return number >= SCRAMBLED_FROM
    }
}
