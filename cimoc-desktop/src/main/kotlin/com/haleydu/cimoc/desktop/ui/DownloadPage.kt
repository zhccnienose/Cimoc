package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haleydu.cimoc.backend.download.DownloadEvent
import com.haleydu.cimoc.backend.download.DownloadState
import com.haleydu.cimoc.backend.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * 下载页：一行一个章节，左边是标题和进度条，右边是暂停 / 继续 / 取消。
 *
 * ### 必须先铺底再订阅
 *
 * [com.haleydu.cimoc.backend.download.DownloadManager.events] 是 `replay = 0`：**订阅之前
 * 发过的事件不会补发**。所以进来第一件事是 `books.tasks.listAll()` 读一遍库里已有的任务，
 * 读完再 `collect`。顺序反了，「上次没下完、这次自动续传」的任务在页面上永远是空的
 * （导航栏的角标也踩过同一个坑，见 [com.haleydu.cimoc.desktop.AppHolder.watchDownloads]）。
 *
 * 铺底与订阅之间有一个几十微秒的窗口，这期间发的事件会丢。不为它加锁：进度是**按页**
 * 发的，一章几十条，下一条就会把状态纠正回来；真要为它加一层缓冲，复杂度远超收益。
 *
 * ### 进度口径是「页」
 *
 * `progress / total` 都是**页数**（一章几十页，每页一个图片文件），不是章节数。
 * 这正是需求要的「已下载文件数 / 总文件数」——一页 = 一个文件。`total` 在解析出图片
 * 列表之前是 0（[DownloadState.WAIT] / [DownloadState.PARSE]），这时候显示 0/0 毫无
 * 意义，所以那两行改成不定态进度条或干脆不画条，由左侧的状态文字说明在干什么。
 *
 * ### 行序按 task.id 升序，不是 DAO 的 `ORDER BY id DESC`
 *
 * 用户在详情页勾选章节是从上往下勾的，入队顺序就是章节顺序；按 id 升序排出来，
 * 列表读起来就是「第 1 话 → 第 2 话 → …」。DAO 那个 DESC 是给「最近的在前」用的，
 * 放在这里会让整本漫画的章节倒着排。
 */
@Composable
fun DownloadPage() {
    val app = LocalApp.current

    val tasks = remember { mutableStateListOf<DownloadTask>() }
    // 标题只在第一次见到这个 id 时查一次库，之后走内存。不能每条进度事件都查：
    // 进度按页发，一章几十条，等于把 SQLite 放进热路径。
    val comicTitles = remember { mutableStateMapOf<Long, String>() }
    val chapterTitles = remember { mutableStateMapOf<Long, String>() }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    // reload 也在 key 里：点「刷新」时整个 effect 重来一遍（重读库 + 重新订阅）。
    // 重读前必须 clear，否则 listAll 的结果会追加到已有列表后面，行数翻倍。
    LaunchedEffect(app.backend, reload) {
        tasks.clear()
        loadError = null

        val initial = withContext(Dispatchers.IO) {
            runCatching { app.backend.books.tasks.listAll() }
        }
        initial
            .onSuccess { list -> tasks.addAll(list.sortedBy { it.id }) }
            .onFailure { loadError = describe(it) }
        loaded = true

        app.backend.download.events.collect { event ->
            when (event) {
                is DownloadEvent.Changed -> {
                    // 按 chapterId 而不是 task.id 找行：chapterId 是任务的真正身份
                    // （download_task 一行一章），取消后重下会换一个新的 task.id
                    val index = tasks.indexOfFirst { it.chapterId == event.task.chapterId }
                    if (index >= 0) {
                        tasks[index] = event.task
                    } else {
                        tasks.add(insertIndexFor(tasks, event.task.id), event.task)
                    }
                }

                // 取消只发这一个事件（任务行已经删了，没有完整的 task 可发）。
                // 少了它，取消在界面上不可见——列表里那行会一直留着。
                is DownloadEvent.Removed -> {
                    val index = tasks.indexOfFirst { it.chapterId == event.chapterId }
                    if (index >= 0) tasks.removeAt(index)
                }

                // 队列跑空。本页不需要它：「还有没有在跑」是从列表里的状态算出来的，
                // 而 Idle 只在全部 worker 退出时才发（正常使用中不会出现）。
                is DownloadEvent.Idle -> Unit
            }
        }
    }

    // 补齐列表里还没有标题的行。键用「缺哪些 id」而不是任务列表本身：
    // 进度事件每页都改任务，拿任务列表当键会每页重跑一次。
    val wantComics = tasks.map { it.comicId }.distinct().filter { it !in comicTitles }
    val wantChapters = tasks.map { it.chapterId }.distinct().filter { it !in chapterTitles }
    LaunchedEffect(wantComics, wantChapters) {
        if (wantComics.isEmpty() && wantChapters.isEmpty()) return@LaunchedEffect
        val (comics, chapters) = withContext(Dispatchers.IO) {
            wantComics.associateWith { app.backend.books.comics.load(it)?.title.orEmpty() } to
                wantChapters.associateWith { app.backend.books.chapters.load(it)?.title.orEmpty() }
        }
        // 查不到也写个空串进去：否则这个 id 会一直留在 want 列表里，每次重组都重查一遍
        comicTitles.putAll(comics)
        chapterTitles.putAll(chapters)
    }

    /** 不在 IO 上跑会卡住界面：Dao 全是阻塞的。 */
    fun act(what: String, block: () -> Unit) {
        app.scope.launch {
            runCatching(block)
                .onFailure { notice = "$what 失败：${describe(it)}" }
        }
    }

    val active = tasks.count { it.state != DownloadState.FINISH }
    val running = tasks.any {
        it.state == DownloadState.WAIT || it.state == DownloadState.PARSE || it.state == DownloadState.DOING
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下载", style = MaterialTheme.typography.h5)
            Spacer(Modifier.width(12.dp))
            if (!loaded) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "读取中…",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            } else {
                Text(
                    // 口径和左侧导航的角标一致（state != FINISH，出错的也算未完成）
                    "共 ${tasks.size} 个任务 · 未完成 $active",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = running,
                onClick = { act("全部暂停") { app.backend.download.pauseAll() } },
            ) {
                Text("全部暂停")
            }
            TextButton(onClick = { reload += 1 }) { Text("刷新") }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "进度按页统计：一章由几十张图片组成，下完一章会打包成一个 CBZ 文件。",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )

        loadError?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                "任务列表读取失败：$message",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
            )
        }

        notice?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // key 用 chapterId：download_task 一行一章，它是这行的身份。
                // 用 task.id 的话，取消后重下会换 id，那一行会被当成新行重建
                items(items = tasks, key = { it.chapterId }) { task ->
                    TaskRow(
                        task = task,
                        chapterTitle = chapterTitles[task.chapterId].orEmpty(),
                        comicTitle = comicTitles[task.comicId].orEmpty(),
                        onPause = { act("暂停") { app.backend.download.pause(task.chapterId) } },
                        onResume = { act("继续下载") { app.backend.download.resume(task.chapterId) } },
                        onCancel = { act("取消") { app.backend.download.cancel(task.chapterId) } },
                        onOpenFolder = { notice = openContainingFolder(task.filePath) },
                    )
                }
            }

            if (loaded && tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (loadError != null) {
                            "没有读到任务。上面是失败原因。"
                        } else {
                            "还没有下载任务。在漫画详情页勾选章节后点「开始下载」。"
                        },
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: DownloadTask,
    chapterTitle: String,
    comicTitle: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    // 暂停的行压暗。一屏几十行时，靠读状态文字找「哪些还没下完」太慢
    val dim = if (task.state == DownloadState.PAUSE) 0.6f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                // 章节行理论上不会被删（收藏和下载都会把它留住），查不到就是数据不一致，
                // 这时候把行号印出来，排错时至少能对上库里的那一行
                text = chapterTitle.ifEmpty { "章节 #${task.chapterId}" },
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurface.copy(alpha = dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = comicTitle,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f * dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))
            ProgressLine(task)

            // 失败原因原文给出来：它来自图源解析或 HTTP 层（见 README 未完成项），
            // 界面能做的只是把它完整交到用户手上
            task.error?.let { reason ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "失败原因：$reason",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 每种状态只给一个主操作，避免「暂停 / 取消」并排时误点——取消会删掉任务记录
        when (task.state) {
            DownloadState.WAIT, DownloadState.PARSE, DownloadState.DOING ->
                TextButton(onClick = onPause) { Text("暂停") }

            DownloadState.PAUSE -> TextButton(onClick = onResume) { Text("继续") }

            // ERROR 不自动重试（坏图源会无限重试），只能由用户显式点这一次
            DownloadState.ERROR -> TextButton(onClick = onResume) { Text("重试") }

            DownloadState.FINISH -> TextButton(onClick = onOpenFolder) { Text("打开所在文件夹") }
        }

        TextButton(onClick = onCancel) {
            // 同一个 cancel()，但对已完成的任务说「取消」是错的：它没在跑，
            // 这句实际是「把这条记录从列表里去掉」（CBZ 文件保留，成品属于用户）
            Text(if (task.state == DownloadState.FINISH) "移除记录" else "取消")
        }
    }
}

/** 状态文字 + 「已下载 n/m 页」+ 进度条。 */
@Composable
private fun ProgressLine(task: DownloadTask) {
    // total <= 0 时进度是没有意义的（0/0），用 null 表示「算不出百分比」，
    // 而不是拿 0f 去画一根空条——空条会被读成「一页都没下成」
    val fraction = if (task.total <= 0) {
        null
    } else {
        (task.progress.toFloat() / task.total).coerceIn(0f, 1f)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stateLabel(task.state),
                style = MaterialTheme.typography.caption,
                color = stateColor(task.state),
                modifier = Modifier.weight(1f),
            )
            if (task.total > 0) {
                Text(
                    // 需求要的标注。一页 = 一个文件，所以这就是「已下载文件数 / 总文件数」
                    text = "已下载 ${task.progress}/${task.total} 页",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        when {
            fraction != null -> LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = stateColor(task.state),
                backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            )

            // 解析中是真的在干活，用不定态表示「在动但还估不出多久」。
            // 排队中用不了——那会看起来像已经在跑了
            task.state == DownloadState.PARSE -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            )

            else -> Spacer(Modifier.height(6.dp))
        }
    }
}

private fun stateLabel(state: DownloadState): String = when (state) {
    DownloadState.WAIT -> "排队中"
    DownloadState.PARSE -> "正在解析图片列表…"
    DownloadState.DOING -> "下载中"
    DownloadState.PAUSE -> "已暂停"
    DownloadState.FINISH -> "已完成"
    DownloadState.ERROR -> "失败"
}

@Composable
private fun stateColor(state: DownloadState): Color = when (state) {
    DownloadState.ERROR -> MaterialTheme.colors.error
    DownloadState.PAUSE -> MaterialTheme.colors.onSurface.copy(alpha = 0.45f)
    DownloadState.FINISH -> MaterialTheme.colors.primary
    else -> MaterialTheme.colors.primary
}

/** 新任务按 id 插到正确位置，保持列表始终是「入队顺序」（见文件头的行序说明）。 */
private fun insertIndexFor(list: List<DownloadTask>, id: Long): Int =
    list.indexOfFirst { it.id > id }.let { if (it < 0) list.size else it }

/**
 * 打开成品 CBZ 所在的文件夹。
 *
 * 用 `Desktop.open(目录)` 而不是打开文件本身：CBZ 的默认关联程序可能是压缩软件，
 * 用户想看的是「下到哪儿了」，这跟打开压缩包不是一回事。
 *
 * 返回一句话给页面顶部的提示行（成功时给路径，失败时给原因）。
 */
private fun openContainingFolder(filePath: String?): String {
    if (filePath.isNullOrBlank()) return "这个任务还没有产出文件。"

    return runCatching {
        check(Desktop.isDesktopSupported()) { "当前平台不支持打开文件管理器" }
        val file = File(filePath)
        val dir = file.parentFile ?: error("路径没有父目录：$filePath")
        // 只下了一半就被取消的任务，记录里的路径指向的文件可能已经不在
        check(dir.isDirectory) { "目录不存在（可能已被移动或删除）：$dir" }
        Desktop.getDesktop().open(dir)
        "已在文件管理器中打开：$dir"
    }.getOrElse { "打开文件夹失败：${describe(it)}" }
}
