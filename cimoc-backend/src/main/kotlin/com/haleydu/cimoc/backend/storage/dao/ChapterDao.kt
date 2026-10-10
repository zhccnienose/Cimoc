package com.haleydu.cimoc.backend.storage.dao

import com.haleydu.cimoc.backend.ParserIds
import com.haleydu.cimoc.backend.storage.Database
import com.haleydu.cimoc.model.Chapter
import java.sql.Connection
import java.sql.ResultSet

/**
 * `chapter` 表的读写。
 *
 * ### 这里有两套「章节 ID」，别搞混
 *
 * - **数据库行号**（`id` 列）：本后端自己用，前端拿到的、下载时传回来的都是它。
 *   自增，值形如 1、2、3。
 * - **图源的合成 ID**（`parser_id` + `source_comic` 列）：图源在
 *   `parseImages(html, chapter)` 里做字符串拼接要用，值形如 5000420003。
 *
 * 转换只发生在两个点上：[readForParser]（出去时换成合成值）和 [replaceForComic]（进来时存下合成值）。
 * 原因见 [ParserIds] 的注释——简单说，拿行号去顶替合成值，会让 40 多个图源的下载路径当场挂掉。
 */
class ChapterDao(private val db: Database) {

    // ---------------------------------------------------------------- 查询

    /**
     * 某本漫画的章节列表，顺序与图源给出的一致。
     *
     * 按 `ordinal` 而不是 `id` 排序：`id` 是插入顺序，重新解析时已存在的章节不会换 id，
     * 如果图源调整了章节顺序，按 `id` 排就会错乱。`ordinal` 每次解析都重写。
     */
    fun listForComic(comicId: Long): List<Chapter> = db.read { conn ->
        conn.prepareStatement("SELECT $COLUMNS FROM chapter WHERE comic_id = ? ORDER BY ordinal, id")
            .use { ps ->
                ps.setLong(1, comicId)
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) read(rs) else null }.toList()
                }
            }
    }

    fun load(id: Long): Chapter? = db.read { conn ->
        conn.prepareStatement("SELECT $COLUMNS FROM chapter WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }
    }

    /**
     * 加载一份**交给图源使用**的章节对象。
     *
     * 与 [load] 的唯一区别：`id` 返回的是图源的合成 ID 而不是数据库行号，
     * 并还原 `sourceComic`。`parseImages(html, chapter)` 依赖这两者。
     */
    fun loadForParser(id: Long): Chapter? = db.read { conn -> loadForParser(conn, id) }

    fun find(comicId: Long, path: String): Chapter? = db.read { conn ->
        conn.prepareStatement("SELECT $COLUMNS FROM chapter WHERE comic_id = ? AND path = ?").use { ps ->
            ps.setLong(1, comicId)
            ps.setString(2, path)
            ps.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 把一次 `parseChapter` 的结果落库，返回带数据库行号的章节列表。
     *
     * 按 `(comic_id, path)` 做 upsert，**不删除**这次没返回的章节——图源临时抽风少返回一章，
     * 不应该把它连同已经下好的 CBZ 一起抹掉。
     *
     * `page_count` 不在更新列里：它是下载时才知道的信息，重新解析章节列表不该把它清掉。
     */
    fun replaceForComic(comicId: Long, sourceComic: Long, chapters: List<Chapter>): List<Chapter> {
        if (chapters.isEmpty()) return emptyList()

        db.write { conn ->
            conn.prepareStatement(
                """
                INSERT INTO chapter
                  (comic_id, parser_id, source_comic, title, path, source_group, page_count, ordinal)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                ON CONFLICT(comic_id, path) DO UPDATE SET
                  parser_id    = excluded.parser_id,
                  source_comic = excluded.source_comic,
                  title        = excluded.title,
                  source_group = excluded.source_group,
                  ordinal      = excluded.ordinal
                """
            ).use { ps ->
                chapters.forEachIndexed { index, chapter ->
                    ps.setLong(1, comicId)
                    ps.setLong(2, chapter.id ?: ParserIds.chapterId(sourceComic, index))
                    ps.setLong(3, sourceComic)
                    ps.setString(4, chapter.title ?: "")
                    ps.setString(5, requireNotNull(chapter.path) { "章节缺少 path，无法落库：${chapter.title}" })
                    ps.setString(6, chapter.sourceGroup)
                    ps.setInt(7, index)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        return listForComic(comicId)
    }

    /** 下载完成后回填实际页数。 */
    fun setPageCount(id: Long, count: Int) = db.write { conn ->
        conn.prepareStatement("UPDATE chapter SET page_count = ? WHERE id = ?").use { ps ->
            ps.setInt(1, count)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
        Unit
    }

    fun delete(id: Long) = db.write { conn ->
        conn.prepareStatement("DELETE FROM chapter WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
        Unit
    }

    // ---------------------------------------------------------------- 内部

    private fun loadForParser(conn: Connection, id: Long): Chapter? =
        conn.prepareStatement("SELECT $COLUMNS FROM chapter WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) readForParser(rs) else null }
        }

    /** 数据库行号版本。 */
    private fun read(rs: ResultSet): Chapter = Chapter().apply {
        id = rs.getLong("id")
        sourceComic = rs.getLong("source_comic")
        title = rs.getString("title")
        path = rs.getString("path")
        sourceGroup = rs.getString("source_group")
        count = rs.getInt("page_count")
    }

    /** 图源版本：`id` 换成合成值。 */
    private fun readForParser(rs: ResultSet): Chapter = Chapter().apply {
        id = rs.getLong("parser_id")
        sourceComic = rs.getLong("source_comic")
        title = rs.getString("title")
        path = rs.getString("path")
        sourceGroup = rs.getString("source_group")
        count = rs.getInt("page_count")
    }

    private companion object {
        const val COLUMNS =
            "id, comic_id, parser_id, source_comic, title, path, source_group, page_count, ordinal"
    }
}
