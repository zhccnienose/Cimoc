package com.haleydu.cimoc.backend.engine

import com.haleydu.cimoc.backend.storage.DetailSnapshotCache
import com.haleydu.cimoc.backend.storage.Library

/**
 * 浏览记录：**没收藏**的漫画，详情页打开过就存一份快照，下次直接读它。
 *
 * 详情页的查找顺序是 [FavoriteStore.cached] → [cached] → 网络，见 `ComicDetailPage`。
 * 两个缓存合起来要解决的是同一件事：图源今天连不上时，那本看过的漫画还打得开。
 * 区别只在归属——收藏的那些由 [FavoriteStore] 一直维护着（更新检查还会改写它们），
 * 没必要在这儿再占一份；所以 [record] 见到已收藏的直接跳过，两边不重叠。
 *
 * ### 缓存会过时，这是已知且刻意的
 *
 * 命中的页面里看不到图源后来加的章节。不做 TTL、不做后台静默刷新：后台刷意味着
 * 「打开一本漫画顺带打一次全站」，正是需求要减少的东西。界面上给一个「重新拉取」按钮，
 * 用户想新章节时自己点。
 *
 * ### 收藏 → 取消收藏 → 图源挂掉，这条路径救不了
 *
 * 取消收藏时 `deleteIfOrphan` 会把漫画行删掉（连章节行一起级联），快照里的章节行号
 * 随之作废。所以 [cached] 先查漫画行在不在，不在就当没缓存——没有行号，那份章节列表
 * 交回下载也是废的。
 *
 * ### 不封顶
 *
 * 用户明确要求「存到天荒地老」。唯一的清理入口是设置页那个「清空」按钮。
 *
 * 阻塞（SQLite + 文件读写）。前端请在 IO 上调用。
 */
class HistoryStore(
    private val library: Library,
    private val cache: DetailSnapshotCache,
) {

    /**
     * 从浏览记录读详情。没记录、记录损坏、或那本漫画已经不在库里时返回 null。
     *
     * 跟 [FavoriteStore.cached] 一样，返回对象里的 `id / favorite / update / highlight`
     * 取**数据库当前的值**而不是文件里那份：文件是快照，数据库才是权威，
     * 而下载要用行号定位，`update`/`highlight` 在快照写完之后还会被更新检查改写。
     */
    fun cached(source: Int, cid: String): ComicDetail? {
        val row = library.comics.loadBySourceCid(source, cid) ?: return null
        val cached = cache.read(source, cid) ?: return null

        cached.comic.id = row.id
        cached.comic.favorite = row.favorite
        cached.comic.update = row.update
        cached.comic.highlight = row.highlight
        return ComicDetail(cached.comic, cached.chapters)
    }

    /**
     * 记一次浏览。详情页走网络解析成功后调用。
     *
     * 这本当前是收藏就什么都不做——收藏那份由 [FavoriteStore.refreshCacheIfFavorite] 写，
     * 这里再写一份只是把同一份内容在磁盘上存两遍。
     *
     * 调用方**无条件调用即可**，判断收在这个方法里。失败静默：浏览记录写不成不该让
     * 详情页显示成失败，它本来就只是一层加速。
     */
    fun record(detail: ComicDetail) {
        if (detail.comic.cid.isNullOrEmpty()) return
        val id = detail.comic.id ?: return
        if (library.comics.load(id)?.favorite != null) return
        runCatching { cache.write(detail.comic, detail.chapters) }
    }

    /**
     * 浏览记录占用的磁盘空间（字节）。设置页显示它。
     *
     * 阻塞（遍历目录）。前端请在 IO 上调用。
     */
    fun sizeBytes(): Long = cache.sizeBytes()

    /**
     * 清空浏览记录。@return 删掉的条数
     *
     * 阻塞（遍历目录）。前端请在 IO 上调用。
     */
    fun clear(): Int = cache.clear()
}
