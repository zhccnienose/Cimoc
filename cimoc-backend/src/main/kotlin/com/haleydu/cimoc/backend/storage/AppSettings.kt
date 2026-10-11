package com.haleydu.cimoc.backend.storage

import com.haleydu.cimoc.core.CimocCore

/**
 * 配置项 key。
 *
 * 命名与 Android 版的 `PreferenceManager` 常量不同，因为那份配置里绝大多数项是阅读器
 * 专属的（翻页方向、按键映射、亮度……），在「不做阅读器」的桌面端没有意义。
 * 这里只保留后端真正会读的那些。
 */
object Keys {
    /** 下载并发数。 */
    const val DOWNLOAD_THREAD = "download.thread"

    /** 上次自动检查收藏更新的时间戳（毫秒）。 */
    const val CHECK_UPDATE_LAST = "check.update.last"

    /** 是否启用「每天最多自动检查一次」的门槛。 */
    const val CHECK_UPDATE_DAILY = "check.update.daily"

    /**
     * 是否启用严格的 TLS 证书校验。
     *
     * **默认 false**，此时客户端不验证服务器证书——这与原 Android 版行为一致，
     * 但意味着下载内容可被中间人篡改且无法察觉。见 [com.haleydu.cimoc.backend.http.HttpClientFactory]。
     */
    const val HTTP_STRICT_TLS = "http.strict_tls"

    /** 下载根目录。空串表示用默认位置。 */
    const val STORAGE_ROOT = "storage.root"

    /** 搜索结果之间的随机限速上限（毫秒），沿用原版 `Thread.sleep(random.nextInt(200))` 的意图。 */
    const val SEARCH_RATE_LIMIT_MS = "search.rate_limit_ms"

    /** 单张图片的下载重试次数。原版是 20，过于激进。 */
    const val DOWNLOAD_RETRY = "download.retry"

    /**
     * 收藏页里漫画标题的字号（sp）。
     *
     * 这一项**只有前端会读**（`ui/FavoritesPage.kt`），后端不认识它；放在这里是为了让
     * 所有配置项集中在一处，导出的设置文件也不必特意绕过它。
     */
    const val FAVORITE_TITLE_SIZE = "ui.favorite.title_size"

    // ---- 以下 key 由图源类自己读取，原样沿用 Android 版的字符串值，不做映射。
    //      改这些值等于改变图源行为，改动前先看对应图源类的实现。

    /** 见 `source/HHAAZZ.java`。默认空串，不配置该图源必然失败。 */
    const val HHAAZZ_BASEURL = "pref_hhaazz_baseurl"

    /** 见 `source/HHAAZZ.java`。 */
    const val HHAAZZ_SW = "pref_hhaazz_sw"
}

object Defaults {
    const val DOWNLOAD_THREAD = 2
    const val CHECK_UPDATE_DAILY = true
    const val HTTP_STRICT_TLS = false
    const val STORAGE_ROOT = ""
    const val SEARCH_RATE_LIMIT_MS = 200
    const val DOWNLOAD_RETRY = 4

    /** 14sp 正好是 Material 2 里 `body2` 的字号，即收藏页改之前的样子。 */
    const val FAVORITE_TITLE_SIZE = 14
}

/**
 * 配置读写，落在 `setting` 表上。
 *
 * 同时实现 [CimocCore.Settings]——42 个图源类通过 `MangaParser.settings()` 拿到它。
 * 那是个只有 `getString(key, defaultValue)` 的窄接口（原 Android 版的 `PreferenceManager`
 * 在解析层只被用到这一个方法），所以这里直接用同一份存储喂给它，不需要再套一层。
 *
 * 内存里带缓存：图源会在每次解析时读配置（`HHAAZZ` 每构造一次就读两次），
 * 每次都查库没必要。写操作同步更新缓存。
 */
class AppSettings(private val db: Database) : CimocCore.Settings {

    private val cache = HashMap<String, String?>()

    init {
        db.read { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT key, value FROM setting").use { rs ->
                    while (rs.next()) {
                        cache[rs.getString(1)] = rs.getString(2)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- CimocCore.Settings

    override fun getString(key: String, defaultValue: String): String =
        raw(key) ?: defaultValue

    override fun toString(): String = "AppSettings(${cache.size} 项)"

    // ---------------------------------------------------------------- 原始读写

    /** 返回 null 表示没设置过（与「设置成空串」区分开）。 */
    fun raw(key: String): String? = cache[key]

    fun put(key: String, value: String?) {
        cache[key] = value
        db.write { conn ->
            if (value == null) {
                conn.prepareStatement("DELETE FROM setting WHERE key = ?").use { ps ->
                    ps.setString(1, key)
                    ps.executeUpdate()
                }
            } else {
                conn.prepareStatement(
                    "INSERT INTO setting (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
                ).use { ps ->
                    ps.setString(1, key)
                    ps.setString(2, value)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun getLong(key: String, defaultValue: Long): Long =
        raw(key)?.toLongOrNull() ?: defaultValue

    fun put(key: String, value: Long) = put(key, value.toString())

    fun getInt(key: String, defaultValue: Int): Int =
        raw(key)?.toIntOrNull() ?: defaultValue

    fun put(key: String, value: Int) = put(key, value.toString())

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        raw(key)?.toBooleanStrictOrNull() ?: defaultValue

    fun put(key: String, value: Boolean) = put(key, value.toString())

    /** 一次性列出全部配置，便于前端渲染设置页。 */
    fun snapshot(): Map<String, String?> = cache.toMap()
}
