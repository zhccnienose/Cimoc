package com.haleydu.cimoc.backend.download

import com.haleydu.cimoc.backend.http.HttpEngine
import com.haleydu.cimoc.backend.http.HttpException
import com.haleydu.cimoc.backend.source.ParserFactory
import com.haleydu.cimoc.backend.source.SourceRegistry
import com.haleydu.cimoc.backend.storage.AppSettings
import com.haleydu.cimoc.backend.storage.Defaults
import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.backend.storage.Library
import com.haleydu.cimoc.model.Chapter
import com.haleydu.cimoc.model.Comic
import com.haleydu.cimoc.model.ImageUrl
import com.haleydu.cimoc.parser.MangaParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** 下载队列的事件。 */
sealed interface DownloadEvent {

    /** 某个任务的状态、进度或产出路径发生了变化。前端拿 [DownloadEvent.Changed.task] 直接渲染。 */
    data class Changed(val task: DownloadTask) : DownloadEvent

    /**
     * 任务被删除（[DownloadManager.cancel]）。
     *
     * 为什么不能复用 [Changed]：任务删了就没有 `DownloadTask` 可发，而界面需要知道
     * 把它从列表里去掉。少了这个事件，**取消操作在界面上是不可见的**——列表里那行还在，
     * 角标也一直多算一个。
     */
    data class Removed(val chapterId: Long) : DownloadEvent

    /** 队列跑空了。 */
    data object Idle : DownloadEvent
}

/**
 * 下载队列。对应原版 `DownloadService` + `DownloadPresenter`。
 *
 * ### 结构
 *
 * 固定 [concurrency] 个 worker 协程从同一个队列里取章节。每个章节在**自己的子协程**里跑，
 * 这样暂停一章不影响正在下的其他章——原版 `DownloadService` 用的也是「每任务一个 Future」。
 *
 * ### 与原版的差异
 *
 * | | 原版 | 这里 |
 * |---|---|---|
 * | 落盘 | 每页一个 jpg + index.json | 每章一个 CBZ，第三方阅读器直接能开 |
 * | 续传 | 靠扫目录重建 | 临时页留在 `.partial/<章节行号>/`，重启后跳过已下好的页 |
 * | 单页重试 | 20 次 | 默认 4 次，带退避（见 [Keys.DOWNLOAD_RETRY]） |
 * | 进度上报 | RxBus + 通知栏 | [events] 一个 `SharedFlow` |
 *
 * ### 一个原版的 bug，这里没照抄
 *
 * 原 `Manga.getImageUrls` 对同一个 `response.body().string()` **读了两次**
 * （先 `parseImages(html, chapter)`，失败再 `parseImages(html)`）。OkHttp 的 body
 * 只能读一次，第二次拿到空串——也就是说那个回退分支**从来没生效过**。
 * 这里把 html 取成字符串之后再用，两个分支都是真的。
 */
class DownloadManager(
    private val registry: SourceRegistry,
    private val factory: ParserFactory,
    private val http: HttpEngine,
    private val library: Library,
    private val settings: AppSettings,
    private val layout: DownloadLayout,
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待下载的章节行号。用无界队列：入队永远不该被阻塞。 */
    private val queue = Channel<Long>(Channel.UNLIMITED)

    private val running = ConcurrentHashMap<Long, Job>()

    private val _events = MutableSharedFlow<DownloadEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        // 前端来不及消费时丢最旧的进度事件。丢一条进度不影响正确性，
        // 而挂起发送方会让下载线程白白等着 UI。
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 进度流。收集它来驱动前端。 */
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /**
     * 并发下载数。**构造时固定**，运行期改 `download.thread` 需要重建本对象——
     * 动态增减 worker 会带来「减少时哪些任务该中断」这种没有正确答案的问题。
     */
    val concurrency: Int =
        settings.getInt(Keys.DOWNLOAD_THREAD, Defaults.DOWNLOAD_THREAD).coerceIn(1, MAX_CONCURRENCY)

    init {
        repeat(concurrency) { index ->
            scope.launch(CoroutineName("download-worker-$index")) { worker() }
        }
    }

    // ---------------------------------------------------------------- 入队 / 控制

    /** 把一批章节加入队列。返回各自的任务（含行号与初始状态）。 */
    fun enqueue(comic: Comic, chapters: List<Chapter>): List<DownloadTask> {
        val comicId = requireNotNull(comic.id) { "下载前漫画必须已入库" }
        return chapters.mapNotNull { chapter ->
            val chapterId = chapter.id ?: return@mapNotNull null
            submit(comicId, chapterId)
        }
    }

    fun enqueue(comic: Comic, chapter: Chapter): DownloadTask? =
        enqueue(comic, listOf(chapter)).firstOrNull()

    /**
     * 重新入队。已经跑完的任务也可以重下——`enqueue` 会把进度归零。
     *
     * 注意**临时页不会因为重下而清掉**，所以重下同一章会直接命中续传、瞬间完成。
     * 想真正重下，先 [cancel] 再 [enqueue]。
     */
    fun resume(chapterId: Long): DownloadTask? {
        val task = library.tasks.loadByChapter(chapterId) ?: return null
        return submit(task.comicId, chapterId)
    }

    /** 暂停一章。已经在跑就中断它；还在排队就在轮到它时被跳过。 */
    fun pause(chapterId: Long) {
        running[chapterId]?.cancel()
        // 排队中的任务不会被 cancel 到（还没有 Job），靠状态判断在 worker 里跳过
        library.tasks.loadByChapter(chapterId)
            ?.takeIf { !it.state.isTerminal }
            ?.let { updateState(chapterId, DownloadState.PAUSE) }
    }

    /** 取消并删除任务记录。**不删已产出的 CBZ**——成品文件属于用户。 */
    fun cancel(chapterId: Long) {
        running[chapterId]?.cancel()
        library.tasks.delete(chapterId)
        layout.clearWorkDir(chapterId)
        // 这一条必须显式发。被 cancel 掉的那个任务会走到 runTask 的 CancellationException
        // 分支、试图上报 PAUSE，但 updateState 是「改完再读回来」，行已经删了就**什么都不发**。
        // 不补这一句，事件流上「任务消失」这件事就完全不存在（见 Removed 的注释）。
        _events.tryEmit(DownloadEvent.Removed(chapterId))
    }

    fun pauseAll() {
        library.tasks.listAll().filter { !it.state.isTerminal }.forEach { pause(it.chapterId) }
    }

    /**
     * 启动时调用：把上次进程留下的「进行中」任务打回暂停。
     *
     * @param autoResume true 时顺便把它们重新排进队列。默认 false——
     *   用户刚打开程序就被一堆下载占满带宽，不是每个人都想要。
     */
    fun recover(autoResume: Boolean = false): Int {
        val count = library.recoverInterruptedTasks()
        if (autoResume) {
            library.tasks.listUnfinished()
                .filter { it.state == DownloadState.PAUSE || it.state == DownloadState.WAIT }
                .forEach { queue.trySend(it.chapterId) }
        }
        return count
    }

    /** 当前在跑的任务数。 */
    fun activeCount(): Int = running.size

    override fun close() {
        queue.close()
        scope.cancel()
    }

    // ---------------------------------------------------------------- 队列

    private fun submit(comicId: Long, chapterId: Long): DownloadTask {
        val task = library.tasks.enqueue(comicId, chapterId)
        publish(task)
        queue.trySend(chapterId)
        return task
    }

    private suspend fun worker() {
        for (chapterId in queue) {
            val job = scope.launch(CoroutineName("download-task-$chapterId")) { runTask(chapterId) }
            running[chapterId] = job
            try {
                job.join()
            } finally {
                running.remove(chapterId)
            }
        }
        publishIdle()
    }

    // ---------------------------------------------------------------- 单章流程

    private suspend fun runTask(chapterId: Long) {
        val task = library.tasks.loadByChapter(chapterId) ?: return
        when (task.state) {
            // 入队之后被暂停 / 取消，轮到它时直接跳过
            DownloadState.PAUSE -> return
            // ERROR 要用户显式重试，不自动重来（否则坏图源会无限重试）
            DownloadState.ERROR -> return
            DownloadState.FINISH -> return
            else -> Unit
        }

        try {
            val comic = library.comics.load(task.comicId)
                ?: return fail(chapterId, "漫画记录不存在（comic=${task.comicId}）")
            val chapter = library.chapters.load(chapterId)
                ?: return fail(chapterId, "章节记录不存在")
            val info = registry.byType(comic.source)
                ?: return fail(chapterId, "图源 type=${comic.source} 已不在注册表中")
            // 注意用 loadForParser：图源的 parseImages 要的是合成 ID，不是行号
            val parserChapter = library.chapterForParser(chapterId)
                ?: return fail(chapterId, "章节记录不存在")

            val parser = factory.parser(comic.source)

            // ---- 解析图片列表
            updateState(chapterId, DownloadState.PARSE, progress = 0, total = 0)
            val images = parseImages(parser, comic, parserChapter)
            if (images.isEmpty()) {
                return fail(chapterId, "图源 ${info.title} 没有解析出任何图片")
            }
            library.chapters.setPageCount(chapterId, images.size)
            updateState(chapterId, DownloadState.DOING, progress = 0, total = images.size)

            // ---- 逐页下载
            val workDir = layout.createWorkDir(chapterId)
            val pages = ArrayList<Path>(images.size)
            for ((index, image) in images.withIndex()) {
                val target = workDir.resolve(
                    layout.pageName(index, layout.extensionOf(image.urls?.firstOrNull().orEmpty()))
                )
                // 续传：这一页上次已经下好了
                if (!isUsable(target)) {
                    downloadPage(parser, comic.source, image, target)
                }
                pages.add(target)
                updateState(chapterId, DownloadState.DOING, progress = index + 1, total = images.size)
            }

            // ---- 打包
            val target = layout.chapterFile(comic, chapter, ordinalOf(comic, chapterId), takenNames(comic, chapterId))
            deleteIfReplaced(chapterId, target)
            val cbz = CbzWriter.pack(target, pages)

            updateState(chapterId, DownloadState.FINISH, progress = images.size, filePath = cbz.toString())
            layout.clearWorkDir(chapterId)
        } catch (e: CancellationException) {
            // 暂停。必须用 NonCancellable：当前协程已经被取消，任何挂起/IO 都会立刻再抛
            withContext(NonCancellable) {
                runCatching { updateState(chapterId, DownloadState.PAUSE) }
            }
            throw e
        } catch (e: Throwable) {
            fail(chapterId, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }

    /** 解析章节的图片列表。两个重载都试，与原版语义一致（但这次回退分支是真的能走通）。 */
    private suspend fun parseImages(
        parser: MangaParser,
        comic: Comic,
        chapter: Chapter,
    ): List<ImageUrl> {
        // 第一个参数是**图源内的漫画 ID**（`Comic.cid`），不是 sourceComic，也不是数据库行号
        val request = parser.getImagesRequest(comic.cid, chapter.path)
            ?: return emptyList()

        val html = http.getBody(request)

        val withChapter = runCatching { parser.parseImages(html, chapter) }.getOrNull()
        if (!withChapter.isNullOrEmpty()) return withChapter

        return runCatching { parser.parseImages(html) }.getOrNull().orEmpty()
    }

    private suspend fun downloadPage(
        parser: MangaParser,
        source: Int,
        image: ImageUrl,
        target: Path,
    ) {
        val candidates = image.urls?.filter { it.isNotBlank() }.orEmpty()
        if (candidates.isEmpty()) {
            throw HttpException("", "这张图没有可用的下载地址")
        }

        val retry = settings.getInt(Keys.DOWNLOAD_RETRY, Defaults.DOWNLOAD_RETRY).coerceIn(1, MAX_RETRY)
        var last: Throwable? = null

        for (attempt in 0 until retry) {
            for (raw in candidates) {
                try {
                    val url = if (image.isLazy) resolveLazy(parser, raw) else raw
                    if (url.isNullOrBlank()) continue

                    http.downloadTo(buildRequest(parser, url), target)
                    // JMTT 有部分图片是打乱后发的，落盘后原地还原
                    if (JmttImage.applies(source, url)) JmttImage.process(target)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    last = e
                }
            }
            if (attempt < retry - 1) {
                // 线性退避。图源被打急了会持续失败，立刻重试只是把失败次数刷满
                delay(BACKOFF_STEP_MS * (attempt + 1))
            }
        }

        throw HttpException(candidates.first(), "重试 $retry 次仍未下载成功: ${last?.message}", last)
    }

    /** 惰性加载：先请求一次拿到真正的图片地址。 */
    private suspend fun resolveLazy(parser: MangaParser, url: String): String? {
        val request = parser.getLazyRequest(url) ?: return null
        return parser.parseLazy(http.getBody(request), url)
    }

    private fun buildRequest(parser: MangaParser, url: String): Request {
        val builder = Request.Builder()
            .cacheControl(CacheControl.Builder().noStore().build())
            .get()
            .url(url)

        // 基类 MangaParser.getHeader(url) 默认返回 null，而 OkHttp 的
        // `Builder.headers(null)` 会直接 NPE——必须判空。（原版 DownloadService
        // 是无条件 `.headers(headers)`，这里不照抄。）
        val headers = runCatching { parser.getHeader(url) }.getOrNull()
        if (headers != null) builder.headers(headers)

        return builder.build()
    }

    // ---------------------------------------------------------------- 路径与状态

    private fun ordinalOf(comic: Comic, chapterId: Long): Int {
        val comicId = comic.id ?: return 0
        val index = library.chaptersOf(comicId).indexOfFirst { it.id == chapterId }
        return if (index < 0) 0 else index
    }

    /**
     * 同一本漫画下**其他章节已经占用**的 CBZ 文件名。
     *
     * 用于避免两个章节标题清洗后撞成同一个文件名而互相覆盖。
     * 从 `download_task.file_path` 取，而不是扫目录——扫目录会把用户自己放进去的
     * 文件也算作「已占用」，导致文件名莫名其妙地带上 `(2)`。
     */
    private fun takenNames(comic: Comic, chapterId: Long): Set<String> {
        val comicId = comic.id ?: return emptySet()
        return library.tasks.listByComic(comicId)
            .filter { it.chapterId != chapterId && it.filePath != null }
            .mapNotNull { Path.of(it.filePath!!).fileName?.toString() }
            .toSet()
    }

    /** 重下时章节序号可能变了，旧成品会变成孤儿，顺手删掉。 */
    private fun deleteIfReplaced(chapterId: Long, target: Path) {
        val previous = library.tasks.loadByChapter(chapterId)?.filePath ?: return
        val old = runCatching { Path.of(previous) }.getOrNull() ?: return
        if (old.toAbsolutePath() == target.toAbsolutePath()) return
        runCatching { Files.deleteIfExists(old) }
    }

    private fun isUsable(file: Path): Boolean =
        Files.isRegularFile(file) && runCatching { Files.size(file) > 0L }.getOrDefault(false)

    private fun updateState(
        chapterId: Long,
        state: DownloadState,
        progress: Int? = null,
        total: Int? = null,
        error: String? = null,
        filePath: String? = null,
    ) {
        library.tasks.updateState(chapterId, state, progress, total, error, filePath)
        library.tasks.loadByChapter(chapterId)?.let(::publish)
    }

    private fun fail(chapterId: Long, message: String) {
        runCatching { updateState(chapterId, DownloadState.ERROR, error = message) }
    }

    private fun publish(task: DownloadTask) {
        _events.tryEmit(DownloadEvent.Changed(task))
    }

    private fun publishIdle() {
        _events.tryEmit(DownloadEvent.Idle)
    }

    companion object {
        /** 并发上限。再往上对漫画站就是攻击性的了。 */
        const val MAX_CONCURRENCY = 8

        /** 单页重试次数上限。 */
        const val MAX_RETRY = 10

        /** 重试退避的步长，第 n 次重试前等 `n * 800ms`。 */
        const val BACKOFF_STEP_MS = 800L
    }
}
