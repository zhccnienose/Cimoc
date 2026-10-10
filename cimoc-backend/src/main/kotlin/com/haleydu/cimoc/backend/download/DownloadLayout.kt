package com.haleydu.cimoc.backend.download

import com.haleydu.cimoc.model.Chapter
import com.haleydu.cimoc.model.Comic
import java.nio.file.Files
import java.nio.file.Path

/**
 * 下载目录的布局。
 *
 * ```
 * <root>/
 *   <图源标题>/<漫画标题>/<000 章节标题>.cbz     ← 成品，交给第三方阅读器
 *   .partial/<章节行号>/001.jpg                 ← 下载中的临时页
 * ```
 *
 * 两点说明：
 *
 * - **`.partial` 用章节行号而不是标题**。它是内部临时目录，用行号就不可能撞名；
 *   用标题的话，两个同名章节会共用目录，续传时互相污染。
 * - **临时页留在磁盘上**，不随暂停删除。这是续传的关键：重新开始下载时，
 *   已经下好的页直接跳过，不必重下一整章。
 */
class DownloadLayout(
    val root: Path,
    private val sourceTitle: (Int) -> String,
) {

    /** 所有未完成章节的临时页都挂在这个目录下，便于一次性清理。 */
    val partialRoot: Path get() = root.resolve(".partial")

    /** 成品目录。同名漫画会合到同一个目录里——这是想要的行为，同一本漫画不该有两个文件夹。 */
    fun comicDir(comic: Comic): Path = root
        .resolve(FileNames.sanitize(sourceTitle(comic.source), "来源${comic.source}"))
        .resolve(FileNames.sanitize(comic.title, "cid-${comic.cid}"))

    /**
     * 章节 CBZ 的完整路径。
     *
     * 文件名带三位序号前缀（`000 第一话.cbz`），这样第三方阅读器按文件名排序
     * 就是正确的阅读顺序，不依赖目录里的其他元数据。
     *
     * [taken] 是同一本漫画下**已经用过**的文件名，用来避免清洗后撞名覆盖。
     */
    fun chapterFile(comic: Comic, chapter: Chapter, ordinal: Int, taken: Set<String>): Path {
        val base = "%03d %s".format(ordinal, FileNames.sanitize(chapter.title, "第${ordinal + 1}话"))
        return comicDir(comic).resolve(FileNames.unique("$base.cbz", taken))
    }

    /** 某一章的临时页目录。 */
    fun workDir(chapterId: Long): Path = partialRoot.resolve(chapterId.toString())

    /** 临时页的文件名：`001.jpg`，零填充，保证 zip 内按名字排序即正确顺序。 */
    fun pageName(index: Int, extension: String): String =
        "%03d.%s".format(index + 1, extension)

    fun createWorkDir(chapterId: Long): Path =
        Files.createDirectories(workDir(chapterId))

    /** 下载成功或任务被删除后调用。目录不存在也不报错。 */
    fun clearWorkDir(chapterId: Long) {
        val dir = workDir(chapterId)
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    /**
     * 从 URL 推断图片扩展名，推断不出就按 `jpg`。
     *
     * 只认几种明确安全的静态图格式。不做「信任服务端」的事：`Content-Type` 在漫画站上
     * 经常是错的（一水儿的 `image/jpeg` 配 PNG 内容），而扩展名错了会让一部分阅读器
     * 直接不显示。
     */
    fun extensionOf(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val ext = withoutQuery.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif" -> if (ext == "jpeg") "jpg" else ext
            else -> "jpg"
        }
    }
}
