package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.haleydu.cimoc.backend.CimocBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图源页：一行一个图源，右侧开关决定搜索时用不用它，开关右边是「设置」。
 *
 * ### 开关为什么只影响搜索
 *
 * 开关读写的是 `source_config` 表（[com.haleydu.cimoc.backend.source.ParserFactory.setEnabled]），
 * 而 [com.haleydu.cimoc.backend.engine.SearchEngine] 的 `sources = null` 走的就是
 * 「全部启用中的图源」。所以关掉一个源＝它不再参与搜索；收藏、下载里已有的条目
 * 不受影响（那些是按 type 直接取解析器的，不看开关）——这是对的，否则关一个源会连带
 * 让已下好的漫画读不了。
 *
 * ### 为什么开关是「先改界面再写库」
 *
 * 写库要走 SQLite。等它返回再拨开关，快速连点时界面会一顿一顿的。所以先乐观更新，
 * 写失败再拨回去——**必须拨回去**，否则界面显示的和库里存的不一致，用户下次搜索
 * 会看到「这个源明明是开的却不出结果」。
 *
 * ### 拖动排序
 *
 * 每行**左侧一个手柄**（`≡`），按住上下拖可以改这一行的位置，松手时把整份顺序写回
 * `setting` 表的 `source.order`（见 `ParserFactory.setOrder`），重启后还在。
 *
 * 只让手柄可拖、不做整行可拖：行里的 `Switch` 和「设置」按钮都要吃掉点击事件，
 * 整行再挂一个手势会和它们抢。
 *
 * **这个顺序只影响这一页。** 搜索结果跟它无关——搜索是几十个源并发跑、谁先返回谁先排
 * （见 `SearchEngine`），把某个源拖到第一位并不会让它的结果排在最前面。
 *
 * ### 「设置」按钮是占位
 *
 * 按当前需求先放一个空按钮，点它只在顶部出一行说明。不把按钮做成 disabled：
 * 一个灰掉的按钮看起来像「这个源坏了」，而现在只是功能没做。
 */
// animateItemPlacement() 在 Compose 1.6.11 里还带着 @ExperimentalFoundationApi
// （去掉实验标注的 Modifier.animateItem() 要 1.7 才有）。
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourcesPage(onSourcesChanged: () -> Unit) {
    val app = LocalApp.current

    val rows = remember { mutableStateListOf<SourceRow>() }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 拖动要按布局算落点，必须自己拿着 state
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // ---- 拖动状态。同一时刻只可能有一行在手，所以放在页这一层；每行只负责把手柄上的
    //      指针事件转成对这组的调用，不各自 remember 一套。
    /** 正在拖的行的 key（= type）。null 表示没有在拖。 */
    var draggingType by remember { mutableStateOf<Int?>(null) }

    /** 手势开始时被拖行的槽位顶边（视口坐标，px）。 */
    var dragStartTop by remember { mutableStateOf(0f) }

    /** 手指自手势开始起的累计位移（px），**不做任何补偿**的原始量。 */
    var dragTotal by remember { mutableStateOf(0f) }

    /** 被拖行的高度（px），算中线用。 */
    var dragSize by remember { mutableStateOf(0) }

    /** 真正喂给 `graphicsLayer` 的 translationY。 */
    var dragTranslation by remember { mutableStateOf(0f) }

    /** 手势开始前那一份顺序，写库失败时用它回滚。 */
    var orderBefore by remember { mutableStateOf<List<Int>>(emptyList()) }

    LaunchedEffect(app.backend) {
        runCatching { loadSourceRows(app.backend) }
            .onSuccess { list ->
                rows.clear()
                rows.addAll(list)
            }
            .onFailure { error ->
                loadError = error.javaClass.simpleName + ": " + (error.message ?: "(无消息)")
            }
        loading = false
    }

    val setEnabled: (SourceRow, Boolean) -> Unit = { row, enabled ->
        val index = rows.indexOfFirst { it.type == row.type }
        if (index >= 0) {
            rows[index] = rows[index].copy(enabled = enabled)
        }

        app.scope.launch {
            val result = runCatching { app.backend.parsers.setEnabled(row.type, enabled) }
            if (result.isFailure) {
                val i = rows.indexOfFirst { it.type == row.type }
                if (i >= 0) {
                    rows[i] = rows[i].copy(enabled = !enabled)
                }
                notice = "「${row.title}」的开关没写进库：" +
                    (result.exceptionOrNull()?.message ?: "未知错误") + "（已拨回原样）"
            } else {
                // 导航栏底部那行「已启用 x/y」要跟着变
                onSourcesChanged()
            }
        }
    }

    // ---------------------------------------------------------------- 拖动排序

    /**
     * 把「手指走到哪、压到了谁头上、该浮在什么位置」重算一遍。
     *
     * `onDrag` 和下面那个自动滚动循环都会让局面变化，两边都调它，免得同一套算法写两份。
     *
     * 浮起位置这么算：被拖行的**视觉顶边**恒等于 `dragStartTop + dragTotal`（手指走多远它
     * 浮多远），`translationY` 只是这个视觉位置与它当前槽位顶边之间的差。换位之后槽位跳了
     * 一格，差值自动跟着变——这就是补偿，不用手算「减一行高、加一个 8dp 间距」。窗口缩放、
     * 自动滚动同理，因为读的都是实时的 `offset`。
     */
    fun updateDragPlacement() {
        val type = draggingType ?: return
        val dragged = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == type } ?: return
        val renderedTop = dragStartTop + dragTotal

        // 命中：被拖行的中线落在谁的格子里。用 [offset, offset + size) 而不是把 8dp 行间距也
        // 算进去——中线恰好落在缝隙里时不换位，看起来更稳。
        // 判「是不是自己」用 key 不用 index：换位时 index 会变，key 不会。
        val center = renderedTop + dragSize / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != type && center >= it.offset && center < it.offset + it.size
        }

        val from = rows.indexOfFirst { it.type == type }
        if (target == null || from < 0 || target.index == from) {
            // 没换位（拖出列表外、落在缝隙里、没有别的候选），槽位就是当前布局里的位置
            dragTranslation = renderedTop - dragged.offset
            return
        }

        // removeAt + add，不用 Collections.swap：拖快了一步跨过好几行时，swap 只动两头的元素，
        // 中间那些行不跟着挪，结果就错了。
        rows.add(target.index, rows.removeAt(from))
        // 换位后它的新槽位就是 target 原来的位置。这里**不能**重读 visibleItemsInfo——
        // 重排要等下一帧才重组，现在读到的还是旧布局，会算出一帧的错位。
        dragTranslation = renderedTop - target.offset
    }

    val onDragStart: (Int) -> Unit = { type ->
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == type }
        if (info != null) {
            draggingType = type
            dragStartTop = info.offset.toFloat()
            dragSize = info.size
            dragTotal = 0f
            dragTranslation = 0f
            orderBefore = rows.map { it.type }
            notice = null
        }
    }

    val onDragDelta: (Int, Float) -> Unit = { type, dy ->
        if (draggingType == type) {
            dragTotal += dy
            updateDragPlacement()
        }
    }

    val onDragStop: (Int) -> Unit = { type ->
        if (draggingType == type) {
            draggingType = null
            dragTotal = 0f
            dragTranslation = 0f

            val types = rows.map { it.type }
            // 没挪动就不写库：一次手势写一次就够，没变还写纯属浪费
            if (types != orderBefore) {
                app.scope.launch {
                    // setOrder 是阻塞的 SQLite 写
                    val result = runCatching {
                        withContext(Dispatchers.IO) { app.backend.parsers.setOrder(types) }
                    }
                    if (result.isFailure) {
                        // 按原来的顺序重排现有的行，而不是拿快照整表覆盖：拖动期间用户可能
                        // 还拨过某个开关，覆盖会把那一下抹掉。
                        //
                        // 注意 AppSettings.put 是先改内存缓存再写库，失败时缓存里可能已经是
                        // 新顺序了。界面这一份先拨回去，重启后以库为准。
                        val restored = orderBefore.mapNotNull { t -> rows.firstOrNull { it.type == t } }
                        rows.clear()
                        rows.addAll(restored)
                        notice = "图源顺序没写进库：" +
                            (result.exceptionOrNull()?.message ?: "未知错误") + "（已恢复原顺序）"
                    }
                }
            }
        }
    }

    // 拖到可视区上下边缘时自动滚。
    //
    // onDrag 回调不是挂起函数，里面 scrollBy 不了，所以另起一个循环来盯。滚动会让被拖行的
    // 槽位跟着动，所以每滚一下都要重算浮起位置——复用 updateDragPlacement()，它读的是实时
    // 的 offset，滚动被自动吸收，不需要额外补偿。
    LaunchedEffect(draggingType, listState) {
        if (draggingType == null) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val step = with(density) { 10.dp.toPx() }
        while (isActive) {
            val top = dragStartTop + dragTotal
            val viewport = listState.layoutInfo.viewportSize.height.toFloat()
            val delta = when {
                top < edge -> -step
                top + dragSize > viewport - edge -> step
                else -> 0f
            }
            if (delta != 0f) {
                listState.scrollBy(delta)
                updateDragPlacement()
            }
            delay(16)
        }
    }

    // 恢复默认顺序：把 source.order 这一行删掉（put(key, null) 的语义就是删），
    // 顺序回到 type 升序，然后重新拉一次清单。
    val resetOrder: () -> Unit = {
        app.scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { app.backend.parsers.setOrder(emptyList()) }
            }
            result
                .onSuccess {
                    runCatching { loadSourceRows(app.backend) }
                        .onSuccess { list ->
                            rows.clear()
                            rows.addAll(list)
                            loadError = null
                            notice = "已恢复默认顺序（按 type 升序）"
                        }
                        .onFailure { error ->
                            loadError = error.javaClass.simpleName + ": " + (error.message ?: "(无消息)")
                        }
                }
                .onFailure { error ->
                    notice = "恢复默认顺序失败：" + (error.message ?: "未知错误")
                }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("图源", style = MaterialTheme.typography.h5)
            Spacer(Modifier.width(12.dp))
            if (loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "读取中…",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            } else {
                Text(
                    text = "已启用 ${rows.count { it.enabled }} / 共 ${rows.size}",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.weight(1f))

            // 拖乱了想回到 type 升序时的出口。没有它就只能一行一行拖回去。
            TextButton(onClick = resetOrder, enabled = !loading) {
                Text("恢复默认排序", style = MaterialTheme.typography.caption)
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "只有打开开关的图源会参与搜索。关掉一个源不会影响收藏和已下载的漫画。" +
                "按住左侧的 ≡ 上下拖可以调整这里的排列顺序。",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )

        loadError?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                "图源清单读取失败：$message",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
            )
        }

        // 缺配置的源**单独提一句**，不是每条自己标一下就够了：它们被启用的话每次搜索
        // 都稳定失败一条，用户不一定会去逐行看副标题。
        val unconfigured = rows.count { it.warning != null }
        if (unconfigured > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "有 $unconfigured 个图源缺少必需配置（下面标红的行）：开着它们，每次搜索都会失败一条。",
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
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // key 用 type：它是图源的唯一标识，也省得记住 40 个源的位置。
                // 拖动排序也靠它——换位时行的 key 不变，Compose 才知道「是同一行挪了位置」
                // 而不是「内容变了」，让位动画和拖动中的那一行都以此为前提。
                items(rows, key = { it.type }) { row ->
                    val dragging = draggingType == row.type
                    SourceRowItem(
                        row = row,
                        modifier = if (dragging) {
                            // 被拖这一行：浮起来，并按手指走。zIndex 保证它盖在邻行上面
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = dragTranslation }
                        } else {
                            // 其余各行：让位动画。**被拖那行不能加**——它已经有上面那个
                            // translationY 了，两个位移叠在一起会抖。
                            Modifier.animateItemPlacement()
                        },
                        dragEnabled = rows.size > 1,
                        onDragStart = { onDragStart(row.type) },
                        onDragDelta = { dy -> onDragDelta(row.type, dy) },
                        onDragStop = { onDragStop(row.type) },
                        onToggle = { enabled -> setEnabled(row, enabled) },
                        onSettings = {
                            notice = "「${row.title}」的设置项还没有做——先留成占位，" +
                                "等设置页一起补。"
                        },
                    )
                }
            }
        }
    }
}

/** 一行的状态。开关是乐观更新的，所以这里存的是「界面当前显示的状态」。 */
private data class SourceRow(
    val type: Int,
    val title: String,
    val defaultEnabled: Boolean,
    val enabled: Boolean,
    /** 图源缺的必需配置，null 表示不缺。 */
    val warning: String?,
)

@Composable
private fun SourceRowItem(
    row: SourceRow,
    modifier: Modifier = Modifier,
    dragEnabled: Boolean,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragStop: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    // 关掉的源整行压暗，让「现在参与搜索的是哪些」一眼能扫出来
    val dim = if (row.enabled) 1f else 0.55f

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = if (row.enabled) 0.07f else 0.03f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragHandle(
            enabled = dragEnabled,
            onStart = onDragStart,
            onDelta = onDragDelta,
            onStop = onDragStop,
        )
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurface.copy(alpha = dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    // type 是排错时唯一的对号入座依据：搜索页的失败列表里报的就是它
                    append('#').append(row.type)
                    append(" · ").append(if (row.defaultEnabled) "默认启用" else "默认禁用")
                    row.warning?.let { append(" · ⚠ ").append(it) }
                },
                style = MaterialTheme.typography.caption,
                color = if (row.warning != null) {
                    MaterialTheme.colors.error
                } else {
                    MaterialTheme.colors.onSurface.copy(alpha = 0.6f * dim)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))

        Switch(checked = row.enabled, onCheckedChange = onToggle)

        Spacer(Modifier.width(4.dp))

        TextButton(onClick = onSettings) {
            Text("设置", style = MaterialTheme.typography.caption)
        }
    }
}

/**
 * 拖动手柄。**只有这一块响应拖动**，整行不响应——行里的开关和「设置」按钮都要吃点击，
 * 整行再挂一个手势会和它们抢事件。
 *
 * 触摸区写死 30×40dp，不用 `fillMaxHeight()`：LazyColumn 的 item 在主轴上是无限高度约束，
 * `fillMaxHeight` 遇到无限约束会被忽略、缩回文字那么大，反而更难按。
 */
@Composable
private fun DragHandle(
    enabled: Boolean,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onStop: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 30.dp, height = 40.dp)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onStart() },
                            onDragEnd = { onStop() },
                            onDragCancel = { onStop() },
                            onDrag = { change, amount ->
                                // 吃掉这次事件，别让父级 LazyColumn 收到之后跟着滚起来。
                                // 只管 y：这是上下排序，横向分量丢掉
                                change.consume()
                                onDelta(amount.y)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 沿用本项目「不用 material-icons、一律文字字形」的惯例（同 ← 返回、▾、⚠）。
            // 只有一个图源时手柄压到几乎看不见——拖也没得拖
            text = "≡",
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) 0.45f else 0.12f),
        )
    }
}

/**
 * 拉一次图源清单。
 *
 * 「读清单」本身是内存操作，但每个图源的开关和缺失配置（`enabled` / `unavailableReason`）
 * 都要单独查一次库，40 多个源放主线程上会卡，所以整体挪到 IO 上。
 * 首屏和「恢复默认排序」都走它。
 */
private suspend fun loadSourceRows(backend: CimocBackend): List<SourceRow> =
    withContext(Dispatchers.IO) {
        val factory = backend.parsers
        backend.sources().map { info ->
            SourceRow(
                type = info.type,
                title = info.title,
                defaultEnabled = info.defaultEnabled,
                enabled = factory.enabled(info.type),
                warning = factory.unavailableReason(info.type),
            )
        }
    }
