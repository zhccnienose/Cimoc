package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

/**
 * 设置页。**界面完全由 [SettingsSpec.fields] 渲染**——这一页里没有任何一项设置的名字，
 * 加一项新设置不用改这个文件（要加新控件类型时才需要动 `when (field)` 那个分支）。
 *
 * ### 界面状态就是库里那份文本
 *
 * `setting` 表存的是「key → 字符串」，这里就照着存一份镜像（[values]），不另造一套
 * 类型化的状态：多一份状态就多一处会不一致的地方。取某一行当前的值时用
 * `field.read(values[field.key])`，类型信息在这一刻才出现。
 *
 * ### 为什么可以同步读配置
 *
 * 镜像的初始化在 `remember` 里直接读 `AppSettings.raw()`——读的是它的**内存缓存**，
 * 不碰 SQLite（只有 `put` 会写库）。所以不需要挂协程，也不会有「第一帧显示默认值、
 * 第二帧跳成真值」的闪动。写库是阻塞的，那才要扔到 `scope` 上。
 *
 * ### 写入一律「先改界面、再写库、失败改回来」
 *
 * 和 [SourcesPage] 的开关同一个道理：等 SQLite 返回再动界面，快速连点时会一顿一顿的。
 * 但失败必须改回去并说清楚，否则界面显示的和库里存的是两回事。
 */
@Composable
fun SettingsPage() {
    val app = LocalApp.current
    val backend = app.backend

    val values = remember(backend) {
        mutableStateMapOf<String, String?>().apply {
            SettingsSpec.fields.forEach { put(it.key, backend.settings.raw(it.key)) }
        }
    }

    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    // 导入之后要把镜像重新对齐到库（导入时被校验拦掉的项，得显示成库里的真实状态，
    // 而不是文件里那个值）。revision +1 就是重新对齐一次，走同一条路。
    LaunchedEffect(backend, revision) {
        SettingsSpec.fields.forEach { values[it.key] = backend.settings.raw(it.key) }
    }

    /**
     * 统一的写入路径。
     *
     * [done] 是成功之后给用户看的一句话（可为空）。之所以要传进来而不是在这里拼：
     * 有的项改完没什么好说的（拨个开关），有的必须说（「选了新目录，但**要重开程序**」）。
     */
    fun write(field: SettingField, text: String?, done: String? = null) {
        val previous = values[field.key]
        if (previous == text) return

        values[field.key] = text
        app.scope.launch {
            runCatching { backend.settings.put(field.key, text) }
                .onSuccess {
                    if (done != null) {
                        notice = done
                        noticeIsError = false
                    }
                }
                .onFailure { error ->
                    values[field.key] = previous
                    notice = "「${field.title}」没写进库：${describe(error)}（已改回原样）"
                    noticeIsError = true
                }
        }
    }

    fun exportSettings() {
        val file = chooseSettingsFile(save = true) ?: return
        app.scope.launch {
            runCatching { SettingsFile.exportTo(backend.settings, file.toPath()) }
                .onSuccess {
                    notice = "已导出 $it 项设置到 ${file.absolutePath}"
                    noticeIsError = false
                }
                .onFailure {
                    notice = "导出失败：${describe(it)}"
                    noticeIsError = true
                }
        }
    }

    fun importSettings() {
        val file = chooseSettingsFile(save = false) ?: return
        app.scope.launch {
            runCatching { SettingsFile.importFrom(backend.settings, file.toPath()) }
                .onSuccess { result ->
                    revision += 1
                    notice = buildString {
                        append("已导入 ${result.applied} 项")
                        if (result.invalid > 0) append("，跳过 ${result.invalid} 项（值不合法）")
                        if (result.unknown > 0) append("，忽略 ${result.unknown} 项（本版本不认识）")
                        append("。标「需重启」的项要重开程序才生效")
                    }
                    // 一项都没导进去通常意味着文件不对，当成错误显示，别让它看起来像成功
                    noticeIsError = result.applied == 0
                }
                .onFailure {
                    notice = "导入失败：${describe(it)}"
                    noticeIsError = true
                }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", style = MaterialTheme.typography.h5)
            Spacer(Modifier.width(12.dp))
            Text(
                "共 ${SettingsSpec.fields.size} 项",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { importSettings() }) { Text("导入…") }
            TextButton(onClick = { exportSettings() }) { Text("导出…") }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "标「需重启」的项在程序启动时只读一次，改完要重开才生效。其余项立刻生效。",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )

        notice?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.caption,
                color = if (noticeIsError) {
                    MaterialTheme.colors.error
                } else {
                    MaterialTheme.colors.onSurface.copy(alpha = 0.75f)
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSpec.groups().forEach { (group, fields) ->
                // 分组标题也要参与 key 的唯一性，所以加个前缀
                item(key = "group:$group") { GroupHeader(group) }
                items(items = fields, key = { it.key }) { field ->
                    SettingRow(
                        field = field,
                        text = values[field.key],
                        onWrite = ::write,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** 一行：左边标题 / 说明 / 警示，右边控件。控件按子类分派。 */
@Composable
private fun SettingRow(
    field: SettingField,
    text: String?,
    onWrite: (SettingField, String?, String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.title, style = MaterialTheme.typography.body1)
                if (field.restartRequired) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "需重启",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = field.detail,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
            // 警示用红字，和说明文字分开写：说明是「这项干什么」，警示是「不这么做的代价」
            field.warning?.let { warning ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.error,
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // 穷尽的 when：加了新的 SettingField 子类而忘了在这里渲染，编译不过
        when (field) {
            is ToggleField -> Switch(
                checked = field.read(text),
                onCheckedChange = { onWrite(field, field.format(it), null) },
            )

            is NumberField -> NumberControl(field, field.read(text)) { value ->
                onWrite(field, field.format(value), null)
            }

            is DirectoryField -> DirectoryControl(field.read(text)) { value, done ->
                onWrite(field, value, done)
            }
        }
    }
}

/**
 * 数字：滑块 + 当前值。
 *
 * 拖动过程只改本地的 draft，松手（`onValueChangeFinished`）才写库——每次像素变化都写的话，
 * 拖一次就是几十个 SQLite 事务。
 */
@Composable
private fun NumberControl(field: NumberField, value: Int, onCommit: (Int) -> Unit) {
    // key 里带上 value：导入或「改回原样」之后，draft 要跟着库里那份走
    var draft by remember(field.key, value) { mutableStateOf(value) }

    Column(horizontalAlignment = Alignment.End) {
        Text("$draft${field.unit}", style = MaterialTheme.typography.body2)
        Slider(
            value = draft.toFloat(),
            onValueChange = { draft = it.roundToInt() },
            onValueChangeFinished = { if (draft != value) onCommit(draft) },
            valueRange = field.min.toFloat()..field.max.toFloat(),
            steps = field.steps,
            modifier = Modifier.width(200.dp),
        )
    }
}

/**
 * 目录：当前路径 + 「选择…」「默认」。
 *
 * 路径那一行留空时显示**当前真正生效的目录**（[com.haleydu.cimoc.backend.CimocBackend.storageRoot]），
 * 而不是只写「默认」两个字：用户想知道的是「漫画到底下到哪儿去了」。
 */
@Composable
private fun DirectoryControl(
    value: String,
    onWrite: (String?, String?) -> Unit,
) {
    val backend = LocalApp.current.backend
    val effective = backend.storageRoot().toString()

    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = if (value.isBlank()) "默认（$effective）" else value,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.width(280.dp),
        )
        Spacer(Modifier.height(2.dp))
        Row {
            TextButton(onClick = {
                val chosen = chooseDirectory(value.ifBlank { effective })
                if (chosen != null) {
                    onWrite(chosen.absolutePath, "下载目录已设为 ${chosen.absolutePath}——要重开程序才生效")
                }
            }) {
                Text("选择…")
            }
            // 写 null 是「删掉这一项」，不是「设成空串」：CimocBackend 按「空串」判默认，
            // 而删掉之后 raw() 返回 null，取默认值，两条路都通，删更干净
            TextButton(onClick = { onWrite(null, "下载目录已恢复为默认位置——要重开程序才生效") }) {
                Text("默认")
            }
        }
    }
}

// -------------------------------------------------------------------- 文件选择

/**
 * 弹一个 Swing 文件选择框。
 *
 * **必须先判 EDT**：Compose Desktop 的界面线程在多数情况下**就是** AWT 的事件线程
 * （`Dispatchers.Swing`），此时再调 `SwingUtilities.invokeAndWait` 会直接抛
 * `Cannot call invokeAndWait from the event dispatcher thread`。判一下两种情形都能走。
 */
private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}

private fun chooseSettingsFile(save: Boolean): File? {
    var chosen: File? = null
    onEdt {
        val chooser = JFileChooser().apply {
            dialogTitle = if (save) "导出设置为配置文件" else "导入设置"
            fileFilter = FileNameExtensionFilter("Cimoc 设置 (*.json)", "json")
            isAcceptAllFileFilterUsed = true
            if (save) selectedFile = File(SettingsFile.DEFAULT_FILE_NAME)
        }

        val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            // 导出时补上 .json：JFileChooser 不会自动加扩展名，而用户很可能不写
            chosen = if (save && !file.name.lowercase().endsWith(".json")) {
                File(file.parentFile, file.name + ".json")
            } else {
                file
            }
        }
    }
    return chosen
}

private fun chooseDirectory(initial: String): File? {
    var chosen: File? = null
    onEdt {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择下载目录"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            // 原来那个目录可能已经被删了，设不上就算了，让选择框用默认位置打开
            runCatching { currentDirectory = File(initial) }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chosen = chooser.selectedFile
        }
    }
    return chosen
}
