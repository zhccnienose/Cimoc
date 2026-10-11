package com.haleydu.cimoc.backend.storage

import com.haleydu.cimoc.model.Chapter
import com.haleydu.cimoc.model.Comic
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 快照文件里存的两样东西：漫画（含简介等字段）与章节目录。 */
data class CachedDetail(
    val comic: Comic,
    val chapters: List<Chapter>,
)

/**
 * 详情快照缓存：**一本漫画一个 JSON 文件**，放在传入的那个目录下。
 *
 * 同一套机制被两处用，只是目录不同：
 * - `<下载根>/favorite/`（[FAVORITE_DIR]）——收藏的详情，[com.haleydu.cimoc.backend.engine.FavoriteStore] 维护；
 * - `<下载根>/.history/`（[HISTORY_DIR]）——浏览过的详情，[com.haleydu.cimoc.backend.engine.HistoryStore] 维护。
 *
 * 两边的定位相同：数据库是**权威**（收藏状态、下载任务、章节行号都以它为准），
 * JSON 是**这份权威的快照**，只用来渲染详情页。各自的增删时机由持有它的那个 Store 决定
 * （收藏那套是「收藏时写、取消时删」，浏览那套是「没收藏的才写」），这一类只负责读写。
 *
 * ### 为什么数据库里已经有这些字段，还要再存一份 JSON
 *
 * `comic.intro` 和 `chapter` 表里本来就有简介和章节。这份 JSON 是为了让详情页**不依赖
 * 图源当天是否还活着**——见 README「图源现状一览」：本机 40 个源里 26 个连不上，
 * 能返回章节的只有十来个。
 *
 * ### 文件名的编码
 *
 * `cid` 是图源给的字符串，可能含 `/`、`?` 这类在 Windows 上非法的字符，所以做
 * URL 编码。**不用「清洗非法字符」那种做法**：`a/b` 和 `a?b` 清洗后会撞同一个文件名，
 * 而 URL 编码是可逆的一一映射（封面缓存的键用 SHA-256 也是同一个理由，
 * 只是那里不需要人能看懂文件名，这里需要）。
 *
 * ### 章节的 `id` 是数据库行号
 *
 * 不是图源的合成 ID——详情页的「开始下载」要把它交回 [Library]。
 * 收藏期间这行不会被删（`deleteIfOrphan` 只在「没收藏且没下载任务」时动手，
 * `replaceForComic` 只增不删），所以收藏快照里的行号在整个收藏期间都有效。
 */
class DetailSnapshotCache(private val root: Path) {

    /** 缓存文件路径。文件不存在也返回路径（调用方自己判断）。 */
    fun path(source: Int, cid: String): Path = root.resolve(fileName(source, cid))

    /**
     * 写入（覆盖）。
     *
     * 先写同目录下的临时文件再原子改名：写到一半崩掉/断电时，留下的是一个完整的旧文件，
     * 而不是一个截断的新文件——后者下次读会解析失败，等于把用户唯一的离线副本毁了。
     */
    fun write(comic: Comic, chapters: List<Chapter>) {
        val cid = requireNotNull(comic.cid) { "详情快照要求 comic.cid 非空" }
        val text = toJson(comic, chapters).toString(2)

        val target = path(comic.source, cid)
        Files.createDirectories(root)
        val temp = target.resolveSibling(target.fileName.toString() + PART_SUFFIX)
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        runCatching { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE) }
            .onFailure { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    /**
     * 读取。
     *
     * **任何异常都返回 null**（文件不在、内容被改坏、版本对不上）——调用方据此退回网络解析。
     * 一个损坏的缓存文件不值得让详情页打不开。
     */
    fun read(source: Int, cid: String): CachedDetail? {
        val file = path(source, cid)
        if (!Files.isRegularFile(file)) return null

        val json = runCatching { JSONObject(Files.readString(file, StandardCharsets.UTF_8)) }.getOrNull()
            ?: return null
        if (json.optInt("version") != FORMAT_VERSION) return null

        val comic = Comic().apply {
            this.source = json.optInt("source", source)
            this.cid = json.optString("cid").takeIf { it.isNotEmpty() } ?: cid
            title = json.optString("title")
            cover = json.optString("cover")
            author = json.optString("author").takeIf { it.isNotEmpty() }
            intro = json.optString("intro").takeIf { it.isNotEmpty() }
            url = json.optString("url").takeIf { it.isNotEmpty() }
            update = json.optString("update").takeIf { it.isNotEmpty() }
            // finish 是三态（true 完结 / false 连载 / null 图源没报），
            // 用 optBoolean 会把 null 读成 false，和「连载中」混在一起
            finish = if (json.isNull("finish")) null else json.optBoolean("finish")
        }

        val array = json.optJSONArray("chapters") ?: JSONArray()
        val chapters = ArrayList<Chapter>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // 没有行号的条目直接丢：交回下载时它无法定位到章节行
            val id = item.optLong("id").takeIf { it > 0 } ?: continue
            val chapter = Chapter(
                id,
                null,
                item.optString("title"),
                item.optString("path").takeIf { it.isNotEmpty() },
                item.optString("sourceGroup").takeIf { it.isNotEmpty() },
            )
            chapter.count = item.optInt("pageCount")
            chapters += chapter
        }
        return CachedDetail(comic, chapters)
    }

    /** 删除。文件本来就不在也算成功——调用方只关心「删完之后它不在了」。 */
    fun delete(source: Int, cid: String): Boolean =
        runCatching { Files.deleteIfExists(path(source, cid)) }.getOrDefault(false)

    // ---------------------------------------------------------------- 目录级操作

    /**
     * 清空整个目录，返回删掉的条目数。
     *
     * 顺带收拾崩溃残留的 `.part`（[write] 写到一半留下的那些），它们不在正常条目之列，
     * 但没有理由留着占地方。
     *
     * 目录不存在算清空成功（返回 0）：用户从没产生过缓存时点「清空」不该报错。
     * 阻塞（遍历目录）。前端请在 IO 上调用。
     */
    fun clear(): Int {
        if (!Files.isDirectory(root)) return 0
        var removed = 0
        runCatching {
            Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) {
                    val name = entry.fileName.toString()
                    if (!name.endsWith(FILE_SUFFIX) && !name.endsWith(PART_SUFFIX)) continue
                    if (Files.deleteIfExists(entry) && name.endsWith(FILE_SUFFIX)) removed += 1
                }
            }
        }
        return removed
    }

    /**
     * 目录占用字节数。目录不存在或读不出来时返回 0。
     *
     * 只数正常条目（`*.json`），不算 `.part`——设置页上那个数字要能和「清空」对得上。
     * 阻塞（遍历目录）。前端请在 IO 上调用。
     */
    fun sizeBytes(): Long {
        if (!Files.isDirectory(root)) return 0
        return runCatching {
            Files.newDirectoryStream(root).use { entries ->
                entries.filter { it.fileName.toString().endsWith(FILE_SUFFIX) }
                    .sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
            }
        }.getOrDefault(0L)
    }

    // ---------------------------------------------------------------- 内部

    private fun toJson(comic: Comic, chapters: List<Chapter>): JSONObject = JSONObject().apply {
        put("version", FORMAT_VERSION)
        put("source", comic.source)
        put("cid", comic.cid)
        put("title", comic.title ?: "")
        put("cover", comic.cover ?: "")
        put("author", comic.author ?: "")
        put("update", comic.update ?: "")
        put("finish", comic.finish ?: JSONObject.NULL)
        put("intro", comic.intro ?: "")
        put("url", comic.url ?: "")
        put("favoriteAt", comic.favorite ?: JSONObject.NULL)
        put("chapterCount", chapters.size)
        put(
            "chapters",
            JSONArray().apply {
                chapters.forEach { chapter ->
                    put(
                        JSONObject().apply {
                            put("id", chapter.id ?: JSONObject.NULL)
                            put("title", chapter.title ?: "")
                            put("path", chapter.path ?: "")
                            put("sourceGroup", chapter.sourceGroup ?: JSONObject.NULL)
                            put("pageCount", chapter.count)
                        }
                    )
                }
            },
        )
    }

    private fun fileName(source: Int, cid: String): String =
        "$source-" + encodeFileName(cid) + FILE_SUFFIX

    /**
     * 只保留 `[A-Za-z0-9._-]`，其余字节一律 `%XX`。
     *
     * **不能用 [URLEncoder]**：它按表单编码的约定认为 `*` 是安全字符，而 `*` 在 Windows
     * 上非法——`cid = "a*b"` 会让 `Path.resolve` 直接抛 `InvalidPathException`，
     * 表现为「点收藏报错、收藏不上」。实测踩到过。
     *
     * 逐个**字节**编码而不是逐个字符：cid 里的中文是多字节 UTF-8，按字符编码会得到
     * 长度对不上的结果。
     */
    private fun encodeFileName(value: String): String = buildString {
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') {
                append(ch)
            } else {
                append('%').append(code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    companion object {

        /**
         * 收藏快照的目录名：`<下载根>/favorite/`，与 `.cover/`、`.partial/` 同一层级。
         *
         * **这个值不能改**：改了等于把现有用户已经写下的收藏快照全部作废，
         * 而他们的收藏会因此退回网络——本机十个源倒有七个连不上。
         */
        const val FAVORITE_DIR = "favorite"

        /**
         * 浏览记录的目录名：`<下载根>/.history/`。
         *
         * 带点前缀与 `.cover/` 一致，让它和用户自己的下载产物在目录列表里分得开。
         */
        const val HISTORY_DIR = ".history"

        /**
         * 格式版本。**不匹配就当缓存不存在**，退回网络重新解析。
         *
         * 加了字段却按旧文件渲染，比没有缓存更糟：会静默显示缺了内容的页面。
         */
        private const val FORMAT_VERSION = 1

        private const val FILE_SUFFIX = ".json"

        private const val PART_SUFFIX = ".part"
    }
}
