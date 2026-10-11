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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haleydu.cimoc.backend.engine.ComicDetail
import com.haleydu.cimoc.model.Chapter
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 漫画详情页：上半栏简介，下半栏章节多选，右下角收藏 + 开始下载。
 *
 * ### 进入即落库
 *
 * [com.haleydu.cimoc.backend.engine.ComicEngine.load] 会先把漫画写进 `comic` 表再解析，
 * 因为章节的合成 ID 依赖漫画行号。副作用是**看完不收藏也不下载的漫画会留下孤儿行**。
 * 这里选择接受，而且现在它们有用了：浏览记录那份快照里的章节行号指着这些行
 * （见 [com.haleydu.cimoc.backend.engine.HistoryStore]）。真要清理，`Library.deleteComic(id)`
 * 已经在了——但清掉之后对应的浏览记录也就失效了。
 *
 * ### 章节的 id 是数据库行号
 *
 * 不是图源那套合成 ID——`ChapterDao` 在出去给图源时才会换。多选和下载用的都是行号。
 *
 * ### 打开时先查本地，查不到才发请求
 *
 * 顺序是**收藏快照 → 浏览记录 → 网络**（前两个见
 * [com.haleydu.cimoc.backend.storage.DetailSnapshotCache]）。这么排的理由很实际：
 * 本机 40 个图源里能返回章节的只有十来个，图源今天连不上、那本漫画就打不开是常态；
 * 而这些内容我们其实都已经拿到过。顺带也省掉了大量的重复请求。
 *
 * 代价是**缓存会过时**：命中时看不到图源后来加的章节，也没有 TTL、不做后台静默刷新
 * （后台刷等于「打开一本漫画顺带打一次全站」，正是要减少的东西）。所以命中时页面上有
 * 一个「重新拉取」按钮，想拿最新章节自己点一下。
 *
 * 缓存缺失或损坏时退回网络解析，并顺手把结果补进对应的缓存，所以「收藏时详情还没解析
 * 出来」这种情况会自愈。
 */
@Composable
fun ComicDetailPage(comic: Comic, onBack: () -> Unit) {
    val app = LocalApp.current

    // 用 source/cid 而不是 comic 对象当 key：Comic.equals 在 id 为 null 时直接 NPE
    var detail by remember(comic.source, comic.cid) { mutableStateOf<ComicDetail?>(null) }
    var error by remember(comic.source, comic.cid) { mutableStateOf<Throwable?>(null) }
    var loading by remember(comic.source, comic.cid) { mutableStateOf(true) }
    var selected by remember(comic.source, comic.cid) { mutableStateOf(emptySet<Long>()) }
    var notice by remember(comic.source, comic.cid) { mutableStateOf<String?>(null) }
    var noticeIsError by remember(comic.source, comic.cid) { mutableStateOf(false) }
    var favorited by remember(comic.source, comic.cid) { mutableStateOf(false) }
    var usedCache by remember(comic.source, comic.cid) { mutableStateOf(false) }

    /** 点一次「重新拉取」+1，让下面那个 effect 重跑一轮、且这一轮跳过缓存。 */
    var revision by remember(comic.source, comic.cid) { mutableStateOf(0) }

    val cid = comic.cid ?: ""

    LaunchedEffect(comic.source, comic.cid, revision) {
        loading = true
        error = null
        var readFromCache = false

        val result = try {
            Result.success(
                // 读 JSON、查库、解析详情全是阻塞的，LaunchedEffect 跑在组合线程上
                withContext(Dispatchers.IO) {
                    // revision > 0 表示用户点了「重新拉取」：这一轮直接打网络，
                    // 否则点一下只会在两级缓存里绕一圈，什么都不会变
                    val cached = if (revision == 0) {
                        app.backend.favorites.cached(comic.source, cid)
                            ?: app.backend.history.cached(comic.source, cid)
                    } else {
                        null
                    }

                    if (cached != null) {
                        readFromCache = true
                        cached
                    } else {
                        app.backend.comic.load(comic).also {
                            // 收藏着的漫画，顺手把这次的详情刷进收藏快照：收藏那会儿
                            // 详情可能还没解析出来，不快照的话这个缓存一辈子是空的
                            app.backend.favorites.refreshCacheIfFavorite(it)
                            // 没收藏的进浏览记录（这条规则收在 record 里，这里无条件调）
                            app.backend.history.record(it)
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

        result
            .onSuccess {
                detail = it
                // 只在成功时更新，失败时保留上一轮的判断（见下面 onFailure）
                usedCache = readFromCache
            }
            .onFailure { failure ->
                // 已经画着内容的时候（点「重新拉取」失败），**不能**把内容换成错误页：
                // error 只在 detail == null 时才渲染，这里设了也没人看，而上一句
                // 「清空 error」已经把界面留在了旧内容上。所以错误走底部那行 notice，
                // 缓存标注和「重新拉取」按钮都留在原地，用户还能再点一次。
                if (detail == null) {
                    error = failure
                } else {
                    notice = "重新拉取失败：${describe(failure)}"
                    noticeIsError = true
                }
            }

        // 收藏状态单独查库：详情解析失败时也要能显示成「已收藏」
        favorited = withContext(Dispatchers.IO) {
            runCatching { app.backend.favorites.isFavorite(comic.source, cid) }.getOrDefault(false)
        }
        loading = false
    }

    val toggleFavorite: () -> Unit = {
        app.scope.launch {
            val target = !favorited
            // 显式声明 Unit：两个分支一个返回 Comic、一个返回 Boolean，不指定的话
            // Kotlin 会去求它们的公共父类型，报「无法推断 R」
            val result = runCatching<Unit> {
                if (target) {
                    // 用 detail 那份：它带着解析出来的简介。详情没解析出来时退回参数那份
                    app.backend.favorites.favorite(detail?.comic ?: comic, detail?.chapters ?: emptyList())
                } else {
                    app.backend.favorites.unfavorite(comic.source, cid)
                }
            }

            // 按钮状态一律以库为准重新读一次，不做乐观更新：写入可能失败（磁盘满、
            // 行被别的操作删掉），显示成成功会让用户以为收藏上了
            favorited = runCatching { app.backend.favorites.isFavorite(comic.source, cid) }
                .getOrDefault(favorited)

            val failure = result.exceptionOrNull()
            if (failure == null) {
                notice = if (target) "已收藏（简介与章节目录已写入本地缓存）" else "已取消收藏（本地缓存已删除）"
                noticeIsError = false
            } else {
                // 收藏是「写库 + 写文件」两步：文件那步失败时库已经改好了，
                // 笼统报「收藏失败」会和按钮的真实状态对不上
                notice = if (favorited == target) {
                    "收藏状态已更新，但本地缓存没写成：${describe(failure)}"
                } else {
                    "收藏操作失败：${describe(failure)}"
                }
                noticeIsError = true
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text(
                // 优先用解析出来的那份：从收藏夹进来时，参数那份（搜索结果/列表行）
                // 没有 intro 之类的字段，标题也可能还是空的
                text = detail?.comic?.title?.takeIf { it.isNotBlank() } ?: comic.title ?: "",
                style = MaterialTheme.typography.h6,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 说清楚这页为什么没转圈就出来了：内容来自本地快照，不是刚解析的。
            // 顺带给一个主动刷新的入口——缓存不做 TTL，没有这个按钮，图源后来加的章节
            // 在这页上就永远看不见了。
            if (usedCache) {
                Text(
                    "来自本地缓存",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.width(8.dp))
                if (loading) {
                    // 刷新中：只在标题行转，不把正文换成整页的转圈（见下面 when 的判据）
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                OutlinedButton(enabled = !loading, onClick = { revision += 1 }) {
                    Text(if (loading) "拉取中…" else "重新拉取")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 判据是「还没有内容可画」而不是 loading：点「重新拉取」时 loading 也是 true，
        // 而那时候正文必须留在屏幕上，只让标题行转个小圈（否则刷新一下整页就空了，
        // 正好和「显示缓存内容 + 重新拉取」的做法相反）
        val loaded = detail
        when {
            loaded == null && loading ->
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.height(28.dp), strokeWidth = 3.dp)
                }

            // weight 在调用点给：ColumnScope 只在这里可见
            loaded == null -> LoadFailure(comic, error, Modifier.fillMaxWidth().weight(1f))

            else -> {
                // 上半栏：简介。简介再长也在这个区域里滚，不会把章节列表挤没。
                //
                // 0.34 / 0.66 而不是平分：上半栏的高度实际上由封面钉死了——
                // 封面固定 118dp 宽、按 COVER_ASPECT_RATIO 推出约 174dp 高，简介那列
                // 自己滚动、不参与撑高。默认窗口（720dp 高）下这里可分到约 550dp，
                // 0.42 会给上半栏 230dp 左右，于是封面底下空出五十多 dp，
                // 分割线看着像悬在空地上。0.34 把这块余量收到十几 dp，
                // 分割线跟着上移，章节列表也能多露两三行。
                //
                // 传 loaded.comic 而不是参数那个 comic：走本地缓存时只有这份有简介
                IntroSection(loaded.comic, Modifier.fillMaxWidth().weight(0.34f))

                Divider(Modifier.padding(vertical = 10.dp))

                ChapterSection(
                    chapters = loaded.chapters,
                    selected = selected,
                    onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                    onSelectAll = { selected = loaded.chapters.mapNotNull { it.id }.toSet() },
                    onClear = { selected = emptySet() },
                    modifier = Modifier.fillMaxWidth().weight(0.66f),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 右下角：收藏 + 启动下载
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val message = notice
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.caption,
                    color = if (noticeIsError) {
                        MaterialTheme.colors.error
                    } else {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            // 收藏用 OutlinedButton：这一页的主操作是「开始下载」，两个实心按钮会抢注意力。
            // 不置灰、也不禁用——详情没解析出来时照样能收藏（只是缓存里没有章节）
            OutlinedButton(onClick = toggleFavorite) {
                Text(if (favorited) "已收藏" else "收藏")
            }

            Spacer(Modifier.width(10.dp))

            Button(
                enabled = loaded != null && selected.isNotEmpty(),
                onClick = {
                    val current = detail ?: return@Button
                    val chapters = current.chapters.filter { chapter ->
                        chapter.id?.let { it in selected } == true
                    }
                    if (chapters.isEmpty()) return@Button

                    // enqueue 每章一次 SQLite 写入，且下载 worker 就在后端自己的 scope 里，
                    // 这里只负责入队，不等下载完成
                    app.scope.launch {
                        try {
                            val queued = app.backend.download.enqueue(current.comic, chapters)
                            notice = "已加入下载队列 ${queued.size} 章，进度见左侧「下载」"
                            noticeIsError = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            notice = "入队失败：${describe(e)}"
                            noticeIsError = true
                        }
                    }
                },
            ) {
                Text(if (selected.isEmpty()) "开始下载" else "开始下载（${selected.size}）")
            }
        }
    }
}

/**
 * 上半栏：封面 + 元信息 + 简介。
 *
 * 只让右边那一列滚动，封面固定——把整块一起scroll 的话封面会跟着划走，
 * 用户滚到简介末尾时就看不到自己在看哪本了。
 */
@Composable
private fun IntroSection(comic: Comic, modifier: Modifier = Modifier) {
    // parsers.title 是查内存 Map，不阻塞
    val sourceTitle = LocalApp.current.backend.parsers.title(comic.source)

    Row(modifier) {
        CoverImage(
            comic = comic,
            modifier = Modifier
                .width(118.dp)
                .aspectRatio(COVER_ASPECT_RATIO)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                text = comic.title ?: "",
                style = MaterialTheme.typography.h6,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "作者：${comic.author?.takeIf { it.isNotBlank() } ?: "未知"}",
                style = MaterialTheme.typography.body2,
            )
            Text(
                text = "图源：${sourceTitle ?: "未知（type=${comic.source}）"}",
                style = MaterialTheme.typography.body2,
            )
            Text(
                // null 表示图源没报，和 false（连载中）不是一回事，别合并
                text = "状态：" + when (comic.finish) {
                    true -> "已完结"
                    false -> "连载中"
                    null -> "未知"
                },
                style = MaterialTheme.typography.body2,
            )

            val intro = comic.intro
            if (!intro.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("简介", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                Text(intro, style = MaterialTheme.typography.body2)
            }
        }
    }
}

/** 下半栏：章节列表，每行右侧一个多选框。 */
@Composable
private fun ChapterSection(
    chapters: List<Chapter>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "章节（共 ${chapters.size} 章，已选 ${selected.size}）",
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier.weight(1f),
            )
            // 一本连载漫画动辄几百章，只能一章章点的话这个页面就没法用了
            TextButton(onClick = onSelectAll) { Text("全选") }
            TextButton(onClick = onClear) { Text("清空") }
        }

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.03f)),
            // 行与行之间留 4dp。加上 ChapterRow 自己上下各 2dp 的内边距，两行文字
            // 之间实到 8dp——原来只有 4dp，几百章连排下来像一堵墙。
            //
            // 缝隙本身不带 clickable（可点区域仍是行内那块），要「点哪都能勾上」
            // 的话得把间距算进 ChapterRow 的内边距里，但那样就没有缝隙可言了——
            // 列表底色是整块平铺的，行内留白和行间留白在视觉上没区别。
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // key 用行号。不能用 Chapter 对象：Chapter.equals 只比 path（Chapter.java:132），
            // 跨漫画的章节会判等，path 为 null 还会 NPE。
            items(items = chapters, key = { it.id ?: it.path }) { chapter ->
                val id = chapter.id
                ChapterRow(
                    chapter = chapter,
                    checked = id != null && id in selected,
                    onToggle = { if (id != null) onToggle(id) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.title ?: "",
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 点整行也能勾选——只留一个小方块的话，几百章点起来很费劲
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * 详情解析失败。
 *
 * 把原始 message 显示出来并允许选中复制：失败几乎都出在图源解析过期上
 * （见 README 未完成 4~6），拿着那句异常文本才能判断是选择器失效还是页面改版。
 */
@Composable
private fun LoadFailure(comic: Comic, error: Throwable?, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("解析这本漫画失败", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.error)
        Text(
            text = "图源：${LocalApp.current.backend.parsers.title(comic.source) ?: "type=${comic.source}"}",
            style = MaterialTheme.typography.body2,
        )
        SelectionContainer {
            Text(
                text = error?.let { describe(it) } ?: "未知原因",
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

/**
 * 异常的一句话描述。带上类名——`ParseException`（解析不出章节）、`HttpException`（网络）、
 * `IllegalArgumentException`（图源不在注册表里）的处置方式完全不同，只给 message 分不清。
 *
 * 同包的收藏页也在用，所以是 internal 而不是 private。
 */
internal fun describe(e: Throwable): String =
    "${e.javaClass.simpleName}: ${e.message?.takeIf { it.isNotBlank() } ?: "(无消息)"}"
