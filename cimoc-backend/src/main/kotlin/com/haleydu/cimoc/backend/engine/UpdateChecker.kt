package com.haleydu.cimoc.backend.engine

import com.haleydu.cimoc.backend.http.HttpEngine
import com.haleydu.cimoc.backend.source.ParserFactory
import com.haleydu.cimoc.backend.source.SourceRegistry
import com.haleydu.cimoc.backend.storage.AppSettings
import com.haleydu.cimoc.backend.storage.Defaults
import com.haleydu.cimoc.backend.storage.Keys
import com.haleydu.cimoc.backend.storage.Library
import com.haleydu.cimoc.model.Comic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 收藏夹更新检查的事件流。
 */
sealed interface UpdateEvent {

    /** 检查完一本。[updated] 为 true 表示发现了新章节（已落库：`update_at` / `highlight` / `favorite_at` 都已更新）。 */
    data class Checked(val comic: Comic, val updated: Boolean) : UpdateEvent

    /**
     * 某本检查失败。**静默跳过、不中断整体**——沿用原版行为。
     *
     * 收藏夹里总有一部分漫画是图源已经下架或改版的，让它们把整轮检查拖垮没有意义。
     */
    data class Failed(val comicId: Long, val source: Int, val error: String) : UpdateEvent

    /** 全部检查完毕。 */
    data class Done(val checked: Int, val updated: Int, val failed: Int) : UpdateEvent
}

/**
 * 收藏夹更新检查。对应原版 `Manga.checkUpdate`。
 *
 * 与原版的区别：
 * - 原版是**逐个串行**检查，收藏了 100 本就要等 100 次网络往返。这里做**受限并发**（默认 4）。
 *   并发数不能开太大：同时几十个请求打到同一批站点，很容易被限流甚至封 IP。
 * - 原版只有一个 `getCheckRequest`，这里在它返回 null 时退回 `getInfoRequest`
 *   （基类 `MangaParser.getCheckRequest` 默认返回 null，退回总比直接失败好）。
 */
class UpdateChecker(
    private val registry: SourceRegistry,
    private val factory: ParserFactory,
    private val http: HttpEngine,
    private val library: Library,
    private val settings: AppSettings,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
) {

    /**
     * 检查全部收藏。逐本产出结果，方便前端实时更新进度条。
     */
    fun checkAll(): Flow<UpdateEvent> = channelFlow {
        val favorites = library.favorites()
        if (favorites.isEmpty()) {
            send(UpdateEvent.Done(0, 0, 0))
            return@channelFlow
        }

        // 检查用的超时远比常规请求短：收藏夹可能有上百本，一本卡 30 秒就是不可接受的等待。
        // 原版也是单独建了一个 1500ms 的客户端。
        val fast = http.withTimeouts(CHECK_TIMEOUT_MS, CHECK_TIMEOUT_MS)
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val counter = Counter()

        coroutineScope {
            favorites.forEach { comic ->
                launch {
                    semaphore.withPermit {
                        when (val result = checkOne(comic, fast)) {
                            is Outcome.Updated -> {
                                counter.checked.incrementAndGet()
                                counter.updated.incrementAndGet()
                                send(UpdateEvent.Checked(result.comic, true))
                            }
                            is Outcome.Unchanged -> {
                                counter.checked.incrementAndGet()
                                send(UpdateEvent.Checked(result.comic, false))
                            }
                            is Outcome.Skipped -> {
                                counter.checked.incrementAndGet()
                                send(UpdateEvent.Checked(comic, false))
                            }
                            is Outcome.Failed -> {
                                counter.failed.incrementAndGet()
                                send(UpdateEvent.Failed(comic.id ?: -1L, comic.source, result.error))
                            }
                        }
                    }
                }
            }
        }

        send(UpdateEvent.Done(counter.checked.get(), counter.updated.get(), counter.failed.get()))
    }

    // ---------------------------------------------------------------- 自动检查门槛

    /**
     * 自动检查到点了没有。
     *
     * 后端**只提供判断**，是否触发由前端决定（桌面端在 `AppHolder.start()` 里问一次）。
     * 间隔取自 [Keys.CHECK_UPDATE_INTERVAL_HOURS]：改小查得勤，代价是每轮都要把整个
     * 收藏夹打一遍图源站。
     */
    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean {
        if (!settings.getBoolean(Keys.CHECK_UPDATE_AUTO, Defaults.CHECK_UPDATE_AUTO)) return false
        val last = settings.getLong(Keys.CHECK_UPDATE_LAST, 0L)
        if (last <= 0L) return true

        val interval = settings.getInt(
            Keys.CHECK_UPDATE_INTERVAL_HOURS,
            Defaults.CHECK_UPDATE_INTERVAL_HOURS,
        )
        val elapsed = now - last
        // 系统时钟被往回拨时 elapsed 是负数。当成「间隔已过」：多查一轮只是多几次网络请求，
        // 而按字面取负值的话会静悄悄地把自动检查停掉，用户根本看不见。
        return elapsed < 0 || elapsed >= interval.coerceAtLeast(1) * HOUR_MS
    }

    /**
     * 记录本次检查时间。前端在检查**开始时**调用一次即可。
     *
     * 手动检查也要调：[shouldAutoCheck] 比的是「距上次检查」，刚手动查完又被自动查一遍
     * 是白费一轮请求。
     */
    fun markChecked(now: Long = System.currentTimeMillis()) {
        settings.put(Keys.CHECK_UPDATE_LAST, now)
    }

    // ---------------------------------------------------------------- 单本检查

    private sealed interface Outcome {
        data class Updated(val comic: Comic) : Outcome
        data class Unchanged(val comic: Comic) : Outcome

        /** 图源给出的更新时间拿不到，无从比较。 */
        data object Skipped : Outcome

        data class Failed(val error: String) : Outcome
    }

    private suspend fun checkOne(comic: Comic, http: HttpEngine): Outcome {
        val info = registry.byType(comic.source)
            ?: return Outcome.Failed("图源 type=${comic.source} 已不在注册表中")
        val parser = factory.parser(comic.source)

        return try {
            val request = parser.getCheckRequest(comic.cid) ?: parser.getInfoRequest(comic.cid)
                ?: return Outcome.Failed("图源 ${info.title} 没有提供更新检查请求")

            val update = parser.parseCheck(http.getBody(request))
                ?: return Outcome.Skipped

            // 原版的条件：旧值非 null、新值非 null、且两者不同。
            // 旧值为 null（还没记录过更新时间）时不标记更新——否则第一次收藏的漫画
            // 会全部被标成「有更新」，高亮瞬间失去意义。
            if (comic.update == null || comic.update == update) {
                Outcome.Unchanged(comic)
            } else {
                val id = comic.id ?: return Outcome.Unchanged(comic)
                library.markUpdated(id, update)
                Outcome.Updated(requireNotNull(library.comics.load(id)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Outcome.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }

    /**
     * 各协程共享的计数。用原子类型而不是普通 `var`：`channelFlow` 的子协程跑在
     * `Dispatchers.Default` 这种多线程调度器上，`n++` 不是原子操作，会丢计数。
     */
    private class Counter {
        val checked = AtomicInteger()
        val updated = AtomicInteger()
        val failed = AtomicInteger()
    }

    companion object {
        /** 更新检查的网络超时。比常规请求短得多，见 [checkAll]。 */
        const val CHECK_TIMEOUT_MS = 1_500L

        /** 自动检查间隔的换算基数（小时 → 毫秒）。 */
        private const val HOUR_MS = 3_600_000L

        /**
         * 默认并发。原版是串行，这里开 4 路。
         *
         * 不取更大值是因为这些请求全都打向**同一批**漫画站，而不是分散在很多域名上——
         * 收藏几十本同源漫画时，并发数就等于对该站的瞬时压力。
         */
        const val DEFAULT_CONCURRENCY = 4
    }
}
