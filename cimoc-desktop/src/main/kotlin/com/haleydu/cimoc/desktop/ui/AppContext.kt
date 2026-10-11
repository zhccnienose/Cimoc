package com.haleydu.cimoc.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.haleydu.cimoc.backend.CimocBackend
import kotlinx.coroutines.CoroutineScope

/**
 * 各页面共用的两样东西：后端门面与全局 IO scope。
 *
 * ### 为什么用 CompositionLocal 而不是逐层传参
 *
 * 六个页面每个都要这两样，而页面之间还会嵌套（详情页挂在搜索页的结果上）。逐层往下传
 * 的话，每加一层就要多透传一次，且任何一个中间层都得知道自己用不到的东西。
 *
 * ### [scope] 不要换成 `rememberCoroutineScope()`
 *
 * 那个 scope 跑在**主线程**上。后端里 `Library` / `ParserFactory` / `AppSettings` 的方法
 * 全是阻塞的（SQLite 单连接 + 同步反射），`SearchEngine.search` / `UpdateChecker.checkAll`
 * 这两个 Flow 的 `channelFlow` 内部也用阻塞 DAO——在主线程上 collect 会直接卡住界面。
 * 所以后端调用一律 `scope.launch { ... }` 或 `scope.launch { flow.collect { ... } }`。
 */
class AppContext(
    val backend: CimocBackend,
    val scope: CoroutineScope,
)

/**
 * 由 [Shell] 提供。页面拿不到它会直接抛——比拿到一个默认值（比如 null）然后在运行时
 * 静默不干活要好。
 */
val LocalApp = staticCompositionLocalOf<AppContext> {
    error("LocalApp 未提供：页面必须挂在 Shell 之下")
}
