package com.haleydu.cimoc.backend.engine

import com.haleydu.cimoc.backend.ParserIds
import com.haleydu.cimoc.backend.http.HttpEngine
import com.haleydu.cimoc.backend.source.ParserFactory
import com.haleydu.cimoc.backend.source.SourceRegistry
import com.haleydu.cimoc.backend.storage.Library
import com.haleydu.cimoc.model.Chapter
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次详情页解析的产物。
 *
 * [comic] 与 [chapters] 都带着数据库行号（`Comic.id` 是 comic 表行号，
 * `Chapter.id` 是 chapter 表行号），前端拿它们去收藏、下载、更新检查都直接可用。
 */
data class ComicDetail(
    val comic: Comic,
    val chapters: List<Chapter>,
)

/** 详情页或章节列表解析失败。 */
class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 拉取漫画详情与章节列表，并落库。
 *
 * 对应原版 `Manga.getComicInfo`，流程逐步对齐，只有一处**有意**的区别：见 [load] 的第 1 步。
 */
class ComicEngine(
    private val registry: SourceRegistry,
    private val factory: ParserFactory,
    private val http: HttpEngine,
    private val library: Library,
) {

    /**
     * 解析详情 + 章节列表，写库后返回带行号的对象。
     *
     * @param comic 至少要有 `source` 和 `cid`（搜索结果直接传来即可）
     * @throws IllegalArgumentException 图源不在注册表中
     * @throws ParseException 详情页解析不出章节
     */
    suspend fun load(comic: Comic): ComicDetail = withContext(Dispatchers.IO) {
        val info = registry.byType(comic.source)
            ?: throw IllegalArgumentException("未知的图源 type=${comic.source}，无法拉取详情")
        val parser = factory.parser(comic.source)

        // ---- 1. 先落库，拿到行号
        //
        // 原版是在解析完之后才用 `comic.getId()` 拼 sourceComic，id 为 null 时
        // 退化成 `source + "000" + "00"` 这个假的占位值（见原 Manga.getComicInfo）。
        // 那套逻辑假定漫画一定已经入库，占位分支其实是个隐患。
        // 这里改成「先 ensure 再解析」，行号必定存在，合成 ID 也就必定正确。
        val row = library.comics.ensure(comic.source, comic.cid)
        comic.id = row.id

        // ---- 2. 详情页
        comic.url = parser.getUrl(comic.cid)
        val infoRequest = parser.getInfoRequest(comic.cid)
            ?: throw ParseException("图源 ${info.title} 没有提供详情页请求")
        var html = http.getBody(infoRequest)

        // parseInfo 会就地改 comic 的字段（setInfo）并返回它。
        // 部分图源（CopyMH、HotManga）还借 `comic.note` 把内容传给下面的 parseChapter，
        // 所以**必须**一路传同一个对象，不能换成新实例。
        parser.parseInfo(html, comic)

        // ---- 3. 章节列表。有的图源在详情页里就把章节解析完了，此时 getChapterRequest 返回 null
        val chapterRequest = parser.getChapterRequest(html, comic.cid)
        if (chapterRequest != null) {
            html = http.getBody(chapterRequest)
        }

        val sourceComic = ParserIds.sourceComic(comic.source, requireNotNull(comic.id))
        val parsed = parser.parseChapter(html, comic, sourceComic) ?: parser.parseChapter(html)

        if (parsed.isNullOrEmpty()) {
            throw ParseException("图源 ${info.title} 没有解析出任何章节")
        }

        // ---- 4. 落库
        library.comics.saveInfo(comic)
        val stored = library.refreshChapters(comic, parsed)

        ComicDetail(requireNotNull(library.comics.load(comic.id!!)), stored)
    }
}
