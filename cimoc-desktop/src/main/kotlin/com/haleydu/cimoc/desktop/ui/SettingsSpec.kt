package com.haleydu.cimoc.desktop.ui

import com.haleydu.cimoc.backend.download.DownloadManager
import com.haleydu.cimoc.backend.storage.Defaults
import com.haleydu.cimoc.backend.storage.Keys

/**
 * 设置项的描述表。**界面渲染、导入、导出三件事都从这张表派生。**
 *
 * ### 加一项新选项要做什么
 *
 * 1. 在 [Keys] / [Defaults] 里加 key 和默认值（后端要读它的话；纯界面的项也放那儿，
 *    理由是为了让所有配置项集中在一处，见 `Keys.FAVORITE_TITLE_SIZE` 的注释）；
 * 2. 往 [SettingsSpec.fields] 里加**一行**，挑一个合适的子类。
 *
 * 就这两步。界面按 [SettingField.group] 自动分组、自动排到对应位置，导入导出按 key
 * 自动带上——不需要再去改界面、导入、导出三个地方。
 *
 * 要加**第四种控件**（文本框、下拉框……）时：写一个新的 [SettingField] 子类，实现
 * [SettingField.toExternal] / [SettingField.fromExternal] 和它自己那三个类型化的小函数
 * （read / parse / format），再到 [SettingsPage] 的 `when (field)` 里加一个渲染分支。
 * 那个 `when` 是穷尽的，漏了编译不过——这是有意的，它保证「加了类型却没人渲染」
 * 这种错留不到上线。
 *
 * ### 每个子类为什么有三个小函数而不是一个
 *
 * - `read`：库里的文本 → 界面上的值。**宽松**，越界就夹进范围。库里存着 999 而滑块
 *   最大 24 时，界面得能显示出来，夹住比崩掉强。
 * - `parse`：外来的文本 → 值。**严格**，越界返回 null。把非法值悄悄夹一个数写进库，
 *   比拒绝它糟得多：用户以为导进去了，实际导进去的是另一个数。
 * - `format`：值 → 存进库的文本。
 *
 * 三个的作用域不同，所以没法合并成一个。
 *
 * ### 为什么值在库里是字符串
 *
 * [com.haleydu.cimoc.backend.storage.AppSettings] 存的就是字符串（`setting` 表只有
 * key / value 两列）。这里不造一套类型化的影子存储：类型只活在「读出来的那一刻」。
 */
sealed interface SettingField {

    val key: String

    /** 界面上的标题。 */
    val title: String

    /** 分组。分组的先后 = 在 [SettingsSpec.fields] 里第一次出现的先后。 */
    val group: String

    /** 标题下面那行小字：这一项到底影响什么。 */
    val detail: String

    /**
     * 改完要重开程序才生效。
     *
     * 标 true 的都是 `CimocBackend.open()` 时读一次的值（并发数、TLS 策略、下载根目录）：
     * 运行期换掉它们意味着重建连接池 / 下载 worker / 目录布局，代价远大于收益。
     */
    val restartRequired: Boolean

    /** 红字警示，null 表示没有。 */
    val warning: String?

    /**
     * 库里的文本 → 配置文件里的一格。返回 JSON 原生类型（布尔 / 数字 / 字符串），
     * 这样导出的文件是人能读、也能直接改的。
     */
    fun toExternal(text: String?): Any

    /** 配置文件里的一格 → 存进库的文本。不合法返回 null，导入时跳过并计数。 */
    fun fromExternal(raw: String?): String?
}

/** 开关。 */
data class ToggleField(
    override val key: String,
    override val title: String,
    override val group: String,
    override val detail: String,
    override val restartRequired: Boolean,
    val default: Boolean,
    override val warning: String? = null,
) : SettingField {

    fun read(text: String?): Boolean = text?.toBooleanStrictOrNull() ?: default

    /** 只认 true / false（大小写与两侧空格不管），写别的（如 "yes"）算非法。 */
    fun parse(text: String?): Boolean? = when (text?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun format(value: Boolean): String = value.toString()

    override fun toExternal(text: String?): Any = read(text)
    override fun fromExternal(raw: String?): String? = parse(raw)?.let(::format)
}

/** 数字。界面上是一个滑块——范围由这里定死，所以拖不出界。 */
data class NumberField(
    override val key: String,
    override val title: String,
    override val group: String,
    override val detail: String,
    override val restartRequired: Boolean,
    val default: Int,
    val min: Int,
    val max: Int,
    val step: Int = 1,
    /** 显示在数字后面，如 "sp"、" 毫秒"。 */
    val unit: String = "",
    override val warning: String? = null,
) : SettingField {

    fun read(text: String?): Int = (text?.toIntOrNull() ?: default).coerceIn(min, max)

    fun parse(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in min..max }

    fun format(value: Int): String = value.toString()

    /** 滑块两端的端点之间还能停几个值。0 表示连续。 */
    val steps: Int get() = ((max - min) / step - 1).coerceAtLeast(0)

    override fun toExternal(text: String?): Any = read(text)
    override fun fromExternal(raw: String?): String? = parse(raw)?.let(::format)
}

/** 目录。界面上一行当前路径 + 「选择…」「默认」两个按钮。 */
data class DirectoryField(
    override val key: String,
    override val title: String,
    override val group: String,
    override val detail: String,
    override val restartRequired: Boolean,
    override val warning: String? = null,
) : SettingField {

    /** 空串 = 用默认位置。`CimocBackend.resolveStorageRoot` 就是按「空串」判的。 */
    fun read(text: String?): String = text.orEmpty()

    /** 两侧空格去掉。空串是合法的，表示回到默认位置。 */
    fun parse(text: String?): String? = text?.trim()

    fun format(value: String): String = value

    override fun toExternal(text: String?): Any = read(text)
    override fun fromExternal(raw: String?): String? = parse(raw)?.let(::format)
}

object SettingsSpec {

    /**
     * 全部设置项，也是界面上的渲染顺序。
     *
     * 只放**全局**配置。图源专属的那些（`pref_hhaazz_baseurl` 等）不放这儿——它们归
     * 图源页那一行的「设置」按钮管，混进全局设置页会让人以为改的是所有图源。
     * `check.update.last` 同理不放：那是程序自己记的时间戳，不是给人改的。
     */
    val fields: List<SettingField> = listOf(
        DirectoryField(
            key = Keys.STORAGE_ROOT,
            title = "下载目录",
            group = "存储",
            detail = "漫画成品、封面缓存、收藏快照、浏览记录都放在这里。留空表示放在数据目录下的 download/。",
            restartRequired = true,
        ),

        NumberField(
            key = Keys.FAVORITE_TITLE_SIZE,
            title = "收藏页标题字号",
            group = "界面",
            detail = "收藏夹每格里漫画名的大小。图源名那一行不跟着变——它比书名小一号是刻意的。",
            restartRequired = false,
            default = Defaults.FAVORITE_TITLE_SIZE,
            min = 8,
            max = 24,
            unit = "sp",
        ),

        NumberField(
            key = Keys.DOWNLOAD_THREAD,
            title = "同时下载的章节数",
            group = "下载",
            detail = "同时跑几个下载任务。调大能更快，但对图源的压力成比例增加，也更容易被限速或封。",
            restartRequired = true,
            default = Defaults.DOWNLOAD_THREAD,
            min = 1,
            // 上限取后端那个常量，不另写一个数字：两处各写死一个，迟早会对不上
            max = DownloadManager.MAX_CONCURRENCY,
        ),

        NumberField(
            key = Keys.DOWNLOAD_RETRY,
            title = "单页重试次数",
            group = "下载",
            detail = "一张图下载失败后重试几次（每次之间线性退避）。原版是 20 次，过于激进。",
            restartRequired = false,
            default = Defaults.DOWNLOAD_RETRY,
            min = 1,
            max = DownloadManager.MAX_RETRY,
            unit = " 次",
        ),

        NumberField(
            key = Keys.SEARCH_RATE_LIMIT_MS,
            title = "搜索限速上限",
            group = "搜索",
            detail = "每次搜索之间的随机等待上限（在 0 到该值之间随机，沿用原版的意图）。调小搜得快，也更容易被图源当爬虫。",
            restartRequired = false,
            default = Defaults.SEARCH_RATE_LIMIT_MS,
            min = 0,
            max = 1000,
            step = 50,
            unit = " 毫秒",
        ),

        ToggleField(
            key = Keys.CHECK_UPDATE_AUTO,
            title = "启动时自动检查收藏更新",
            group = "更新",
            detail = "启动后若距上次检查已超过下面的间隔，就自动把收藏夹查一遍。" +
                "关掉之后仍可在收藏页手动检查。",
            restartRequired = false,
            default = Defaults.CHECK_UPDATE_AUTO,
        ),

        // 6 小时的步长是刻意的：这一项是滑块，1 小时一档的话 200dp 里要挤下 160 多档，
        // 根本停不到想要的数上。6/12/24/48… 这个粒度对「多久查一次」也够用了
        NumberField(
            key = Keys.CHECK_UPDATE_INTERVAL_HOURS,
            title = "自动检查间隔",
            group = "更新",
            detail = "两次自动检查之间至少隔这么久。每轮检查都会把整个收藏夹打一遍图源站，" +
                "间隔太短容易被限流。24 小时即每天一次。",
            restartRequired = false,
            default = Defaults.CHECK_UPDATE_INTERVAL_HOURS,
            min = 6,
            max = 168,
            step = 6,
            unit = " 小时",
        ),

        ToggleField(
            key = Keys.HTTP_STRICT_TLS,
            title = "严格校验 HTTPS 证书",
            group = "网络",
            detail = "打开后证书不对就拒绝连接。关掉它是为了兼容那些证书有问题的图源站。",
            restartRequired = true,
            default = Defaults.HTTP_STRICT_TLS,
            warning = "关闭时（默认）客户端不验证服务器证书，下载内容可被中间人篡改且无法察觉。" +
                "这是与原 Android 版一致的有意行为，但在公共 Wi-Fi、公司网络下风险完全不同。",
        ),
    )

    /** 按分组切开，保持 [fields] 里的先后顺序（`groupBy` 返回的是 LinkedHashMap）。 */
    fun groups(): List<Pair<String, List<SettingField>>> =
        fields.groupBy { it.group }.map { it.key to it.value }

    fun byKey(key: String): SettingField? = fields.firstOrNull { it.key == key }
}
