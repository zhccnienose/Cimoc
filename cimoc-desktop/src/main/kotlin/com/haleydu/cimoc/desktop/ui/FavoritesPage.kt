package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haleydu.cimoc.backend.storage.Defaults
import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.desktop.UpdateCheckState
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收藏页：每行 4 本，封面的下三分之一盖一层半透明白蒙版，上面写漫画名（黑）与图源名（灰）。
 *
 * ### 为什么蒙版里放书名，而不是把书名排在封面下面
 *
 * 要求就是这么定的，也确实更省纵向空间：每行 4 本、一屏能放三行多，书名排在下面的话
 * 每本要多占一行文字的高度，一屏就少一行。封面本身杂色多，直接压黑字或白字都读不清，
 * 所以垫一层半透明白——它是「让文字可读」的手段，不是为了好看。
 *
 * ### 点开的是缓存，不是网络
 *
 * [ComicDetailPage] 的 `fromCache = true`：详情用收藏时写下的 JSON 快照。
 * 见 [com.haleydu.cimoc.backend.storage.FavoriteCache] 与详情页的注释。
 *
 * ### 更新检查挂在这一页上
 *
 * 原来导航栏里有个独立的「更新」入口，那只是个占位页。更新检查的对象就是收藏夹，
 * 所以它做进了这里：一个「检查更新」按钮 + 一行状态。
 *
 * 按钮和状态都**不属于本页**：状态在 [com.haleydu.cimoc.desktop.AppHolder] 上，
 * 自动检查在程序启动时就可能已经在跑了（那时用户还没进过这一页），手动检查跑一半
 * 切走再切回来也不该从头开始。这里只负责订阅与显示。
 *
 * ### 从详情页返回要重读列表
 *
 * 详情页里可以取消收藏，回来后那本就该消失。页面本身不会重组到新数据（`favorites()`
 * 是一次阻塞查询），所以 [revision] 在返回时 +1，让下面那个 LaunchedEffect 重跑。
 * 一轮更新检查跑完同理——[UpdateCheckState.Done.finishedAt] 变化就是那个信号。
 */
@Composable
fun FavoritesPage(
    updateCheck: StateFlow<UpdateCheckState>,
    onCheckUpdates: () -> Unit,
) {
    val app = LocalApp.current
    val check by updateCheck.collectAsState()

    val items = remember { mutableStateListOf<Comic>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    var opened by remember { mutableStateOf<Comic?>(null) }

    // 只有 Done 参与 key。整个 check 放进去的话，检查过程中每查完一本进度就变一次，
    // 这个 effect 会跟着重跑一遍——等于每本漫画重读一次收藏夹。
    val checkedAt = (check as? UpdateCheckState.Done)?.finishedAt

    LaunchedEffect(app.backend, revision, checkedAt) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { app.backend.favorites.list() }
        }
        loaded
            .onSuccess { list ->
                items.clear()
                items.addAll(list)
            }
            .onFailure { error = "收藏夹读取失败：${describe(it)}" }
        loading = false
    }

    /** 「全部标为已读」：更新检查留下的高亮只有这里能清掉，否则它会一直在收藏页上。 */
    fun markAllRead() {
        app.scope.launch {
            runCatching { app.backend.books.clearHighlights() }
                .onSuccess { revision += 1 }
                .onFailure { error = "标记已读失败：${describe(it)}" }
        }
    }

    val current = opened
    if (current != null) {
        ComicDetailPage(
            comic = current,
            onBack = {
                opened = null
                // 详情页可能刚取消过收藏，回来必须重读
                revision += 1
            },
            fromCache = true,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("收藏", style = MaterialTheme.typography.h5)
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
                    "共 ${items.size} 本",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.weight(1f))
            // 只有确实存着「有更新」标记时才出现：平时多一个按钮只是噪音
            if (items.any { it.highlight }) {
                TextButton(onClick = { markAllRead() }) { Text("全部标为已读") }
            }
            TextButton(
                // 检查中禁用而不是隐藏：按钮突然消失会让人以为点错了；
                // 禁用时位置还在，状态行也正在说明它在干什么
                enabled = check !is UpdateCheckState.Running,
                onClick = onCheckUpdates,
            ) {
                Text("检查更新")
            }
            TextButton(onClick = { revision += 1 }) { Text("刷新") }
        }

        CheckStatusLine(check)

        error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // key 用 "source:cid"，不能拿 Comic 当 key——Comic.equals 在 id 为 null 时
                // 直接 NPE（见详情页与 FRONTEND.md 第六节）
                items(items = items, key = { "${it.source}:${it.cid}" }) { comic ->
                    FavoriteCell(comic) { opened = comic }
                }
            }

            if (!loading && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (error != null) {
                            "没有读到收藏。上面是失败原因。"
                        } else {
                            "还没有收藏。在搜索结果的详情页点「收藏」，这里就会出现。"
                        },
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * 检查状态那一行：进度 / 结果 / 失败原因。
 *
 * [UpdateCheckState.Idle]（本次启动内还没查过）时**什么都不画**，连外面那层间距也不占——
 * 否则没查过的时候，标题和网格之间会凭空多出一条空白。
 */
@Composable
private fun CheckStatusLine(state: UpdateCheckState) {
    if (state is UpdateCheckState.Idle) return

    Spacer(Modifier.height(6.dp))

    when (state) {
        is UpdateCheckState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            // total 还没数出来就画不定态。这时候写 0/0 的进度条会被读成「一本都没查成」
            if (state.total <= 0) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在读取收藏夹…",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            } else {
                LinearProgressIndicator(
                    progress = state.checked.toFloat() / state.total,
                    modifier = Modifier.width(160.dp).height(6.dp),
                    backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在检查 ${state.checked}/${state.total} 本",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }

            if (state.updated > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "已发现 ${state.updated} 本更新",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.error,
                )
            }
        }

        is UpdateCheckState.Done -> Text(
            text = if (state.checked == 0) {
                "检查完成：收藏夹里没有可检查的漫画。"
            } else {
                buildString {
                    append("检查完成：已查 ${state.checked} 本")
                    append(
                        if (state.updated > 0) {
                            "，发现 ${state.updated} 本有更新（已置顶，封面右上角有标记）"
                        } else {
                            "，没有发现更新"
                        }
                    )
                    // 失败的单独说一句。图源下架的收藏会稳定地落在这一档，
                    // 不说清楚的话用户会以为「已查 N 本」里少了什么
                    if (state.failed > 0) {
                        append("；另有 ${state.failed} 本检查失败已跳过（图源下架或超时），不影响其他本")
                    }
                }
            },
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
        )

        is UpdateCheckState.Failed -> Text(
            text = "更新检查没跑起来：${state.error}",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.error,
        )

        UpdateCheckState.Idle -> Unit
    }
}

/**
 * 一格收藏：封面 + 下三分之一的蒙版。
 *
 * 格子高度由 [COVER_ASPECT_RATIO] 定死，和搜索结果页一致——不固定的话每格高度会随
 * 图片原始尺寸变，同一行参差不齐。
 */
@Composable
private fun FavoriteCell(comic: Comic, onClick: () -> Unit) {
    // parsers.title 只查内存里的 Map，不是阻塞调用
    val sourceTitle = LocalApp.current.backend.parsers.title(comic.source)

    // 书名号在设置页可调（见 SettingsSpec.fields）。这里读的是 AppSettings 的**内存缓存**，
    // 不碰 SQLite，所以能在组合里直接读，不用挂协程、也不会闪一下默认字号。
    // 换页返回时本页会重建，所以改完设置回到这里就是新字号了。
    val settings = LocalApp.current.backend.settings
    val titleSize = remember {
        settings.getInt(Keys.FAVORITE_TITLE_SIZE, Defaults.FAVORITE_TITLE_SIZE)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(COVER_ASPECT_RATIO)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
    ) {
        CoverImage(comic, Modifier.fillMaxSize())

        // 更新检查的落点。`highlight` 由后端在发现新章节时写进库（见 ComicDao.applyUpdateCheck），
        // 光靠「排到最前」判断不出来是谁被顶上去的，得有这个标记
        if (comic.highlight) {
            Text(
                text = "更新",
                style = MaterialTheme.typography.caption,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(MaterialTheme.colors.error, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(CAPTION_HEIGHT_FRACTION)
                .align(Alignment.BottomCenter)
                // 半透明白：封面杂色多，不垫一层的话黑字在深色封面上读不出来
                .background(Color.White.copy(alpha = CAPTION_ALPHA))
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = comic.title?.takeIf { it.isNotBlank() } ?: "（无标题）",
                style = MaterialTheme.typography.body2,
                // 只改字号，行高之类的仍走 body2：设置项叫「标题字号」，给的就是字号
                fontSize = titleSize.sp,
                color = Color.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 未知 type 也把号印出来：收藏来自已下架图源时查不到标题，
                // 写「未知」等于什么都没说，写 type 号至少能对上搜索页的失败列表
                text = sourceTitle ?: "未知图源(type=${comic.source})",
                style = MaterialTheme.typography.caption,
                color = Color(0xFF757575),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 每行几本。用户要求的就是 4。 */
private const val COLUMNS = 4

/** 蒙版占封面高度的比例：下三分之一。 */
private const val CAPTION_HEIGHT_FRACTION = 0.34f

/** 蒙版的不透明度。0.62 是「白得能压住封面、又能看出下面有图」的分界。 */
private const val CAPTION_ALPHA = 0.62f
