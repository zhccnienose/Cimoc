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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
 * ### 「设置」按钮是占位
 *
 * 按当前需求先放一个空按钮，点它只在顶部出一行说明。不把按钮做成 disabled：
 * 一个灰掉的按钮看起来像「这个源坏了」，而现在只是功能没做。
 */
@Composable
fun SourcesPage(onSourcesChanged: () -> Unit) {
    val app = LocalApp.current

    val rows = remember { mutableStateListOf<SourceRow>() }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(app.backend) {
        // 读清单本身是内存操作，但每个图源的开关要单独查一次库，40 多个源放主线程上会卡。
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val factory = app.backend.parsers
                app.backend.sources().map { info ->
                    SourceRow(
                        type = info.type,
                        title = info.title,
                        defaultEnabled = info.defaultEnabled,
                        enabled = factory.enabled(info.type),
                        warning = factory.unavailableReason(info.type),
                    )
                }
            }
        }
        loaded
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "只有打开开关的图源会参与搜索。关掉一个源不会影响收藏和已下载的漫画。",
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // key 用 type：它是图源的唯一标识，也省得记住 40 个源的位置
                items(rows, key = { it.type }) { row ->
                    SourceRowItem(
                        row = row,
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
    onToggle: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    // 关掉的源整行压暗，让「现在参与搜索的是哪些」一眼能扫出来
    val dim = if (row.enabled) 1f else 0.55f

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = if (row.enabled) 0.07f else 0.03f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
