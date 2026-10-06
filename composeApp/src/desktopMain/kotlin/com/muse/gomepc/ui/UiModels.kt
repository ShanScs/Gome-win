package com.muse.gomepc.ui

import com.muse.gomepc.emby.YambyClient

/**
 * UI 统一模型：屏蔽 MockItem 与 YambyClient.Item 的差异。
 * 真实模式用 imageUrl 加载海报；演示模式用 hue 占位渐变。
 */

/** 媒体库 */
data class UiLibrary(
    val id: String,
    val name: String,
    val hue: Float = 210f
)

/** 媒体条目（电影/剧集） */
data class UiMediaItem(
    val id: String,
    val name: String,
    val year: String,
    val rating: String?,
    val overview: String,
    val libName: String,
    /** 真实海报 URL（演示模式为 null，走占位渐变） */
    val imageUrl: String?,
    val hue: Float = 210f,
    /** 继续观看进度 0..1（无则 null） */
    val progress: Float? = null
)

/** 选集 */
data class UiEpisode(
    val id: String,
    val index: Int,
    val name: String
)

/** YambyClient.Item → UiMediaItem */
fun YambyClient.Item.toUi(libName: String = ""): UiMediaItem {
    val progress = if (runTicks > 0 && playTicks > 0) {
        (playTicks.toFloat() / runTicks.toFloat()).coerceIn(0f, 1f)
    } else null
    return UiMediaItem(
        id = id,
        name = name,
        year = if (year > 0) year.toString() else "",
        rating = if (communityRating > 0) "%.1f".format(communityRating) else null,
        overview = overview,
        libName = libName,
        imageUrl = YambyClient.imageUrl(id, "Primary", 400),
        progress = progress
    )
}

/** YambyClient.Library → UiLibrary */
fun YambyClient.Library.toUi(): UiLibrary {
    // 按类型给个固定色相（演示占位用，真实模式有海报时不用）
    val hue = when (type.lowercase()) {
        "movies" -> 210f
        "tvshows" -> 150f
        "music" -> 280f
        else -> 190f
    }
    return UiLibrary(id, name, hue)
}

/** MockItem → UiMediaItem（演示模式） */
fun MockItem.toUi(): UiMediaItem = UiMediaItem(
    id = id,
    name = name,
    year = year,
    rating = rating,
    overview = overview,
    libName = libName,
    imageUrl = null,
    hue = hue
)

/** MockEpisode → UiEpisode（演示模式） */
fun MockEpisode.toUi(itemId: String): UiEpisode = UiEpisode(
    id = "$itemId-ep$index",
    index = index,
    name = name
)
