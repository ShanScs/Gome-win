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

/** 保存的服务器条目（用于服务器切换） */
data class ServerEntry(
    val name: String,
    val protocol: String,
    val host: String,
    val port: String,
    val path: String,
    val username: String,
    val password: String
) {
    /** 唯一键：地址+端口+路径+用户名 */
    fun key(): String = "$host:$port/${path.trim('/')}/$username"
}

object Prefs {
    private val prefs: Preferences = Preferences.userRoot().node("com/muse/gomepc")

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

    /** 持久设备 ID（Yamby 用固定 DeviceId） */
    val deviceId: String
        get() {
            var id = prefs.get("deviceId", null)
            if (id.isNullOrEmpty()) {
                id = UUID.randomUUID().toString()
                put("deviceId", id)
            }
            return id
        }

    /** 桌面端设备名：用主机名，取不到就用 GomePC */
    val deviceName: String
        get() = try {
            java.net.InetAddress.getLocalHost().hostName?.takeIf { it.isNotEmpty() } ?: "GomePC"
        } catch (_: Exception) {
            "GomePC"
        }

    /** 规范化服务器地址：去掉默认端口，用于新旧记录兼容匹配 */
    fun normServerUrl(u: String): String {
        return u.replace(":80/", "/").replace(":80$", "")
            .replace(":443/", "/").replace(":443$", "")
    }

    /** baseUrl 如 http://xmsl.org:80/emby */
    fun baseUrl(): String {
        val p = path.trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
        val proto = protocol.trim().lowercase()
        val portStr = port.trim()
        val portPart = if (portStr.isEmpty()) "" else ":$portStr"
        return "$proto://${host.trim()}$portPart$p"
    }

    fun isLoggedIn(): Boolean = token.isNotEmpty() && userId.isNotEmpty() && host.isNotEmpty()

    fun clearLogin() {
        remove("token")
        remove("userId")
    }

    // ---- 多服务器 ----
    private const val KEY_SERVERS = "servers_json"

    /** 读取保存的服务器列表 */
    fun getServers(): List<ServerEntry> {
        val raw = get(KEY_SERVERS, "")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServerEntry(
                    name = o.optString("name"),
                    protocol = o.optString("protocol", "http"),
                    host = o.optString("host"),
                    port = o.optString("port", "80"),
                    path = o.optString("path", ""),
                    username = o.optString("username"),
                    password = o.optString("password")
                )
            }.filter { it.host.isNotEmpty() }
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
                    .put("password", s.password)
            )
        }
        put(KEY_SERVERS, arr.toString())
    }

    /** 新增或更新服务器（按 key 去重） */
    fun upsertServer(entry: ServerEntry) {
        val list = getServers().toMutableList()
        val idx = list.indexOfFirst { it.key() == entry.key() }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        saveServers(list)
    }

    /** 当前正在用的服务器 key */
    fun currentServerKey(): String =
        "${host.trim()}:${port.trim().ifEmpty { "80" }}/${path.trim().trim('/')}/${username.trim()}"

    /** 更新当前服务器条目的显示名 */
    fun updateCurrentServerName(name: String) {
        val key = currentServerKey()
        val list = getServers().toMutableList()
        val idx = list.indexOfFirst { it.key() == key }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name)
            saveServers(list)
        }
    }

    /** 把当前 Prefs 里的服务器记入列表（登录成功后调用） */
    fun rememberCurrentServer(password: String) {
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
                password = password
            )
        )
    }

    /** 删除保存的服务器（含统计缓存） */
    fun removeServer(key: String) {
        saveServers(getServers().filter { it.key() != key })
        val stats = getServerStats().toMutableMap()
        stats.remove(key)
        saveServerStats(stats)
    }

    /** 编辑服务器导致 key 变化时，迁移统计缓存 */
    fun transferServerStat(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val m = getServerStats().toMutableMap()
        val s = m.remove(oldKey) ?: return
        m[newKey] = s
        saveServerStats(m)
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
        val m = getServerStats().toMutableMap()
        m[key] = stat
        saveServerStats(m)
    }

    /** 更新某服务器的上次使用时间（同步，登录成功后立即调用） */
    fun touchServerLastUsed(key: String) {
        val cur = getServerStats()[key] ?: ServerStat()
        putServerStat(key, cur.copy(lastUsed = System.currentTimeMillis()))
    }

    /**
     * 登录/切换成功后调用：先同步记下 lastUsed，再后台拉取影片数/剧集数缓存。
     * 不阻塞调用方。
     */
    fun recordServerVisit(scope: CoroutineScope) {
        val key = currentServerKey()
        touchServerLastUsed(key)
        scope.launch(Dispatchers.IO) {
            try {
                val movies = YambyClient.getTotalCount("Movie")
                val series = YambyClient.getTotalCount("Series")
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
        val q = query.trim()
        if (q.isEmpty()) return
        val list = getSearchHistory().toMutableList()
        list.remove(q)
        list.add(0, q)
        put(KEY_SEARCH_HISTORY, list.take(20).joinToString("\n"))
    }

    fun clearSearchHistory() {
        put(KEY_SEARCH_HISTORY, "")
    }
}
