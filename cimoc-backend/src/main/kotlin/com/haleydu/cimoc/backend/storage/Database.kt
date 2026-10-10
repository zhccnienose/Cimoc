package com.haleydu.cimoc.backend.storage

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 连接与 schema 管理。
 *
 * <p>**并发模型：单连接 + 全局锁。** 桌面端的数据库负载极低（收藏列表、章节、下载进度），
 * 用连接池纯属过度设计；而 JDBC 的 `Connection` 本身不是线程安全的，多协程并发访问
 * 必须串行化。所以这里用一把锁把所有访问收口，调用方负责把调用放在 `Dispatchers.IO` 上。
 *
 * <p>代价是写操作之间不能并行。对这个量级的数据量没有实际影响。
 *
 * <p>与原 Android 版的区别：原版用 GreenDAO + `IdentityScopeType.None`（无一级缓存）。
 * 这里不用 ORM，DAO 直接写 SQL——原版的 `ComicManager` 本来就是这个形状，直译即可，
 * 而且能绕开 GreenDAO 那套「没有索引、没有唯一约束、全靠在应用层先查后插」的约定。
 */
class Database private constructor(
    private val connection: Connection,
    val path: Path,
) : Closeable {

    private val lock = Any()

    /** 只读查询。 */
    fun <T> read(block: (Connection) -> T): T = synchronized(lock) { block(connection) }

    /**
     * 写事务。块内抛异常则整体回滚。
     *
     * 不支持嵌套——SQLite 的 JDBC 驱动不支持保存点嵌套之外的嵌套事务，
     * 而这里也没有嵌套调用的场景。
     */
    fun <T> write(block: (Connection) -> T): T = synchronized(lock) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun close(): Unit = synchronized(lock) {
        runCatching { connection.close() }
    }

    companion object {

        /** 当前 schema 版本，写在 SQLite 的 `PRAGMA user_version` 里。 */
        const val SCHEMA_VERSION = 1

        fun open(path: Path): Database {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }

            // 显式加载驱动，不依赖 ServiceLoader——在某些打包方式（app-image / 影子 jar）下
            // META-INF/services 不一定被保留，届时 DriverManager 会静默找不到驱动。
            Class.forName("org.sqlite.JDBC")

            val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            applyPragmas(connection)
            migrate(connection)
            return Database(connection, path.toAbsolutePath())
        }

        private fun applyPragmas(connection: Connection) {
            connection.createStatement().use { statement ->
                // 外键约束默认是关的，必须每个连接显式打开，否则 REFERENCES 只是注释
                statement.execute("PRAGMA foreign_keys = ON")
                // WAL 允许读写并发，桌面端多协程访问下明显更顺
                statement.execute("PRAGMA journal_mode = WAL")
                // 下载进度写入很频繁，NORMAL 在 WAL 下已足够安全且快得多
                statement.execute("PRAGMA synchronous = NORMAL")
                // 单连接下基本用不到，但留着以防将来换成多连接
                statement.execute("PRAGMA busy_timeout = 5000")
            }
        }

        private fun migrate(connection: Connection) {
            val current = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }

            if (current > SCHEMA_VERSION) {
                throw IllegalStateException(
                    "数据库 schema 版本为 $current，高于本程序支持的 $SCHEMA_VERSION。" +
                        "请升级 cimoc-backend，或换一个数据库文件——降级读取可能丢数据。"
                )
            }
            if (current == SCHEMA_VERSION) {
                return
            }

            connection.createStatement().use { statement ->
                for (sql in if (current == 0) CREATE_TABLES else emptyList()) {
                    statement.execute(sql)
                }
                // user_version 不支持参数占位符，只能拼字符串；SCHEMA_VERSION 是常量，无注入风险
                statement.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
        }

        /**
         * 建表语句。
         *
         * 与原 Android 版的差异（都是有意为之，不是遗漏）：
         * - `comic` 补上 `UNIQUE(source, cid)`——原版只在应用层「先查后插」维持，
         *   并发下会插出重复行。
         * - `chapter.comic_id` 是**真外键**，取代原版 `Long.parseLong(source + "000" + id)`
         *   拼出来的假外键。那条规则是字符串拼接出的数字，既不能 JOIN 也不能级联。
         * - 没有 `image_url` 表：原版对它的持久化代码大多被注释掉了，是个预留缓存；
         *   这里落盘产物是 CBZ，图片清单只作为下载任务的临时状态。
         * - 没有 `tag` / `tag_ref`：标签不在本次功能清单内。
         */
        private val CREATE_TABLES = listOf(
            // 通用 KV，取代 Android 的 SharedPreferences。
            // 图源读取的 key（如 pref_hhaazz_baseurl）原样沿用，不做映射。
            """
            CREATE TABLE setting (
              key   TEXT PRIMARY KEY,
              value TEXT
            )
            """,

            // 图源清单来自代码反射，这张表**只存用户改过的开关**。
            // 表里没有记录 = 用 getDefaultSource().getEnable() 的默认值。
            """
            CREATE TABLE source_config (
              type    INTEGER PRIMARY KEY,
              enabled INTEGER NOT NULL
            )
            """,

            """
            CREATE TABLE comic (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              source      INTEGER NOT NULL,
              cid         TEXT    NOT NULL,
              title       TEXT    NOT NULL DEFAULT '',
              cover       TEXT    NOT NULL DEFAULT '',
              author      TEXT,
              intro       TEXT,
              url         TEXT,
              finish      INTEGER,
              update_at   TEXT,
              favorite_at INTEGER,
              highlight   INTEGER NOT NULL DEFAULT 0,
              created_at  INTEGER NOT NULL,
              UNIQUE (source, cid)
            )
            """,
            "CREATE INDEX idx_comic_favorite ON comic (favorite_at DESC)",
            "CREATE INDEX idx_comic_highlight ON comic (highlight)",

            """
            CREATE TABLE chapter (
              id           INTEGER PRIMARY KEY AUTOINCREMENT,
              comic_id     INTEGER NOT NULL REFERENCES comic(id) ON DELETE CASCADE,
              parser_id    INTEGER NOT NULL,
              source_comic INTEGER NOT NULL,
              title        TEXT    NOT NULL,
              path         TEXT    NOT NULL,
              source_group TEXT    NOT NULL DEFAULT '',
              page_count   INTEGER NOT NULL DEFAULT 0,
              ordinal      INTEGER NOT NULL DEFAULT 0,
              UNIQUE (comic_id, path)
            )
            """,
            "CREATE INDEX idx_chapter_comic ON chapter (comic_id)",

            """
            CREATE TABLE download_task (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              comic_id   INTEGER NOT NULL REFERENCES comic(id) ON DELETE CASCADE,
              chapter_id INTEGER NOT NULL REFERENCES chapter(id) ON DELETE CASCADE,
              state      INTEGER NOT NULL DEFAULT 0,
              progress   INTEGER NOT NULL DEFAULT 0,
              total      INTEGER NOT NULL DEFAULT 0,
              error      TEXT,
              file_path  TEXT,
              updated_at INTEGER NOT NULL,
              UNIQUE (chapter_id)
            )
            """,
        )
    }
}
