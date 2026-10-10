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

    fun getCachedEpisodes(itemId: String): EpisodeData? = episodeCache[itemId]

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

    suspend fun resumeItems(): List<UiMediaItem> {
        if (demoMode) return mockResumeItems().map { it.toUi() }
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
        return YambyClient.getItem(id).toUi()
    }

    /**
     * 取选集。电影返回单集"正片"；剧集按季取（默认第一季）。
     * 返回 Pair(剧集列表, 季列表)——季列表用于多季切换，演示模式为空。
     */
    /**
     * 取剧集的季/集（1:1 安卓 DetailActivity.loadSeasons：先按 type=="Season" 过滤，
     * 为空则用未过滤列表兜底）。
     */
    private suspend fun seriesEpisodes(itemId: String): EpisodeData {
        val allSeasons = YambyClient.getSeasons(itemId)
        val seasons = allSeasons.filter { it.type.equals("Season", ignoreCase = true) }
            .ifEmpty { allSeasons }
        if (seasons.isEmpty()) {
            return EpisodeData(emptyList(), emptyList(), null)
        }
        val first = seasons.first()
        val eps = YambyClient.getEpisodes(itemId, first.id).map {
            UiEpisode(
                it.id, it.episodeIdx, it.epLabel(),
                width = it.width, height = it.height,
                runTicks = it.runTicks, sizeBytes = it.sizeBytes,
                played = it.played
            )
        }
        return EpisodeData(
            eps,
            seasons.map { UiSeason(it.id, it.name.ifBlank { "第${it.seasonIdx}季" }) },
            first.id
        )
    }

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
            // type 规范化：去空白、忽略大小写（Emby 偶发返回非标准大小写）
            val t = item.type.trim()
            val result2: EpisodeData = when {
                t.equals("Movie", ignoreCase = true) -> {
                    // 电影：单集直接播本体
                    EpisodeData(
                        listOf(UiEpisode(itemId, 1, "正片")),
                        emptyList(), null
                    )
                }
                t.equals("Series", ignoreCase = true) -> {
                    seriesEpisodes(itemId)
                }
                else -> {
                    // 未知类型：先按剧集试（取季），有季则按剧集处理，
                    // 否则兜底单集"正片"保证播放按钮可用（1:1 安卓：电影直接播本体）
                    val se = try { seriesEpisodes(itemId) } catch (_: Exception) { null }
                    if (se != null && se.episodes.isNotEmpty()) se
                    else EpisodeData(
                        listOf(UiEpisode(itemId, 1, "正片")),
                        emptyList(), null
                    )
                }
            }
            result2
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
                played = it.played
            )
        }
    }

    // ---------- 演员 ----------

    /** 演员详情：简介 + 作品（转 UiMediaItem）。演示模式返回占位数据。 */
    suspend fun personDetail(personId: String): Pair<String, List<UiMediaItem>> {
        if (demoMode) {
            val items = mockLibraries.flatMap { mockItems(it, 6) }
                .map { it.toUi() }
            return "演示模式：暂无演员简介。" to items
        }
        val bio = YambyClient.getPersonBio(personId)
        val works = YambyClient.getPersonWorks(personId).map { it.toUi() }
        return bio to works
    }

    // ---------- 播放 ----------

    /**
     * 播放地址候选列表（按顺序试）。
     * @param mediaSourceId 详情页选中的版本（空=自动）；指定时只取该版本的地址
     * @param startTicks 服务器续播位置（转码会话用；直链靠播放器 seek）
     */
    suspend fun playbackUrls(
        episodeId: String,
        mediaSourceId: String? = null,
        startTicks: Long = 0
    ): List<String> {
        if (demoMode) {
            // 演示模式：用系统属性指定的测试视频
            val v = System.getProperty("ui.video", "/tmp/dvtest/dv_p81.mp4")
            return listOf(v)
        }
        return if (!mediaSourceId.isNullOrEmpty()) {
            YambyClient.getPlaybackUrlsForSource(episodeId, mediaSourceId, startTicks)
        } else {
            YambyClient.getPlaybackUrls(episodeId, startTicks)
        }
    }

    /** 播放前上报（可失败忽略） */
    suspend fun reportPlaying(episodeId: String, mediaSourceId: String? = null) {
        if (demoMode) return
        try { YambyClient.reportPlaying(episodeId, mediaSourceId.orEmpty()) } catch (_: Exception) { }
    }

    /** 播放中进度上报（每 10 秒调一次），让服务器"继续观看"更新 */
    suspend fun reportProgress(episodeId: String, ticks: Long, paused: Boolean) {
        if (demoMode) return
        try { YambyClient.reportProgress(episodeId, ticks, paused) } catch (_: Exception) { }
    }

    /** 退出/销毁时上报停止位置 */
    suspend fun reportStopped(episodeId: String, ticks: Long) {
        if (demoMode) return
        try { YambyClient.reportStopped(episodeId, ticks) } catch (_: Exception) { }
    }

    /** 服务器记录的续播位置（ticks）；演示模式或失败返回 0 */
    suspend fun getResumeTicks(episodeId: String): Long {
        if (demoMode) return 0L
        return try { YambyClient.getItem(episodeId).playTicks } catch (_: Exception) { 0L }
    }

    /** 诊断播放地址：Range 请求看服务器到底返回什么（状态码/类型/前几个字节），不泄露 token */
    suspend fun diagnoseUrl(rawUrl: String): String {
        if (demoMode) return "演示模式跳过诊断"
        return try { YambyClient.diagnoseUrl(rawUrl) } catch (e: Exception) { "诊断失败：${e.message?.take(60)}" }
    }

    // ---------- 轨道/版本选择（详情页 → 播放器） ----------
    //
    // 注意：DetailScreen（Screens.kt）的 onPlay 回调签名是 (UiMediaItem, UiEpisode) -> Unit，
    // 选中的版本/音轨/字幕目前传不出来。先经此中转：Screens.kt 里 onPlay 调用前写一次
    // Repo.pendingTrackParams = PlaybackTrackParams(selectedMediaSourceId.ifEmpty { null },
    //     selectedAudioIndex, selectedSubtitleIndex)，PlayerScreen 启动时 consume 取走。
    // 在 Screens.kt 补上那一行之前，播放器用默认值（自动版本/默认音轨/默认字幕）。
    var pendingTrackParams: PlaybackTrackParams? = null

    /** 取走待处理的轨道参数（取后清空，避免污染下一次播放） */
    fun consumePendingTrackParams(): PlaybackTrackParams? {
        val p = pendingTrackParams
        pendingTrackParams = null
        return p
    }
}

/** 详情页选中的版本/音轨/字幕（-1=默认；mediaSourceId 空=自动版本） */
data class PlaybackTrackParams(
    val mediaSourceId: String? = null,
    val audioIndex: Int = -1,
    val subtitleIndex: Int = -1
)

data class UiSeason(val id: String, val name: String)

data class EpisodeData(
    val episodes: List<UiEpisode>,
    val seasons: List<UiSeason>,
    val currentSeasonId: String?
)
