package com.haleydu.cimoc.desktop

import com.haleydu.cimoc.backend.CimocBackend
import com.haleydu.cimoc.backend.download.DownloadEvent
import com.haleydu.cimoc.backend.download.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/** 后端在启动过程中的三种状态。 */
sealed interface BackendState {

    data object Loading : BackendState

    data class Ready(val backend: CimocBackend) : BackendState

    /**
     * [CimocBackend.open] 抛出的任何异常。**必须显示出来**：它会以异常结束的路径不止一条
     * ——数据库损坏、目录不可写、图源 TYPE 冲突（`SourceRegistry.scan` 直接 `error()`）。
     * 吞掉的话用户的观感就是「双击没反应」，无从下手。
     */
    data class Failed(val error: Throwable) : BackendState
}

/**
 * 应用级生命周期与线程上下文。
 *
 * ### 为什么后端要在协程里开
 *
 * [CimocBackend.open] 是阻塞的：建表、迁移、反射扫描图源、按图源读开关，全在这一句里。
 * 直接在 Compose 的 `main()` 里同步调用，窗口会「先卡住几秒再出现」——Windows 上还会
 * 被判定为无响应。所以丢进 IO 协程，界面先显示启动态。
 *
 * ### [scope] 是给整个界面用的
 *
 * `Library` / `ParserFactory` / `AppSettings` 的方法**全是阻塞的**（直连 SQLite 单连接、
 * 图源构造要反射），必须在 IO 上调用。`SearchEngine.search` 与 `UpdateChecker.checkAll`
 * 这两个 Flow 的 `channelFlow` 内部也会用阻塞 DAO，所以它们的 `collect` 同样要落在这个
 * scope 上——用 `rememberCoroutineScope()` 会跑在主线程上，界面会卡住。
 */
class AppHolder(private val database: Path) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<BackendState>(BackendState.Loading)
    val state: StateFlow<BackendState> = _state.asStateFlow()

    private val _unfinishedTasks = MutableStateFlow(0)

    /**
     * 未完成的下载任务数，导航栏「下载」的角标用它。
     *
     * 口径是 `state != FINISH`：**出错的任务也算未完成**——它不会自己好，在用户重试或
     * 取消之前一直是个待办。这和 `DownloadState.isTerminal`（FINISH 或 ERROR）不是一回事，
     * 别混用。
     */
    val unfinishedTasks: StateFlow<Int> = _unfinishedTasks.asStateFlow()

    /** 由 UI 线程写、IO 线程读，要 volatile。 */
    @Volatile
    private var closed = false

    /**
     * 开后端并开始跟踪下载队列。**只该在 `main()` 里调一次。**
     *
     * `autoResume = true` 让上次没下完的任务自动重新排队（见 `DownloadManager.recover`）。
     */
    fun start() {
        scope.launch {
            val result = runCatching {
                CimocBackend.open(database, storageRoot = null, autoResume = true)
            }

            // 启动还没完窗口就被关了。open() 是阻塞的、没有可中断点，cancel 不掉，
            // 所以只能在它返回之后立刻把连接关掉——否则这个进程会留着 SQLite 文件和
            // .db-wal 不放（Windows 上表现为「文件被占用」）。
            if (closed) {
                result.getOrNull()?.let { runCatching { it.close() } }
                return@launch
            }

            _state.value = result.fold(
                onSuccess = { BackendState.Ready(it) },
                onFailure = { BackendState.Failed(it) },
            )
            result.getOrNull()?.let { backend -> scope.launch { watchDownloads(backend) } }
        }
    }

    /**
     * 关后端再停 scope。顺序不能反——先 cancel 会让正在跑的下载协程在写库途中被打断。
     */
    fun close() {
        closed = true
        (_state.value as? BackendState.Ready)?.let { runCatching { it.backend.close() } }
        scope.cancel()
    }

    /**
     * 维护未完成任务数。
     *
     * **必须先铺底再订阅**：[DownloadManager.events] 是 `replay = 0`，订阅之前就存在的任务
     * 不会补发。少这一步的话，「上次没下完、这次自动续传的任务」在角标上永远是 0。
     *
     * 铺完底之后只在内存里维护这个集合，不再回查数据库——进度事件是**按页**发的，
     * 一章几十页，每次回查一遍 `countUnfinished()` 是白费。
     */
    private suspend fun watchDownloads(backend: CimocBackend) {
        val unfinished = HashSet<Long>()
        for (task in backend.books.tasks.listAll()) {
            if (task.state != DownloadState.FINISH) unfinished.add(task.chapterId)
        }
        _unfinishedTasks.value = unfinished.size

        backend.download.events.collect { event ->
            when (event) {
                is DownloadEvent.Changed -> {
                    val id = event.task.chapterId
                    if (event.task.state == DownloadState.FINISH) unfinished.remove(id) else unfinished.add(id)
                }
                is DownloadEvent.Removed -> unfinished.remove(event.chapterId)
                is DownloadEvent.Idle -> return@collect
            }
            _unfinishedTasks.value = unfinished.size
        }
    }
}
