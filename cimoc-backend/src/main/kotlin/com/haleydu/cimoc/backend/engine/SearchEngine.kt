package com.haleydu.cimoc.backend.engine

import com.haleydu.cimoc.backend.http.HttpEngine
import com.haleydu.cimoc.backend.source.ParserFactory
import com.haleydu.cimoc.backend.source.SourceInfo
import com.haleydu.cimoc.backend.source.SourceRegistry
import com.haleydu.cimoc.backend.storage.AppSettings
import com.haleydu.cimoc.backend.storage.Defaults
import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 多图源搜索的事件流。
 *
 * 刻意做成「一条结果一个事件」而不是「等所有图源返回后给一个 List」：
 * 这是原版 `Parser.getSearchIterator` 的设计意图（那个方法返回迭代器而不是列表，
 * 注释明说了是为了让不同图源的漫画**交错出现**）。36 个默认启用的图源里只要有一个站点慢，
 * 等它会让整个搜索卡住几十秒；逐条产出时用户立刻就能看到快的源的结果。
 */
sealed interface SearchEvent {

    /** 一条搜索结果。 */
    data class Found(val comic: Comic) : SearchEvent

    /** 某个图源跑完了。[found] 是它这次过滤后实际产出的条数。 */
    data class SourceDone(val type: Int, val title: String, val found: Int) : SearchEvent

    /**
     * 某个图源失败。**不影响其余图源**——原版要所有源都失败才报错，
     * 这里把「哪个源挂了」如实报给前端，前端可以只提示不中断。
     */
    data class SourceFailed(val type: Int, val title: String, val error: String) : SearchEvent

    /** 所有图源都已结束（成功的、失败的都是）。 */
    data object AllDone : SearchEvent
}

/**
 * 多图源并发搜索。
 *
 * 每个启用中的图源起一个协程，各自发一次搜索请求、逐条产出结果。
 * 图源之间互不等待，因此结果是**交错**出来的，顺序取决于谁先返回。
 */
class SearchEngine(
    private val registry: SourceRegistry,
    private val factory: ParserFactory,
    private val http: HttpEngine,
    private val settings: AppSettings,
) {

    /**
     * @param keyword 关键字
     * @param page    页码，从 1 开始（原版也是从 1 开始）
     * @param sources 指定图源；null 表示全部启用中的图源
     * @param strict  true 时只保留标题或作者含关键字的结果（忽略大小写）
     */
    fun search(
        keyword: String,
        page: Int = 1,
        sources: Collection<Int>? = null,
        strict: Boolean = false,
    ): Flow<SearchEvent> = channelFlow {
        val targets = resolve(sources)
        if (targets.isEmpty()) {
            send(SearchEvent.AllDone)
            return@channelFlow
        }

        coroutineScope {
            targets.forEach { info -> launch { runOne(info, keyword, page, strict) } }
        }

        send(SearchEvent.AllDone)
    }

    // ---------------------------------------------------------------- 内部

    private fun resolve(sources: Collection<Int>?): List<SourceInfo> =
        if (sources == null) {
            factory.enabledSources()
        } else {
            // 指定了图源就照单全收，不再看启用开关——调用方明确要的就是这些。
            // 未知 type 直接跳过：可能是旧数据里已下架的图源。
            sources.mapNotNull { registry.byType(it) }
        }

    private suspend fun ProducerScope<SearchEvent>.runOne(
        info: SourceInfo,
        keyword: String,
        page: Int,
        strict: Boolean,
    ) {
        var found = 0
        try {
            val parser = factory.parser(info.type)
            val request = parser.getSearchRequest(keyword, page)
                ?: throw IllegalStateException("图源没有提供搜索请求（可能不支持按关键字搜索）")

            val html = http.getBody(request)
            val iterator = parser.getSearchIterator(html, page)
                ?: throw IllegalStateException("图源未返回搜索结果迭代器")

            // NodeIterator 的 hasNext() 在列表为空时会对 null 解引用，
            // 必须先问 empty()。原版 Manga.getSearchResult 也是这个顺序。
            if (iterator.empty()) throw IllegalStateException("图源没有返回任何搜索结果")

            val limit = settings.getInt(Keys.SEARCH_RATE_LIMIT_MS, Defaults.SEARCH_RATE_LIMIT_MS)
            while (iterator.hasNext()) {
                val comic = iterator.next() ?: continue
                if (!matches(comic, keyword, strict)) continue

                send(SearchEvent.Found(comic))
                found++

                // 原版每产出 0~200ms 的随机停顿（Thread.sleep(random.nextInt(200))），
                // 用来别把图源站打得太急。协程里换成 delay，不占线程。
                if (limit > 0) {
                    val wait = Random.nextInt(limit)
                    if (wait > 0) delay(wait.toLong())
                }
            }
            send(SearchEvent.SourceDone(info.type, info.title, found))
        } catch (e: CancellationException) {
            // 收集方取消（用户换了关键字）不是图源失败，必须原样抛出
            throw e
        } catch (e: Throwable) {
            send(SearchEvent.SourceFailed(info.type, info.title, describe(e)))
        }
    }

    /**
     * 严格模式的匹配。
     *
     * 原版写的是 `indexOfIgnoreCase(title, kw) || indexOfIgnoreCase(author, kw) || !strict`，
     * 而 `indexOfIgnoreCase` 直接对入参调 `toLowerCase()`——**作者为 null 时这一行就 NPE 了**，
     * 异常被外层吞掉，表现为「这个源莫名其妙一个结果都没有」。这里对 null 做了处理。
     */
    private fun matches(comic: Comic, keyword: String, strict: Boolean): Boolean {
        if (!strict) return true
        return containsIgnoreCase(comic.title, keyword) || containsIgnoreCase(comic.author, keyword)
    }

    private fun containsIgnoreCase(text: String?, keyword: String): Boolean =
        text != null && text.lowercase().contains(keyword.lowercase())

    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
