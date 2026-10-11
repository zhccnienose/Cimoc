package com.haleydu.cimoc.backend.source

import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.backend.storage.dao.SourceConfigDao
import com.haleydu.cimoc.core.CimocCore
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
 * [CimocCore.install] 必须在第一个解析器被创建之前调用过。`CimocBackend.open()` 负责这个次序。
 */
class ParserFactory(
    private val registry: SourceRegistry,
    private val config: SourceConfigDao,
    /** 只用来回答「这个图源必需的配置项填了没有」。窄接口，与解析器拿到的是同一个。 */
    private val settings: CimocCore.Settings,
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

    /** 启用中的图源清单，多图源搜索用。按 type 升序，保证结果顺序稳定。 */
    fun enabledSources(): List<SourceInfo> =
        registry.all.filter { enabled(it.type) }

    /** 全部图源及其当前开关，设置界面用。 */
    fun listSources(): List<SourceInfo> = registry.all

    /** 图源标题。未知 type 返回 null。 */
    fun title(type: Int): String? = registry.byType(type)?.title

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
