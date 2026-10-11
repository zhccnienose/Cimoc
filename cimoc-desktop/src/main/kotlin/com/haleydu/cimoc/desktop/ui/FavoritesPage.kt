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
import androidx.compose.material.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haleydu.cimoc.backend.storage.Defaults
import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.Dispatchers
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
 * ### 从详情页返回要重读列表
 *
 * 详情页里可以取消收藏，回来后那本就该消失。页面本身不会重组到新数据（`favorites()`
 * 是一次阻塞查询），所以 [revision] 在返回时 +1，让下面那个 LaunchedEffect 重跑。
 */
@Composable
fun FavoritesPage() {
    val app = LocalApp.current

    val items = remember { mutableStateListOf<Comic>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    var opened by remember { mutableStateOf<Comic?>(null) }

    LaunchedEffect(app.backend, revision) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { app.backend.favorites.list() }
        }
        loaded
            .onSuccess { list ->
                items.clear()
                items.addAll(list)
            }
            .onFailure { error = describe(it) }
        loading = false
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
            TextButton(onClick = { revision += 1 }) { Text("刷新") }
        }

        error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                "收藏夹读取失败：$message",
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
