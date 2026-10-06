package com.muse.gomepc.emby

import kotlinx.coroutines.runBlocking

/**
 * 真实流程冒烟测试（连 mock Emby 服务器）。
 * 用法：先起 `python3 /tmp/mock_emby.py 8099`，再跑 :composeApp:runEmbyFlowTest
 */
fun main() = runBlocking {
    // 指向 mock 服务器
    Prefs.protocol = "http"
    Prefs.host = "127.0.0.1"
    Prefs.port = "8099"
    Prefs.path = "emby"
    Prefs.clearLogin()

    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) { pass++; println("  PASS: $name") }
        else { fail++; println("  FAIL: $name $detail") }
    }

    println("== Emby 真实流程测试 ==")

    // 1. 登录
    val auth = try {
        YambyClient.login("test", "123")
    } catch (e: Exception) {
        println("  登录异常堆栈:")
        e.printStackTrace()
        null.also { fail++ }
    }
    check("登录", auth != null && auth.token == "mock-token-12345" && auth.userId == "user-1")
    check("Prefs 持久化", Prefs.isLoggedIn())

    // 2. 媒体库
    val libs = try { YambyClient.getLibraries() } catch (e: Exception) { emptyList<YambyClient.Library>().also { println("  FAIL: getLibraries ${e.message}"); fail++ } }
    check("媒体库数量", libs.size == 2, "got ${libs.size}")
    check("媒体库名称", libs.any { it.name == "电影" } && libs.any { it.name == "电视剧" })

    // 3. 条目列表
    val movieLib = libs.find { it.name == "电影" }!!
    val movies = try { YambyClient.getItems(movieLib.id, limit = 10) } catch (e: Exception) { emptyList<YambyClient.Item>().also { println("  FAIL: getItems ${e.message}"); fail++ } }
    check("电影列表", movies.size == 2, "got ${movies.size}")
    check("条目字段", movies.firstOrNull()?.name == "测试电影A")

    // 4. 继续观看
    val resume = try { YambyClient.getResumeItems(10) } catch (e: Exception) { emptyList<YambyClient.Item>().also { println("  FAIL: getResumeItems ${e.message}"); fail++ } }
    check("继续观看", resume.isNotEmpty(), "got ${resume.size}")

    // 5. 详情
    val tvLib = libs.find { it.name == "电视剧" }!!
    val series = try { YambyClient.getItems(tvLib.id, limit = 10) } catch (e: Exception) { emptyList<YambyClient.Item>() }
    val seriesId = series.firstOrNull()?.id ?: ""
    check("剧集列表", seriesId == "s1", "got $seriesId")
    val detail = try { YambyClient.getItem(seriesId) } catch (e: Exception) { null.also { println("  FAIL: getItem ${e.message}"); fail++ } }
    check("详情", detail?.name == "测试剧集")

    // 6. 季/集
    val seasons = try { YambyClient.getSeasons(seriesId) } catch (e: Exception) { emptyList<YambyClient.Item>().also { println("  FAIL: getSeasons ${e.message}"); fail++ } }
    check("季", seasons.size == 1, "got ${seasons.size}")
    val eps = try { YambyClient.getEpisodes(seriesId, seasons.first().id) } catch (e: Exception) { emptyList<YambyClient.Item>().also { println("  FAIL: getEpisodes ${e.message}"); fail++ } }
    check("集", eps.size == 2, "got ${eps.size}")

    // 7. 播放地址
    val epId = eps.firstOrNull()?.id ?: "e1"
    val urls = try { YambyClient.getPlaybackUrls(epId) } catch (e: Exception) { emptyList<String>().also { println("  FAIL: getPlaybackUrls ${e.message}"); fail++ } }
    check("播放地址", urls.isNotEmpty(), "got ${urls.size}")
    println("  播放地址示例: ${urls.firstOrNull()?.take(80)}")

    // 8. 图片 URL 构造
    val imgUrl = YambyClient.imageUrl("m1", "Primary", 400)
    check("图片URL", imgUrl.contains("/emby/Items/m1/Images/Primary") && imgUrl.contains("api_key=mock-token-12345"))

    // 9. 图片实际可下载
    val bmp = try {
        com.muse.gomepc.ui.EmbyImageLoader.load(imgUrl)
    } catch (e: Exception) { null.also { println("  FAIL: 图片下载 ${e.message}"); fail++ } }
    check("图片下载解码", bmp != null)

    println("== 结果: $pass 通过, $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
