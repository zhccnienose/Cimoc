package com.haleydu.cimoc.backend.source

import com.haleydu.cimoc.model.Source
import com.haleydu.cimoc.parser.MangaParser
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 一个可用的图源。`type` / `title` / `defaultEnabled` 全部来自图源类自己的静态声明。
 */
data class SourceInfo(
    val type: Int,
    val title: String,
    val defaultEnabled: Boolean,
    val parserClass: Class<out MangaParser>,
) {
    /** 按图源自己声明的默认值造一个 [Source]，供 `ParserFactory` 构造解析器。 */
    fun defaultSource(): Source = Source(null, title, type, defaultEnabled)

    /** 用户改过开关时用这个。 */
    fun sourceOf(enabled: Boolean): Source = Source(null, title, type, enabled)
}

/**
 * 图源注册表：**反射扫描** `com.haleydu.cimoc.source`，取代 Android 版两处硬编码
 * （`SourceManager.getParser` 的 43 分支 switch + `UpdateHelper.initSource` 的 45 行 `list.add`）。
 *
 * 以后加图源只需要在 `cimoc-core` 里放一个 `MangaParser` 子类，
 * 本类、`ParserFactory`、前端都不需要改。
 *
 * ### 判定规则
 *
 * 同时满足这三条才算一个图源：
 * 1. 是 [MangaParser] 的具体子类（排除接口、抽象类、内部类）
 * 2. 有 **public static** `getDefaultSource()` 方法
 * 3. 该方法返回非 null 的 [Source]
 *
 * 第 2 条把一批历史遗留的占位类挡在外面很有效：`HHSSEE` / `ExHentai` / `NHentai` /
 * `Pic177` / `Wnacg` 这几个类的整个类体都被注释掉了（写着 `class ExHentai/* extends MangaParser */`），
 * 既不是子类也没有这个方法，自动出局，不会出现在图源列表里。
 *
 * `Locality`（本地漫画，TYPE=-2）和 `Null`（TYPE=-1）同样没有 `getDefaultSource()`。
 * 这本就符合预期——前者扫本机图片文件，不在本项目功能清单内；后者是 Android 版
 * switch 的 `default` 分支用的空实现，桌面端遇到未知 type 应当是明确报错，而不是返回一个空解析器。
 */
class SourceRegistry private constructor(val all: List<SourceInfo>) {

    private val index: Map<Int, SourceInfo> = all.associateBy { it.type }

    /** 未知 type 返回 null——收藏夹里可能留着已下架图源的条目，不能因此崩掉。 */
    fun byType(type: Int): SourceInfo? = index[type]

    fun types(): Set<Int> = index.keys

    companion object {

        /** 图源所在的包。扫描范围就到这里，不含子包（当前也没有）。 */
        const val PACKAGE = "com.haleydu.cimoc.source"

        private const val PACKAGE_PATH = "com/haleydu/cimoc/source"

        fun scan(loader: ClassLoader = SourceRegistry::class.java.classLoader): SourceRegistry {
            val classes = classNames(loader)
                .mapNotNull { loadParser(it, loader) }
                .distinctBy { it.name }

            val infos = ArrayList<SourceInfo>(classes.size)
            for (clazz in classes) {
                infoOf(clazz)?.let { infos += it }
            }
            infos.sortBy { it.type }

            // type 是图源的唯一标识：收藏、章节、下载任务全靠它定位图源。
            // 两个类声明同一个 type，运行期就会出现「搜到的是 A、下载走的是 B」这种
            // 极难排查的错乱。宁可在启动时直接失败。
            infos.groupBy { it.type }.filterValues { it.size > 1 }.forEach { (type, dup) ->
                error("图源 TYPE 冲突：type=$type 被 ${dup.map { it.parserClass.name }} 同时声明")
            }

            return SourceRegistry(infos)
        }

        // ------------------------------------------------------------ 类名枚举

        /**
         * 列出包里所有类的二进制名。
         *
         * 必须用 `getResources`（**复数**）并同时处理 `file:` 和 `jar:` 两种协议：
         *
         * - **复数**：`cimoc-core` 的 `.java` 与 `.kt` 混放在 `src/main/java` 下，
         *   编译产物分落在 `build/classes/java/main` 和 `build/classes/kotlin/main` 两个目录，
         *   也就是两个独立的 `file:` 根。用单数 `getResource()` 只会拿到第一个，
         *   `MangaBZ.kt`（落在 kotlin 那个目录）会被静默漏掉。
         * - **`jar:`**：打包成 jar / app-image 后，类路径根不再是目录。
         *   只认 `file:` 的扫描器在这时会扫到 0 个图源，而且开发期完全看不出来。
         */
        private fun classNames(loader: ClassLoader): Set<String> {
            val names = LinkedHashSet<String>()
            val roots = loader.getResources(PACKAGE_PATH)
            while (roots.hasMoreElements()) {
                val url = roots.nextElement()
                when (url.protocol) {
                    "file" -> collectFromDirectory(Paths.get(url.toURI()), names)
                    "jar" -> collectFromJar(url, names)
                    else -> Unit
                }
            }
            return names
        }

        /**
         * `root` 是**包目录**（`.../com/haleydu/cimoc/source`），所以相对路径只到类名，
         * 必须自己把包名拼回去——`Class.forName("MangaBZ")` 是找不到类的，
         * 而且它抛的是 `ClassNotFoundException`，会被 `loadParser` 的 `runCatching`
         * 当作「不是图源」静默吞掉，最终表现为**一个图源都扫不到且不报错**。
         */
        private fun collectFromDirectory(root: Path, into: MutableSet<String>) {
            if (!Files.isDirectory(root)) return
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .forEach { file ->
                        val relative = root.relativize(file).toString().replace(File.separatorChar, '/')
                        into += "$PACKAGE.${relative.removeSuffix(".class").replace('/', '.')}"
                    }
            }
        }

        private fun collectFromJar(url: java.net.URL, into: MutableSet<String>) {
            val connection = url.openConnection() as? JarURLConnection ?: return
            // useCaches=false：否则 JarFile 会被 JVM 缓存住，Windows 上会导致
            // 构建产物无法删除/覆盖（"另一个程序正在使用此文件"）。
            connection.useCaches = false
            val jar = runCatching { connection.jarFile }.getOrNull() ?: return
            jar.use {
                it.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter { name -> name.startsWith("$PACKAGE_PATH/") && name.endsWith(".class") }
                    .forEach { name -> into += name.removeSuffix(".class").replace('/', '.') }
            }
        }

        // ------------------------------------------------------------ 过滤

        @Suppress("UNCHECKED_CAST")
        private fun loadParser(name: String, loader: ClassLoader): Class<out MangaParser>? {
            // 内部类 / 匿名类（`Outer$Inner`）不是图源
            if (name.contains('$')) return null

            val clazz = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return null
            if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return null
            if (!MangaParser::class.java.isAssignableFrom(clazz)) return null

            return clazz as Class<out MangaParser>
        }

        /** 读图源自己声明的 type / title / 默认开关。读不到就当作不是图源。 */
        private fun infoOf(clazz: Class<out MangaParser>): SourceInfo? {
            val method = runCatching { clazz.getMethod("getDefaultSource") }.getOrNull() ?: return null
            if (!Modifier.isStatic(method.modifiers)) return null

            val source = runCatching { method.invoke(null) as? Source }.getOrNull() ?: return null
            val title = source.title ?: return null

            return SourceInfo(
                type = source.type,
                title = title,
                defaultEnabled = source.enable,
                parserClass = clazz,
            )
        }
    }
}
