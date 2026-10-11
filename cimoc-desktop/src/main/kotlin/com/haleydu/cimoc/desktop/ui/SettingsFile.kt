package com.haleydu.cimoc.desktop.ui

import com.haleydu.cimoc.backend.storage.AppSettings
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 导入的结果。
 *
 * 三个计数分开报，因为用户要做的事完全不同：`invalid` 是文件里的值不合法（要么改文件、
 * 要么不管），`unknown` 通常意味着文件来自更新的版本（那一项本程序不认识），
 * `applied` 才是真的写进去了几项。
 */
data class SettingsImport(
    val applied: Int,
    val invalid: Int,
    val unknown: Int,
)

/**
 * 设置配置文件的读写。
 *
 * ### 只认 [SettingsSpec] 里的项
 *
 * 导出只导出注册表里的项，导入也只写注册表里的项。**注册表之外的 key 一律不碰**——
 * 图源配置（`pref_hhaazz_baseurl`）和内部时间戳（`check.update.last`）都在这张表外，
 * 所以导入一个文件不会顺手把它们抹掉。这是「部分导入」而不是「整体替换」，代价是
 * 导出的文件不是一份完整的库快照，收益是导入永远不会毁掉用户没在文件里看到的东西。
 *
 * ### 文件长什么样
 *
 * ```json
 * {
 *   "format": "cimoc-settings",
 *   "version": 1,
 *   "exportedAt": "2026-10-11T12:34:56.789",
 *   "values": { "download.thread": 2, "storage.root": "D:\\comics" }
 * }
 * ```
 *
 * `values` 里是 JSON 原生类型，缩进过，直接拿文本编辑器改也行。为了照顾手写的文件，
 * 顶层没有 `values` 时把**整个对象**当成键值对（这时 `format` 那一格会被当成未知 key 忽略）。
 *
 * 没设置过的项导出的是**当前生效的默认值**，不是缺项：文件里每一项都有值，用户打开
 * 就能看懂这个程序有哪些可调的。
 */
object SettingsFile {

    /** 文件里的 `format` 字段。导入时用它挡掉「随便一个 JSON」。 */
    private const val FORMAT = "cimoc-settings"

    /**
     * 文件格式版本。
     *
     * **加字段不用动它**——读的时候是按 key 逐个认的，不认识的 key 计入 `unknown`。
     * 只有某一项的**含义**变了、旧文件里的值会被理解成另一个意思时才 +1。
     */
    private const val VERSION = 1

    const val DEFAULT_FILE_NAME = "cimoc-settings.json"

    /** 写文件，返回写了几项。 */
    fun exportTo(settings: AppSettings, file: Path): Int {
        Files.writeString(file, exportJson(settings), StandardCharsets.UTF_8)
        return SettingsSpec.fields.size
    }

    fun importFrom(settings: AppSettings, file: Path): SettingsImport =
        importJson(settings, Files.readString(file, StandardCharsets.UTF_8))

    fun exportJson(settings: AppSettings): String {
        val values = JSONObject()
        for (field in SettingsSpec.fields) {
            // raw() 为 null（从没设置过）时 toExternal 给的是默认值，所以文件里每一项都有值
            values.put(field.key, field.toExternal(settings.raw(field.key)))
        }

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            put("note", "可以直接用文本编辑器改，改完在「设置」页导入。认不出来的 key 会被忽略。")
            put("values", values)
        }.toString(2)
    }

    /**
     * 解析并写入。
     *
     * 文件本身读不出来（不是合法 JSON、不是本程序的文件）时**抛异常**，让调用方报错；
     * 单个项有问题则跳过并计数，不中断整次导入——一个拼错的数字不该让另外 6 项白导。
     */
    fun importJson(settings: AppSettings, text: String): SettingsImport {
        val root = JSONObject(text)

        val format = root.optString("format", "")
        require(format.isEmpty() || format == FORMAT) {
            "不是 Cimoc 的设置文件（format=$format）"
        }

        // 有 values 就用它；没有就把整个对象当键值对，手写的精简文件也能用
        val values = root.optJSONObject("values") ?: root

        var applied = 0
        var invalid = 0
        var unknown = 0

        for (key in values.keys()) {
            val field = SettingsSpec.byKey(key)
            if (field == null) {
                unknown++
                continue
            }
            val stored = field.fromExternal(textOf(values.opt(key)))
            if (stored == null) {
                invalid++
                continue
            }
            settings.put(key, stored)
            applied++
        }

        return SettingsImport(applied, invalid, unknown)
    }

    /** JSON 的一格 → 文本。Kotlin 的 null 和 JSON 的 null 都算「这一格没有值」。 */
    private fun textOf(raw: Any?): String? =
        if (raw == null || raw === JSONObject.NULL) null else raw.toString()
}
