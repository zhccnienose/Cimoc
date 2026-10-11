package com.haleydu.cimoc.backend.source

import com.haleydu.cimoc.backend.storage.AppSettings
import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.backend.storage.dao.SourceConfigDao
import com.haleydu.cimoc.model.Source
import com.haleydu.cimoc.parser.MangaParser

/**
 * 按图源 type 造解析器。取代 Android 版 `SourceManager.getParser` 的 43 分支 switch。
 *
 * ### 为什么不做实例缓存
 *
 * Android 版把解析器按 type 缓存在一个 `SparseArray` 里复用。这里**故意不缓存**，
 * 每次调用都新建一个。
 *
 * 原因是至少 8 个解析器把「请求参数」放在实例字段里跨方法传：
 * `ChuiXue._cid/_path`、`MangaBZ._cid/_path`、`Manhuatai._path`、`YYLS._cid`、
 * `IKanman.referer`、`JMTT.imgpath`、`WebtoonDongManManHua.k`、`HHAAZZ._path`——
 * 都是「`getImagesRequest` 里赋值、`parseImages` 里读取」。复用一个实例并并发下载，
 * 两个章节会互相把对方的 `_path` 覆盖掉，下错图且不报错。
 *
 * 构造解析器的开销只有 `init(source, category)`，没有任何 I/O（`HHAAZZ` 会读两个配置项，
 * 走的是内存缓存），所以放弃缓存几乎不花钱。
 *
 * ### 前置条件
 *
 * 部分解析器（如 `HHAAZZ`）在**构造函数**里读 `settings()`，因此
 * [com.haleydu.cimoc.core.CimocCore.install] 必须在第一个解析器被创建之前调用过。
 * `CimocBackend.open()` 负责这个次序。
 */
class ParserFactory(
    private val registry: SourceRegistry,
    private val config: SourceConfigDao,
    /**
     * 图源配置。
     *
     * 声明成 [AppSettings] 而不是解析器拿到的那个窄接口 `CimocCore.Settings`（只有
     * `getString`）：图源顺序要**写**回配置，窄接口写不了。解析器侧不受影响——它们
     * 仍然只通过 `MangaParser.settings()` 拿到窄接口，宽的是本类自己。
     */
    private val settings: AppSettings,
) {

    /**
     * 造一个解析器。
     *
     * @throws IllegalArgumentException type 未知，或该图源类没有可用的构造函数
     */
    fun parser(type: Int): MangaParser =
        parserOrNull(type) ?: throw IllegalArgumentException(
            registry.byType(type)?.let { "图源 ${it.title}(type=$type) 无法构造：缺少 (Source) 或无参构造函数" }
                ?: "未知的图源 type=$type（不在注册表中，可能来自已被移除的图源）"
        )

    /**
     * 同 [parser]，但 type 未知时返回 null。
     *
     * 用于遍历收藏夹这类场景：数据库里可能留有已下架图源的条目，
     * 应当跳过并提示，而不是让整个列表操作失败。
     */
    fun parserOrNull(type: Int): MangaParser? {
        val info = registry.byType(type) ?: return null
        val source = info.sourceOf(enabled(type))
        val clazz = info.parserClass

        // 绝大多数图源是 `(Source)` 构造
        runCatching { clazz.getConstructor(Source::class.java).newInstance(source) }
            .onSuccess { return it }

        // `Locality` / `Null` 是无参构造。它们不在注册表里（没有 getDefaultSource），
        // 这里留着是为了将来出现「无参 + 有 getDefaultSource」的图源不必改代码。
        runCatching { clazz.getConstructor().newInstance() }
            .onSuccess { return it }

        return null
    }

    // ---------------------------------------------------------------- 开关

    /**
     * 图源当前是否启用。用户没设置过就取图源自己声明的默认值
     * （`getDefaultSource().getEnable()`，绝大多数是 true）。
     */
    fun enabled(type: Int): Boolean {
        val info = registry.byType(type) ?: return false
        return config.isEnabled(type, info.defaultEnabled)
    }

    fun setEnabled(type: Int, enabled: Boolean) {
        require(registry.byType(type) != null) { "未知的图源 type=$type" }
        config.setEnabled(type, enabled)
    }

    /**
     * 启用中的图源清单，多图源搜索用。顺序同 [setOrder]。
     *
     * 这个顺序**不影响搜索结果的排列**：`SearchEngine` 是并发跑所有源、谁先返回谁先到，
     * 界面收到的是交错的事件流。这里跟着排序只是让「图源顺序」在整条链路上是同一个概念。
     */
    fun enabledSources(): List<SourceInfo> =
        byOrder(registry.all.filter { enabled(it.type) })

    /** 全部图源及其当前开关，设置界面用。顺序同 [setOrder]。 */
    fun listSources(): List<SourceInfo> = byOrder(registry.all)

    /** 图源标题。未知 type 返回 null。 */
    fun title(type: Int): String? = registry.byType(type)?.title

    // ---------------------------------------------------------------- 顺序

    /**
     * 用户在图源页拖出来的顺序。
     *
     * 返回空列表表示没设置过，调用方应当退回 type 升序（即 [SourceRegistry] 的默认顺序）。
     *
     * 只读内存缓存（[AppSettings.raw]），不阻塞，可以在组合里直接读。
     */
    fun order(): List<Int> = settings.getString(Keys.SOURCE_ORDER, "")
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }

    /**
     * 保存顺序。**传空列表表示恢复默认**——[AppSettings.put] 收到 null 是删掉这一行，
     * 而不是存一个空串，所以「从没设置过」和「拖乱了想回到默认」是同一个状态。
     *
     * 阻塞（写 SQLite），前端请在 IO 上调用。
     */
    fun setOrder(types: List<Int>) {
        // 顺手把不认识的 type 和重复项滤掉：写进去的字符串是给 [byOrder] 读的，
        // 存垃圾进去只会让它越来越长，没有任何意义
        val clean = types.distinct().filter { registry.byType(it) != null }
        settings.put(
            Keys.SOURCE_ORDER,
            clean.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
    }

    /**
     * 按用户顺序重排。
     *
     * 分两段：先按存的顺序取出还存在的图源（存过的重复 type 只认第一次，已下架的跳过），
     * 再把没存过的接在后面。**尾巴不用再排序**——[registry.all] 本身就是 type 升序
     * （`SourceRegistry.scan` 里排过），新图源因此总是出现在末尾。
     */
    private fun byOrder(all: List<SourceInfo>): List<SourceInfo> {
        val saved = order()
        if (saved.isEmpty()) return all

        val byType = all.associateBy { it.type }
        val head = ArrayList<SourceInfo>(all.size)
        val placed = HashSet<Int>()
        for (type in saved) {
            val info = byType[type] ?: continue
            if (placed.add(type)) head += info
        }
        return head + all.filter { it.type !in placed }
    }

    // ------------------------------------------------------------ 配置缺失

    /**
     * 图源缺了哪项必修配置。null 表示没发现已知问题。
     *
     * 现在只有 `HHAAZZ`(type=2)：它对站址**没有默认值**（`baseUrl` 初值是空串，构造时从配置里读），
     * 用户在设置里不填的话，它只要被启用，每次搜索就稳定贡献一条 `SourceFailed`。
     * 图源页据此把它标出来——否则用户只能对着一句「连接失败」猜是网络问题还是没配置。
     *
     * 这张表**暂时是硬编码的**：图源类目前没有「声明自己需要哪些配置项」的位置
     * （`getDefaultSource()` 只带 title / type / enable 三样）。等 `MangaParser` 上加了这样的
     * 声明，这里换成反射取值即可，本方法的签名与调用方都不用动。
     *
     * 阻塞（查配置走 SQLite 缓存，通常是内存命中）。前端请在 IO 上调用。
     */
    fun unavailableReason(type: Int): String? {
        val required = REQUIRED_CONFIG[type] ?: return null
        val missing = required.filter { settings.getString(it, "").isBlank() }
        return if (missing.isEmpty()) null else "缺少配置 ${missing.joinToString("、")}"
    }
}

/** `HHAAZZ.TYPE`。 */
private const val HHAAZZ_TYPE = 2

/**
 * 「会读配置、且不填就不能用」的图源：`type -> 配置项 key`。
 *
 * type 写死而不是引 `HHAAZZ.TYPE`——本模块按设计只通过反射认识图源，
 * 不 import 任何一个图源类（见 [SourceRegistry] 的说明）。
 */
private val REQUIRED_CONFIG: Map<Int, List<String>> = mapOf(
    HHAAZZ_TYPE to listOf(Keys.HHAAZZ_BASEURL),
)
