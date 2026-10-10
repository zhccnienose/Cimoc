package com.haleydu.cimoc.backend.storage.dao

import com.haleydu.cimoc.backend.download.DownloadState
import com.haleydu.cimoc.backend.download.DownloadTask
import com.haleydu.cimoc.backend.storage.Database
import java.sql.ResultSet

/**
 * `download_task` 表的读写。一行 = 一个章节的下载。
 *
 * 键是 `chapter_id`（表上有 `UNIQUE`），不是自增主键：一个章节只可能有一个任务，
 * 重复入队应当是「覆盖」而不是「再来一条」。对外接口因此大多按 `chapterId` 寻址。
 */
class DownloadTaskDao(private val db: Database) {

    fun loadByChapter(chapterId: Long): DownloadTask? = db.read { conn ->
        conn.prepareStatement("SELECT $COLUMNS FROM download_task WHERE chapter_id = ?").use { ps ->
            ps.setLong(1, chapterId)
            ps.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }
    }

    fun listByComic(comicId: Long): List<DownloadTask> = db.read { conn ->
        conn.prepareStatement("SELECT $COLUMNS FROM download_task WHERE comic_id = ? ORDER BY id").use { ps ->
            ps.setLong(1, comicId)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }
    }

    /** 全部任务，新建的在前。 */
    fun listAll(): List<DownloadTask> = db.read { conn ->
        conn.prepareStatement("SELECT $COLUMNS FROM download_task ORDER BY id DESC").use { ps ->
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }
    }

    /**
     * 未完成的任务（等待 / 解析中 / 下载中 / 暂停 / 出错）。
     *
     * 进程启动时用它恢复队列：这些是「用户还没拿到成品」的章节。
     */
    fun listUnfinished(): List<DownloadTask> = db.read { conn ->
        conn.prepareStatement(
            "SELECT $COLUMNS FROM download_task WHERE state NOT IN (?, ?) ORDER BY id"
        ).use { ps ->
            ps.setInt(1, DownloadState.FINISH.code)
            ps.setInt(2, DownloadState.ERROR.code)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
        }
    }

    fun countUnfinished(): Int = db.read { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM download_task WHERE state NOT IN (?, ?)"
        ).use { ps ->
            ps.setInt(1, DownloadState.FINISH.code)
            ps.setInt(2, DownloadState.ERROR.code)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 入队或覆盖。入队时进度归零——重下一个已有任务应当是重新开始，
     * 而不是接上次的进度（分页图片地址可能已经变了）。
     */
    fun enqueue(comicId: Long, chapterId: Long, total: Int = 0): DownloadTask = db.write { conn ->
        conn.prepareStatement(
            """
            INSERT INTO download_task (comic_id, chapter_id, state, progress, total, error, file_path, updated_at)
            VALUES (?, ?, ?, 0, ?, NULL, NULL, ?)
            ON CONFLICT(chapter_id) DO UPDATE SET
              state      = excluded.state,
              progress   = 0,
              total      = excluded.total,
              error      = NULL,
              file_path  = NULL,
              updated_at = excluded.updated_at
            """
        ).use { ps ->
            ps.setLong(1, comicId)
            ps.setLong(2, chapterId)
            ps.setInt(3, DownloadState.WAIT.code)
            ps.setInt(4, total)
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }
        requireNotNull(loadByChapter(conn, chapterId)) { "入队后读不到下载任务：chapter=$chapterId" }
    }

    /**
     * 更新任务状态。`filePath` 传 null 表示保持原值——只有写成 CBZ 那一刻才需要设置它，
     * 中间的进度回调不该把它抹掉。
     */
    fun updateState(
        chapterId: Long,
        state: DownloadState,
        progress: Int? = null,
        total: Int? = null,
        error: String? = null,
        filePath: String? = null,
    ) = db.write { conn ->
        conn.prepareStatement(
            """
            UPDATE download_task SET
              state      = ?,
              progress   = COALESCE(?, progress),
              total      = COALESCE(?, total),
              error      = CASE WHEN ? = 1 THEN NULL ELSE COALESCE(?, error) END,
              file_path  = COALESCE(?, file_path),
              updated_at = ?
            WHERE chapter_id = ?
            """
        ).use { ps ->
            ps.setInt(1, state.code)
            ps.setObject(2, progress)
            ps.setObject(3, total)
            // 进非 ERROR 态时顺手清掉上次的错误信息，免得前端看到「下载中」配一条旧报错
            ps.setInt(4, if (state == DownloadState.ERROR) 0 else 1)
            ps.setString(5, error)
            ps.setString(6, filePath)
            ps.setLong(7, System.currentTimeMillis())
            ps.setLong(8, chapterId)
            ps.executeUpdate()
        }
        Unit
    }

    /**
     * 把上次进程留下的「进行中」任务打回暂停。
     *
     * 启动时调用。`DOING` / `PARSE` / `WAIT` 都表示「有个协程正在跑」，而那个协程随进程一起没了。
     * 不打回 `PAUSE` 的话，前端会看到永远不动的「下载中」，任务队列也永远认为它还在跑。
     * `ERROR` 不动：用户可能还没看到那条报错。
     *
     * @return 被改动的任务数
     */
    fun markInterruptedAsPaused(): Int = db.write { conn ->
        conn.prepareStatement(
            "UPDATE download_task SET state = ?, updated_at = ? WHERE state IN (?, ?, ?)"
        ).use { ps ->
            ps.setInt(1, DownloadState.PAUSE.code)
            ps.setLong(2, System.currentTimeMillis())
            ps.setInt(3, DownloadState.DOING.code)
            ps.setInt(4, DownloadState.PARSE.code)
            ps.setInt(5, DownloadState.WAIT.code)
            ps.executeUpdate()
        }
    }

    /** 删任务不动 CBZ 文件——文件是用户的，不该由一条数据库记录的删除来决定去向。 */
    fun delete(chapterId: Long) = db.write { conn ->
        conn.prepareStatement("DELETE FROM download_task WHERE chapter_id = ?").use { ps ->
            ps.setLong(1, chapterId)
            ps.executeUpdate()
        }
        Unit
    }

    fun deleteByComic(comicId: Long) = db.write { conn ->
        conn.prepareStatement("DELETE FROM download_task WHERE comic_id = ?").use { ps ->
            ps.setLong(1, comicId)
            ps.executeUpdate()
        }
        Unit
    }

    // ---------------------------------------------------------------- 内部

    private fun loadByChapter(conn: java.sql.Connection, chapterId: Long): DownloadTask? =
        conn.prepareStatement("SELECT $COLUMNS FROM download_task WHERE chapter_id = ?").use { ps ->
            ps.setLong(1, chapterId)
            ps.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
        }

    private fun read(rs: ResultSet): DownloadTask = DownloadTask(
        id = rs.getLong("id"),
        comicId = rs.getLong("comic_id"),
        chapterId = rs.getLong("chapter_id"),
        state = DownloadState.of(rs.getInt("state")),
        progress = rs.getInt("progress"),
        total = rs.getInt("total"),
        error = rs.getString("error"),
        filePath = rs.getString("file_path"),
        updatedAt = rs.getLong("updated_at"),
    )

    private companion object {
        const val COLUMNS =
            "id, comic_id, chapter_id, state, progress, total, error, file_path, updated_at"
    }
}
