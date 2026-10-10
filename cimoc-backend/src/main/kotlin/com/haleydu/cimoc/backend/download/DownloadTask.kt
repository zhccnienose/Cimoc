package com.haleydu.cimoc.backend.download

/**
 * 下载任务状态。
 *
 * 数值沿用原 Android 版 `model/Task.java` 的常量，便于对照旧代码阅读。
 * 存在 `download_task.state` 列里。
 */
enum class DownloadState(val code: Int) {
    /** 已下完（`progress == total`）。 */
    FINISH(0),

    /** 用户暂停，或进程退出时留下的中断状态。可续传。 */
    PAUSE(1),

    /** 正在解析图片列表。 */
    PARSE(2),

    /** 正在逐页下载。 */
    DOING(3),

    /** 已入队但还没开始。 */
    WAIT(4),

    /** 失败。具体原因见 [DownloadTask.error]。 */
    ERROR(5);

    val isTerminal: Boolean get() = this == FINISH || this == ERROR

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun of(code: Int): DownloadState =
            BY_CODE[code] ?: throw IllegalArgumentException("未知的下载状态: $code")
    }
}

/**
 * 一个章节的下载任务。对应 `download_task` 表的一行。
 *
 * 与原版 `model/Task.java` 的差别：原版把 `source` / `cid` / `state` 标了 `@Transient`
 * **根本不入库**，于是进程重启后任务就丢了图源信息、也没法续传，只能靠重新扫描
 * `download` 目录来恢复。这里这三样要么入库（`state`），要么可以顺着 `comic_id`
 * 外键查到（`source` / `cid`），不需要那一套恢复逻辑。
 */
data class DownloadTask(
    val id: Long,
    val comicId: Long,
    val chapterId: Long,
    val state: DownloadState,
    val progress: Int,
    val total: Int,
    val error: String? = null,
    /** 产出 CBZ 的绝对路径，未完成时为 null。 */
    val filePath: String? = null,
    val updatedAt: Long,
) {
    val isFinished: Boolean get() = total > 0 && progress >= total

    val percent: Int get() = if (total <= 0) 0 else (progress * 100 / total)
}
