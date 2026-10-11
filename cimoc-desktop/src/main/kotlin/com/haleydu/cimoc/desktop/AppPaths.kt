package com.haleydu.cimoc.desktop

import java.nio.file.Path

/**
 * 运行期数据的位置。**这是用户会问的第一个问题**（「我的收藏和下载在哪」），
 * 所以定成固定规则而不是让用户选：
 *
 * ```
 * %APPDATA%/Cimoc/
 *   cimoc.db      收藏、章节缓存、下载任务、设置
 *   download/     CBZ 成品与 .partial 临时页
 *   .cover/       封面磁盘缓存
 * ```
 *
 * 选 `%APPDATA%` 而不是 exe 同级目录：Program Files 下的目录默认不可写，
 * 「装在 Program Files 就用不了」是个很常见的坑。
 *
 * 环境变量 `CIMOC_HOME` 可以整体覆盖（调试、便携模式、跑第二份互不干扰的数据用）。
 * `download/` 与 `.cover/` 是 [com.haleydu.cimoc.backend.CimocBackend.open] 在
 * storageRoot 传 null 时的默认落点：数据库同级的 `download/`，不用这里另算。
 */
object AppPaths {

    /** 覆盖数据目录的环境变量。 */
    const val HOME_ENV: String = "CIMOC_HOME"

    val dataDir: Path = resolveDataDir()

    val database: Path get() = dataDir.resolve("cimoc.db")

    private fun resolveDataDir(): Path {
        System.getenv(HOME_ENV)?.takeIf { it.isNotBlank() }?.let {
            return Path.of(it).toAbsolutePath().normalize()
        }
        // Windows 的漫游应用数据目录（如 C:\Users\x\AppData\Roaming）
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let {
            return Path.of(it).resolve("Cimoc").toAbsolutePath().normalize()
        }
        // 非 Windows（或 APPDATA 被清掉）：退到用户主目录下的隐藏目录
        return Path.of(System.getProperty("user.home"), ".cimoc")
            .toAbsolutePath().normalize()
    }
}
