package com.haleydu.cimoc.backend.download

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把一章的图片打成一个 CBZ（就是一个 zip）。
 *
 * CBZ 的内容约定决定了第三方阅读器能不能正确翻页，所以这几条是硬要求：
 *
 * - **条目名零填充**（`001.jpg`、`002.jpg`…）。阅读器普遍按条目名做字典序排序，
 *   不填充的话 `10.jpg` 会排在 `2.jpg` 前面，第 2 页和第 10 页颠倒。
 * - **图片直接放在根目录**，不套子目录。部分阅读器不会递归查找。
 * - **不写压缩注释、不加目录条目**。内容越朴素，兼容性越好。
 *
 * 先写 `.part` 再改名：中途崩溃/断电时留下的是一个明显的半成品文件，
 * 而不是一个看起来正常、实际缺页的 CBZ——后者用户要等打开才发现。
 */
object CbzWriter {

    /** 临时文件后缀，与成品区分开。 */
    const val PART_SUFFIX = ".part"

    /**
     * 打包 [pages] 到 [target]，条目名取各文件自己的文件名（调用方负责按 `001.jpg` 命名）。
     *
     * @return [target]
     */
    fun pack(target: Path, pages: List<Path>): Path {
        require(pages.isNotEmpty()) { "没有图片可打包：$target" }

        val absolute = target.toAbsolutePath()
        absolute.parent?.let { Files.createDirectories(it) }
        val part = absolute.resolveSibling(absolute.fileName.toString() + PART_SUFFIX)

        try {
            Files.newOutputStream(part).use { fileOut ->
                ZipOutputStream(fileOut).use { zip ->
                    // 图片本身已经是压缩格式，压不动；但也不该用 STORED——
                    // 少数老阅读器只认 DEFLATED 条目。默认级别是兼容性最好的选择。
                    zip.setLevel(Deflater.DEFAULT_COMPRESSION)

                    for (page in pages) {
                        val name = page.fileName.toString()
                        val entry = ZipEntry(name).apply {
                            // 用源文件的修改时间而不是当前时间：重新打包同内容会得到
                            // 字节完全一致的产物，便于比对
                            runCatching { lastModifiedTime = Files.getLastModifiedTime(page) }
                        }
                        zip.putNextEntry(entry)
                        Files.newInputStream(page).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            move(part, absolute)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(part) }
            throw e
        }

        return absolute
    }

    /**
     * `ATOMIC_MOVE` 在同一个卷内是原子的。跨卷（比如临时目录和目标目录在不同的盘符）会
     * 抛 `AtomicMoveNotSupportedException`，退回到普通替换——那种情况下至少有 `.part`
     * 文件存在的事实可以判断「没写完」。
     */
    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
