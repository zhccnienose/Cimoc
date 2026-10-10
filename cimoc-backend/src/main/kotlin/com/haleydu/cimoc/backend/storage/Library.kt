package com.haleydu.cimoc.backend.storage

import com.haleydu.cimoc.backend.ParserIds
import com.haleydu.cimoc.backend.storage.dao.ChapterDao
import com.haleydu.cimoc.backend.storage.dao.ComicDao
import com.haleydu.cimoc.backend.storage.dao.DownloadTaskDao
import com.haleydu.cimoc.backend.storage.dao.SourceConfigDao
import com.haleydu.cimoc.model.Chapter
import com.haleydu.cimoc.model.Comic

/**
 * 收藏夹与章节列表的高层入口。DAOs 做单表读写，这里做跨表、需要多个步骤的操作。
 *
 * 上层（引擎层、前端）只认这个类和 [AppSettings]，不直接碰 DAO——
 * 这样「收藏时顺带删孤儿行」之类的规则只有一处实现。
 */
class Library(private val db: Database) {

    val comics = ComicDao(db)
    val chapters = ChapterDao(db)
    val tasks = DownloadTaskDao(db)
    val sources = SourceConfigDao(db)

    // ---------------------------------------------------------------- 收藏

    fun favorites(): List<Comic> = comics.listFavorites()

    fun isFavorite(id: Long): Boolean = comics.load(id)?.favorite != null

    /**
     * 收藏。漫画没落库就顺手落库（搜索结果点收藏走这条路，此时标题作者都还没解析）。
     *
     * @return 落库后的对象，[Comic.id] 必定非空——调用方后面要靠它取章节
     */
    fun favorite(comic: Comic): Comic {
        val row = comics.ensure(comic.source, comic.cid)
        // 把行号写回传入的对象：`ensure` 返回的那份只带 (source, cid)，
        // 而调用方手上的这份才有标题/封面（搜索结果的字段）。saveInfo 用的是后者。
        // 顺带让调用方拿到 id，不必再查一次。
        comic.id = row.id
        comics.saveInfo(comic)
        comics.setFavorite(row.id, System.currentTimeMillis())
        return requireNotNull(comics.load(row.id))
    }

    /** 取消收藏。此后如果这行既没收藏也没下载任务，整行删掉。 */
    fun unfavorite(id: Long): Boolean {
        comics.setFavorite(id, null)
        return comics.deleteIfOrphan(id)
    }

    fun setHighlight(id: Long, highlight: Boolean) = comics.setHighlight(id, highlight)

    /** 更新检查发现新章节：写更新时间、打上高亮、顶到收藏夹最前。 */
    fun markUpdated(id: Long, updateAt: String, now: Long = System.currentTimeMillis()) =
        comics.applyUpdateCheck(id, updateAt, now)

    // ---------------------------------------------------------------- 章节

    fun chaptersOf(comicId: Long): List<Chapter> = chapters.listForComic(comicId)

    /**
     * 把一次 `parseChapter` 的结果落库，返回带数据库行号的章节列表。
     *
     * 这里负责生成图源的合成 ID（[ParserIds]），因为只有这一层同时知道
     * 漫画的数据库行号和图源编号。
     *
     * **调用前 [Comic.id] 必须已经落库**——合成 ID 里含这个行号。
     */
    fun refreshChapters(comic: Comic, parsed: List<Chapter>): List<Chapter> {
        val comicId = requireNotNull(comic.id) { "落库章节前 comic 必须先入库（合成 ID 依赖行号）" }
        return chapters.replaceForComic(comicId, ParserIds.sourceComic(comic.source, comicId), parsed)
    }

    /** 交给图源用的章节对象（`id` 是合成 ID），`parseImages` 要用。 */
    fun chapterForParser(id: Long): Chapter? = chapters.loadForParser(id)

    // ---------------------------------------------------------------- 维护

    /** 进程启动时调用：把上次留下的「下载中」任务打回暂停。 */
    fun recoverInterruptedTasks(): Int = tasks.markInterruptedAsPaused()

    /** 删一本漫画，章节与下载任务随外键级联。 */
    fun deleteComic(id: Long) = comics.delete(id)
}
