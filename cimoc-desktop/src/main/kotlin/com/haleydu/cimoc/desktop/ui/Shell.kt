package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haleydu.cimoc.backend.CimocBackend
import com.haleydu.cimoc.desktop.AppHolder
import com.haleydu.cimoc.desktop.AppPaths
import com.haleydu.cimoc.desktop.BackendState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 五个入口。顺序即使用频率：搜索在最上，设置在最下。
 *
 * 曾经还有一个「更新」入口，它只是更新检查的占位页——功能做进收藏页之后，
 * 单独留一个入口反而让人以为收藏页上的检查是另一回事。
 */
enum class Section(val label: String) {
    SEARCH("搜索"),
    FAVORITE("收藏"),
    DOWNLOAD("下载"),
    SOURCES("图源"),
    SETTINGS("设置"),
}

@Composable
fun App(holder: AppHolder) {
    val state by holder.state.collectAsState()

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            when (val current = state) {
                is BackendState.Loading -> Centered("正在启动后端…")
                is BackendState.Failed -> StartupFailure(current.error)
                is BackendState.Ready -> {
                    // remember 住：每次重组都新建一个 AppContext 会让下游所有依赖它的
                    // composable 无谓地失效
                    val app = remember(current.backend, holder) {
                        AppContext(current.backend, holder.scope)
                    }
                    CompositionLocalProvider(LocalApp provides app) {
                        Shell(holder, current.backend)
                    }
                }
            }
        }
    }
}

@Composable
private fun Shell(holder: AppHolder, backend: CimocBackend) {
    val app = LocalApp.current

    var section by remember { mutableStateOf(Section.SEARCH) }
    var sources by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 图源页动过开关就 +1，让下面那个 LaunchedEffect 重跑一遍。
    // 不把这份计数交给图源页维护：它只在自己可见时存在，而导航栏这行是一直显示的。
    var sourcesRevision by remember { mutableStateOf(0) }
    val unfinished by holder.unfinishedTasks.collectAsState()

    // 搜索会话挂在这一层，不挂在搜索页里。Shell 一旦进入 Ready 就再也不会离开组合
    // （_state 只在 AppHolder.start() 里被赋成 Ready，之后没人动），所以这一句能稳稳
    // 跨过所有切页。放在搜索页里的话，切走 = 从组合里摘掉 = 结果连同关键字一起作废。
    val search = remember(app) { SearchSession(app) }

    // 读图源清单是阻塞的（查开关要走 SQLite），放 IO 上。
    // 一次性读取用 withContext 就够；要持续收集的 Flow 请用 LocalApp.current.scope。
    LaunchedEffect(backend, sourcesRevision) {
        sources = withContext(Dispatchers.IO) {
            backend.sources().size to backend.enabledSources().size
        }
    }

    Row(Modifier.fillMaxSize()) {
        Nav(
            section = section,
            onSelect = { section = it },
            sources = sources,
            unfinished = unfinished,
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                // Material 2 的 Colors 里没有 divider 这一项（那是 Material 3 的 outlineVariant），
                // 这里取默认分隔线的实际取值
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)),
        )
        Box(Modifier.fillMaxHeight().weight(1f)) {
            when (section) {
                Section.SEARCH -> SearchPage(search)
                // 更新检查的状态与触发都挂在 holder 上：这个页面来回切走时，
                // 检查还在跑（见 UpdateCheckState），进度不该跟着页面一起消失。
                // 搜索同理，会话挂在上面那个 search 上（见 SearchSession）
                Section.FAVORITE -> FavoritesPage(
                    updateCheck = holder.updateCheck,
                    onCheckUpdates = { holder.checkForUpdates() },
                )
                Section.DOWNLOAD -> DownloadPage()
                Section.SOURCES -> SourcesPage(onSourcesChanged = { sourcesRevision += 1 })
                Section.SETTINGS -> SettingsPage()
            }
        }
    }
}

@Composable
private fun Nav(
    section: Section,
    onSelect: (Section) -> Unit,
    sources: Pair<Int, Int>?,
    unfinished: Int,
) {
    Column(
        Modifier
            .width(180.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(
            "Cimoc",
            style = MaterialTheme.typography.h6,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(12.dp))

        for (item in Section.entries) {
            val selected = item == section
            TextButton(
                onClick = { onSelect(item) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (selected) {
                        MaterialTheme.colors.primary
                    } else {
                        MaterialTheme.colors.onSurface
                    },
                ),
            ) {
                Text(
                    item.label,
                    // weight 而不是 fillMaxWidth：给右边的角标留位置。
                    // 没有角标时这一条也能把标题撑到左边。
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.body1,
                )
                // 角标只挂在「下载」上。0 时不显示——一个写着 0 的角标只是噪音。
                if (item == Section.DOWNLOAD && unfinished > 0) {
                    Text(
                        text = unfinished.toString(),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onPrimary,
                        modifier = Modifier
                            .background(MaterialTheme.colors.primary, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = sources?.let { "图源 ${it.second}/${it.first} 已启用" } ?: "图源读取中…",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun Centered(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.body1)
    }
}

/**
 * 启动失败页。
 *
 * 这个界面存在的理由只有一个：失败**必须能被看见，而且能被复制走**。
 * 启动失败几乎总是环境问题（路径不对、文件锁住、数据目录没权限），
 * 用户拿着异常文本才能搜或者发出来。所以用 [SelectionContainer] 而不是普通 Text。
 */
@Composable
private fun StartupFailure(error: Throwable) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("后端启动失败", style = MaterialTheme.typography.h5, color = MaterialTheme.colors.error)
        Text(
            "数据目录：${AppPaths.dataDir}",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
        Text(
            "可以设环境变量 CIMOC_HOME 指向另一个目录再试一次。",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )
        SelectionContainer {
            Text(
                buildString {
                    append(error.javaClass.name).append(": ").append(error.message ?: "(无消息)")
                    var cause = error.cause
                    while (cause != null) {
                        append("\n  由 ").append(cause.javaClass.name)
                            .append(": ").append(cause.message ?: "(无消息)")
                        cause = cause.cause
                    }
                },
                style = MaterialTheme.typography.body2,
            )
        }
    }
}
