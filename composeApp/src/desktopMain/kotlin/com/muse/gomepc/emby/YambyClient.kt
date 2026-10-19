package com.muse.gomepc.emby

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import okhttp3.OkHttpClient
import java.net.Proxy
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Yamby 风格 Emby API 客户端。
 * 关键差异（相对官版实现）：
 * 1. 所有接口带 /emby 路径前缀（nginx 只转发 /emby/ 下的路径）
 * 2. 认证用 X-Emby-* query 参数（Client=Yamby），而非 api_key
 * 3. Authorization 头格式：MediaBrowser Client=Yamby,Device=...,DeviceId=...,Version=...
 *
 * 会话说明（P0-E6）：不再用全局可变 playSessionId/lastDvProfile。
 * PlaySessionId / DV Profile 一律按 itemId (+mediaSourceId) 存在内部 ConcurrentHashMap 里，
 * 由 getPlaybackInfoJson/getPlaybackUrls 写入，report* 上报时按 itemId 取用；
 * 调用方也可通过 getCachedPlaySessionId/getCachedDvProfile 读取。
 */
object YambyClient {
    /** 播放地址获取失败的具体原因，用于精准报错 */
    sealed class PlaybackError(msg: String) : Exception(msg) {
        class InfoFailed(cause: String) : PlaybackError("播放信息获取失败($cause)")
        class NoDirectUrl : PlaybackError("服务器未返回直链")
    }

    /**
     * 未授权异常（P0-E13）。
     * 仅登录接口的 401 译为“用户名或密码错误”（isLogin=true）；
     * 其余接口的 401 一律抛本异常（isLogin=false），表示 Token 过期/无效，调用方应走重新登录，
     * 不再全当“密码错误”。
     */
    class UnauthorizedException(message: String, val isLogin: Boolean = false) : Exception(message)

    // ---- 会话 / DV 按 itemId (+mediaSourceId) 的缓存（P0-E6，替代全局可变变量） ----
    private val playSessionIds = ConcurrentHashMap<String, String>()
    private val dvProfiles = ConcurrentHashMap<String, Int>()
    private val lastProgressAt = ConcurrentHashMap<String, Long>()
    private val lastProgressPaused = ConcurrentHashMap<String, Boolean>()

    /**
     * 401 全局回调（P0-12）：任一接口收到 401（Token 过期）时触发，调用方（Main.GomeApp）
     * 在此把用户踢回登录页。之前 UnauthorizedException 只在 YambyClient 内抛、UI 层零处理，
     * 表现为继续观看为空、收藏点不动等假象。
     */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    private fun notifyUnauthorized() {
        try { onUnauthorized?.invoke() } catch (_: Exception) { }
    }

    /** 停止上报待重试队列（P0-14）：reportStopped 失败不再丢进度，下次任一上报时先重发 */
    private data class PendingStopped(val itemId: String, val ticks: Long, val mediaSourceId: String, val playSessionId: String)
    private val pendingStopped = ConcurrentLinkedQueue<PendingStopped>()
    private const val MAX_PENDING_STOPPED = 50

    private fun sessionKey(itemId: String, mediaSourceId: String) = "$itemId|$mediaSourceId"

    private fun rememberPlaySessionId(itemId: String, mediaSourceId: String, sessionId: String) {
        if (sessionId.isEmpty()) return
        // 会话 Map 上限（P2）：超 200 条时清掉旧会话，避免无界增长；会话本身有时效，清掉只会多拉一次 PlaybackInfo
        if (playSessionIds.size > 200) playSessionIds.clear()
        playSessionIds[sessionKey(itemId, "")] = sessionId
        if (mediaSourceId.isNotEmpty()) playSessionIds[sessionKey(itemId, mediaSourceId)] = sessionId
    }

    /** 取已缓存的 PlaySessionId（无则 ""，调用方上报时可用它；为空时 reportPlaying 会现取一次 PlaybackInfo） */
    fun getCachedPlaySessionId(itemId: String, mediaSourceId: String = ""): String =
        playSessionIds[sessionKey(itemId, mediaSourceId)]
            ?: playSessionIds[sessionKey(itemId, "")] ?: ""

    /** 取已缓存的 DV Profile（null = 该条目未检出 DV；注意：未拉取过 PlaybackInfo 时也是 null） */
    fun getCachedDvProfile(itemId: String, mediaSourceId: String = ""): Int? =
        dvProfiles[sessionKey(itemId, mediaSourceId)]
            ?: dvProfiles[sessionKey(itemId, "")]

    /** 清空会话/DV/上报节流/媒体库缓存（登出、切换服务器时调用，见 Prefs.clearLogin） */
    fun clearSessionCache() {
        playSessionIds.clear()
        dvProfiles.clear()
        lastProgressAt.clear()
        lastProgressPaused.clear()
        librariesCache = null
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

    const val CLIENT_NAME = "Yamby"
    const val CLIENT_VERSION = "2.1.0.10"

    /** 网络重试：超时/IO 异常/5xx 最多重试 2 次（共 3 次尝试，P0-E2） */
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 300L
    /** getLibraries 成功结果缓存时长（P2-E2）：短时间缓存，键含 serverKey，切换服务器自动失效 */
    private const val LIBRARIES_CACHE_TTL_MS = 60_000L
    /** 进度上报节流窗口（P1-E20）：同一条目+源在窗口内且暂停状态未变时跳过重复上报 */
    private const val PROGRESS_THROTTLE_MS = 5_000L

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

    /** Authorization 头：值一律带引号，设备名/Token 里的反斜杠与引号做转义（P1-E12） */
    fun authHeader(includeToken: Boolean = true): String {
        val n = headerEscape(Prefs.deviceName)
        val d = headerEscape(Prefs.deviceId)
        var h = "MediaBrowser Client=\"Yamby\", Device=\"$n\", DeviceId=\"$d\", Version=\"2.1.0.10\""
        val t = Prefs.token
        if (includeToken && t.isNotEmpty()) h += ", Token=\"${headerEscape(t)}\""
        return h
    }

    private fun headerEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 往 HttpUrl.Builder 里追加 X-Emby-* 认证参数（值由 Builder 负责编码，P1-E11） */
    private fun addAuthParams(b: HttpUrl.Builder, withToken: Boolean) {
        b.addQueryParameter("X-Emby-Client", CLIENT_NAME)
        b.addQueryParameter("X-Emby-Device-Name", Prefs.deviceName)
        b.addQueryParameter("X-Emby-Device-Id", Prefs.deviceId)
        b.addQueryParameter("X-Emby-Client-Version", CLIENT_VERSION)
        if (withToken) {
            val t = Prefs.token
            if (t.isNotEmpty()) b.addQueryParameter("X-Emby-Token", t)
        }
    }

    /** URL 拼装统一走 HttpUrl.Builder（P1-E11）。extra 里的值按已编码处理（如 SearchTerm），其余为普通安全字符。 */
    private fun url(path: String, extra: String = ""): String {
        val rawBase = base()
        val parsed = (rawBase + path).toHttpUrlOrNull()
        if (parsed == null) {
            // base 非法时回退旧拼接，由连接层报错（不静默吞掉）
            val ap = authParamsWithToken()
            val sep = if (extra.isEmpty()) "" else "&"
            return "$rawBase$path?$ap$sep$extra"
        }
        val b = parsed.newBuilder()
        addAuthParams(b, withToken = true)
        if (extra.isNotEmpty()) {
            for (pair in extra.split("&")) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) b.addEncodedQueryParameter(pair, "")
                else b.addEncodedQueryParameter(pair.substring(0, eq), pair.substring(eq + 1))
            }
        }
        return b.build().toString()
    }

    /**
     * 统一 JSON 请求（P0-E2）：
     * - 显式 UTF-8 读响应体，流用 use{} 关闭；
     * - 空响应 / 非 JSON 响应判错（不再把空串/HTML 当 JSON 解析而崩）；
     * - 超时 / IO 异常 / 5xx 重试最多 2 次；
     * - 401 单独抛 UnauthorizedException（P0-E13），不重试；
     * - 错误信息里的 URL 与服务端回显先脱敏（P0-E3）。
     */
    /** 带上限读文本：超限抛异常，不把超大响应全读进内存 */
    private fun readCappedText(stream: java.io.InputStream, maxBytes: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw Exception("响应体超过 ${maxBytes / 1024 / 1024}MB 上限")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8)
    }

    private suspend fun requestJson(
        method: String,
        u: String,
        body: JSONObject?,
        what: String,
        headerName: String = "Authorization",
        headerValue: String? = null,
        isLogin: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                val url = java.net.URL(u)
                val c = (url.openConnection(Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15000
                    readTimeout = 30000
                    if (body != null) doOutput = true
                    setRequestProperty(headerName, headerValue ?: authHeader())
                    if (body != null) setRequestProperty("Content-Type", "application/json")
                    // 注意：不发 Cache-Control: no-cache——HttpURLConnection 默认无缓存，该头无效
                }
                try {
                    if (body != null) {
                        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    // 响应体上限 10MB：超限直接判错，不再全量读入内存（防超大响应 OOM）
                    val txt = stream?.use { readCappedText(it, 10 * 1024 * 1024) } ?: ""
                    if (code == 401) {
                        if (isLogin) throw UnauthorizedException("用户名或密码错误", isLogin = true)
                        notifyUnauthorized()
                        throw UnauthorizedException("未授权（Token 可能已过期，请重新登录）", isLogin = false)
                    }
                    if (code !in 200..299) {
                        if (code >= 500 && attempt < MAX_RETRIES) {
                            delay(RETRY_DELAY_MS)
                            continue
                        }
                        val snippet = if (txt.isNotEmpty()) ": ${Log.sanitizeForLog(txt.take(200))}" else ""
                        throw Exception("$what 失败（HTTP $code）$snippet\n${Log.sanitizeForLog(u)}")
                    }
                    if (txt.isBlank()) throw Exception("$what 返回空响应")
                    try {
                        return@withContext JSONObject(txt)
                    } catch (e: JSONException) {
                        throw Exception("$what 返回非 JSON 响应: ${Log.sanitizeForLog(txt.take(120))}")
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: Exception) {
                // 立即失败的错误不重试：DNS 解析失败重试也一样失败，只会白等
                if (e is java.net.UnknownHostException) throw e
                if ((e is SocketTimeoutException || e is IOException) && attempt < MAX_RETRIES) {
                    lastError = e
                    delay(RETRY_DELAY_MS)
                    continue
                }
                throw e
            }
        }
        throw lastError ?: Exception("$what 失败")
    }

    private suspend fun getJson(u: String, what: String): JSONObject =
        requestJson("GET", u, null, what)

    private suspend fun postJsonWithHeader(u: String, body: JSONObject, what: String, headerName: String, headerValue: String): JSONObject =
        requestJson("POST", u, body, what, headerName, headerValue, isLogin = true)

    private suspend fun postJson(u: String, body: JSONObject, what: String): JSONObject =
        requestJson("POST", u, body, what)

    // ---------- 登录 ----------
    data class AuthResult(val userId: String, val token: String, val username: String)

    suspend fun login(username: String, password: String): AuthResult {
        // 登录不用查询参数，只用头和 body（部分服务器对查询参数敏感报 400）
        val u = "${base()}/Users/AuthenticateByName"
        val body = JSONObject().put("Username", username).put("Pw", password)
        // 登录用标准头（带引号，不带 Token，避免旧 Token 干扰新登录；P1-E12 统一走 authHeader）
        val json = postJsonWithHeader(u, body, "登录",
            "X-Emby-Authorization",
            authHeader(includeToken = false))
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
        /** 未看集数：null = 服务器未返回该字段（缺失时不显示徽章，P1-E1），非 null 才表示真实计数 */
        val unplayedCount: Int? = null,
    ) {
        /** 剩余可播时长（ticks） */
        val remainingTicks: Long get() = (runTicks - playTicks).coerceAtLeast(0L)
        /** 第X集 集名（epLabel / epPanelTitle 共享同一实现，P2-E3） */
        private fun epTitle(): String =
            if (type == "Episode" && episodeIdx > 0) {
                val epLabel = "第${episodeIdx}集"
                // 去空格后比较，避免"第 1 集"和"第1集"被判为不同而重复拼接
                val normName = name.replace(" ", "")
                if (name.isNotEmpty() && normName != epLabel && !normName.startsWith(epLabel)) "$epLabel $name" else if (normName.startsWith(epLabel)) name else epLabel
            } else name
        fun epLabel(): String = epTitle()
        /** 剧集面板标题：第4集 集名 */
        fun epPanelTitle(): String = epTitle()
        /** 集数：Series 用 (RecursiveItemCount - ChildCount) 即总集数；Movie 返回 0 */
        val episodeCount: Int get() =
            if (type == "Series" && recursiveCount > childCount) recursiveCount - childCount else 0
        /** 徽章显示数：未看集数（随观看减少）；未看数据缺失时返回 0（不显示徽章，不再回退总集数，P1-E1） */
        val displayCount: Int get() =
            if (type == "Series") unplayedCount ?: 0 else 0
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

    // ---- getLibraries 成功结果短时缓存（P2-E2）：键含 serverKey，切换服务器自动失效 ----
    private data class LibrariesCache(val serverKey: String, val at: Long, val libs: List<Library>)
    @Volatile private var librariesCache: LibrariesCache? = null
    private val librariesMutex = Mutex()

    suspend fun getLibraries(): List<Library> {
        val key = Prefs.currentServerKey()
        librariesCache?.let {
            if (it.serverKey == key && System.currentTimeMillis() - it.at < LIBRARIES_CACHE_TTL_MS) return it.libs
        }
        return librariesMutex.withLock {
            librariesCache?.let {
                if (it.serverKey == key && System.currentTimeMillis() - it.at < LIBRARIES_CACHE_TTL_MS) return@withLock it.libs
            }
            val libs = fetchLibraries()
            librariesCache = LibrariesCache(key, System.currentTimeMillis(), libs)
            libs
        }
    }

    /** 主动失效媒体库缓存（收藏/进度等变更不需要，但供调用方在服务器配置变化后调用） */
    fun invalidateLibrariesCache() {
        librariesCache = null
    }

    /**
     * 拉取媒体库（P0-E12）：
     * - Views 成功即直接返回，哪怕合法空列表（不再用 MediaFolders 兜底展示无权限的物理文件夹）；
     * - 仅 Views 异常时才尝试 MediaFolders 兜底；
     * - MediaFolders 也异常时错误向上传（不再吞成空列表）。
     */
    private suspend fun fetchLibraries(): List<Library> {
        // 用户视图是"媒体库"的唯一真实来源（Emby 网页显示的媒体库列表）
        try {
            val views = parseLibraries(getJson(url("/Users/${Prefs.userId}/Views"), "获取媒体库"))
            for (v in views) Log.d("YambyLib", "Views: ${v.name} (${v.id}) type=${v.type}")
            Log.d("YambyLib", "使用 Views（${views.size} 个）")
            return views
        } catch (e: Exception) {
            Log.w("YambyLib", "Views 失败，尝试 MediaFolders 兜底: ${e.message}")
        }
        // MediaFolders 是服务器物理文件夹，不是用户看到的媒体库，仅在 Views 异常时兜底
        val folders = parseLibraries(getJson(url("/Library/MediaFolders"), "获取媒体文件夹"))
        for (f in folders) Log.d("YambyLib", "MediaFolders(兜底): ${f.name} (${f.id}) type=${f.type}")
        return folders
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
        minimal: Boolean = false,
        startIndex: Int = 0,
        mediaSourceId: String = ""
    ): List<Item> {
        val fields = if (minimal) "" else
            "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate,DateCreated,DateLastContentAdded,CriticRating,OfficialRating,UserData"
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "ParentId=$parentId&Recursive=true&IncludeItemTypes=Movie,Series" +
                fields +
                "&SortBy=$sortBy&SortOrder=$sortOrder&Limit=$limit&StartIndex=$startIndex"
        )
        return parseItems(getJson(u, "获取列表"), mediaSourceId)
    }

    suspend fun getItemsTotal(parentId: String): Int {
        // 与 getItems 同一套过滤（ParentId + Recursive + IncludeItemTypes，P2-E7），仅 Limit 不同
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "ParentId=$parentId&Recursive=true&IncludeItemTypes=Movie,Series&Limit=1"
        )
        return try { getJson(u, "获取总数").optInt("TotalRecordCount", -1) } catch (_: Exception) { -1 }
    }

    /** 资源库卡片统计：某类型的总数（Movie / Series）；失败返回 -1，与真 0 区分（P2） */
    suspend fun getTotalCount(types: String): Int {
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "Recursive=true&IncludeItemTypes=$types&Limit=1"
        )
        return try { getJson(u, "获取总数").optInt("TotalRecordCount", -1) } catch (_: Exception) { -1 }
    }

    suspend fun getLatestItems(limit: Int = 8): List<Item> {
        // 注意：不再加 _t 时间戳防缓存（P2-E4）；getJson 已带 Cache-Control: no-cache
        val u = url(
            "/Users/${Prefs.userId}/Items/Latest",
            "IncludeItemTypes=Movie,Series&Fields=Overview,ProductionYear,Genres,Status,UserData&Limit=$limit"
        )
        return parseItems(getJson(u, "获取最新"))
    }

    suspend fun getServerName(): String {
        val u = url("/System/Info")
        return getJson(u, "获取服务器信息").optString("ServerName", "")
    }

    /**
     * 继续观看（P1-E5）：
     * - /Resume 成功即直接返回（哪怕合法空列表，不再因空结果多发一次 Filters 请求）；
     * - 仅异常时才用 Filters=IsResumable 兜底；
     * - 结果按 Id 去重。
     */
    suspend fun getResumeItems(limit: Int = 20): List<Item> {
        // 简化参数提高兼容性：只取必要的 RunTimeTicks 和 SeriesName
        return try {
            val u = url(
                "/Users/${Prefs.userId}/Items/Resume",
                "Recursive=true&IncludeItemTypes=Movie,Episode&Fields=RunTimeTicks,SeriesName,SeriesId,UserData&Limit=$limit"
            )
            parseItems(getJson(u, "获取继续观看")).distinctBy { it.id }
        } catch (e: Exception) {
            Log.w("YambyResume", "Resume接口失败，尝试Filters方式: ${e.message}")
            // 兜底：用 Filters=IsResumable 查询，兼容不支持 /Resume 的服务器
            try {
                val u2 = url(
                    "/Users/${Prefs.userId}/Items",
                    "Recursive=true&IncludeItemTypes=Movie,Episode&Filters=IsResumable&Fields=RunTimeTicks,SeriesName,SeriesId,UserData&Limit=$limit&SortBy=DatePlayed&SortOrder=Descending"
                )
                parseItems(getJson(u2, "获取继续观看(兜底)")).distinctBy { it.id }
            } catch (e2: Exception) {
                Log.w("YambyResume", "Filters兜底也失败: ${e2.message}")
                emptyList()
            }
        }
    }

    /** 搜索：按关键词搜电影/剧集（Person 不再混进媒体结果，见 searchPersons；P1-E9） */
    suspend fun searchItems(query: String, limit: Int = 60, startIndex: Int = 0): List<Item> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "SearchTerm=$q&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate,UserData" +
                "&SortBy=SortName&SortOrder=Ascending&Limit=$limit&StartIndex=$startIndex"
        )
        return parseItems(getJson(u, "搜索"))
    }

    /** 搜索：单独搜演职人员（Person 独立类型，不再当媒体返回；P1-E9） */
    suspend fun searchPersons(query: String, limit: Int = 60, startIndex: Int = 0): List<Item> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "SearchTerm=$q&Recursive=true&IncludeItemTypes=Person" +
                "&Fields=Overview,ProductionYear,UserData" +
                "&SortBy=SortName&SortOrder=Ascending&Limit=$limit&StartIndex=$startIndex"
        )
        return parseItems(getJson(u, "搜索演员"))
    }

    /** 仅搜索当前服务器（聚合搜索改名后的明确语义，P1-E9） */
    suspend fun searchCurrentServer(query: String, limit: Int = 60, startIndex: Int = 0): List<Item> =
        searchItems(query, limit, startIndex)

    /**
     * 聚合搜索：注意——当前实现仅搜索当前服务器，并非真正的多服务器聚合。
     * 真正的多服务器聚合需要持有各服务器的独立 Token，而 Prefs 只存当前服务器的 Token（密码不再落盘），
     * 故本方法等价于 searchCurrentServer，保留名称仅为调用方兼容。如需真聚合，需先实现多服务器 Token 存储。
     */
    suspend fun searchAggregated(query: String): List<Item> {
        return searchItems(query)
    }

    /** 收藏列表 */
    suspend fun getFavoriteItems(limit: Int = 200): List<Item> {
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "Filters=IsFavorite&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate,UserData" +
                "&SortBy=DateCreated&SortOrder=Descending&Limit=$limit"
        )
        return parseItems(getJson(u, "获取收藏"))
    }

    /** 演员作品：按 PersonIds 查电影/剧集 */
    suspend fun getPersonWorks(personId: String, limit: Int = 100): List<Item> {
        val u = url(
            "/Users/${Prefs.userId}/Items",
            "PersonIds=$personId&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&Fields=Overview,ProductionYear,RunTimeTicks,ChildCount,RecursiveItemCount,CommunityRating,SeriesName,Status,Genres,PremiereDate,UserData" +
                "&SortBy=SortName&SortOrder=Ascending&Limit=$limit"
        )
        return parseItems(getJson(u, "获取演员作品"))
    }

    /** 演员详情：/Users/{uid}/Items/{personId} 带 Overview 简介 */
    suspend fun getPersonBio(personId: String): String {
        return try {
            val u = url("/Users/${Prefs.userId}/Items/$personId", "Fields=Overview,UserData")
            getJson(u, "获取演员简介").optString("Overview", "")
        } catch (e: Exception) { "" }
    }

    /**
     * 取季列表，按季号排序：正常季（seasonIdx>=1）按季号升序在前，特别篇（seasonIdx<=0）在后（P1-E10）。
     * 这样调用方取 first() 即为“seasonIdx>=1 的第一季”，不再默认取到特别篇。
     */
    suspend fun getSeasons(seriesId: String): List<Item> {
        val u = url("/Shows/$seriesId/Seasons", "UserId=${Prefs.userId}&Fields=Overview,ProductionYear,ChildCount,UserData")
        return parseItems(getJson(u, "获取季"))
            .sortedWith(compareBy<Item> { it.seasonIdx <= 0 }.thenBy { it.seasonIdx })
    }

    suspend fun getEpisodes(seriesId: String, seasonId: String, mediaSourceId: String = ""): List<Item> {
        // 注意：不再加 _t 时间戳防缓存（P2-E4）；getJson 已带 Cache-Control: no-cache
        val u = url(
            "/Shows/$seriesId/Episodes",
            "SeasonId=$seasonId&UserId=${Prefs.userId}&Fields=Overview,RunTimeTicks,SeriesName,MediaSources,UserData"
        )
        return parseItems(getJson(u, "获取剧集"), mediaSourceId)
    }

    /** 取某部剧的可续播剧集（带 UserData 进度），详情页用 */
    suspend fun getResumableEpisodes(seriesId: String): List<Item> {
        return try {
            val u = url(
                "/Users/${Prefs.userId}/Items",
                "ParentId=$seriesId&Filters=IsResumable&Recursive=true&Fields=RunTimeTicks,UserData"
            )
            com.muse.gomepc.player.DebugLog.d("UI", "取可续播集开始: seriesId=$seriesId")
            val items = parseItems(getJson(u, "取可续播剧集"))
            com.muse.gomepc.player.DebugLog.d("UI", "取可续播集成功: 返回${items.size}集")
            for (it in items) {
                com.muse.gomepc.player.DebugLog.d("UI", "可续播: ${it.name} id=${it.id} playTicks=${it.playTicks}")
            }
            items
        } catch (e: Exception) {
            com.muse.gomepc.player.DebugLog.d("UI", "取可续播集失败: ${e.message}")
            emptyList()
        }
    }

    suspend fun getItem(id: String, mediaSourceId: String = ""): Item {
        val u = url("/Users/${Prefs.userId}/Items/$id", "Fields=Overview,ProductionYear,RunTimeTicks,People,Genres,ChildCount,CommunityRating,SeriesName,Status,UserData")
        val o = getJson(u, "获取详情")
        return parseItem(o, mediaSourceId)
    }

    /** 逐条 try-catch：单条脏数据只跳过该条，不拖垮整个列表（P0-E1） */
    private fun parseItems(json: JSONObject, preferredMediaSourceId: String = ""): List<Item> {
        val out = mutableListOf<Item>()
        val arr = json.optJSONArray("Items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            try {
                out.add(parseItem(arr.getJSONObject(i), preferredMediaSourceId))
            } catch (e: Exception) {
                Log.w("YambyParse", "跳过脏条目 #$i: ${e.message}")
            }
        }
        return out
    }

    private fun parseItem(o: JSONObject, preferredMediaSourceId: String = ""): Item {
        val type = o.optString("Type")
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
        // 媒体信息：分辨率 + 文件大小（剧集面板用）。
        // P1-E3：优先取调用方选中的 MediaSourceId 对应的源（详情页版本选择），未指定则取第一个源。
        var vw = 0
        var vh = 0
        var sz = 0L
        try {
            val ms = o.optJSONArray("MediaSources")
            if (ms != null && ms.length() > 0) {
                var src: JSONObject? = null
                if (preferredMediaSourceId.isNotEmpty()) {
                    for (i in 0 until ms.length()) {
                        val cand = ms.optJSONObject(i) ?: continue
                        if (cand.optString("Id") == preferredMediaSourceId) { src = cand; break }
                    }
                }
                if (src == null) src = ms.optJSONObject(0)
                if (src != null) {
                    sz = src.optLong("Size", 0L)
                    val streams = src.optJSONArray("MediaStreams")
                    if (streams != null) for (i in 0 until streams.length()) {
                        val st = streams.optJSONObject(i) ?: continue
                        if (st.optString("Type") == "Video") {
                            vw = st.optInt("Width", 0)
                            vh = st.optInt("Height", 0)
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // P1-E1：UnplayedItemCount 缺失时记为 null（不显示徽章），不要默认 0 再回退总集数
        val unplayed: Int? = if (ud == null || !ud.has("UnplayedItemCount")) null else ud.optInt("UnplayedItemCount", 0)
        return Item(
            id = o.optString("Id"),
            name = o.optString("Name"),
            type = type,
            year = o.optInt("ProductionYear", 0),
            overview = o.optString("Overview", ""),
            seriesId = o.optString("SeriesId", ""),
            seriesName = o.optString("SeriesName", ""),
            // P1-E2：Season 的季号在 IndexNumber 里（ParentIndexNumber 是父级编号，不是季号）
            seasonIdx = if (type == "Season") o.optInt("IndexNumber", 0) else o.optInt("ParentIndexNumber", 0),
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
            unplayedCount = unplayed,
        )
    }

    // ---------- 收藏 / 已看 ----------
    /**
     * 收藏 / 已看状态切换（P0-E5）：
     * 返回 true = 服务器 2xx 确认成功；返回 false = 失败，调用方应回滚乐观更新的 UI 状态。
     * 不再吞异常且不看状态码。
     */
    private suspend fun postFavoriteState(path: String, active: Boolean, what: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val c = (java.net.URL(url(path)).openConnection(Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                    requestMethod = if (active) "POST" else "DELETE"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = active
                    setRequestProperty("Authorization", authHeader())
                }
                try {
                    if (active) c.outputStream.use { }
                    val code = c.responseCode
                    try { c.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } } catch (_: Exception) {}
                    try { c.errorStream?.close() } catch (_: Exception) {}
                    if (code == 401) {
                        notifyUnauthorized()
                        throw UnauthorizedException("未授权（Token 可能已过期，请重新登录）", isLogin = false)
                    }
                    if (code !in 200..299) {
                        Log.w("YambyFav", "$what 失败（HTTP $code）")
                        return@withContext false
                    }
                    true
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.w("YambyFav", "$what 异常: ${e.message}")
                false
            }
        }

    suspend fun setFavorite(id: String, fav: Boolean): Boolean =
        postFavoriteState("/Users/${Prefs.userId}/FavoriteItems/$id", fav, "收藏")

    suspend fun setPlayed(id: String, played: Boolean): Boolean =
        postFavoriteState("/Users/${Prefs.userId}/PlayedItems/$id", played, "标记已看")

    /**
     * 图片地址（P2-E8）：路径段走 HttpUrl.Builder 编码，w 钳制到 1..4096；itemId 为空返回 ""。
     * 认证参数同样由 Builder 编码，不再手写字符串拼接。
     */
    /**
     * 图片 URL：不带 Token（P2）。Token 在 query 里会进图片缓存 key、日志与代理记录；
     * 调用方（EmbyImageLoader / EpisodePanel）请求时带 X-Emby-Authorization 头认证。
     */
    fun imageUrl(itemId: String, type: String = "Primary", w: Int = 400): String {
        if (itemId.isBlank()) return ""
        val width = w.coerceIn(1, 4096)
        val imgType = type.ifBlank { "Primary" }
        val b = base().toHttpUrlOrNull()?.newBuilder() ?: return ""
        b.addPathSegment("Items")
            .addPathSegment(itemId)
            .addPathSegment("Images")
            .addPathSegment(imgType)
        // 只带非敏感客户端参数，绝不带 Token
        addAuthParams(b, withToken = false)
        b.addQueryParameter("maxWidth", width.toString())
        return b.build().toString()
    }

    /** ticks → "00:35:15"（续播位置，用于播放按钮胶囊进度条文字）；负值钳制为 0，上限 99:59:59（P1-E4） */
    fun posLabel(ticks: Long): String {
        val s = (ticks.coerceAtLeast(0L) / 10_000_000).coerceAtMost(359_999L)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** ticks → "剩余：22分47秒" / 超过 1 小时时 "剩余：1小时22分47秒"（P1-E4） */
    fun remainingLabel(ticks: Long): String {
        val s = ticks.coerceAtLeast(0L) / 10_000_000
        return "剩余：" + durationParts(s)
    }

    /** ticks → "49分23秒"（无"剩余："前缀，用于剧集时长）；超过 1 小时带小时（P1-E4） */
    fun durationLabel(ticks: Long): String {
        val s = ticks.coerceAtLeast(0L) / 10_000_000
        return durationParts(s)
    }

    private fun durationParts(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分${sec}秒"
            m > 0 -> "${m}分${sec}秒"
            else -> "${sec}秒"
        }
    }

    /** 短时长：46:10；超过 1 小时时为 H:MM:SS，如 1:30:00（P1-E4） */
    fun durShort(ticks: Long): String {
        val s = (ticks.coerceAtLeast(0L) / 10_000_000).coerceAtMost(359_999L)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    /** 文件大小：1.34G / 856M / 512K；小于 1K 显示 xB（不再是 0K，P1-E4），负值返回 "" */
    /** 文件大小：Locale.US 固定小数点（默认 Locale 下德语等会打出 1,34G，P2） */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024L -> "${bytes}B"
        bytes >= 1024L * 1024 * 1024 * 1024 -> "%.2fT".format(Locale.US, bytes / 1024.0 / 1024 / 1024 / 1024)
        bytes >= 1024L * 1024 * 1024 -> "%.2fG".format(Locale.US, bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.0fM".format(Locale.US, bytes / 1024.0 / 1024)
        else -> "%.0fK".format(Locale.US, bytes / 1024.0)
    }

    // ---------- 播放地址（YP 1.0 原始逻辑） ----------
    /** 单一直链：取第一个候选（P0-E10：firstOrNull，空时抛 NoDirectUrl） */
    suspend fun getPlaybackUrl(itemId: String): String {
        return getPlaybackUrls(itemId).firstOrNull() ?: throw PlaybackError.NoDirectUrl()
    }

    /** 取原始 PlaybackInfo JSON（含 MediaSources 与 MediaStreams），供版本/音轨/字幕选择器用 */
    suspend fun getPlaybackInfoJson(itemId: String): JSONObject = withContext(Dispatchers.IO) {
        // 带 DeviceProfile：与 getPlaybackUrls 同一套 body，详情与播放的播放信息不再不一致
        // （之前详情页的 PlaybackInfo 无 DeviceProfile，服务器可能因容器不在列表里拒绝直链）
        val body = JSONObject()
            .put("UserId", Prefs.userId)
            .put("MaxStreamingBitrate", 140_000_000)
            .put("StartTimeTicks", 0)
            .put("DeviceProfile", buildDeviceProfile())
        val u = url("/Items/$itemId/PlaybackInfo", "UserId=${Prefs.userId}")
        // 取 PlaySessionId 供后续上报用（按 itemId 存入会话缓存，P0-E6）
        val json = postJson(u, body, "获取播放信息(详情页)")
        val sessionId = json.optString("PlaySessionId", "")
        if (sessionId.isNotEmpty()) rememberPlaySessionId(itemId, "", sessionId)
        json
    }

    /** 按指定的 MediaSourceId 取播放地址（详情页版本选择用） */
    suspend fun getPlaybackUrlsForSource(itemId: String, mediaSourceId: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        try {
            val json = getPlaybackInfoJson(itemId)
            val sources = json.optJSONArray("MediaSources")
            // MediaSources 为空也不直接抛，落到后面的 Static=true 兜底（P0-E10）
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val src = sources.optJSONObject(i) ?: continue
                    if (src.optString("Id") != mediaSourceId) continue
                    // 该源的 DV Profile 也按 mediaSourceId 缓存（P0-E6）
                    try {
                        val dv = DolbyVision.profileFromPlaybackInfo(json, mediaSourceId)
                        if (dv != null) dvProfiles[sessionKey(itemId, mediaSourceId)] = dv
                    } catch (_: Exception) {}
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
            }
        } catch (e: PlaybackError) {
            throw e
        } catch (e: Exception) {
            throw PlaybackError.InfoFailed(e.message ?: "unknown")
        }
        // P0-E10：为空时也追加 Static=true 兜底，不直接抛 NoDirectUrl
        val fallback = url("/Videos/$itemId/stream", "Static=true")
        out.add(fallback)
        // P2-E9：不再追加 http→https 双份候选，只保留与 base 同协议的兜底地址
        return@withContext out.distinct()
    }

    /**
     * 播放地址列表：
     * 只发一份 PlaybackInfo（P1-E15），带补全容器的 DeviceProfile；
     * 收集全部 MediaSource 的 DirectStreamUrl / STRM 地址；
     * 最后追加兜底 /Videos/{id}/stream?Static=true（新服务器靠它播）。
     * 播放器按顺序试，一个播不了换下一个。
     */
    suspend fun getPlaybackUrls(itemId: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        try {
            val body = JSONObject()
                .put("UserId", Prefs.userId)
                .put("MaxStreamingBitrate", 140_000_000)
                .put("StartTimeTicks", 0)
                .put("DeviceProfile", buildDeviceProfile())
            val u = url("/Items/$itemId/PlaybackInfo", "UserId=${Prefs.userId}")
            val json = postJson(u, body, "获取播放信息")
            // 取出 PlaySessionId，后续上报进度用（按 itemId 缓存，P0-E6）
            val sessionId = json.optString("PlaySessionId", "")
            if (sessionId.isNotEmpty()) rememberPlaySessionId(itemId, "", sessionId)
            // 杜比视界检测：从已有的 PlaybackInfo 直接提取，不额外请求
            try {
                val dv = DolbyVision.profileFromPlaybackInfo(json)
                if (dv != null) dvProfiles[sessionKey(itemId, "")] = dv
            } catch (_: Exception) {}
            val sources = json.optJSONArray("MediaSources")
            if (sources != null) {
                // 遍历所有 MediaSource（不只取第一个，有的集第一个源没有直链）
                for (si in 0 until sources.length()) {
                    val src = sources.optJSONObject(si) ?: continue
                    // DirectStreamUrl 优先（服务器给的直链最快最稳）
                    val direct = src.optString("DirectStreamUrl", "")
                    if (direct.isNotEmpty()) {
                        val s = resolveUrl(direct)
                        if (s.isNotEmpty() && !out.contains(s)) out.add(s)
                    }
                    // STRM 源地址其次
                    val protocol = src.optString("Protocol", "")
                    val path = src.optString("Path", "")
                    if (protocol.equals("Http", true) && path.startsWith("http", true)) {
                        val s = strmSourceUrl(path)
                        if (s.isNotEmpty() && !out.contains(s)) out.add(s)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YambyPlay", "PlaybackInfo 失败: ${e.message}")
        }
        // 兜底（YP 1.0）：新服务器靠它播；老服务器 nginx 会 404，播放器会自动跳过
        val std = url("/Videos/$itemId/stream", "Static=true")
        if (!out.contains(std)) out.add(std)
        // P2-E9：不再追加 http→https 双份候选，只保留与 base 同协议的兜底地址
        Log.d("YambyPlay", "共 ${out.size} 个候选地址")
        out
    }

    /** 补全容器的 DeviceProfile（P1-E15）：直通容器覆盖常见格式，避免服务器因容器不在列表里而拒绝直链/强制转码 */
    private fun buildDeviceProfile(): JSONObject = JSONObject()
        .put("Name", "Android")
        .put("MaxStreamingBitrate", 140_000_000)
        .put("DirectPlayProfiles", JSONArray().apply {
            for (c in listOf("mkv", "mp4", "m2ts", "ts", "avi", "webm", "mov", "flv")) {
                put(JSONObject().put("Container", c).put("Type", "Video"))
            }
        })
        .put("TranscodingProfiles", JSONArray())
        .put("ContainerProfiles", JSONArray())
        .put("CodecProfiles", JSONArray())
        .put("SubtitleProfiles", JSONArray().apply {
            put(JSONObject().put("Format", "srt").put("Method", "External"))
            put(JSONObject().put("Format", "ass").put("Method", "External"))
            put(JSONObject().put("Format", "vtt").put("Method", "External"))
        })

    /**
     * 诊断视频地址：用 HTTP Range 请求看服务器到底返回什么（状态码/Content-Type/前几个字节）。
     * 返回如 "HTTP 404 text/html" 或 "HTTP 206 video/x-matroska"；不泄露 token。
     * P0-E4：仅同源（scheme+host+port 与 base 一致）时才带 Authorization 头，不把 Token 发给任意第三方地址；
     * Range 只取 64 字节且只读 64 字节，不再 body.bytes() 全读（防 OOM）。
     */
    suspend fun diagnoseUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val target = rawUrl.toHttpUrlOrNull() ?: return@withContext "请求异常:地址无效"
            val baseHttp = try { base().toHttpUrlOrNull() } catch (_: Exception) { null }
            val sameOrigin = baseHttp != null &&
                target.scheme == baseHttp.scheme && target.host == baseHttp.host && target.port == baseHttp.port
            val reqBuilder = Request.Builder()
                .url(target)
                .header("Range", "bytes=0-63")
                .header("User-Agent", "Yamby/1.0")
                .get()
            if (sameOrigin) reqBuilder.header("Authorization", authHeader())
            http.newCall(reqBuilder.build()).execute().use { resp ->
                val ct = resp.header("Content-Type", "?") ?: "?"
                val len = resp.header("Content-Length", "?") ?: "?"
                val body = try {
                    val buf = ByteArray(64)
                    val n = resp.body?.byteStream()?.use { it.read(buf) } ?: -1
                    if (n <= 0) "(空)"
                    else if (ct.contains("html", true) || looksLikeHtml(buf.copyOf(n))) {
                        val txt = String(buf, 0, n, Charsets.UTF_8).take(80).replace("\n", " ")
                        "HTML:$txt"
                    } else {
                        "二进制前4字节:${buf.take(4).joinToString(" ") { "%02X".format(it) }}"
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
        val head = String(b.copyOf(minOf(b.size, 32)), Charsets.UTF_8).trimStart()
        return head.startsWith("<", true)
    }

    /**
     * 解析直链地址（P1-E14）：用 HttpUrl 解析，已有认证参数（X-Emby-Token / api_key / X-Emby-*）先移除再追加新的，
     * 避免旧 Token 残留或重复参数；解析失败返回 ""。
     */
    private fun resolveUrl(u: String): String {
        if (u.isEmpty()) return ""
        val absolute = if (u.startsWith("http", true)) u else base() + u
        val parsed = absolute.toHttpUrlOrNull() ?: return ""
        val b = parsed.newBuilder()
        for (name in listOf("X-Emby-Token", "api_key", "X-Emby-Client", "X-Emby-Device-Name", "X-Emby-Device-Id", "X-Emby-Client-Version")) {
            b.removeAllQueryParameters(name)
        }
        addAuthParams(b, withToken = true)
        return b.build().toString()
    }

    /**
     * STRM 源地址回环替换（P1-E13）：用 HttpUrl 解析 host，仅当 host 是回环地址时才替换为服务器 host。
     * 回环判断覆盖 127.0.0.1 / ::1 / localhost / 0.0.0.0 及系统判定（isLoopbackAddress）。
     */
    private fun strmSourceUrl(path: String): String {
        if (path.isEmpty() || !path.startsWith("http", true)) return ""
        val parsed = path.toHttpUrlOrNull() ?: return ""
        if (!isLoopbackHost(parsed.host)) return path
        val server = base().toHttpUrlOrNull() ?: return path
        return parsed.newBuilder().host(server.host).build().toString()
    }

    private fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase()
        if (h in setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]")) return true
        return try { java.net.InetAddress.getByName(host).isLoopbackAddress } catch (_: Exception) { false }
    }

    // ---------- 播放进度上报 ----------
    /** POST 不关心响应体（204 No Content），空响应不抛异常；超时/IO/5xx 重试最多 2 次（P0-E2）；401 抛 UnauthorizedException（P0-E13） */
    private suspend fun postNoContent(u: String, body: JSONObject, what: String) =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (attempt in 0..MAX_RETRIES) {
                try {
                    val url = java.net.URL(u)
                    val c = (url.openConnection(Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
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
                        // 消费掉响应体，避免连接复用问题；204 无内容也算成功
                        try { c.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } } catch (_: Exception) {}
                        try { c.errorStream?.close() } catch (_: Exception) {}
                        if (code == 401) {
                            notifyUnauthorized()
                            throw UnauthorizedException("未授权（Token 可能已过期，请重新登录）", isLogin = false)
                        }
                        if (code !in 200..299) {
                            if (code >= 500 && attempt < MAX_RETRIES) {
                                delay(RETRY_DELAY_MS)
                                continue
                            }
                            throw Exception("$what 失败（HTTP $code）")
                        }
                        return@withContext
                    } finally {
                        c.disconnect()
                    }
                } catch (e: UnauthorizedException) {
                    throw e
                } catch (e: Exception) {
                    if ((e is SocketTimeoutException || e is IOException) && attempt < MAX_RETRIES) {
                        lastError = e
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    throw e
                }
            }
            throw lastError ?: Exception("$what 失败")
        }

    suspend fun reportProgress(itemId: String, ticks: Long, paused: Boolean = false, mediaSourceId: String = "", playSessionId: String = ""): String? {
        if (itemId.isEmpty()) return "itemId为空"
        // 节流（P1-E20）：同一条目+源在窗口内且暂停状态未变时跳过重复上报
        val key = sessionKey(itemId, mediaSourceId)
        val now = System.currentTimeMillis()
        val last = lastProgressAt[key]
        if (last != null && now - last < PROGRESS_THROTTLE_MS && lastProgressPaused[key] == paused) return null
        return try {
            val u = url("/Sessions/Playing/Progress")
            val body = JSONObject()
                .put("ItemId", itemId)
                .put("PositionTicks", ticks)
                .put("IsPaused", paused)
                .put("PlaySessionId", playSessionId.ifEmpty { getCachedPlaySessionId(itemId, mediaSourceId) })
            if (mediaSourceId.isNotEmpty()) body.put("MediaSourceId", mediaSourceId)
            postNoContent(u, body, "进度上报")
            lastProgressAt[key] = System.currentTimeMillis()
            lastProgressPaused[key] = paused
            null
        } catch (e: Exception) {
            e.message
        }
    }

    /** 先把待重试的停止上报发出去（失败的留在队列里，下次再试；队列有上限，防无界增长） */
    private suspend fun flushPendingStopped() {
        while (true) {
            val p = pendingStopped.peek() ?: return
            try {
                val u = url("/Sessions/Playing/Stopped")
                val body = JSONObject()
                    .put("ItemId", p.itemId)
                    .put("PositionTicks", p.ticks)
                    .put("PlaySessionId", p.playSessionId.ifEmpty { getCachedPlaySessionId(p.itemId, p.mediaSourceId) })
                if (p.mediaSourceId.isNotEmpty()) body.put("MediaSourceId", p.mediaSourceId)
                postNoContent(u, body, "停止上报（重试）")
                pendingStopped.poll()
            } catch (_: Exception) {
                return
            }
        }
    }

    suspend fun reportStopped(itemId: String, ticks: Long, mediaSourceId: String = "", playSessionId: String = "") {
        if (itemId.isEmpty()) return
        // 停止上报不节流；同时清掉该条目的节流状态，避免下一轮播放被旧时间戳挡住
        lastProgressAt.remove(sessionKey(itemId, mediaSourceId))
        lastProgressPaused.remove(sessionKey(itemId, mediaSourceId))
        // 先重发上次失败的停止上报（退出时网络抖动丢的进度，在下次上报时补上，不再静默丢弃）
        try { flushPendingStopped() } catch (_: Exception) { }
        try {
            val u = url("/Sessions/Playing/Stopped")
            val body = JSONObject()
                .put("ItemId", itemId)
                .put("PositionTicks", ticks)
                .put("PlaySessionId", playSessionId.ifEmpty { getCachedPlaySessionId(itemId, mediaSourceId) })
            if (mediaSourceId.isNotEmpty()) body.put("MediaSourceId", mediaSourceId)
            postNoContent(u, body, "停止上报")
        } catch (_: Exception) {
            // 失败进待重试队列（上限 50，超了丢最旧的），不再静默丢进度
            if (pendingStopped.size >= MAX_PENDING_STOPPED) pendingStopped.poll()
            pendingStopped.add(PendingStopped(itemId, ticks, mediaSourceId, playSessionId))
        }
    }

    /**
     * 开始播放时先创建会话，否则服务器可能丢弃后续的 Progress 上报。返回 null 表示成功，否则返回错误信息。
     * P0-E6：PlaySessionId 从 PlaybackInfo 解析（先读按 itemId 缓存的，缺失则现取一次 PlaybackInfo），
     * 不再用全局可变变量，也不再带空会话上报。
     */
    suspend fun reportPlaying(itemId: String, mediaSourceId: String = ""): String? {
        if (itemId.isEmpty()) return "itemId为空"
        return try {
            var sessionId = getCachedPlaySessionId(itemId, mediaSourceId)
            if (sessionId.isEmpty()) {
                try {
                    val info = getPlaybackInfoJson(itemId)
                    sessionId = info.optString("PlaySessionId", "")
                    if (sessionId.isNotEmpty()) rememberPlaySessionId(itemId, mediaSourceId, sessionId)
                } catch (_: Exception) {
                    // PlaybackInfo 取不到则继续用空会话上报，不阻塞播放
                }
            }
            val u = url("/Sessions/Playing")
            val body = JSONObject()
                .put("ItemId", itemId)
                .put("PlaySessionId", sessionId)
            if (mediaSourceId.isNotEmpty()) body.put("MediaSourceId", mediaSourceId)
            postNoContent(u, body, "开始播放上报")
            null
        } catch (e: Exception) {
            e.message
        }
    }
}
