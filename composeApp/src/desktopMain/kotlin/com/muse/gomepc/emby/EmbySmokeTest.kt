package com.muse.gomepc.emby

import org.json.JSONObject

/**
 * YambyClient 冒烟测试（Step 3 验证用）。
 * 不连真实服务器，只验证编译通过 + 纯函数/DV检测/Prefs桌面存储逻辑正确。
 * 运行：./gradlew :composeApp:runEmbySmokeTest
 */
fun main() {
    var passed = 0
    fun check(name: String, cond: Boolean) {
        if (!cond) throw AssertionError("FAILED: $name")
        println("  ✓ $name")
        passed++
    }

    println("== 1. 纯函数 ==")
    check("CLIENT_NAME", YambyClient.CLIENT_NAME == "Yamby")
    check("posLabel", YambyClient.posLabel(72_300_000_000L) == "02:00:30")
    check("durShort", YambyClient.durShort(2_770_000_000L) == "04:37")
    check("durationLabel", YambyClient.durationLabel(29_630_000_000L) == "49分23秒")
    check("sizeLabel GB", YambyClient.sizeLabel(1_440_000_000L) == "1.34G")
    check("sizeLabel MB", YambyClient.sizeLabel(898_000_000L) == "856M")
    check("remainingLabel", YambyClient.remainingLabel(1_370_000_000L) == "剩余：2分17秒")

    println("== 2. Prefs 桌面存储 ==")
    Prefs.protocol = "https"
    Prefs.host = "example.com"
    Prefs.port = "443"
    Prefs.path = "emby"
    check("baseUrl", Prefs.baseUrl() == "https://example.com:443/emby")
    check("deviceId stable", Prefs.deviceId == Prefs.deviceId && Prefs.deviceId.length == 36)
    check("deviceName", Prefs.deviceName.isNotEmpty())
    val ap = YambyClient.authParams()
    check("authParams", ap.contains("X-Emby-Client=Yamby") && ap.contains("X-Emby-Device-Id="))
    val ah = YambyClient.authHeader()
    check("authHeader", ah.startsWith("MediaBrowser Client=Yamby,Device="))
    // ServerEntry 序列化往返
    val entry = ServerEntry("Test", "https", "example.com", "443", "emby", "user", "pw")
    Prefs.upsertServer(entry)
    val found = Prefs.getServers().firstOrNull { it.key() == entry.key() }
    check("server roundtrip", found != null && found.password == "pw")
    Prefs.removeServer(entry.key())
    check("server remove", Prefs.getServers().none { it.key() == entry.key() })
    // 恢复空值，避免污染后续测试环境
    Prefs.host = ""
    Prefs.token = ""
    Prefs.userId = ""

    println("== 3. DV Profile 检测 ==")
    val dvh1 = JSONObject("""{"MediaSources":[{"MediaStreams":[
        {"Type":"Video","Codec":"hevc","CodecTag":"dvh1"},
        {"Type":"Audio","Codec":"aac"}]}]}""")
    check("dvh1 tag → 5", DolbyVision.profileFromPlaybackInfo(dvh1) == 5)

    val dvhe05 = JSONObject("""{"MediaSources":[{"MediaStreams":[
        {"Type":"Video","Codec":"dvhe.05.06","CodecTag":"dvhe"},
        {"Type":"Audio","Codec":"aac"}]}]}""")
    check("dvhe.05.06 → 5", DolbyVision.profileFromPlaybackInfo(dvhe05) == 5)

    val dvProfileField = JSONObject("""{"MediaSources":[{"MediaStreams":[
        {"Type":"Video","Codec":"hevc","DvProfile":8}]}]}""")
    check("DvProfile=8 → 8", DolbyVision.profileFromPlaybackInfo(dvProfileField) == 8)

    val doviRange = JSONObject("""{"MediaSources":[{"MediaStreams":[
        {"Type":"Video","Codec":"hevc","VideoRangeType":"DOVI"}]}]}""")
    check("VideoRangeType=DOVI → 5", DolbyVision.profileFromPlaybackInfo(doviRange) == 5)

    val hdr10 = JSONObject("""{"MediaSources":[{"MediaStreams":[
        {"Type":"Video","Codec":"hevc","VideoRangeType":"HDR10"}]}]}""")
    check("HDR10 → null", DolbyVision.profileFromPlaybackInfo(hdr10) == null)

    val sdr = JSONObject("""{"MediaSources":[{"MediaStreams":[
        {"Type":"Video","Codec":"h264"}]}]}""")
    check("SDR h264 → null", DolbyVision.profileFromPlaybackInfo(sdr) == null)

    println("== 4. URL 构造（不连网） ==")
    Prefs.protocol = "http"
    Prefs.host = "192.168.1.10"
    Prefs.port = "8096"
    Prefs.path = "emby"
    val img = YambyClient.imageUrl("abc123", "Primary", 400)
    check(
        "imageUrl",
        img == "http://192.168.1.10:8096/emby/Items/abc123/Images/Primary?" +
            YambyClient.authParamsWithToken() + "&maxWidth=400"
    )
    Prefs.host = ""

    println("\nSMOKE TEST PASSED ($passed checks)")
}
