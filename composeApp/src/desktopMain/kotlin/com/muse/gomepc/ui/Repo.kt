package com.muse.gomepc.ui

import com.muse.gomepc.emby.YambyClient

/**
 * 数据仓库：UI 层唯一数据源。
 * demoMode=true 时用 MockData（演示模式）；false 时走真实 YambyClient。
 */
object Repo {
    var demoMode: Boolean = false

    /** 剧集列表内存缓存：itemId -> EpisodeData，避免重复请求 */
    private val episodeCache = mutableMapOf<String, EpisodeData>()
    /** 详情页头图信息缓存：itemId -> UiMediaItem，返回时不重拉服务器 */
    private val itemCache = mutableMapOf<String, UiMediaItem>()

    fun getCachedEpisodes(itemId: String): EpisodeData? = episodeCache[itemId]

    fun getCachedItem(itemId: String): UiMediaItem? = itemCache[itemId]

    fun cacheItem(itemId: String, item: UiMediaItem) {
        itemCache[itemId] = item
        if (itemCache.size > 20) {
            val k = itemCache.keys.firstOrNull()
            if (k != null) itemCache.remove(k)
        }
    }

    /** 只更新某集的播放进度（不重拉服务器，详情页重进时直接读缓存） */
    fun updateEpisodeProgress(itemId: String, episodeId: String, ticks: Long) {
        val cached = episodeCache[itemId] ?: return
        val updated = cached.episodes.map { ep ->
            if (ep.id == episodeId) ep.copy(playTicks = ticks)
            else ep
        }
        episodeCache[itemId] = cached.copy(episodes = updated)
    }

    fun cacheEpisodes(itemId: String, data: EpisodeData) {
        episodeCache[itemId] = data
        // 限制缓存大小，防止内存膨胀
        if (episodeCache.size > 20) {
            episodeCache.keys.firstOrNull()?.let { episodeCache.remove(it) }
        }
    }

    fun clearEpisodeCache() = episodeCache.clear()

    // ---------- 媒体库 ----------

    suspend fun libraries(): List<UiLibrary> {
        if (demoMode) return mockLibraries.map { UiLibrary(it.id, it.name, it.hue) }
        return YambyClient.getLibraries().map { it.toUi() }
    }

    suspend fun items(libId: String, count: Int = 30): List<UiMediaItem> {
        if (demoMode) {
            val lib = mockLibraries.find { it.id == libId } ?: mockLibraries.first()
            return mockItems(lib, count).map { it.toUi() }
        }
        val libName = try {
            libraries().find { it.id == libId }?.name ?: ""
        } catch (_: Exception) { "" }
        return YambyClient.getItems(libId, limit = count).map { it.toUi(libName) }
    }

    /**
     * 媒体库全量列表（分页取全，对齐 Android 的排序参数）。
     * 先取总数，再按 200 一页循环拉取，直到取完或达到上限。
     */
    suspend fun libraryItems(
        libId: String,
        sortBy: String = "PremiereDate",
        sortOrder: String = "Descending",
        maxItems: Int = 2000
    ): List<UiMediaItem> {
        if (demoMode) {
            val lib = mockLibraries.find { it.id == libId } ?: mockLibraries.first()
            return mockItems(lib, 120).map { it.toUi() }
        }
        val libName = try {
            libraries().find { it.id == libId }?.name ?: ""
        } catch (_: Exception) { "" }
        val total = try { YambyClient.getItemsTotal(libId) } catch (_: Exception) { 0 }
        val pageSize = 200
        val out = mutableListOf<YambyClient.Item>()
        var start = 0
        // 至少取一页；total 为 0 时也尝试取一页（服务器可能没返回总数）
        while (start < maxItems && (start == 0 || start < total)) {
            val page = try {
                YambyClient.getItems(
                    libId, limit = pageSize,
                    sortBy = sortBy, sortOrder = sortOrder,
                    startIndex = start
                )
            } catch (_: Exception) { break }
            if (page.isEmpty()) break
            out.addAll(page)
            if (page.size < pageSize) break  // 最后一页
            start += pageSize
        }
        return out.map { it.toUi(libName) }
    }

    suspend fun resumeItems(): List<UiMediaItem> {
        if (demoMode) return mockResumeItems().map { it.toUi() }
        // 直接返回列表，不逐个补 seriesId（点进详情页时 openDetail 会按需补，刷新速度优先）
        return YambyClient.getResumeItems(20).map { it.toUi() }
    }

    suspend fun latestItems(limit: Int = 8): List<UiMediaItem> {
        if (demoMode) return mockResumeItems().take(limit).map { it.toUi() }
        val latest = YambyClient.getLatestItems(limit).map { it.toUi() }
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
    suspend fun episodes(itemId: String): EpisodeData {
        // 先查缓存
        getCachedEpisodes(itemId)?.let { return it }
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
        // 写入缓存
        cacheEpisodes(itemId, result)
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
            // 演示模式：用系统属性指定的测试视频
            val v = System.getProperty("ui.video", "/tmp/dvtest/dv_p81.mp4")
            return listOf(v)
        }
        return YambyClient.getPlaybackUrls(episodeId)
    }

    /** 播放前上报（可失败忽略） */
    suspend fun reportPlaying(episodeId: String) {
        if (demoMode) return
        try { YambyClient.reportPlaying(episodeId) } catch (_: Exception) { }
    }
}

data class UiSeason(val id: String, val name: String)

data class EpisodeData(
    val episodes: List<UiEpisode>,
    val seasons: List<UiSeason>,
    val currentSeasonId: String?
)
