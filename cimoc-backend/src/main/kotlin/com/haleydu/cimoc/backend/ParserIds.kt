package com.haleydu.cimoc.backend

/**
 * 图源层的**合成 ID**约定。这是整个后端里最反直觉的一块，单独放一个文件说明。
 *
 * ### 它是什么
 *
 * `parseChapter` 与 `parseImages` 之间靠一串数字传话，规则是字符串拼接：
 *
 * ```
 * sourceComic = "${source}000${comicRowId}"        // 例如 source=5, comicId=42 → 500042
 * chapterId   = "${sourceComic}000${index}"        // 例如 index=3        → 5000420003
 * ```
 *
 * 这**不是**数据库主键，是一串「把几个数字拼起来当数字用」的编码：
 * `Chapter.sourceComic` 里装着 `comic` 的部分，`Chapter.id` 里装着「哪一章」的部分。
 *
 * ### 为什么必须照做
 *
 * 40 多个图源的 `parseImages(html, chapter)` 第一行就是这个：
 *
 * ```java
 * Long comicChapter = chapter.getId();
 * Long id = Long.parseLong(comicChapter + "000" + i);   // ← 用到了 chapter.getId()
 * ```
 *
 * 也就是说**下载路径依赖 `Chapter.getId()` 非空且是一个能拼进数字串的值**。
 * 如果我们用数据库的自增行号（1、2、3……）去顶替它，拼接结果仍是合法数字，
 * 但 `DmzjFix.parseImages` 会进一步做
 * `chapter.getId().toString().replace(chapter.getSourceComic().toString(), "")`
 * 来区分「默认线路 / 备用线路」——那时拼出来的值就对不上了。
 *
 * 所以：**数据库自己用自增行号，图源读到的 `Chapter.id` / `sourceComic` 由本文件还原。**
 * 两者的转换只发生在 `ChapterDao` 的读写两个点上，见 `ChapterDao.loadForParser`。
 *
 * ### 一个必须遵守的时序约束
 *
 * `sourceComic` 里含 `comic` 的**数据库行号**，所以 `parseChapter` **必须在 comic 行落库之后调用**。
 * `ComicEngine` 里对此有依赖，改动调用顺序时要留意。
 */
object ParserIds {

    fun sourceComic(source: Int, comicRowId: Long): Long =
        (source.toString() + "000" + comicRowId).toLong()

    /**
     * 给没有自己赋 `Chapter.id` 的图源兜底生成一个。
     *
     * 大部分图源在 `parseChapter` 里就自己拼好了 id；但走
     * `parseChapter(html)`（无 sourceComic 参数的那个重载）时拿到的是 id 为 null 的章节。
     * `parseImages` 里 `null + "000" + i` 会抛 NumberFormatException，所以这里补上。
     */
    fun chapterId(sourceComic: Long, index: Int): Long =
        (sourceComic.toString() + "000" + index).toLong()
}
