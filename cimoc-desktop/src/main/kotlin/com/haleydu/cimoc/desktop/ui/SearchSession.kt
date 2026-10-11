package com.haleydu.cimoc.desktop.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.haleydu.cimoc.backend.engine.SearchEvent
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 一次搜索会话的全部状态：关键字、结果、进度、失败清单，以及跑着它的那个 [Job]。
 *
 * ### 为什么不放在 `SearchPage` 里
 *
 * [Shell] 用 `when (section)` 只组合当前那一页，所以离开「搜索」时 `SearchPage` 会被整个
 * 从组合里摘掉，`remember` 的东西全部作废。收藏页/下载页/图源页这样无所谓——它们的数据
 * 能从 SQLite 重新读回来；搜索结果不一样，它只存在于这里这个 list 里，除了重跑几十次
 * 网络请求没有第二个来源。
 *
 * 挂在 [Shell] 上（组合里永不销毁的那一层）而不是页面里，跟 [com.haleydu.cimoc.desktop.AppHolder.updateCheck]
 * 是一个路子：那次也是「跑了一半切走，进度不该从头开始」。
 *
 * ### 切走了搜索还在跑，这是故意的
 *
 * 不停也不取消：取消等于把已经拿到的部分结果一起扔掉，那正是要修的问题。代价是用户在
 * 别的页面时网络仍在用——但这一条**改动之前就存在**（协程本来就挂在 [AppContext.scope]
 * 上，切页从没停过它，只是结果写进了一个没人看得到的 list）。这里只是给了它一个落地的
 * 地方，没有新增后台负载。
 */
class SearchSession(private val app: AppContext) {

    var keyword by mutableStateOf("")

    val results = mutableStateListOf<Comic>()

    var running by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var doneCount by mutableStateOf(0)
    var totalCount by mutableStateOf(0)
    var failures by mutableStateOf<List<SearchEvent.SourceFailed>>(emptyList())
    var failuresShown by mutableStateOf(false)

    /** 点开的那本漫画的详情页。留在会话里，切走再切回来还停在那本书上。 */
    var opened by mutableStateOf<Comic?>(null)

    /**
     * 当前这次搜索的编号。用编号而不是「running 就拒绝」：用户想换关键字重搜时应当立刻
     * 响应，而不是等上一次跑完；而旧协程被 cancel 后还是会走到 finally，没有编号的话
     * 它会把新一轮的 running 抹掉。
     */
    var generation by mutableStateOf(0)

    /**
     * 滚动位置。跟着会话走，否则切回来会跳回顶部——那和「结果还在」是矛盾的。
     *
     * 注意别和 [pendingScroll] 搞混：这个负责「停在哪」，那个负责「新搜索要回顶」。
     */
    val gridState = LazyGridState()

    /**
     * 新一轮搜索该把网格滚回顶部。
     *
     * 不能拿 `generation > 0` 当判据：`LaunchedEffect` 在**首次组合**时就会跑一次，
     * 切回来时 generation 还是上次那个值（比如 2），于是每切一次页就把滚动位置顶掉一次。
     */
    var pendingScroll by mutableStateOf(false)

    /** 正在跑的那轮。跑完或被下一轮取消后由协程自己维护。 */
    private var job: Job? = null

    /**
     * 开一轮搜索。关键字为空则什么都不做。
     *
     * 整段跑在 [AppContext.scope]（IO）上：`search()` 的 channelFlow 内部用阻塞 DAO，
     * 且它逐条产出、跨几十个图源，这里 collect 会持续很久。
     */
    fun start() {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return

        job?.cancel()
        results.clear()
        failures = emptyList()
        failuresShown = false
        doneCount = 0
        totalCount = 0
        running = true
        searched = true
        pendingScroll = true

        val mine = generation + 1
        generation = mine

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
