package com.haleydu.cimoc.backend.storage.dao

import com.haleydu.cimoc.backend.storage.Database
import com.haleydu.cimoc.model.Comic
import java.sql.Connection
import java.sql.ResultSet

/**
 * `comic` 表的读写。
 *
 * 复用 cimoc-core 的 [Comic] 作为载体，不进再定义一套 DTO——图源产出的就是这个类型，
 * 前端从搜索结果拿到它、点收藏、再传给下载，全程同一个对象，不用来回转换。
 *
 * 表里用不到的字段（`history` / `last` / `page` / `chapter` / `local`）全部留空：
 * 那些是阅读器专用的，本后端不做阅读器。
 */
class ComicDao(private val db: Database) {

    // ---------------------------------------------------------------- 查询

    fun load(id: Long): Comic? = db.read { conn -> load(conn, id) }

    fun loadBySourceCid(source: Int, cid: String): Comic? =
        db.read { conn -> loadBySourceCid(conn, source, cid) }

    /** 收藏夹，最近收藏的在前。新章节会把 `favorite_at` 顶到当前时间，因此更新的漫画自然浮到顶部。 */
    fun listFavorites(): List<Comic> = db.read { conn ->
        conn.prepareStatement(
            "SELECT $COLUMNS FROM comic WHERE favorite_at IS NOT NULL ORDER BY favorite_at DESC"
        ).use { ps ->
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) readComic(rs) else null }.toList() }
        }
    }

    fun listAll(): List<Comic> = db.read { conn ->
        conn.prepareStatement("SELECT $COLUMNS FROM comic ORDER BY id")
            .use { ps -> ps.executeQuery().use { rs -> generateSequence { if (rs.next()) readComic(rs) else null }.toList() } }
    }

    fun countFavorites(): Int = db.read { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM comic WHERE favorite_at IS NOT NULL").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 保证 `(source, cid)` 对应的行存在并返回它（带数据库 id）。
     *
     * 读与写在**同一个事务**里完成。原版是「先 `load` 再 `insert`」两次独立操作、
     * 靠调用方保证不并发——桌面端搜索与更新检查会同时跑，那个约定守不住。
     * 这里靠 `UNIQUE(source, cid)` 兜底，重复插入会被数据库直接挡掉。
     *
     * 注意返回的 [Comic.id] 非空，图源的合成 ID 依赖它，见 [com.haleydu.cimoc.backend.ParserIds]。
     */
    fun ensure(source: Int, cid: String): Comic = db.write { conn ->
        loadBySourceCid(conn, source, cid) ?: insert(conn, source, cid)
    }

    /** 详情页解析出的信息回写。`null` 字段跳过，不覆盖已有值（与原版 `Comic.setInfo` 语义一致）。 */
    fun saveInfo(comic: Comic): Comic = db.write { conn ->
        val id = requireNotNull(comic.id) { "saveInfo 需要 comic 已经落库" }
        conn.prepareStatement(
            """
            UPDATE comic SET
              title    = COALESCE(?, title),
              cover    = COALESCE(?, cover),
              author   = COALESCE(?, author),
              intro    = COALESCE(?, intro),
              url      = COALESCE(?, url),
              finish   = COALESCE(?, finish),
              update_at= COALESCE(?, update_at)
            WHERE id = ?
            """
        ).use { ps ->
            ps.setString(1, comic.title)
            ps.setString(2, comic.cover)
            ps.setString(3, comic.author)
            ps.setString(4, comic.intro)
            ps.setString(5, comic.url)
            ps.setObject(6, comic.finish?.let { if (it) 1 else 0 })
            ps.setString(7, comic.update)
            ps.setLong(8, id)
            ps.executeUpdate()
        }
        load(conn, id) ?: comic
    }

    /** 收藏 / 取消收藏。传 null 表示取消。 */
    fun setFavorite(id: Long, favoriteAt: Long?) = db.write { conn ->
        conn.prepareStatement("UPDATE comic SET favorite_at = ? WHERE id = ?").use { ps ->
            ps.setObject(1, favoriteAt)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
        Unit
    }

    fun setHighlight(id: Long, highlight: Boolean) = db.write { conn ->
        conn.prepareStatement("UPDATE comic SET highlight = ? WHERE id = ?").use { ps ->
            ps.setInt(1, if (highlight) 1 else 0)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
        Unit
    }

    /**
     * 更新检查发现新章节时的写回（对应原版 `Manga.checkUpdate` 里的三个 setter）。
     *
     * `favorite_at` 一并顶到 [now]，让这本漫画浮到收藏夹最前——原版也是这个行为。
     * 不这么做的话，有更新的漫画会混在中间，收藏夹失去「一眼看出谁更新了」的作用。
     */
    fun applyUpdateCheck(id: Long, updateAt: String, now: Long) = db.write { conn ->
        conn.prepareStatement(
            "UPDATE comic SET update_at = ?, highlight = 1, favorite_at = ? WHERE id = ?"
        ).use { ps ->
            ps.setString(1, updateAt)
            ps.setLong(2, now)
            ps.setLong(3, id)
            ps.executeUpdate()
        }
        Unit
    }

    fun delete(id: Long) = db.write { conn ->
        conn.prepareStatement("DELETE FROM comic WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
        Unit
    }

    /**
     * 既没收藏、也没有下载任务时，把整行删掉——否则搜过的漫画会无限堆积。
     *
     * 对应原版 `ComicManager.updateOrDelete` 的语义，只是判断条件从
     * 「三个时间戳全空」换成了「没收藏且没有下载任务」（桌面端没有阅读历史）。
     *
     * @return 是否删除了
     */
    fun deleteIfOrphan(id: Long): Boolean = db.write { conn ->
        conn.prepareStatement(
            """
            DELETE FROM comic
            WHERE id = ?
              AND favorite_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM download_task WHERE comic_id = comic.id)
            """
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate() > 0
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun load(conn: Connection, id: Long): Comic? =
        conn.prepareStatement("SELECT $COLUMNS FROM comic WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) readComic(rs) else null }
        }

    private fun loadBySourceCid(conn: Connection, source: Int, cid: String): Comic? =
        conn.prepareStatement("SELECT $COLUMNS FROM comic WHERE source = ? AND cid = ?").use { ps ->
            ps.setInt(1, source)
            ps.setString(2, cid)
            ps.executeQuery().use { rs -> if (rs.next()) readComic(rs) else null }
        }

    private fun insert(conn: Connection, source: Int, cid: String): Comic {
        conn.prepareStatement(
            "INSERT INTO comic (source, cid, title, cover, created_at) VALUES (?, ?, '', '', ?)"
        ).use { ps ->
            ps.setInt(1, source)
            ps.setString(2, cid)
            ps.setLong(3, System.currentTimeMillis())
            ps.executeUpdate()
        }

        // 用 last_insert_rowid() 而不是 JDBC 的 getGeneratedKeys()：
        // 前者是 SQLite 的内建函数，语义没有歧义；后者要看驱动实现，
        // 在同一个连接上批量插入时行为容易踩雷。这里是事务内，取到的必然是刚插的那行。
        val id = conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                if (rs.next()) rs.getLong(1) else error("插入 comic 后拿不到行号")
            }
        }

        return Comic().apply {
            this.id = id
            this.source = source
            this.cid = cid
            this.title = ""
            this.cover = ""
        }
    }

    private fun readComic(rs: ResultSet): Comic = Comic().apply {
        id = rs.getLong("id")
        source = rs.getInt("source")
        cid = rs.getString("cid")
        title = rs.getString("title")
        cover = rs.getString("cover")
        author = rs.getString("author")
        intro = rs.getString("intro")
        url = rs.getString("url")
        update = rs.getString("update_at")
        highlight = rs.getInt("highlight") != 0

        // 这几列必须用 getObject 而不是 getLong/getInt：后者对 NULL 返回 0，
        // 而 0 在这里是有意义的「1970 年」，会把「未收藏」读成「已收藏」。
        finish = rs.getObject("finish")?.let { (it as Number).toInt() != 0 }
        favorite = rs.getObject("favorite_at")?.let { (it as Number).toLong() }
    }

    private companion object {
        const val COLUMNS =
            "id, source, cid, title, cover, author, intro, url, finish, update_at, favorite_at, highlight"
    }
}
