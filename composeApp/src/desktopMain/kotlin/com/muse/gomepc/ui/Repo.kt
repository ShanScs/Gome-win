package com.muse.gomepc.ui

import com.muse.gomepc.emby.Prefs
import com.muse.gomepc.emby.YambyClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据仓库：UI 层唯一数据源。
 * demoMode=true 时用 MockData（演示模式）；false 时走真实 YambyClient。
 */
object Repo {
    /**
     * 演示模式开关。多线程可见（Main 与后台协程都会读写，P2-E6）。
     * 缓存 Key 含 demoMode，切换后旧条目自动失效，不会把演示数据当成真实数据返回。
     */
    @Volatile
    var demoMode: Boolean = false

    // ---------- 线程安全缓存（P0-E9） ----------

    /** 缓存条目：值 + 写入时的缓存 Key + 写入时间戳（TTL 用） */
    private data class CacheEntry<T>(val value: T, val serverKey: String, val cachedAt: Long)

    /** 缓存 TTL：5 分钟，过期视为 miss，下次重拉服务器 */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 20

    /** 剧集列表内存缓存：itemId -> EpisodeData，避免重复请求 */
    private val episodeCache = ConcurrentHashMap<String, CacheEntry<EpisodeData>>()
    /** 详情页头图信息缓存：itemId -> UiMediaItem，返回时不重拉服务器 */
    private val itemCache = ConcurrentHashMap<String, CacheEntry<UiMediaItem>>()
    /** 复合写操作（清过期 Key 条目 + 写入 + 按最旧淘汰）加锁，避免并发写互相覆盖 */
    private val cacheMutex = Mutex()

    /**
     * 当前缓存 Key：服务器 Key + demoMode + 登录态 + Token 哈希。
     * 切换服务器 / 登出 / demoMode 变化 / Token 变化时 Key 变化，旧条目即视为 miss；
     * 写路径还会把旧 Key 条目全清，不让旧服务器数据残留串台。
     * 注意：之前 Key 里只有 isLoggedIn 布尔值，同账号重登（Token 换了）5 分钟内仍读旧缓存串态；
     * 现在 Key 含 Token 哈希（只存哈希，不存 Token 明文），重登即失效。
     */
    private fun currentCacheKey(): String =
        Prefs.currentServerKey() + "|demo=" + demoMode + "|in=" + Prefs.isLoggedIn() +
            "|tok=" + Prefs.token.hashCode()

    /** 条目新鲜：同 Key 且在 TTL 内 */
    private fun <T> CacheEntry<T>?.isFresh(key: String): Boolean {
        if (this == null) return false
        return serverKey == key && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
    }

    /** 清掉所有非当前 Key 的条目；调用方需持有 cacheMutex */
    private fun purgeStaleLocked(key: String) {
        episodeCache.entries.removeIf { it.value.serverKey != key }
        itemCache.entries.removeIf { it.value.serverKey != key }
    }

    /** 按写入时间淘汰最旧条目，直到回到上限；调用方需持有 cacheMutex */
    private fun <T> evictOldestLocked(map: ConcurrentHashMap<String, CacheEntry<T>>) {
        while (map.size > MAX_CACHE_ENTRIES) {
            val oldest = map.entries.minByOrNull { it.value.cachedAt }?.key ?: break
            map.remove(oldest)
        }
    }

    fun getCachedEpisodes(itemId: String): EpisodeData? {
        val key = currentCacheKey()
        val entry = episodeCache[itemId]
        if (entry == null || !entry.isFresh(key)) {
            // 过期 / 换服务器 / 登出 / demo 切换：删条目，视为 miss
            if (entry != null) episodeCache.remove(itemId, entry)
            return null
        }
        return entry.value
    }

    fun getCachedItem(itemId: String): UiMediaItem? {
        val key = currentCacheKey()
        val entry = itemCache[itemId]
        if (entry == null || !entry.isFresh(key)) {
            if (entry != null) itemCache.remove(itemId, entry)
            return null
        }
        return entry.value
    }

    suspend fun cacheItem(itemId: String, item: UiMediaItem) {
        val key = currentCacheKey()
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            purgeStaleLocked(key)
            itemCache[itemId] = CacheEntry(item, key, now)
            evictOldestLocked(itemCache)
        }
    }

    /** 只更新某集的播放进度（不重拉服务器，详情页重进时直接读缓存） */
    fun updateEpisodeProgress(itemId: String, episodeId: String, ticks: Long) {
        val key = currentCacheKey()
        // computeIfPresent 原子改条目：过期 / 换服务器则删条目，否则就地刷新该集 ticks
        episodeCache.computeIfPresent(itemId) { _, entry ->
            if (!entry.isFresh(key)) null
            else entry.copy(
                value = entry.value.copy(
                    episodes = entry.value.episodes.map { ep ->
                        if (ep.id == episodeId) ep.copy(playTicks = ticks)
                        else ep
                    }
                )
            )
        }
    }

    suspend fun cacheEpisodes(itemId: String, data: EpisodeData) {
        val key = currentCacheKey()
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            purgeStaleLocked(key)
            episodeCache[itemId] = CacheEntry(data, key, now)
            evictOldestLocked(episodeCache)
        }
    }

    fun clearEpisodeCache() = episodeCache.clear()

    /** 全清两份缓存：供登出 / 切换服务器等流程显式调用（平时读路径按 Key 懒失效） */
    fun clearAllCaches() {
        episodeCache.clear()
        itemCache.clear()
    }

    /** 失效单个详情条目（收藏 / 已看 / 进度变更成功后调用） */
    fun invalidateItem(itemId: String) {
        itemCache.remove(itemId)
    }

    /** 失效某剧的选集缓存 */
    fun invalidateEpisodes(seriesId: String) {
        episodeCache.remove(seriesId)
    }

    /** 失效包含某集的所有选集缓存（setPlayed 按集 id 调时用：剧集的 played 标志存在系列条目里） */
    fun invalidateEpisodesContaining(episodeId: String) {
        episodeCache.entries.removeIf { (_, entry) ->
            entry.value.episodes.any { ep -> ep.id == episodeId }
        }
    }

    /**
     * 收藏切换：透传 YambyClient.setFavorite 的 Boolean（true = 服务器 2xx 确认成功）。
     * 成功后失效详情缓存，下次进详情重拉服务器，避免 UI 读到旧收藏态。
     */
    suspend fun setFavorite(id: String, fav: Boolean): Boolean {
        val ok = YambyClient.setFavorite(id, fav)
        if (ok) invalidateItem(id)
        return ok
    }

    /** 已看切换：同 setFavorite；另失效含该集的选集缓存（played 标志存在系列条目里） */
    suspend fun setPlayed(id: String, played: Boolean): Boolean {
        val ok = YambyClient.setPlayed(id, played)
        if (ok) {
            invalidateItem(id)
            invalidateEpisodesContaining(id)
        }
        return ok
    }

    /**
     * 进度上报：透传 YambyClient.reportProgress（null = 成功或节流跳过，非 null = 错误信息）。
     * 成功后失效详情条目（续看进度已变）；选集条目就地刷新为上报的 ticks（值已知，
     * 直接失效会丢掉刚上报的位置、被迫重拉一次才拿到同一数值）。
     */
    suspend fun reportProgress(
        seriesItemId: String,
        episodeId: String,
        ticks: Long,
        paused: Boolean = false,
        mediaSourceId: String = "",
        playSessionId: String = ""
    ): String? {
        val err = YambyClient.reportProgress(episodeId, ticks, paused, mediaSourceId, playSessionId)
        if (err == null) {
            invalidateItem(seriesItemId)
            updateEpisodeProgress(seriesItemId, episodeId, ticks)
        }
        return err
    }

    // ---------- 媒体库 ----------

    suspend fun libraries(): List<UiLibrary> {
        if (demoMode) return mockLibraries.map { UiLibrary(it.id, it.name, it.hue) }
        return YambyClient.getLibraries().map { it.toUi() }
    }

    suspend fun items(libId: String, count: Int = 30, libName: String = ""): List<UiMediaItem> {
        if (demoMode) {
            val lib = mockLibraries.find { it.id == libId } ?: mockLibraries.first()
            return mockItems(lib, count).map { it.toUi() }
        }
        // libName 由调用方透传（已有库名时不再为查名字多发一次 libraries 请求）
        val name = libName.ifBlank {
            try {
                libraries().find { it.id == libId }?.name ?: ""
            } catch (_: Exception) { "" }
        }
        return YambyClient.getItems(libId, limit = count).map { it.toUi(name) }
    }

    /**
     * 媒体库增量分页：每拉到一页就回调一次累计列表，UI 首屏不用等全量取完。
     * 分页不依赖总数（不再多发一次 getItemsTotal 请求：总数在分页循环里用不上，
     * 之前每次进媒体库都多一次请求）；页满继续、页不满停止；按 Id 去重。
     */
    suspend fun libraryItemsPaged(
        libId: String,
        sortBy: String = "PremiereDate",
        sortOrder: String = "Descending",
        maxItems: Int = 2000,
        libName: String = "",
        onPage: (List<UiMediaItem>) -> Unit
    ) {
        if (demoMode) {
            val lib = mockLibraries.find { it.id == libId } ?: mockLibraries.first()
            onPage(mockItems(lib, 120).map { it.toUi() })
            return
        }
        val name = libName.ifBlank {
            try {
                libraries().find { it.id == libId }?.name ?: ""
            } catch (_: Exception) { "" }
        }
        val pageSize = 200
        val out = mutableListOf<YambyClient.Item>()
        val seenIds = mutableSetOf<String>()
        var start = 0
        while (start < maxItems) {
            val page = try {
                YambyClient.getItems(
                    libId, limit = pageSize,
                    sortBy = sortBy, sortOrder = sortOrder,
                    startIndex = start
                )
            } catch (e: Exception) {
                // 首页失败必须抛给调用方（UI 区分“失败”与“空库”）；后续页失败则按已有数据截断
                if (start == 0) throw e
                break
            }
            if (page.isEmpty()) break
            for (item in page) if (seenIds.add(item.id)) out.add(item)
            onPage(out.map { it.toUi(name) })
            if (page.size < pageSize) break
            start += pageSize
        }
    }

    /**
     * 媒体库全量列表（分页取全，对齐 Android 的排序参数）。
     * 分页不依赖 total：不再多发一次 getItemsTotal 请求，页满继续、页不满停止。
     * 按 Id 去重，避免服务器重复返回同一条目。
     */
    suspend fun libraryItems(
        libId: String,
        sortBy: String = "PremiereDate",
        sortOrder: String = "Descending",
        maxItems: Int = 2000,
        libName: String = ""
    ): List<UiMediaItem> {
        if (demoMode) {
            val lib = mockLibraries.find { it.id == libId } ?: mockLibraries.first()
            return mockItems(lib, 120).map { it.toUi() }
        }
        val name = libName.ifBlank {
            try {
                libraries().find { it.id == libId }?.name ?: ""
            } catch (_: Exception) { "" }
        }
        val pageSize = 200
        val out = mutableListOf<YambyClient.Item>()
        val seenIds = mutableSetOf<String>()
        var start = 0
        // 不依赖总数：页满继续、页不满停止（不再为总数多发一次请求）
        while (start < maxItems) {
            val page = try {
                YambyClient.getItems(
                    libId, limit = pageSize,
                    sortBy = sortBy, sortOrder = sortOrder,
                    startIndex = start
                )
            } catch (e: Exception) {
                if (start == 0) throw e
                break
            }
            if (page.isEmpty()) break
            for (item in page) if (seenIds.add(item.id)) out.add(item)
            if (page.size < pageSize) break  // 最后一页
            start += pageSize
        }
        return out.map { it.toUi(name) }
    }

    suspend fun resumeItems(): List<UiMediaItem> {
        if (demoMode) return mockResumeItems().map { it.toUi() }
        // 直接返回列表，不逐个补 seriesId（点进详情页时 openDetail 会按需补，刷新速度优先）
        return YambyClient.getResumeItems(20).map { it.toUi() }
    }

    suspend fun latestItems(limit: Int = 8): List<UiMediaItem> {
        if (demoMode) return mockResumeItems().take(limit).map { it.toUi() }
        // 第一行也包进 try：getLatestItems 抛异常时走兜底，不直接抛给调用方（P1-E8）
        val latest = try {
            YambyClient.getLatestItems(limit).map { it.toUi() }
        } catch (_: Exception) { emptyList() }
        if (latest.isNotEmpty()) return latest
        // 兜底：取第一个媒体库按创建日期倒序
        return try {
            val libs = YambyClient.getLibraries()
            if (libs.isNotEmpty()) {
                YambyClient.getItems(libs[0].id, limit = limit, sortBy = "DateCreated", sortOrder = "Descending")
                    .map { it.toUi() }
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun search(query: String): List<UiMediaItem> {
        if (demoMode) {
            if (query.isBlank()) return emptyList()
            return mockLibraries.flatMap { mockItems(it, 12) }
                .filter { it.name.contains(query) }
                .map { it.toUi() }
        }
        // 只搜当前服务器：真正的多服务器聚合需要各服务器的独立 Token，而 Prefs 只存当前
        // 服务器的 Token（密码不落盘），之前的“聚合搜索”分支实际也只是搜当前服务器，
        // 是假分支，已删；搜索页的服务器选择器也相应改为只显示“当前服务器”。
        return YambyClient.searchItems(query).map { it.toUi() }
    }

    suspend fun favorites(): List<UiMediaItem> {
        if (demoMode) return mockLibraries.flatMap { mockItems(it, 8) }
            .filterIndexed { i, _ -> i % 3 == 0 }
            .map { it.toUi() }
        return YambyClient.getFavoriteItems().map { it.toUi() }
    }

    // ---------- 详情 ----------

    suspend fun itemDetail(id: String): UiMediaItem {
        if (demoMode) {
            // mock id 形如 "lib2-3"：解析出 library 和序号
            val libId = id.substringBefore("-")
            val idx = id.substringAfter("-", "0").toIntOrNull() ?: 0
            val lib = mockLibraries.find { it.id == libId } ?: mockLibraries.first()
            val mock = mockItems(lib, idx + 1).getOrElse(idx) {
                mockItems(lib, 10).first()
            }
            return mock.copy(id = id).toUi()
        }
        val result = YambyClient.getItem(id).toUi()
        cacheItem(id, result)
        return result
    }

    /** 取详情（优先缓存，不重拉服务器，用于播放器返回时） */
    suspend fun itemDetailCached(id: String): UiMediaItem {
        getCachedItem(id)?.let { return it }
        return itemDetail(id)
    }

    /**
     * 取选集。电影返回单集"正片"；剧集按季取（默认第一季）。
     * 返回 Pair(剧集列表, 季列表)——季列表用于多季切换，演示模式为空。
     */
    /** 选集在途请求去重：同剧并发进详情/播放器时只发一次请求，其余等同一份结果 */
    private val episodesInFlight = ConcurrentHashMap<String, CompletableDeferred<EpisodeData>>()

    suspend fun episodes(itemId: String): EpisodeData {
        // 先查缓存
        getCachedEpisodes(itemId)?.let { return it }
        // 在途去重：已有同剧请求在飞，等它的结果，不再重复发
        episodesInFlight[itemId]?.let { return it.await() }
        val deferred = CompletableDeferred<EpisodeData>()
        // 只有一个调用方能抢到占位；抢不到的等占位者的结果
        if (episodesInFlight.putIfAbsent(itemId, deferred) != null) {
            return episodesInFlight[itemId]!!.await()
        }
        try {
            val result = loadEpisodes(itemId)
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            episodesInFlight.remove(itemId, deferred)
        }
    }

    /** episodes() 的实际加载体（被 in-flight 去重包装） */
    private suspend fun loadEpisodes(itemId: String): EpisodeData {
        val result: EpisodeData = if (demoMode) {
            val libId = itemId.substringBefore("-")
            val lib = mockLibraries.find { it.id == libId }
            val mockItem = MockItem(itemId, "", "", null, 150f, "", "")
            val eps = mockEpisodes(mockItem).map { it.toUi(itemId) }
            EpisodeData(eps, emptyList(), null)
        } else {
            val item = YambyClient.getItem(itemId)
            when (item.type) {
                "Movie" -> {
                    // 电影：单集直接播本体
                    EpisodeData(
                        listOf(UiEpisode(itemId, 1, "正片", playTicks = item.playTicks)),
                        emptyList(), null
                    )
                }
                "Series" -> {
                    val seasons = YambyClient.getSeasons(itemId)
                    if (seasons.isEmpty()) {
                        EpisodeData(emptyList(), emptyList(), null)
                    } else {
                        // 默认季：getSeasons 已按“seasonIdx>=1 在前、按季号升序”排好序
                        // （YambyClient.getSeasons），first() 即 seasonIdx>=1 的第一季，
                        // 不会默认取到特别篇（P1-E10 已确认）。
                        val first = seasons.first()
                        val eps = YambyClient.getEpisodes(itemId, first.id).map {
                            UiEpisode(
                                it.id, it.episodeIdx, it.epLabel(),
                                width = it.width, height = it.height,
                                runTicks = it.runTicks, sizeBytes = it.sizeBytes,
                                played = it.played, playTicks = it.playTicks
                            )
                        }.sortedBy { it.index }
                        EpisodeData(
                            eps,
                            seasons.map { UiSeason(it.id, it.name.ifBlank { "第${it.seasonIdx}季" }) },
                            first.id
                        )
                    }
                }
                else -> EpisodeData(emptyList(), emptyList(), null)
            }
        }
        // 只缓存非空成功结果：空结果（季为空 / 该季无集）不写缓存，下次重拉；
        // 网络异常直接抛给调用方，同样不写缓存（P1-E10）。
        if (result.episodes.isNotEmpty()) cacheEpisodes(itemId, result)
        return result
    }

    suspend fun seasonEpisodes(seriesId: String, seasonId: String): List<UiEpisode> {
        if (demoMode) return emptyList()
        return YambyClient.getEpisodes(seriesId, seasonId).map {
            UiEpisode(
                it.id, it.episodeIdx, it.epLabel(),
                width = it.width, height = it.height,
                runTicks = it.runTicks, sizeBytes = it.sizeBytes,
                played = it.played, playTicks = it.playTicks
            )
        }.sortedBy { it.index }
    }

    // ---------- 播放 ----------

    suspend fun playbackUrls(episodeId: String): List<String> {
        if (demoMode) {
            // 演示视频可配置：启动时加 -Dui.video=<本地视频路径> 覆盖；
            // 未指定时用默认测试视频（P2-E6）。
            val v = System.getProperty("ui.video", "/tmp/dvtest/dv_p81.mp4")
            return listOf(v)
        }
        return YambyClient.getPlaybackUrls(episodeId)
    }

    /** 按详情页选的版本（MediaSourceId）取流；取不到则回退默认取流，不直接抛给 UI */
    suspend fun playbackUrlsForSource(episodeId: String, mediaSourceId: String): List<String> {
        if (demoMode) return playbackUrls(episodeId)
        return try {
            YambyClient.getPlaybackUrlsForSource(episodeId, mediaSourceId)
        } catch (_: Exception) {
            YambyClient.getPlaybackUrls(episodeId)
        }
    }

    /** 播放前上报（可失败忽略）；带 MediaSourceId 时服务器按所选版本建会话 */
    suspend fun reportPlaying(episodeId: String, mediaSourceId: String = "") {
        if (demoMode) return
        try { YambyClient.reportPlaying(episodeId, mediaSourceId) } catch (_: Exception) { }
    }
}

data class UiSeason(val id: String, val name: String)

data class EpisodeData(
    val episodes: List<UiEpisode>,
    val seasons: List<UiSeason>,
    val currentSeasonId: String?
)
