package com.haleydu.cimoc.backend

import com.haleydu.cimoc.backend.download.DownloadLayout
import com.haleydu.cimoc.backend.download.DownloadManager
import com.haleydu.cimoc.backend.engine.ComicEngine
import com.haleydu.cimoc.backend.engine.FavoriteStore
import com.haleydu.cimoc.backend.engine.HistoryStore
import com.haleydu.cimoc.backend.engine.SearchEngine
import com.haleydu.cimoc.backend.engine.UpdateChecker
import com.haleydu.cimoc.backend.http.CoverLoader
import com.haleydu.cimoc.backend.http.HttpClientFactory
import com.haleydu.cimoc.backend.http.HttpEngine
import com.haleydu.cimoc.backend.source.ParserFactory
import com.haleydu.cimoc.backend.source.SourceInfo
import com.haleydu.cimoc.backend.source.SourceRegistry
import com.haleydu.cimoc.backend.storage.AppSettings
import com.haleydu.cimoc.backend.storage.Database
import com.haleydu.cimoc.backend.storage.Defaults
import com.haleydu.cimoc.backend.storage.DetailSnapshotCache
import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.backend.storage.Library
import com.haleydu.cimoc.core.CimocCore
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * 后端门面。前端的入口就是它——[open] 一次，拿到各子系统；退出时 [close]。
 *
 * ### 为什么要有这一层
 *
 * cimoc-core 里那 40 个图源类仍然通过 `CimocCore.httpClient()` / `CimocCore.settings()`
 * 这两个**静态**方法取依赖（`MangaParser` 的构造函数只收 `Source`，没有携带依赖的位置）。
 * 改造成实例注入要动 40 个文件，不在本次范围。所以由本类在启动时调一次
 * [CimocCore.install]，把进程内的 HTTP 客户端与配置装进去。
 *
 * 由此产生一条**必须遵守的次序**：`install` 必须在任何解析器被构造之前完成。
 * [open] 内部保证了这一点，调用方只要别再自己 `new` 解析器就没问题。
 *
 * 单进程单配置。要同时跑两套互不干扰的配置（比如测试），需要先做实例注入的收尾工作。
 */
class CimocBackend private constructor(
    private val database: Database,
    /** 配置读写，同时也是喂给图源的 `CimocCore.Settings`。 */
    val settings: AppSettings,
    /** 收藏夹与章节列表。 */
    val books: Library,
    /** 收藏夹门面：数据库那份 + 简介/章节目录的 JSON 快照，两个存储一起维护。 */
    val favorites: FavoriteStore,
    /** 浏览记录：没收藏的漫画访问过就存一份详情快照，详情页优先读它。 */
    val history: HistoryStore,
    /** 图源注册表（反射扫描的结果）。 */
    val registry: SourceRegistry,
    /** 按 type 取解析器、读写图源开关。 */
    val parsers: ParserFactory,
    /** 多图源并发搜索。 */
    val search: SearchEngine,
    /** 详情 + 章节列表。 */
    val comic: ComicEngine,
    /** 收藏夹更新检查。 */
    val update: UpdateChecker,
    /** 下载队列。 */
    val download: DownloadManager,
    /** 下载目录布局。 */
    val layout: DownloadLayout,
    /** 封面取用与磁盘缓存。列表界面用它，缓存对调用方透明。 */
    val covers: CoverLoader,
) : Closeable {

    /** 全部图源，含启用状态。设置界面用。 */
    fun sources(): List<SourceInfo> = parsers.listSources()

    /** 启用中的图源。 */
    fun enabledSources(): List<SourceInfo> = parsers.enabledSources()

    /** 下载根目录。 */
    fun storageRoot(): Path = layout.root

    /**
     * 关闭。先停下载再关库——反过来会让正在写的任务撞上已关闭的连接。
     */
    override fun close() {
        runCatching { download.close() }
        database.close()
    }

    companion object {

        const val VERSION: String = "0.1.0"

        /**
         * @param database     SQLite 文件路径。父目录不存在会自动创建。
         * @param storageRoot  下载根目录。null 表示用配置项 `storage.root`；
         *                     那一项也是空的话，退到数据库同级的 `download/`。
         * @param classLoader  反射扫描图源用的类加载器。默认可直接用于普通 jar / app-image；
         *                     前端如果用了自定义类加载器（如插件化），从这里传进来。
         * @param autoResume   启动时是否把上次未完成的任务自动重新排队。
         */
        fun open(
            database: Path,
            storageRoot: Path? = null,
            classLoader: ClassLoader? = null,
            autoResume: Boolean = false,
        ): CimocBackend {
            val db = Database.open(database)
            try {
                val settings = AppSettings(db)

                // ---- HTTP。TLS 策略只在启动时读一次：改这个开关需要重开后端，
                //      运行期换客户端意味着已建立的连接、连接池全部作废，得不偿失。
                val strictTls = settings.getBoolean(Keys.HTTP_STRICT_TLS, Defaults.HTTP_STRICT_TLS)
                val client = HttpClientFactory.create(strictTls)

                // 必须在构造任何解析器之前
                CimocCore.install(client, settings)

                val books = Library(db)
                val registry = SourceRegistry.scan(
                    classLoader ?: CimocBackend::class.java.classLoader
                )
                val parsers = ParserFactory(registry, books.sources, settings)
                val http = HttpEngine(client)

                val root = resolveStorageRoot(database, storageRoot, settings)
                Files.createDirectories(root)
                val layout = DownloadLayout(root) { type ->
                    parsers.title(type) ?: "来源$type"
                }

                val download = DownloadManager(registry, parsers, http, books, settings, layout)
                download.recover(autoResume)

                return CimocBackend(
                    database = db,
                    settings = settings,
                    books = books,
                    favorites = FavoriteStore(
                        books,
                        DetailSnapshotCache(root.resolve(DetailSnapshotCache.FAVORITE_DIR)),
                    ),
                    history = HistoryStore(
                        books,
                        DetailSnapshotCache(root.resolve(DetailSnapshotCache.HISTORY_DIR)),
                    ),
                    registry = registry,
                    parsers = parsers,
                    search = SearchEngine(registry, parsers, http, settings),
                    comic = ComicEngine(registry, parsers, http, books),
                    update = UpdateChecker(registry, parsers, http, books, settings),
                    download = download,
                    layout = layout,
                    // 封面缓存放在下载根下，与 `.partial` 同一层级
                    covers = CoverLoader(http, root.resolve(CoverLoader.DIR), parsers::parserOrNull),
                )
            } catch (e: Throwable) {
                runCatching { db.close() }
                throw e
            }
        }

        private fun resolveStorageRoot(
            database: Path,
            explicit: Path?,
            settings: AppSettings,
        ): Path {
            if (explicit != null) return explicit.toAbsolutePath()
            val configured = settings.raw(Keys.STORAGE_ROOT)?.takeIf { it.isNotBlank() }
            if (configured != null) return Path.of(configured).toAbsolutePath()
            val beside = database.toAbsolutePath().parent ?: Path.of(".")
            return beside.resolve("download")
        }
    }
}
