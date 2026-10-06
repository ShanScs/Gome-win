package com.muse.gomepc.ui

import com.muse.gomepc.emby.YambyClient

/**
 * 数据仓库：UI 层唯一数据源。
 * demoMode=true 时用 MockData（演示模式）；false 时走真实 YambyClient。
 */
object Repo {
    var demoMode: Boolean = false

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

    suspend fun search(query: String): List<UiMediaItem> {
        if (demoMode) {
            if (query.isBlank()) return emptyList()
            return mockLibraries.flatMap { mockItems(it, 12) }
                .filter { it.name.contains(query) }
                .map { it.toUi() }
        }
        return YambyClient.searchItems(query).map { it.toUi() }
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
    suspend fun episodes(itemId: String): EpisodeData {
        if (demoMode) {
            val libId = itemId.substringBefore("-")
            val lib = mockLibraries.find { it.id == libId }
            val mockItem = MockItem(itemId, "", "", null, 150f, "", "")
            val eps = mockEpisodes(mockItem).map { it.toUi(itemId) }
            return EpisodeData(eps, emptyList(), null)
        }
        val item = YambyClient.getItem(itemId)
        return when (item.type) {
            "Movie" -> {
                // 电影：单集直接播本体
                EpisodeData(
                    listOf(UiEpisode(itemId, 1, "正片")),
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
                        UiEpisode(it.id, it.episodeIdx, it.epLabel())
                    }
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

    suspend fun seasonEpisodes(seriesId: String, seasonId: String): List<UiEpisode> {
        if (demoMode) return emptyList()
        return YambyClient.getEpisodes(seriesId, seasonId).map {
            UiEpisode(it.id, it.episodeIdx, it.epLabel())
        }
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
