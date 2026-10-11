package com.haleydu.cimoc.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.haleydu.cimoc.desktop.ui.App

/**
 * 入口。
 *
 * 这里只做三件事：定数据目录、开后端、开窗口。**不要**把后端调用写进这个函数——
 * 它是 Compose 的启动路径，任何阻塞都会变成「窗口迟迟不出现」。
 */
fun main() {
    val holder = AppHolder(AppPaths.database)
    holder.start()

    application {
        Window(
            onCloseRequest = {
                // 先关后端（停下载队列、关 SQLite），再退进程。
                // 反过来的话正在写的 .cbz/.part 会留在磁盘上，下次启动要靠 recover 收拾。
                holder.close()
                exitApplication()
            },
            state = rememberWindowState(width = 1100.dp, height = 720.dp),
            title = "Cimoc",
        ) {
            App(holder)
        }
    }
}
