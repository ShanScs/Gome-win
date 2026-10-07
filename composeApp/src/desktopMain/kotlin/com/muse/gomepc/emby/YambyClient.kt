package com.muse.gomepc.emby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.net.Proxy
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Yamby 风格 Emby API 客户端。
 * 关键差异（相对官版实现）：
 * 1. 所有接口带 /emby 路径前缀（nginx 只转发 /emby/ 下的路径）
 * 2. 认证用 X-Emby-* query 参数（Client=Yamby），而非 api_key
 * 3. Authorization 头格式：MediaBrowser Client=Yamby,Device=...,DeviceId=...,Version=...
 */
object YambyClient {
    /** 当前播放会话 ID（PlaybackInfo 返回，上报进度时必须带） */
    @Volatile var playSessionId: String = ""
    /** 最近一次 PlaybackInfo 检测到的杜比视界 Profile（null=非DV），供播放器路由用 */
    @Volatile var lastDvProfile: Int? = null

    /** 播放地址获取失败的具体原因，用于精准报错 */
    sealed class PlaybackError(msg: String) : Exception(msg) {
        class InfoFailed(cause: String) : PlaybackError("播放信息获取失败($cause)")
        class NoDirectUrl : PlaybackError("服务器未返回直链")
    }

    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY) // 不走系统代理（与 Yamby 一致，直连）
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1)) // 只用 HTTP/1.1：部分服务器的反代会重置 HTTP/2 流
        .addNetworkInterceptor { chain ->
            // 部分服务器反代会掐长连接，导致 unexpected end of stream；统一用短连接
            val req = chain.request().newBuilder()
                .header("Connection", "close")
                .build()
            chain.proceed(req)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 干净客户端：不强制协议版本，不加 Connection: close（给 HTTPS 登录用）
    private val httpClean = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    const val CLIENT_NAME = "Yamby"
    const val CLIENT_VERSION = "2.1.0.10"

    private fun base() = Prefs.baseUrl()

    // 未登录时的公共认证参数
    fun authParams(): String {
        val d = Prefs.deviceId
        val n = URLEncoder.encode(Prefs.deviceName, "UTF-8")
        return "X-Emby-Client=$CLIENT_NAME&X-Emby-Device-Name=$n" +
            "&X-Emby-Device-Id=$d&X-Emby-Client-Version=$CLIENT_VERSION"
    }

    // 登录后的完整认证参数（含 Token）。与 YP 1.0 一致：URL 里用 X-Emby-Token。
    fun authParamsWithToken(): String {
        val t = Prefs.token
        return if (t.isEmpty()) authParams()
        else "${authParams()}&X-Emby-Token=$t"
    }

    fun authHeader(): String {
        // 与 YP 1.0 一字不差：Token 不带引号，不带 UserId
        val n = Prefs.deviceName
        val d = Prefs.deviceId
        var h = "MediaBrowser Client=Yamby,Device=$n,DeviceId=$d,Version=2.1.0.10"
        val t = Prefs.token
        if (t.isNotEmpty()) h += ",Token=$t"
        return h
    }

    private fun url(path: String, extra: String = ""): String {
        val ap = authParamsWithToken()
        val sep = if (extra.isEmpty()) "" else "&"
        return "${base()}$path?$ap$sep$extra"
    }

    private suspend fun getJson(u: String, what: String): JSONObject = withContext(Dispatchers.IO) {
        // 用 HttpURLConnection（对 su.vicclub.top 这类服务器，OkHttp 会 unexpected end of stream）
        val url = java.net.URL(u)
        val c = (url.openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", authHeader())
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                // 脱敏：去掉 api_key 参数再显示
                val safeUrl = u.replace(Regex("api_key=[^&]*"), "api_key=***")
                throw Exception("$what 失败（HTTP $code）\n$safeUrl")
            }
            JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }

    private suspend fun postJsonWithHeader(u: String, body: JSONObject, what: String, headerName: String, headerValue: String): JSONObject =
        withContext(Dispatchers.IO) {
            // 登录用 HttpURLConnection（不用 OkHttp），避开 unexpected end of stream
            val url = java.net.URL(u)
            val c = (url.openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty(headerName, headerValue)
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val txt = stream?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) {
                    val msg = when (code) {
                        401 -> "用户名或密码错误"
                        404 -> "服务器地址不对，检查地址/端口/路径"
                        else -> "$what 失败（HTTP $code）${if (txt.isNotEmpty()) ": ${txt.take(200)}" else ""}"
                    }
                    throw Exception(msg)
                }
                JSONObject(txt)
            } finally {
                c.disconnect()
            }
        }

    private suspend fun postJson(u: String, body: JSONObject, what: String): JSONObject =
        withContext(Dispatchers.IO) {
            // 用 HttpURLConnection（对 su.vicclub.top 这类服务器，OkHttp 会 unexpected end of stream）
            val url = java.net.URL(u)
            val c = (url.openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Authorization", authHeader())
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val txt = stream?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) {
                    val msg = when (code) {
                        401 -> "用户名或密码错误"
                        404 -> "服务器地址不对，检查地址/端口/路径"
                        else -> "$what 失败（HTTP $code）"
                    }
                    throw Exception(msg)
                }
                JSONObject(txt)
            } finally {
                c.disconnect()
            }
        }

    // ---------- 登录 ----------
    data class AuthResult(val userId: String, val token: String, val username: String)

    suspend fun login(username: String, password: String): AuthResult {
        // 登录不用查询参数，只用头和 body（部分服务器对查询参数敏感报 400）
        val u = "${base()}/Users/AuthenticateByName"
        val body = JSONObject().put("Username", username).put("Pw", password)
        // 登录用标准头（带引号），部分服务器对 Authorization 头格式敏感
        val json = postJsonWithHeader(u, body, "登录",
            "X-Emby-Authorization",
            "MediaBrowser Client=\"Yamby\", Device=\"${Prefs.deviceName}\", DeviceId=\"${Prefs.deviceId}\", Version=\"2.1.0.10\"")
        val token = json.optString("AccessToken", "")
        val user = json.optJSONObject("User")
        val userId = user?.optString("Id", "") ?: ""
        if (token.isEmpty() || userId.isEmpty()) throw Exception("登录返回异常")
        Prefs.token = token
        Prefs.userId = userId
        Prefs.username = username
        return AuthResult(userId, token, username)
    }

    // ---------- 媒体库 ----------
    data class Library(val id: String, val name: String, val type: String)
    data class Item(
        val id: String, val name: String, val type: String,
        val year: Int = 0, val overview: String = "",
        val seriesId: String = "", val seriesName: String = "",
        val seasonIdx: Int = 0, val episodeIdx: Int = 0,
        val runTicks: Long = 0L, val playTicks: Long = 0L,
        val genres: List<String> = emptyList(),
        val childCount: Int = 0,
        val recursiveCount: Int = 0,
        val communityRating: Float = 0f,
        val status: String = "",
        val people: List<Person> = emptyList(),
        val premiereDate: String = "",
        val width: Int = 0, val height: Int = 0,
        val sizeBytes: Long = 0L,
        val backdropCount: Int = 0,
        val played: Boolean = false,
        val isFavorite: Boolean = false,
        val unplayedCount: Int = 0,
    ) {
        /** 剩余可播时长（ticks） */
        val remainingTicks: Long get() = (runTicks - playTicks).coerceAtLeast(0L)
        /** 第X集 集名 */
        fun epLabel(): String =
            if (type == "Episode" && episodeIdx > 0) {
                val epLabel = "第${episodeIdx}集"
                if (name.isNotEmpty() && name != epLabel) "$epLabel $name" else epLabel
            } else name
        /** 剧集面板标题：第4集 集名 */
        fun epPanelTitle(): String =
            if (type == "Episode" && episodeIdx > 0) {
                val epLabel = "第${episodeIdx}集"
                if (name.isNotEmpty() && name != epLabel) "$epLabel $name" else epLabel
            } else name
        /** 集数：Series 用 (RecursiveItemCount - ChildCount) 即总集数；Movie 返回 0 */
        val episodeCount: Int get() =
            if (type == "Series" && recursiveCount > childCount) recursiveCount - childCount else 0
        /** 徽章显示数：未看集数（随观看减少），无未看数据时用总集数 */
        val displayCount: Int get() =
            if (type == "Series" && unplayedCount > 0) unplayedCount else episodeCount
        /** 剧集面板元信息：1920P · 46:10 · 1.34G */
        fun epPanelMeta(): String {
            val parts = mutableListOf<String>()
            if (height > 0) parts.add("${height}P")
            else if (width > 0) parts.add("${width}P")
            if (runTicks > 0) parts.add(durShort(runTicks))
            if (sizeBytes > 0) parts.add(sizeLabel(sizeBytes))
            return parts.joinToString(" · ")
        }
        /** 2026 - 现在 / 2026 */
        fun yearLabel(): String {
            if (year <= 0) return ""
            return if (status.equals("Continuing", true)) "$year - 现在" else year.toString()
        }
    }

    data class Person(val id: String, val name: String, val role: String)

    suspend fun getLibraries(): List<Library> {
        // 用户视图是"媒体库"的唯一真实来源（Emby 网页显示的媒体库列表）
        // MediaFolders 是服务器物理文件夹，不是用户看到的媒体库，仅在 Views 为空时兜底
        var viewsCount = 0
        var foldersCount = 0
        try {
            val views = parseLibraries(getJson(url("/Users/${Prefs.userId}/Views"), "获取媒体库"))
            viewsCount = views.size
            for (v in views) Log.d("YambyLib", "Views: ${v.name} (${v.id}) type=${v.type}")
            if (views.isNotEmpty()) {
                Log.d("YambyLib", "使用 Views（${views.size} 个），忽略 MediaFolders 物理文件夹")
                return views
            }
            Log.w("YambyLib", "Views 返回空，尝试 MediaFolders 兜底")
        } catch (e: Exception) {
            Log.d("YambyLib", "Views 失败: ${e.message}")
        }
        try {
            val folders = parseLibraries(getJson(url("/Library/MediaFolders"), "获取媒体文件夹"))
            foldersCount = folders.size
            for (f in folders) Log.d("YambyLib", "MediaFolders(兜底): ${f.name} (${f.id}) type=${f.type}")
            return folders
        } catch (e: Exception) {
            Log.d("YambyLib", "MediaFolders 失败: ${e.message}")
        }
        Log.d("YambyLib", "Views=$viewsCount MediaFolders=$foldersCount，均失败返回空")
        return emptyList()
    }

    private fun parseLibraries(json: JSONObject): List<Library> {
        val out = mutableListOf<Library>()
        val arr = json.optJSONArray("Items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("Id", "")
            if (id.isEmpty()) continue  // 单条缺字段只跳过这一条，不整体失败
            out.add(Library(id, o.optString("Name", "未命名"), o.optString("CollectionType", "")))
        }
        return out
    }

    suspend fun getItems(
        parentId: String,
        limit: Int = 60,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        minimal: Boolean = false
    ): List<Item> {
        val fields = if (minimal) "" else
            "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate,DateCreated,DateLastContentAdded,CriticRating,OfficialRating"
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "ParentId=$parentId&Recursive=true&IncludeItemTypes=Movie,Series" +
                fields +
                "&SortBy=$sortBy&SortOrder=$sortOrder&Limit=$limit"
        )
        return parseItems(getJson(u, "获取列表"))
    }

    suspend fun getItemsTotal(parentId: String): Int {
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "ParentId=$parentId&Recursive=true&IncludeItemTypes=Movie,Series&Limit=1"
        )
        return getJson(u, "获取总数").optInt("TotalRecordCount", 0)
    }

    /** 资源库卡片统计：某类型的总数（Movie / Series） */
    suspend fun getTotalCount(types: String): Int {
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "Recursive=true&IncludeItemTypes=$types&Limit=1"
        )
        return getJson(u, "获取总数").optInt("TotalRecordCount", 0)
    }

    suspend fun getLatestItems(limit: Int = 8): List<Item> {
        val ts = System.currentTimeMillis()
        val u = url(
            "/Users/${Prefs.userId}/Items/Latest",
            "IncludeItemTypes=Movie,Series&Fields=Overview,ProductionYear,Genres,Status&Limit=$limit&_t=$ts"
        )
        return parseItems(getJson(u, "获取最新"))
    }

    suspend fun getServerName(): String {
        val u = url("/System/Info")
        return getJson(u, "获取服务器信息").optString("ServerName", "")
    }

    suspend fun getResumeItems(limit: Int = 20): List<Item> {
        // 简化参数提高兼容性：只取必要的 RunTimeTicks 和 SeriesName
        // 加时间戳防缓存，确保拿到最新数据
        val ts = System.currentTimeMillis()
        val u = url(
            "/Users/${Prefs.userId}/Items/Resume",
            "Recursive=true&IncludeItemTypes=Movie,Episode&Fields=RunTimeTicks,SeriesName&Limit=$limit&_t=$ts"
        )
        val items = try {
            parseItems(getJson(u, "获取继续观看"))
        } catch (e: Exception) {
            Log.w("YambyResume", "Resume接口失败，尝试Filters方式: ${e.message}")
            emptyList()
        }
        if (items.isNotEmpty()) return items
        // 兜底：用 Filters=IsResumable 查询，兼容不支持 /Resume 的服务器
        return try {
            val u2 = url(
                "/Users/${Prefs.userId}/Items",
                "Recursive=true&IncludeItemTypes=Movie,Episode&Filters=IsResumable&Fields=RunTimeTicks,SeriesName&Limit=$limit&SortBy=DatePlayed&SortOrder=Descending&_t=$ts"
            )
            parseItems(getJson(u2, "获取继续观看(兜底)"))
        } catch (e: Exception) {
            Log.w("YambyResume", "Filters兜底也失败: ${e.message}")
            emptyList()
        }
    }

    /** 搜索：按关键词搜电影/剧集 */
    suspend fun searchItems(query: String, limit: Int = 60): List<Item> {
        val q = URLEncoder.encode(query, "UTF-8")
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "SearchTerm=$q&Recursive=true&IncludeItemTypes=Movie,Series,Person" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate" +
                "&SortBy=SortName&SortOrder=Ascending&Limit=$limit"
        )
        return parseItems(getJson(u, "搜索"))
    }

    /** 聚合搜索：在所有已配置服务器上搜索并合并结果 */
    suspend fun searchAggregated(query: String): List<Item> {
        // 当前只搜索当前服务器；多服务器需要各服务器的独立 token
        // TODO: 实现真正的多服务器聚合（需要存储每个服务器的 token）
        return searchItems(query)
    }

    /** 收藏列表 */
    suspend fun getFavoriteItems(limit: Int = 200): List<Item> {
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "Filters=IsFavorite&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate" +
                "&SortBy=DateCreated&SortOrder=Descending&Limit=$limit"
        )
        return parseItems(getJson(u, "获取收藏"))
    }

    /** 演员作品：按 PersonIds 查电影/剧集 */
    suspend fun getPersonWorks(personId: String, limit: Int = 100): List<Item> {
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "PersonIds=$personId&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate" +
                "&SortBy=SortName&SortOrder=Ascending&Limit=$limit"
        )
        return parseItems(getJson(u, "获取演员作品"))
    }

    /** 演员详情：/Users/{uid}/Items/{personId} 带 Overview 简介 */
    suspend fun getPersonBio(personId: String): String {
        return try {
            val u = url("/Users/${Prefs.userId}/Items/$personId", "Fields=Overview")
            getJson(u, "获取演员简介").optString("Overview", "")
        } catch (e: Exception) { "" }
    }

    suspend fun getSeasons(seriesId: String): List<Item> {
        val u = url("/Shows/$seriesId/Seasons", "UserId=${Prefs.userId}&Fields=Overview,ProductionYear,ChildCount")
        return parseItems(getJson(u, "获取季"))
    }

    suspend fun getEpisodes(seriesId: String, seasonId: String): List<Item> {
        val u = url(
            "/Shows/$seriesId/Episodes",
            "SeasonId=$seasonId&UserId=${Prefs.userId}&Fields=Overview,RunTimeTicks,SeriesName,MediaSources"
        )
        return parseItems(getJson(u, "获取剧集"))
    }

    suspend fun getItem(id: String): Item {
        val u = url("/Users/${Prefs.userId}/Items/$id", "Fields=Overview,ProductionYear,RunTimeTicks,People,Genres,ChildCount,CommunityRating,SeriesName,Status")
        val o = getJson(u, "获取详情")
        return parseItem(o)
    }

    private fun parseItems(json: JSONObject): List<Item> {
        val out = mutableListOf<Item>()
        val arr = json.optJSONArray("Items") ?: JSONArray()
        for (i in 0 until arr.length()) out.add(parseItem(arr.getJSONObject(i)))
        return out
    }

    private fun parseItem(o: JSONObject): Item {
        val ud = o.optJSONObject("UserData")
        val genres = mutableListOf<String>()
        val ga = o.optJSONArray("Genres")
        if (ga != null) for (i in 0 until ga.length()) genres.add(ga.optString(i))
        val people = mutableListOf<Person>()
        val pa = o.optJSONArray("People")
        if (pa != null) for (i in 0 until pa.length()) {
            val p = pa.optJSONObject(i) ?: continue
            people.add(Person(p.optString("Id", ""), p.optString("Name", ""), p.optString("Role", "")))
        }
        // 媒体信息：分辨率 + 文件大小（剧集面板用）
        var vw = 0
        var vh = 0
        var sz = 0L
        try {
            val ms = o.optJSONArray("MediaSources")
            if (ms != null && ms.length() > 0) {
                val src = ms.getJSONObject(0)
                sz = src.optLong("Size", 0L)
                val streams = src.optJSONArray("MediaStreams")
                if (streams != null) for (i in 0 until streams.length()) {
                    val st = streams.getJSONObject(i)
                    if (st.optString("Type") == "Video") {
                        vw = st.optInt("Width", 0)
                        vh = st.optInt("Height", 0)
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
        return Item(
            id = o.optString("Id"),
            name = o.optString("Name"),
            type = o.optString("Type"),
            year = o.optInt("ProductionYear", 0),
            overview = o.optString("Overview", ""),
            seriesId = o.optString("SeriesId", ""),
            seriesName = o.optString("SeriesName", ""),
            seasonIdx = o.optInt("ParentIndexNumber", 0),
            episodeIdx = o.optInt("IndexNumber", 0),
            runTicks = o.optLong("RunTimeTicks", 0L),
            playTicks = ud?.optLong("PlaybackPositionTicks", 0L) ?: 0L,
            genres = genres,
            childCount = o.optInt("ChildCount", 0),
            recursiveCount = o.optInt("RecursiveItemCount", 0),
            communityRating = o.optDouble("CommunityRating", 0.0).toFloat(),
            status = o.optString("Status", ""),
            people = people,
            premiereDate = o.optString("PremiereDate", ""),
            width = vw,
            height = vh,
            sizeBytes = sz,
            backdropCount = o.optJSONArray("BackdropImageTags")?.length() ?: 0,
            played = ud?.optBoolean("Played", false) ?: false,
            isFavorite = ud?.optBoolean("IsFavorite", false) ?: false,
            unplayedCount = ud?.optInt("UnplayedItemCount", 0) ?: 0,
        )
    }

    // ---------- 收藏 / 已看 ----------
    suspend fun setFavorite(id: String, fav: Boolean) {
        try {
            val u = url("/Users/${Prefs.userId}/FavoriteItems/$id")
            withContext(Dispatchers.IO) {
                val c = (java.net.URL(u).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                    requestMethod = if (fav) "POST" else "DELETE"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = fav
                    setRequestProperty("Authorization", authHeader())
                }
                try {
                    if (fav) c.outputStream.use { }
                    c.responseCode
                    try { c.inputStream?.close() } catch (_: Exception) {}
                    try { c.errorStream?.close() } catch (_: Exception) {}
                } finally { c.disconnect() }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun setPlayed(id: String, played: Boolean) {
        try {
            val u = url("/Users/${Prefs.userId}/PlayedItems/$id")
            withContext(Dispatchers.IO) {
                val c = (java.net.URL(u).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                    requestMethod = if (played) "POST" else "DELETE"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = played
                    setRequestProperty("Authorization", authHeader())
                }
                try {
                    if (played) c.outputStream.use { }
                    c.responseCode
                    try { c.inputStream?.close() } catch (_: Exception) {}
                    try { c.errorStream?.close() } catch (_: Exception) {}
                } finally { c.disconnect() }
            }
        } catch (_: Exception) {
        }
    }

    fun imageUrl(itemId: String, type: String = "Primary", w: Int = 400): String =
        "${base()}/Items/$itemId/Images/$type?${authParamsWithToken()}&maxWidth=$w"

    /** ticks → "00:35:15"（续播位置，用于播放按钮胶囊进度条文字） */
    fun posLabel(ticks: Long): String {
        val s = (ticks / 10_000_000).toInt().coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** ticks → "剩余：22分47秒" */
    fun remainingLabel(ticks: Long): String {
        val s = ticks / 10_000_000
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "剩余：${m}分${sec}秒" else "剩余：${sec}秒"
    }

    /** ticks → "49分23秒"（无"剩余："前缀，用于剧集时长） */
    fun durationLabel(ticks: Long): String {
        val s = ticks / 10_000_000
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "${m}分${sec}秒" else "${sec}秒"
    }

    /** 短时长：46:10 */
    fun durShort(ticks: Long): String {
        val s = (ticks / 10_000_000).toInt().coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** 文件大小：1.34G / 856M */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1024L * 1024 * 1024 -> "%.2fG".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.0fM".format(bytes / 1024.0 / 1024)
        else -> "${bytes / 1024}K"
    }

    // ---------- 播放地址（YP 1.0 原始逻辑） ----------
    /** 单一直链：取第一个候选（1.0 逻辑保证至少有兜底地址） */
    suspend fun getPlaybackUrl(itemId: String): String {
        return getPlaybackUrls(itemId).first()
    }

    /** 播放地址列表（兼容双服务器）：
     * 1. 发两次 PlaybackInfo：先带 DeviceProfile（老服务器垃圾影音需要它才给能播的直链），
     *    再不带（新服务器 hxd.as174.de 按 YP 1.0 方式）。两次的 STRM/DirectStreamUrl 都收集。
     * 2. 永远追加兜底：/Videos/{id}/stream?Static=true&认证参数（新服务器靠它播）。
     * 播放器按顺序试，一个播不了换下一个。 */
    /** 取原始 PlaybackInfo JSON（含 MediaSources 与 MediaStreams），供版本/音轨/字幕选择器用 */
    suspend fun getPlaybackInfoJson(itemId: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("UserId", Prefs.userId)
            .put("MaxStreamingBitrate", 140_000_000)
            .put("StartTimeTicks", 0)
        val u = url("/Items/$itemId/PlaybackInfo", "UserId=${Prefs.userId}")
        // 取 PlaySessionId 供后续上报用
        val json = postJson(u, body, "获取播放信息(详情页)")
        json.optString("PlaySessionId", "").takeIf { it.isNotEmpty() }?.let { playSessionId = it }
        json
    }

    /** 按指定的 MediaSourceId 取播放地址（详情页版本选择用） */
    suspend fun getPlaybackUrlsForSource(itemId: String, mediaSourceId: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        try {
            val json = getPlaybackInfoJson(itemId)
            val sources = json.optJSONArray("MediaSources") ?: throw PlaybackError.NoDirectUrl()
            for (i in 0 until sources.length()) {
                val src = sources.getJSONObject(i)
                if (src.optString("Id") != mediaSourceId) continue
                val direct = src.optString("DirectStreamUrl", "")
                if (direct.isNotEmpty()) {
                    val s = resolveUrl(direct)
                    if (s.isNotEmpty()) out.add(s)
                }
                val protocol = src.optString("Protocol", "")
                val path = src.optString("Path", "")
                if (protocol.equals("Http", true) && path.startsWith("http", true)) {
                    val s = strmSourceUrl(path)
                    if (s.isNotEmpty()) out.add(s)
                }
                break
            }
        } catch (e: PlaybackError) {
            throw e
        } catch (e: Exception) {
            throw PlaybackError.InfoFailed(e.message ?: "unknown")
        }
        if (out.isEmpty()) throw PlaybackError.NoDirectUrl()
        // 兜底：有序 URL 候选（与 getPlaybackUrls 一致）
        val fallback = url("/Videos/$itemId/stream", "Static=true")
        out.add(fallback)
        // 如果是 http，追加 https 版本（CF 站点 http 可能不通）
        if (fallback.startsWith("http://")) {
            out.add(fallback.replaceFirst("http://", "https://"))
        }
        return@withContext out.distinct()
    }

    suspend fun getPlaybackUrls(itemId: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        // 两轮并行：带 DeviceProfile（老服务器）+ 不带（新服务器/YP 1.0），省一半时间
        val bodies = listOf(
            "withProfile" to JSONObject()
                .put("UserId", Prefs.userId)
                .put("MaxStreamingBitrate", 140_000_000)
                .put("StartTimeTicks", 0)
                .put("DeviceProfile", JSONObject()
                    .put("Name", "Android")
                    .put("MaxStreamingBitrate", 140_000_000)
                    .put("DirectPlayProfiles", JSONArray().apply {
                        put(JSONObject().put("Container", "mkv").put("Type", "Video"))
                        put(JSONObject().put("Container", "mp4").put("Type", "Video"))
                    })
                    .put("TranscodingProfiles", JSONArray())
                    .put("ContainerProfiles", JSONArray())
                    .put("CodecProfiles", JSONArray())
                    .put("SubtitleProfiles", JSONArray().apply {
                        put(JSONObject().put("Format", "srt").put("Method", "External"))
                        put(JSONObject().put("Format", "ass").put("Method", "External"))
                    })),
            "noProfile" to JSONObject()
                .put("UserId", Prefs.userId)
                .put("MaxStreamingBitrate", 140_000_000)
                .put("StartTimeTicks", 0)
        )
        // 并行发两轮，结果按 withProfile 优先的顺序合并（老服务器优先）
        val deferred = bodies.map { (tag, body) ->
            async {
                val found = mutableListOf<String>()
                try {
                    val u = url("/Items/$itemId/PlaybackInfo", "UserId=${Prefs.userId}")
                    val json = postJson(u, body, "获取播放信息($tag)")
                    // 取出 PlaySessionId，后续上报进度用
                    json.optString("PlaySessionId", "").takeIf { it.isNotEmpty() }?.let { playSessionId = it }
                    // 杜比视界检测（1.22.53）：从已有的 PlaybackInfo 直接提取，不额外请求
                    try {
                        val dv = DolbyVision.profileFromPlaybackInfo(json)
                        if (dv != null) lastDvProfile = dv
                    } catch (_: Exception) {}
                    val sources = json.optJSONArray("MediaSources")
                    if (sources != null && sources.length() > 0) {
                        // 遍历所有 MediaSource（不只取第一个，有的集第一个源没有直链）
                        for (si in 0 until sources.length()) {
                            val src = sources.getJSONObject(si)
                            // DirectStreamUrl 优先（服务器给的直链最快最稳）
                            val direct = src.optString("DirectStreamUrl", "")
                            if (direct.isNotEmpty()) {
                                val s = resolveUrl(direct)
                                if (s.isNotEmpty() && !found.contains(s)) found.add(s)
                            }
                            // STRM 源地址其次
                            val protocol = src.optString("Protocol", "")
                            val path = src.optString("Path", "")
                            if (protocol.equals("Http", true) && path.startsWith("http", true)) {
                                val s = strmSourceUrl(path)
                                if (s.isNotEmpty() && !found.contains(s)) found.add(s)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("YambyPlay", "PlaybackInfo($tag) 失败: ${e.message}")
                }
                tag to found
            }
        }
        // 排序：DirectStreamUrl 优先于 STRM（直链通常比 STRM 源地址更快更稳）
        for ((tag, found) in deferred.awaitAll()) {
            for (s in found) {
                if (!out.contains(s)) {
                    Log.d("YambyPlay", "候选[$tag]: ${s.take(100)}")
                    out.add(s)
                }
            }
        }
        // 兜底（YP 1.0）：新服务器靠它播；老服务器 nginx 会 404，播放器会自动跳过
        val std = "${base()}/Videos/$itemId/stream?Static=true&${authParamsWithToken()}"
        if (!out.contains(std)) out.add(std)
        // http 追加 https 版本
        if (std.startsWith("http://")) {
            val httpsStd = std.replaceFirst("http://", "https://")
            if (!out.contains(httpsStd)) out.add(httpsStd)
        }
        Log.d("YambyPlay", "共 ${out.size} 个候选地址")
        out
    }

    /** 诊断视频地址：用 HTTP Range 请求看服务器到底返回什么（状态码/Content-Type/前几个字节）。
     *  返回如 "HTTP 404 text/html" 或 "HTTP 206 video/x-matroska"；不泄露 token。 */
    suspend fun diagnoseUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(rawUrl)
                .header("Authorization", authHeader())
                .header("Range", "bytes=0-15")
                .header("User-Agent", "Yamby/1.0")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val ct = resp.header("Content-Type", "?") ?: "?"
                val len = resp.header("Content-Length", "?") ?: "?"
                val body = try {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) "(空)"
                    else if (ct.contains("html", true) || looksLikeHtml(bytes)) {
                        val txt = String(bytes, Charsets.UTF_8).take(80).replace("\n", " ")
                        "HTML:$txt"
                    } else {
                        "二进制前4字节:${bytes.take(4).joinToString(" ") { "%02X".format(it) }}"
                    }
                } catch (_: Exception) { "(读失败)" }
                "HTTP ${resp.code} $ct 长$len $body"
            }
        } catch (e: Exception) {
            "请求异常:${e.message?.take(60) ?: "?"}"
        }
    }

    private fun looksLikeHtml(b: ByteArray): Boolean {
        if (b.size < 5) return false
        val head = String(b.take(32).toByteArray(), Charsets.UTF_8).trimStart()
        return head.startsWith("<", true)
    }

    private fun resolveUrl(u: String): String {
        if (u.isEmpty()) return ""
        var t = if (u.startsWith("http", true)) u else base() + u
        if (!t.contains("X-Emby-Token") && !t.contains("api_key")) {
            t += (if (t.contains("?")) "&" else "?") + authParamsWithToken()
        }
        return t
    }

    private fun strmSourceUrl(path: String): String {
        if (path.isEmpty() || !path.startsWith("http", true)) return ""
        val b = base()
        val serverHost = b.substringAfter("://").substringBefore(":").substringBefore("/")
        if (serverHost.isEmpty()) return ""
        return path
            .replace("://127.0.0.1:", "://$serverHost:")
            .replace("://127.0.0.1/", "://$serverHost/")
            .replace("://localhost:", "://$serverHost:")
            .replace("://localhost/", "://$serverHost/")
    }

    // ---------- 播放进度上报 ----------
    /** POST 不关心响应体（204 No Content），空响应不抛异常 */
    private suspend fun postNoContent(u: String, body: JSONObject, what: String) =
        withContext(Dispatchers.IO) {
            // 用 HttpURLConnection（对 su.vicclub.top 这类服务器，OkHttp 会 unexpected end of stream）
            val url = java.net.URL(u)
            val c = (url.openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Authorization", authHeader())
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                // 204 无内容也算成功；读掉 errorStream 避免连接复用问题
                try { c.errorStream?.close() } catch (_: Exception) {}
                try { c.inputStream?.close() } catch (_: Exception) {}
                if (code !in 200..299) throw Exception("$what 失败（HTTP $code）")
            } finally {
                c.disconnect()
            }
        }

    suspend fun reportProgress(itemId: String, ticks: Long, paused: Boolean = false): String? {
        if (itemId.isEmpty()) return "itemId为空"
        return try {
            val u = url("/Sessions/Playing/Progress")
            val body = JSONObject()
                .put("ItemId", itemId)
                .put("PositionTicks", ticks)
                .put("IsPaused", paused)
                .put("PlaySessionId", playSessionId)
            postNoContent(u, body, "进度上报")
            null
        } catch (e: Exception) {
            e.message
        }
    }

    suspend fun reportStopped(itemId: String, ticks: Long) {
        if (itemId.isEmpty()) return
        try {
            val u = url("/Sessions/Playing/Stopped")
            val body = JSONObject()
                .put("ItemId", itemId)
                .put("PositionTicks", ticks)
                .put("PlaySessionId", playSessionId)
            postNoContent(u, body, "停止上报")
        } catch (_: Exception) {
        }
    }

    /** 开始播放时先创建会话，否则服务器可能丢弃后续的 Progress 上报。返回 null 表示成功，否则返回错误信息 */
    suspend fun reportPlaying(itemId: String, mediaSourceId: String = ""): String? {
        if (itemId.isEmpty()) return "itemId为空"
        return try {
            val u = url("/Sessions/Playing")
            val body = JSONObject()
                .put("ItemId", itemId)
                .put("PlaySessionId", playSessionId)
            if (mediaSourceId.isNotEmpty()) body.put("MediaSourceId", mediaSourceId)
            postNoContent(u, body, "开始播放上报")
            null
        } catch (e: Exception) {
            e.message
        }
    }
}
