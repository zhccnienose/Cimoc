package com.haleydu.cimoc.backend.engine

import com.haleydu.cimoc.backend.storage.DetailSnapshotCache
import com.haleydu.cimoc.backend.storage.Library
import com.haleydu.cimoc.model.Chapter
import com.haleydu.cimoc.model.Comic

/**
 * 收藏夹。数据库那份是权威，另外维护一份「简介 + 章节目录」的 JSON 快照
 * （见 [DetailSnapshotCache]，目录是 `favorite/`；没收藏的那些归
 * [com.haleydu.cimoc.backend.engine.HistoryStore] 管）。
 *
 * ### 为什么单独一类，而不是直接给前端 [Library]
 *
 * 「收藏」现在是**两个存储上的一个动作**：写 `comic.favorite_at` + 写 JSON 文件。
 * 两个动作必须成对出现（收藏时写缓存、取消时删缓存），散在界面代码里迟早漏一个——
 * 漏了「取消时删」就是磁盘上永远删不掉的垃圾文件，漏了「收藏时写」就是详情页
 * 退回网络、而当前一半以上的图源连不上。
 *
 * 所以跟 [Library] 里「收藏时顺带删孤儿行」是同一个理由：规则只有一处实现。
 *
 * 阻塞（SQLite + 文件读写）。前端请在 IO 上调用。
 */
class FavoriteStore(
    private val library: Library,
    private val cache: DetailSnapshotCache,
) {

    /** 收藏夹，最近收藏的在前。 */
    fun list(): List<Comic> = library.favorites()

    fun isFavorite(source: Int, cid: String): Boolean =
        library.comics.loadBySourceCid(source, cid)?.favorite != null

    /**
     * 收藏，并把简介与章节目录写进 JSON 缓存。
     *
     * [chapters] 允许为空（从搜索结果直接收藏、详情还没解析出来就是这样）：
     * 此时缓存里只有简介字段，详情页打开时会因为读不到章节而退回网络解析。
     *
     * @param comic 搜索结果里那个对象即可，`intro` 之类的字段缺失不影响——
     *   [Library.favorite] 用的是「库里的旧值 + 这次带上的新值」合并后的结果
     * @return 落库后的对象（[Comic.id] 非空）
     */
    fun favorite(comic: Comic, chapters: List<Chapter> = emptyList()): Comic {
        val stored = library.favorite(comic)
        cache.write(stored, chapters)
        return stored
    }

    /**
     * 取消收藏并删掉缓存文件。
     *
     * 传 source + cid 而不是 `comic.id`：从详情页调用时手上的对象不一定落过库，
     * 而 `(source, cid)` 在任何入口都拿得到。
     *
     * @return 是否真的取消了一个收藏
     */
    fun unfavorite(source: Int, cid: String): Boolean {
        val row = library.comics.loadBySourceCid(source, cid) ?: return false
        val removed = library.unfavorite(requireNotNull(row.id))
        // 先删缓存再判断：这行如果因为「没收藏也没下载任务」被顺手删掉，
        // 缓存文件更应该走——但反过来，删文件失败不该影响取消收藏本身
        runCatching { cache.delete(source, cid) }
        return removed
    }

    /**
     * 从本地缓存读详情（简介 + 章节目录）。没有缓存、或缓存不可用时返回 null。
     *
     * 返回的 [ComicDetail] 里，漫画行号取**数据库当前的值**而不是文件里那份：
     * 文件是快照，数据库才是权威，而下载要用行号定位。
     */
    fun cached(source: Int, cid: String): ComicDetail? {
        val row = library.comics.loadBySourceCid(source, cid) ?: return null
        val cached = cache.read(source, cid) ?: return null

        cached.comic.id = row.id
        cached.comic.favorite = row.favorite
        // 图上那几个字段以库为准：更新检查会改写 update_at / highlight，
        // 而这些字段在缓存写完之后还会变
        cached.comic.update = row.update
        cached.comic.highlight = row.highlight
        return ComicDetail(cached.comic, cached.chapters)
    }

    /**
     * 已经收藏着的话，把这次的详情刷新进缓存。
     *
     * 详情页走网络解析成功后调用。不做的话缓存会停在「当初点收藏的那一秒」：
     * 收藏时详情还没解析出来（从搜索结果直接收藏）就会留下一个**章节为空**的缓存，
     * 之后再打开这本收藏永远看不见章节。
     */
    fun refreshCacheIfFavorite(detail: ComicDetail) {
        val id = detail.comic.id ?: return
        if (library.comics.load(id)?.favorite == null) return
        cache.write(detail.comic, detail.chapters)
    }
}
