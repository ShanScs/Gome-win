package com.muse.gomepc.emby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.prefs.Preferences

/**
 * 桌面端 Prefs（从 Android 版 Prefs.kt 移植的服务器/登录相关部分）。
 * Android 用 SharedPreferences，这里用 java.util.prefs.Preferences（Linux 存 ~/.java/.userPrefs）。
 * 播放设置/UI 设置等 Android 特定项后续按需添加。
 */

/** 保存的服务器条目（用于服务器切换）。
 * 注意（P0-E7）：password 字段仅保留签名兼容（调用方仍传内存中的密码），永不落盘：
 * getServers() 读取时忽略磁盘上的 password，saveServers() 写入时固定写 ""；
 * 首次读取到存量明文密码时会立即重写文件清掉旧值。 */
data class ServerEntry(
    val name: String,
    val protocol: String,
    val host: String,
    val port: String,
    val path: String,
    val username: String,
    val password: String,
    val backupProtocol: String = "",
    val backupHost: String = "",
    val backupPort: String = "",
    val backupPath: String = "",
    val backupName: String = ""
) {
    /** 唯一键：地址+端口+路径+用户名（统一走 Prefs.normalizeServerKey，P1-E16） */
    fun key(): String = Prefs.normalizeServerKey(host, port, path, username)
}

object Prefs {
    private val prefs: Preferences = Preferences.userRoot().node("com/muse/gomepc")

    /** 读-改-写操作的互斥锁（P1-E18）：upsert/remove/统计/搜索历史等 RMW 一律在该锁内完成，避免并发丢失更新 */
    private val rmwLock = Any()
    private val deviceIdLock = Any()

    private fun get(key: String, def: String): String = prefs.get(key, def) ?: def
    private fun put(key: String, value: String) {
        prefs.put(key, value)
        try { prefs.flush() } catch (_: Exception) {}
    }
    private fun remove(key: String) {
        prefs.remove(key)
        try { prefs.flush() } catch (_: Exception) {}
    }

    var protocol: String
        get() = get("protocol", "http")
        set(v) = put("protocol", v)

    /** 首页媒体库排序：逗号分隔的库ID列表，空表示默认顺序 */
    var libOrder: String
        get() = get("libOrder", "")
        set(v) = put("libOrder", v)

    /** 手动跳过片头秒数（按剧集 seriesId 存，0=不跳） */
    fun getSkipIntroSec(seriesId: String): Int =
        get("skip_intro_$seriesId", "0").toIntOrNull() ?: 0

    fun setSkipIntroSec(seriesId: String, sec: Int) {
        put("skip_intro_$seriesId", sec.toString())
    }

    /** 手动跳过片尾秒数（按剧集 seriesId 存，0=不跳）：片尾最后 X 秒显示"下一集"按钮 */
    fun getSkipOutroSec(seriesId: String): Int =
        get("skip_outro_$seriesId", "0").toIntOrNull() ?: 0

    fun setSkipOutroSec(seriesId: String, sec: Int) {
        put("skip_outro_$seriesId", sec.toString())
    }

    var host: String
        get() = get("host", "")
        set(v) = put("host", v)
    var port: String
        get() = get("port", "80")
        set(v) = put("port", v)
    /** 路径前缀，Yamby 硬编码 /emby */
    var path: String
        get() = get("path", "emby")
        set(v) = put("path", v)

    var username: String
        get() = get("username", "")
        set(v) = put("username", v)
    var serverName: String
        get() = get("serverName", "")
        set(v) = put("serverName", v)
    var userId: String
        get() = get("userId", "")
        set(v) = put("userId", v)
    var token: String
        get() = get("token", "")
        set(v) = put("token", v)

    /** 持久设备 ID（Yamby 用固定 DeviceId），双检锁避免并发生成两个 ID（P1-E17） */
    @Volatile private var cachedDeviceId: String? = null
    val deviceId: String
        get() {
            cachedDeviceId?.let { if (it.isNotEmpty()) return it }
            synchronized(deviceIdLock) {
                cachedDeviceId?.let { if (it.isNotEmpty()) return it }
                var id = prefs.get("deviceId", null)
                if (id.isNullOrEmpty()) {
                    id = UUID.randomUUID().toString()
                    put("deviceId", id)
                }
                cachedDeviceId = id
                return id
            }
        }

    /** 桌面端设备名：用主机名，取不到就用 GomePC；只解析一次并缓存（P1-E17，避免每次阻塞 DNS） */
    @Volatile private var cachedDeviceName: String? = null
    val deviceName: String
        get() {
            cachedDeviceName?.let { return it }
            val n = try {
                java.net.InetAddress.getLocalHost().hostName?.takeIf { it.isNotEmpty() } ?: "GomePC"
            } catch (_: Exception) {
                "GomePC"
            }
            cachedDeviceName = n
            return n
        }

    /** 规范化服务器地址：去掉默认端口，用于新旧记录兼容匹配 */
    fun normServerUrl(u: String): String {
        return u.replace(":80/", "/").replace(":80$", "")
            .replace(":443/", "/").replace(":443$", "")
    }

    /**
     * 统一服务器 Key（P1-E16）：ServerEntry.key() 与 currentServerKey() 都走这里，
     * 不再各写一套。规则：host 去空格转小写、port 为空按 80、path 去首尾 /、username 去空格。
     */
    fun normalizeServerKey(host: String, port: String, path: String, username: String): String {
        val h = host.trim().lowercase()
        val p = port.trim().ifEmpty { "80" }
        val pa = path.trim().trim('/')
        return "$h:$p/$pa/${username.trim()}"
    }

    /** baseUrl 如 http://example.com:8096/emby（P0-E8：协议白名单、host 去尾部/拒绝非法字符、port 1-65535、path 拒绝 ..） */
    fun baseUrl(): String {
        val h = host.trim().trimEnd('/').trim()
        if (h.isEmpty()) return ""
        val proto = protocol.trim().lowercase().let { if (it == "http" || it == "https") it else "http" }
        val portPart = port.trim().toIntOrNull()?.let { if (it in 1..65535) ":$it" else "" } ?: ""
        // path 按段过滤：丢掉空段 / "." / ".."，杜绝目录穿越拼进 URL
        val segs = path.trim().trim('/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        val pathPart = if (segs.isEmpty()) "" else "/" + segs.joinToString("/")
        return "$proto://$h$portPart$pathPart"
    }

    /** 校验当前服务器配置，返回错误描述；null 表示合法（供登录/添加服务器时提示，不在 baseUrl 里抛异常） */
    fun validateServerConfig(): String? {
        val proto = protocol.trim().lowercase()
        if (proto != "http" && proto != "https") return "协议必须是 http 或 https"
        val h = host.trim()
        if (h.isEmpty()) return "主机地址为空"
        if (h.any { !(it.isLetterOrDigit() || it in ".-_[]:") }) return "主机地址含非法字符"
        if (h.contains("..")) return "主机地址含非法字符"
        val p = port.trim()
        if (p.isNotEmpty()) {
            val pi = p.toIntOrNull() ?: return "端口必须是数字"
            if (pi !in 1..65535) return "端口必须在 1-65535 之间"
        }
        val rawPath = path.trim()
        if (rawPath.split('/').any { it == ".." }) return "路径不能包含 .."
        if (rawPath.any { it.isISOControl() || it == '\\' }) return "路径含非法字符"
        return null
    }

    fun isLoggedIn(): Boolean = token.isNotEmpty() && userId.isNotEmpty() && host.isNotEmpty()

    /** 清登录态，并清干净派生键（P1-E19）：token / userId / 服务器显示名；同时清会话/DV 缓存 */
    fun clearLogin() {
        synchronized(rmwLock) {
            remove("token")
            remove("userId")
            remove("serverName")
        }
        try { YambyClient.clearSessionCache() } catch (_: Exception) {}
    }

    // ---- 多服务器 ----
    private const val KEY_SERVERS = "servers_json"

    /** 读取保存的服务器列表。密码永不落盘：磁盘上的 password 一律忽略为 ""，
     * 读到存量明文密码时立即重写文件清掉旧值（P0-E7 存量迁移）。 */
    fun getServers(): List<ServerEntry> {
        val raw = get(KEY_SERVERS, "")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            var hadPlainPassword = false
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (o.optString("password").isNotEmpty()) hadPlainPassword = true
                val e = ServerEntry(
                    name = o.optString("name"),
                    protocol = o.optString("protocol", "http"),
                    host = o.optString("host"),
                    port = o.optString("port", "80"),
                    path = o.optString("path", ""),
                    username = o.optString("username"),
                    password = "",
                    backupProtocol = o.optString("backupProtocol", ""),
                    backupHost = o.optString("backupHost", ""),
                    backupPort = o.optString("backupPort", ""),
                    backupPath = o.optString("backupPath", ""),
                    backupName = o.optString("backupName", "")
                )
                if (e.host.isNotEmpty()) e else null
            }
            if (hadPlainPassword) saveServers(list)
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveServers(list: List<ServerEntry>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("protocol", s.protocol)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("path", s.path)
                    .put("username", s.username)
                    .put("password", "") // 密码永不落盘（P0-E7），只存 Token（Prefs.token）
                    .put("backupProtocol", s.backupProtocol)
                    .put("backupHost", s.backupHost)
                    .put("backupPort", s.backupPort)
                    .put("backupPath", s.backupPath)
                    .put("backupName", s.backupName)
            )
        }
        put(KEY_SERVERS, arr.toString())
    }

    /** 新增或更新服务器（按 key 去重），读-改-写加锁（P1-E18） */
    fun upsertServer(entry: ServerEntry) {
        synchronized(rmwLock) {
            val list = getServers().toMutableList()
            val idx = list.indexOfFirst { it.key() == entry.key() }
            if (idx >= 0) list[idx] = entry else list.add(entry)
            saveServers(list)
        }
    }

    /** 当前正在用的服务器 key（统一走 normalizeServerKey，P1-E16） */
    fun currentServerKey(): String =
        normalizeServerKey(host, port, path, username)

    /** 更新当前服务器条目的显示名 */
    fun updateCurrentServerName(name: String) {
        synchronized(rmwLock) {
            val key = currentServerKey()
            val list = getServers().toMutableList()
            val idx = list.indexOfFirst { it.key() == key }
            if (idx >= 0) {
                list[idx] = list[idx].copy(name = name)
                saveServers(list)
            }
        }
    }

    /**
     * 把当前 Prefs 里的服务器记入列表（登录成功后调用）。
     * password 参数仅保留签名兼容：密码只留在内存，永不写入磁盘（P0-E7）；登录态改存 Token（Prefs.token）。
     */
    fun rememberCurrentServer(@Suppress("UNUSED_PARAMETER") password: String) {
        if (host.isBlank()) return
        val name = serverName.ifEmpty { username.ifEmpty { host } }
        upsertServer(
            ServerEntry(
                name = name,
                protocol = protocol,
                host = host,
                port = port,
                path = path,
                username = username,
                password = ""
            )
        )
    }

    /** 删除保存的服务器（含统计缓存与全部派生键，P1-E19） */
    fun removeServer(key: String) {
        synchronized(rmwLock) {
            saveServers(getServers().filter { it.key() != key })
            val stats = getServerStats().toMutableMap()
            stats.remove(key)
            saveServerStats(stats)
            // 派生键一并清理：自定义图标 / 自定义名 / 搜索选中（若指向该服务器）
            remove("server_icon_$key")
            remove("custom_name_$key")
            if (searchServerKey == key) {
                searchServerKey = ""
                searchServerName = "聚合搜索"
            }
            // 删的是当前正在用的服务器：断开连接，清登录态与当前服务器信息
            if (key == currentServerKey()) {
                clearLogin()
                host = ""
                port = ""
                path = ""
                username = ""
                serverName = ""
                remove("libOrder")
            }
        }
    }

    /** 编辑服务器导致 key 变化时，迁移统计缓存 */
    fun transferServerStat(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        synchronized(rmwLock) {
            val m = getServerStats().toMutableMap()
            val s = m.remove(oldKey) ?: return
            m[newKey] = s
            saveServerStats(m)
        }
    }

    // ---- 资源库：服务器统计缓存（影片数/剧集数/上次使用） ----
    data class ServerStat(val movies: Int = -1, val series: Int = -1, val lastUsed: Long = 0L)

    private const val KEY_SERVER_STATS = "server_stats_json"

    fun getServerStats(): Map<String, ServerStat> {
        val raw = get(KEY_SERVER_STATS, "")
        if (raw.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, ServerStat>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val s = o.optJSONObject(k) ?: continue
                out[k] = ServerStat(
                    movies = s.optInt("movies", -1),
                    series = s.optInt("series", -1),
                    lastUsed = s.optLong("lastUsed", 0L)
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveServerStats(map: Map<String, ServerStat>) {
        val o = JSONObject()
        map.forEach { (k, s) ->
            o.put(k, JSONObject()
                .put("movies", s.movies)
                .put("series", s.series)
                .put("lastUsed", s.lastUsed))
        }
        put(KEY_SERVER_STATS, o.toString())
    }

    fun putServerStat(key: String, stat: ServerStat) {
        synchronized(rmwLock) {
            val m = getServerStats().toMutableMap()
            m[key] = stat
            saveServerStats(m)
        }
    }

    /** 更新某服务器的上次使用时间（同步，登录成功后立即调用） */
    fun touchServerLastUsed(key: String) {
        synchronized(rmwLock) {
            val cur = getServerStats()[key] ?: ServerStat()
            putServerStat(key, cur.copy(lastUsed = System.currentTimeMillis()))
        }
    }

    /**
     * 登录/切换成功后调用：先同步记下 lastUsed，再后台拉取影片数/剧集数缓存。
     * 不阻塞调用方。key 在调用时快照（P1-E18）；后台取数期间若已切换服务器则丢弃结果，
     * 避免把新服务器的统计写到旧 key 下。
     */
    fun recordServerVisit(scope: CoroutineScope) {
        val key = currentServerKey()
        touchServerLastUsed(key)
        scope.launch(Dispatchers.IO) {
            try {
                val movies = YambyClient.getTotalCount("Movie")
                val series = YambyClient.getTotalCount("Series")
                if (currentServerKey() != key) return@launch
                putServerStat(key, ServerStat(movies, series, System.currentTimeMillis()))
            } catch (_: Exception) {
            }
        }
    }

    // ---------- 搜索历史 ----------

    private const val KEY_SEARCH_HISTORY = "search_history"

    fun getSearchHistory(): List<String> {
        val raw = get(KEY_SEARCH_HISTORY, "")
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.take(20)
    }

    fun addSearchHistory(query: String) {
        // 换行符替换为空格（P2-E5）：历史按 \n 切分存储，查询里的 \n 必须先处理掉，否则一条变多条
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return
        synchronized(rmwLock) {
            val list = getSearchHistory().toMutableList()
            list.remove(q)
            list.add(0, q)
            put(KEY_SEARCH_HISTORY, list.take(20).joinToString("\n"))
        }
    }

    fun clearSearchHistory() {
        put(KEY_SEARCH_HISTORY, "")
    }

    // ===== 播放/界面设置（1:1 Android 1.22.75）=====
    /** 解码方式: mediacodec-copy(HW+) / mediacodec(HW) / no(SW) / auto */
    var decodeMode: String
        get() = get("decode_mode", "mediacodec-copy")
        set(v) = put("decode_mode", v)

    /** 缓存开关，默认开 */
    var cacheEnabled: Boolean
        get() = prefs.getBoolean("cache_enabled", true)
        set(v) { prefs.putBoolean("cache_enabled", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 缓存大小（GB）：1 / 2 / 3，默认 1 */
    var cacheSizeGb: Int
        get() = prefs.getInt("cache_size_gb", 1).let { if (it in 1..3) it else 1 }
        set(v) { prefs.putInt("cache_size_gb", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕开关，默认开 */
    var danmakuEnabled: Boolean
        get() = prefs.getBoolean("danmaku_enabled", true)
        set(v) { prefs.putBoolean("danmaku_enabled", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕 API 地址（旧单源，保留做迁移） */
    var danmakuApiUrl: String
        get() = get("danmaku_api_url", "")
        set(v) = put("danmaku_api_url", v)

    // ---- 弹幕源：dandanplay 兼容 API 地址列表（1:1 Android），按顺序尝试 ----
    data class DanmakuSource(val url: String, val enabled: Boolean = true)

    /** 预置默认弹幕源（非法 JSON 时的回退，保证弹幕不因配置损坏而全灭，P1-E22） */
    private fun defaultDanmakuSources(): List<DanmakuSource> =
        listOf(DanmakuSource("https://dm.nnn.xx.kg", true))

    var danmakuSources: List<DanmakuSource>
        get() {
            // 纯读 getter（P1-E22）：不做写盘迁移、不产生副作用。
            // 首次（无存储值）按旧单 URL 迁移计算；非法 JSON 返回预置默认值，不再返回空列表。
            val raw = prefs.get("danmaku_sources", null)
            if (raw == null) {
                val old = danmakuApiUrl.trim().trimEnd('/')
                return if (old.isNotBlank()) listOf(DanmakuSource(old, true))
                else defaultDanmakuSources()
            }
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val u = o.optString("url").trim().trimEnd('/')
                    if (u.isBlank()) null else DanmakuSource(u, o.optBoolean("on", true))
                }
            } catch (_: Exception) {
                defaultDanmakuSources()
            }
        }
        set(v) {
            synchronized(rmwLock) {
                val arr = JSONArray()
                v.map { it.url.trim().trimEnd('/') to it.enabled }
                    .filter { (u, _) -> u.isNotBlank() }
                    .distinctBy { (u, _) -> u }
                    .forEach { (u, on) -> arr.put(JSONObject().put("url", u).put("on", on)) }
                put("danmaku_sources", arr.toString())
            }
        }

    fun addDanmakuSource(url: String): Boolean {
        var u = url.trim()
        if (u.isEmpty()) return false
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        u = u.trimEnd('/')
        synchronized(rmwLock) {
            if (danmakuSources.any { it.url == u }) return false
            danmakuSources = danmakuSources + DanmakuSource(u, true)
            return true
        }
    }

    fun removeDanmakuSource(url: String) {
        synchronized(rmwLock) {
            danmakuSources = danmakuSources.filter { it.url != url }
        }
    }

    fun setDanmakuSourceEnabled(url: String, enabled: Boolean) {
        synchronized(rmwLock) {
            danmakuSources = danmakuSources.map { if (it.url == url) it.copy(enabled = enabled) else it }
        }
    }

    /** 已启用的弹幕源地址（按列表顺序） */
    fun enabledDanmakuUrls(): List<String> = danmakuSources.filter { it.enabled }.map { it.url }

    // ---- 弹幕样式（1:1 Android） ----
    /** 弹幕延迟显示（秒），0~10，默认 0 */
    var danmakuDelay: Float
        get() = prefs.getFloat("danmaku_delay", 0f).let { if (it in 0f..10f) it else 0f }
        set(v) { prefs.putFloat("danmaku_delay", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕字体大小（sp），12~36，默认 20 */
    var danmakuFontSize: Int
        get() = prefs.getInt("danmaku_font_size", 20).let { if (it in 12..36) it else 20 }
        set(v) { prefs.putInt("danmaku_font_size", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕描边大小，0~4，默认 0.8 */
    var danmakuStroke: Float
        get() = prefs.getFloat("danmaku_stroke", 0.8f).let { if (it in 0f..4f) it else 0.8f }
        set(v) { prefs.putFloat("danmaku_stroke", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕速度（%），50~200，默认 100 */
    var danmakuSpeed: Int
        get() = prefs.getInt("danmaku_speed", 100).let { if (it in 50..200) it else 100 }
        set(v) { prefs.putInt("danmaku_speed", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕不透明度（%），10~100，默认 100 */
    var danmakuOpacity: Int
        get() = prefs.getInt("danmaku_opacity", 100).let { if (it in 10..100) it else 100 }
        set(v) { prefs.putInt("danmaku_opacity", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕显示区域比例，0.1~1，默认 0.35：即弹幕占用屏幕高度的比例，唯一控制覆盖范围的项 */
    var danmakuArea: Float
        get() = prefs.getFloat("danmaku_area", 0.35f).let { if (it in 0.1f..1f) it else 0.35f }
        set(v) { prefs.putFloat("danmaku_area", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕密度（%）：同行弹幕水平间距的倒数，50~200，默认 100（100px 间距）；越高越密 */
    var danmakuDensity: Int
        get() = prefs.getInt("danmaku_density", 100).let { if (it in 50..200) it else 100 }
        set(v) { prefs.putInt("danmaku_density", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 弹幕间隔（%）：行距为默认行距的百分比，50~200，默认 100；越大行距越大（越疏） */
    var danmakuLineSpacing: Int
        get() = prefs.getInt("danmaku_line_spacing", 100).let { if (it in 50..200) it else 100 }
        set(v) { prefs.putInt("danmaku_line_spacing", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 播放器内永久禁用弹幕（会话开关再次启用时清除） */
    var danmakuDisabled: Boolean
        get() = prefs.getBoolean("danmaku_disabled", false)
        set(v) { prefs.putBoolean("danmaku_disabled", v); try { prefs.flush() } catch (_: Exception) {} }

    /** Dock 样式：0=M玻璃 1=原玻璃，默认0 */
    var dockStyle: Int
        get() = prefs.getInt("dock_style", 0).let { if (it in 0..1) it else 0 }
        set(v) { prefs.putInt("dock_style", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 飞回动画模式：0=严格 1=标准 2=宽松，默认1 */
    var flybackMode: Int
        get() = prefs.getInt("flyback_mode", 1).let { if (it in 0..2) it else 1 }
        set(v) { prefs.putInt("flyback_mode", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 缩放动画速度：1/2/3，默认2 */
    var zoomSpeed: Int
        get() = prefs.getInt("zoom_speed", 2).let { if (it in 1..3) it else 2 }
        set(v) { prefs.putInt("zoom_speed", v); try { prefs.flush() } catch (_: Exception) {} }

    /**
     * 应用锁 PIN（空=未设置）。
     * 落盘做简单混淆（XOR + Base64，前缀 obf1:），避免明文出现在 prefs 文件里（P0-E7）。
     * 注意：混淆不是加密，仅防随手翻看；真正安全需系统级密钥库（如后续接入再升级）。
     * getter 返回解密后的明文（调用方比较逻辑不变）；读到存量明文时就地转为混淆存储（存量迁移）。
     */
    var appLockPin: String
        get() {
            val raw = prefs.get("app_lock_pin", "") ?: ""
            if (raw.isEmpty()) return ""
            if (!raw.startsWith(PIN_OBF_PREFIX)) {
                prefs.put("app_lock_pin", obfuscatePin(raw))
                try { prefs.flush() } catch (_: Exception) {}
                return raw
            }
            return deobfuscatePin(raw)
        }
        set(v) {
            val stored = if (v.isEmpty()) "" else obfuscatePin(v)
            prefs.put("app_lock_pin", stored)
            try { prefs.flush() } catch (_: Exception) {}
        }

    private const val PIN_OBF_PREFIX = "obf1:"
    private val pinObfKey: ByteArray get() = "GomePcPinObf".toByteArray(Charsets.UTF_8)

    private fun obfuscatePin(pin: String): String {
        val key = pinObfKey
        val data = pin.toByteArray(Charsets.UTF_8)
        val out = ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return PIN_OBF_PREFIX + java.util.Base64.getEncoder().encodeToString(out)
    }

    private fun deobfuscatePin(stored: String): String {
        return try {
            val key = pinObfKey
            val data = java.util.Base64.getDecoder().decode(stored.removePrefix(PIN_OBF_PREFIX))
            String(ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /** 搜索页选中的服务器 key（空=聚合搜索） */
    var searchServerKey: String
        get() = prefs.get("search_server_key", "")
        set(v) { prefs.put("search_server_key", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 搜索页选中的服务器显示名 */
    var searchServerName: String
        get() = prefs.get("search_server_name", "聚合搜索")
        set(v) { prefs.put("search_server_name", v); try { prefs.flush() } catch (_: Exception) {} }

    /** 服务器自定义图标 URL（手动选择），按 serverKey 存 */
    fun getCustomIconUrl(serverKey: String): String =
        prefs.get("server_icon_$serverKey", "")

    fun setCustomIconUrl(serverKey: String, url: String) {
        prefs.put("server_icon_$serverKey", url)
        try { prefs.flush() } catch (_: Exception) {}
    }

    /** 用户在"编辑服务器"里手动改的显示名（按服务器 key 存），优先于服务器上报的名 */
    fun getCustomServerName(key: String): String =
        prefs.get("custom_name_$key", "")

    fun setCustomServerName(key: String, name: String) {
        prefs.put("custom_name_$key", name)
        try { prefs.flush() } catch (_: Exception) {}
    }

    /** key 变化时迁移自定义名（改地址时调用），读-改-写加锁（P1-E18） */
    fun transferCustomServerName(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        synchronized(rmwLock) {
            val n = getCustomServerName(oldKey)
            if (n.isNotEmpty()) {
                setCustomServerName(newKey, n)
                setCustomServerName(oldKey, "")
            }
        }
    }

    /** 生效的显示名：用户手改优先，否则用服务器上报名 */
    fun effectiveServerName(key: String, reported: String): String =
        getCustomServerName(key).ifEmpty { reported }
}
