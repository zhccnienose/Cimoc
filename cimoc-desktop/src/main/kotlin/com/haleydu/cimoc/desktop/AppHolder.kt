package com.haleydu.cimoc.desktop

import com.haleydu.cimoc.backend.CimocBackend
import com.haleydu.cimoc.backend.download.DownloadEvent
import com.haleydu.cimoc.backend.download.DownloadState
import com.haleydu.cimoc.backend.engine.UpdateEvent
import kotlinx.coroutines.CancellationException
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
 * 收藏更新检查的状态。
 *
 * 挂在 [AppHolder] 上而不是收藏页里，理由有两条：自动检查在**启动时**就跑，那时用户
 * 多半还没进过收藏页；手动检查跑了一半切到别的页再切回来，进度也不该从头开始。
 */
sealed interface UpdateCheckState {

    /** 本次启动内还没查过。 */
    data object Idle : UpdateCheckState

    /** 正在查。[total] 为 0 表示收藏总数还没数出来，界面画不定态即可。 */
    data class Running(val checked: Int, val total: Int, val updated: Int) : UpdateCheckState

    /**
     * 查完一轮。
     *
     * [finishedAt] 是给收藏页用的信号：它一变就代表又跑完了一轮，该重读收藏列表了
     * （更新过的会被顶到最前并打上高亮）。
     */
    data class Done(
        val checked: Int,
        val updated: Int,
        val failed: Int,
        val finishedAt: Long,
    ) : UpdateCheckState

    /**
     * 整轮没跑起来（读库出错之类）。**单本图源的失败不在这里**——那种走
     * [UpdateEvent.Failed]，本轮会继续，最后算进 [Done.failed]。
     */
    data class Failed(val error: String) : UpdateCheckState
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

    private val _updateCheck = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)

    /** 收藏更新检查的状态。收藏页订阅它画进度，见 [UpdateCheckState]。 */
    val updateCheck: StateFlow<UpdateCheckState> = _updateCheck.asStateFlow()

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
            result.getOrNull()?.let { backend ->
                scope.launch { watchDownloads(backend) }
                // 自动检查：够间隔才跑。判断只读 AppSettings 的内存缓存、不碰 SQLite，
                // 但仍然丢进协程——启动路径上不该有任何同步调用，哪怕它眼下很快
                scope.launch {
                    if (runCatching { backend.update.shouldAutoCheck() }.getOrDefault(false)) {
                        startCheck(backend)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 收藏更新检查

    /**
     * 手动触发一轮收藏更新检查。收藏页的「检查更新」按钮走这里。
     *
     * 正在跑时直接忽略——连点两下不该同时打两遍整个收藏夹。
     */
    fun checkForUpdates() {
        val backend = (_state.value as? BackendState.Ready)?.backend ?: return
        startCheck(backend)
    }

    private fun startCheck(backend: CimocBackend) {
        if (_updateCheck.value is UpdateCheckState.Running) return
        // 先占住状态再进协程：自动检查与用户点按钮几乎同时发生时，靠这一步挡掉第二轮。
        // 此刻还没有总数，界面按 total == 0 画不定态
        _updateCheck.value = UpdateCheckState.Running(checked = 0, total = 0, updated = 0)
        scope.launch { runCheck(backend) }
    }

    /**
     * 跑一轮检查。全程在 [scope]（[Dispatchers.IO]）上：`checkAll()` 的 `channelFlow`
     * 内部用的是阻塞 DAO，[com.haleydu.cimoc.backend.engine.UpdateChecker.markChecked] 也要写库。
     */
    private suspend fun runCheck(backend: CimocBackend) {
        try {
            val total = backend.books.countFavorites()
            if (total == 0) {
                _updateCheck.value = UpdateCheckState.Done(0, 0, 0, System.currentTimeMillis())
                return
            }

            // 时间戳只在真的要查的时候写。空收藏夹那条路不写：否则用户刚收藏第一本，
            // 就得等满一个间隔才会被自动检查到
            backend.update.markChecked()
            _updateCheck.value = UpdateCheckState.Running(checked = 0, total = total, updated = 0)

            var processed = 0
            var updated = 0
            var summary: UpdateEvent.Done? = null

            backend.update.checkAll().collect { event ->
                when (event) {
                    is UpdateEvent.Checked -> {
                        processed += 1
                        if (event.updated) updated += 1
                    }
                    // 图源层面失败也算「查过了一本」，否则进度条永远差几本到不了头
                    is UpdateEvent.Failed -> processed += 1
                    is UpdateEvent.Done -> summary = event
                }
                if (summary == null) {
                    _updateCheck.value = UpdateCheckState.Running(processed, total, updated)
                }
            }

            // 最终数字以 Done 为准：后端的 checked 不含失败的那几本，和 processed 口径不同
            val done = summary ?: UpdateEvent.Done(processed, updated, 0)
            _updateCheck.value = UpdateCheckState.Done(
                checked = done.checked,
                updated = done.updated,
                failed = done.failed,
                finishedAt = System.currentTimeMillis(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _updateCheck.value = UpdateCheckState.Failed(
                "${e.javaClass.simpleName}: ${e.message?.takeIf { it.isNotBlank() } ?: "(无消息)"}"
            )
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
