package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haleydu.cimoc.backend.engine.SearchEvent
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 搜索页：顶部搜索框，下面是结果网格，点封面进详情。
 *
 * ### 状态为什么全在这里
 *
 * 搜索结果是一次性的会话状态（换关键字就全丢），没有别的页面要用它，所以不往上提。
 * 切到别的导航项时本页会被整个销毁、结果随之丢失——这是刻意的取舍，不是为了省事：
 * 把六个页面的状态全部常驻，就得让它们即使不可见也不断后台加载（详情页会发网络请求）。
 *
 * ### 结果网格的 key 用 "source:cid"
 *
 * **不能用 [Comic] 对象当 key**：它的 `equals` 假设 `id` 非空（`Comic.java:76` 直接
 * `((Comic) o).id.equals(id)`），而搜索结果里的 `id` 是 null，一比对就 NPE；
 * 它也没重写 `hashCode`，放进 Set 里本来就是错的。
 */
@Composable
fun SearchPage() {
    val app = LocalApp.current

    var keyword by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<Comic>() }
    val gridState = rememberLazyGridState()

    var running by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var failures by remember { mutableStateOf<List<SearchEvent.SourceFailed>>(emptyList()) }
    var failuresShown by remember { mutableStateOf(false) }

    var opened by remember { mutableStateOf<Comic?>(null) }
    // 当前这次搜索的编号。用编号而不是「running 就拒绝」：用户想换关键字重搜时应当立刻
    // 响应，而不是等上一次跑完；而旧协程被 cancel 后还是会走到 finally，没有编号的话
    // 它会把新一轮的 running 抹掉。
    var generation by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    val submit: () -> Unit = {
        val trimmed = keyword.trim()
        if (trimmed.isNotEmpty()) {
            job?.cancel()
            results.clear()
            failures = emptyList()
            failuresShown = false
            doneCount = 0
            totalCount = 0
            running = true
            searched = true

            val mine = generation + 1
            generation = mine

            // 跑在 AppHolder 的 IO scope 上。search() 的 channelFlow 内部用阻塞 DAO，
            // 且它逐条产出、跨几十个图源，这里 collect 会持续很久。
            job = app.scope.launch {
                // 分母。enabledSources() 每源一次 SQLite 查询，所以放 IO 上做。
                totalCount = runCatching { app.backend.enabledSources().size }.getOrDefault(0)
                try {
                    app.backend.search.search(trimmed, page = 1).collect { event ->
                        when (event) {
                            is SearchEvent.Found -> results.add(event.comic)
                            is SearchEvent.SourceDone -> doneCount += 1
                            // 不弹窗：当前 40 个源里 26 个在本机连不上，弹窗会弹 26 次。
                            // 收进底部的可折叠区域，默认收起。
                            is SearchEvent.SourceFailed -> failures = failures + event
                            is SearchEvent.AllDone -> Unit
                        }
                    }
                } finally {
                    if (generation == mine) running = false
                }
            }
        }
    }

    // 新的一轮搜索开始时回到顶部。
    //
    // **必须挂在这里、不能写进上面那个 IO 协程**：scrollToItem 会让 LazyGrid 立刻
    // forceRemeasure，而 measure/layout 只能在 UI 线程上跑，在 IO 线程调用会抛
    // 「Detected multithreaded access to SnapshotStateObserver」并**把整个搜索协程打死**
    // （异常发生在 collect 之前，结果是一条都收不到）。LaunchedEffect 跑在组合的
    // 线程上，也就是 UI 线程。
    LaunchedEffect(generation) {
        if (generation > 0) gridState.scrollToItem(0)
    }

    val detailComic = opened
    if (detailComic != null) {
        ComicDetailPage(detailComic, onBack = { opened = null })
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SearchBar(
            keyword = keyword,
            onKeywordChange = { keyword = it },
            onSubmit = submit,
        )

        Spacer(Modifier.height(10.dp))

        StatusLine(
            searched = searched,
            running = running,
            done = doneCount,
            total = totalCount,
            found = results.size,
            failed = failures.size,
        )

        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = results,
                    key = { "${it.source}:${it.cid}" },
                ) { comic ->
                    ResultCell(comic) { opened = comic }
                }
            }

            if (!running && results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            !searched -> "输入关键字，回车或点「搜索」开始"
                            else -> "没有结果。可以换个关键字，或在「图源」里换一批源再试。"
                        },
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }

        if (failures.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FailureSection(
                failures = failures,
                expanded = failuresShown,
                onToggle = { failuresShown = !failuresShown },
            )
        }
    }
}

@Composable
private fun SearchBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    // 用 onPreviewKeyEvent 而不是 KeyboardActions(onSearch)：桌面端的
                    // 回车是普通按键，不经过 IME 的 action 那条路。
                    //
                    // 只认 KeyDown。KeyDown + KeyUp 都处理的话会搜两次。
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
            placeholder = { Text("漫画名 / 作者") },
            singleLine = true,
        )
        Spacer(Modifier.width(10.dp))
        Button(onClick = onSubmit, modifier = Modifier.height(56.dp)) {
            Text("搜索")
        }
    }
}

@Composable
private fun StatusLine(
    searched: Boolean,
    running: Boolean,
    done: Int,
    total: Int,
    found: Int,
    failed: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (running) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = when {
                !searched -> "尚未搜索"
                running -> "搜索中… 已完成 $done/$total 个图源，收到 $found 条"
                else -> "完成 · 图源 $done/$total，结果 $found 条" + if (failed > 0) "，失败 $failed 个" else ""
            },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * 一格结果。封面用 [aspectRatio] 固定比例——不给高度的话，网格里每格高度会随图片
 * 原始尺寸变，同一行看起来会参差不齐。
 */
@Composable
private fun ResultCell(comic: Comic, onClick: () -> Unit) {
    // title(type) 只是查内存里的 Map，不是阻塞调用，可以在这里拿
    val sourceTitle = LocalApp.current.backend.parsers.title(comic.source)

    Column(Modifier.clickable(onClick = onClick).padding(6.dp)) {
        CoverImage(
            comic = comic,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT_RATIO)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = comic.title ?: "",
            style = MaterialTheme.typography.body2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // 同一本漫画会从多个源搜出来，必须标出是哪个源——否则用户不知道点哪个
            text = listOfNotNull(sourceTitle, comic.author?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
                .ifEmpty { "未知作者" },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 失败的图源。默认收起，点标题展开。
 *
 * 不做弹窗是硬要求：本机 40 个源里 26 个连不上，一次搜索会稳定产出二十多条失败，
 * 弹窗会连弹二十六次，把真正要看的结果盖住。
 */
@Composable
private fun FailureSection(
    failures: List<SearchEvent.SourceFailed>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${failures.size} 个图源没有返回结果（点${if (expanded) "收起" else "展开"}）",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
            )
        }

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(FAILURE_BOX_HEIGHT)
                    .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                // 当前源多半是连不上，所以错误文本总是要能看清、能复制走
                for (failure in failures) {
                    Text(
                        text = "${failure.title}（type=${failure.type}）：${failure.error}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 每行几格。用户要求的就是 4。 */
private const val COLUMNS = 4

private val FAILURE_BOX_HEIGHT = 132.dp
