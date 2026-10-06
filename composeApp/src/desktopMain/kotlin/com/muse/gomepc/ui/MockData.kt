package com.muse.gomepc.ui

import androidx.compose.ui.graphics.Color

/**
 * Mock 数据（真实 Emby 服务器对接前用于 UI 验证）。
 * 后续替换为 YambyClient.getLibraries()/getItems() 的真实返回。
 */
data class MockLibrary(
    val id: String,
    val name: String,
    val count: Int,
    val hue: Float // 海报占位渐变色相
)

data class MockItem(
    val id: String,
    val name: String,
    val year: String,
    val rating: String?,
    val hue: Float,
    val libName: String,
    val overview: String
)

data class MockEpisode(
    val index: Int,
    val name: String,
    val duration: String
)

val mockLibraries = listOf(
    MockLibrary("lib1", "电影", 128, 210f),
    MockLibrary("lib2", "电视剧", 86, 150f),
    MockLibrary("lib3", "综艺", 42, 35f),
    MockLibrary("lib4", "动漫", 64, 280f),
    MockLibrary("lib5", "纪录片", 30, 190f),
)

private val names = listOf(
    "很想很想你" to "2023", "漫长的季节" to "2023", "狂飙" to "2023",
    "流浪地球2" to "2023", "封神第一部" to "2023", "长安三万里" to "2023",
    "去有风的地方" to "2023", "三体" to "2023", "繁花" to "2024",
    "我的阿勒泰" to "2024", "庆余年2" to "2024", "唐朝诡事录" to "2024",
    "星际穿越" to "2014", "盗梦空间" to "2010", "千与千寻" to "2001",
    "肖申克的救赎" to "1994", "阿甘正传" to "1994", "霸王别姬" to "1993"
)

private val overviews = listOf(
    "热爱作曲的电视编导顾声，即便默默无闻仍旧努力创作，她最大的心愿就是和知名配音演员莫青成合作。",
    "一九九七年，火车南站发生一起恶性碎尸案，王响和工友们的人生就此改变。",
    "京海市刑警安欣与黑恶势力高启强展开长达二十年的正邪较量。",
    "太阳即将毁灭，人类开启流浪地球计划，数字生命与现实存亡激烈碰撞。"
)

fun mockItems(lib: MockLibrary, count: Int = 10): List<MockItem> =
    (0 until count).map { i ->
        val (n, y) = names[(lib.id.hashCode() + i).let { if (it < 0) -it else it } % names.size]
        MockItem(
            id = "${lib.id}-$i",
            name = n,
            year = y,
            rating = if (i % 3 == 0) "%.1f".format(9.2 - (i % 5) * 0.3) else null,
            hue = (lib.hue + i * 17) % 360,
            libName = lib.name,
            overview = overviews[i % overviews.size]
        )
    }

fun mockResumeItems(): List<MockItem> =
    mockItems(mockLibraries[1], 8).mapIndexed { i, it ->
        it.copy(name = it.name, rating = "${(i + 1) * 12}%")
    }

fun mockEpisodes(item: MockItem, count: Int = 24): List<MockEpisode> =
    (1..count).map { i ->
        MockEpisode(i, "第${i}集", "45分${(i * 7) % 60}秒")
    }

/** 占位海报渐变：按色相生成两档颜色 */
fun posterColors(hue: Float): Pair<Color, Color> {
    val c1 = Color.hsl(hue, 0.45f, 0.55f)
    val c2 = Color.hsl((hue + 40) % 360, 0.5f, 0.35f)
    return c1 to c2
}
