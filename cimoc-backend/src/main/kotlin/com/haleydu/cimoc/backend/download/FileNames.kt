package com.haleydu.cimoc.backend.download

import java.util.Locale

/**
 * 文件名清洗。
 *
 * Android 版靠 SAF（`DocumentFile`）写文件，文件名合不合法由系统兜着——所以原版只是
 * 把几个字符替换成 `_` 就完事（`DownloadService` 里那行 `replaceAll`）。桌面端直接
 * 落在 NTFS 上，下面这些规则**每一条不满足都会真的出问题**，而且是很难排查的问题：
 *
 * - **非法字符** `\ / : * ? " < > |`：`Files.newOutputStream` 直接抛 `InvalidPathException`。
 * - **保留名** `CON` `PRN` `AUX` `NUL` `COM1`-`COM9` `LPT1`-`LPT9`：不只是精确匹配，
 *   `CON.cbz`、`con.txt` 同样打不开——Windows 看的是第一个点之前的部分。
 * - **结尾的点和空格**：Windows 会**静默**把它们去掉。写入时的路径和读回时的路径
 *   就变成了两个，表现为「文件明明下好了却找不到」。这是最阴的一条，因为它不报错。
 * - **控制字符**：`0x00`-`0x1F` 与 `0x7F`，漫画标题里偶尔会混进换行符。
 * - **长度**：单个路径组件超过 255 个 UTF-16 码元才会报错，但整个路径超过 260
 *   （MAX_PATH）在未开启长路径支持的系统上一样打不开。所以按「组件 80 字符」截断，
 *   给拼接层级留足余量。
 */
object FileNames {

    /** 单个名称的长度上限。三四十个汉字的章节标题很常见，80 足够，又不至于撞 MAX_PATH。 */
    const val MAX_LENGTH = 80

    /** Windows 不接受的字符。 */
    private const val ILLEGAL = "\\/:*?\"<>|"

    /**
     * 保留设备名。Windows 处理这些名字时是**忽略扩展名**的，所以 `CON.cbz` 也不行。
     * 一律加下划线前缀规避，比逐个特判简洁。
     */
    private val RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { add("COM$it"); add("LPT$it") }
        // Windows 10 起还有这些；顺手挡掉，成本为零
        addAll(listOf("COM0", "LPT0", "CONIN$", "CONOUT$"))
    }

    /** 换不出有意义的名字时的兜底。 */
    const val FALLBACK = "未命名"

    /**
     * 清洗成一个可以用作文件/目录名的字符串。
     *
     * 不保证唯一——同名不同章节要由 [unique] 处理。
     */
    fun sanitize(raw: String?, fallback: String = FALLBACK): String {
        val source = raw?.takeIf { it.isNotBlank() } ?: return fallback

        val sb = StringBuilder(source.length)
        for (ch in source) {
            when {
                ch.code < 0x20 || ch.code == 0x7F -> Unit          // 控制字符直接丢
                ILLEGAL.indexOf(ch) >= 0 -> sb.append('_')
                else -> sb.append(ch)
            }
        }

        // 结尾的点和空格必须去掉：Windows 会静默截断，导致「写进去的路径」与
        // 「读出来的路径」不是同一个
        var end = sb.length
        while (end > 0 && (sb[end - 1] == '.' || sb[end - 1] == ' ')) end--
        val trimmed = sb.substring(0, end)

        val capped = truncate(trimmed, MAX_LENGTH)
        if (capped.isEmpty()) return fallback

        return if (isReserved(capped)) "_$capped" else capped
    }

    /**
     * 让 [name] 在 [taken] 里不重复：重名时追加 ` (2)`、` (3)`……
     *
     * 需要它是因为不同章节标题清洗后可能撞车（`第1话/序` 与 `第1话:序` 都会变成
     * `第1话_序`），而重名会**静默覆盖**已经下好的章节。
     */
    fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        var index = 2
        while (true) {
            val candidate = "${truncate(base, MAX_LENGTH - 8)} ($index)$ext"
            if (candidate !in taken) return candidate
            index++
        }
    }

    private fun isReserved(name: String): Boolean {
        val head = name.substringBefore('.').uppercase(Locale.ROOT)
        return head in RESERVED
    }

    /**
     * 按 UTF-16 码元截断，但不切断代理对——截出半个代理对会生成非法的 UTF-8 文件名，
     * 写盘时抛 `MalformedInputException`。
     */
    private fun truncate(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd()
    }
}
