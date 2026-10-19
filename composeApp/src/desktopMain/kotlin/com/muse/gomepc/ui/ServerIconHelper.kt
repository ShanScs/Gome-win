package com.muse.gomepc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.muse.gomepc.emby.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 服务器图标（桌面版，对齐安卓 ServerIconHelper）。
 * 图标库：https://raw.githubusercontent.com/lige47/lige_icon/main/lige-emby-icon.json
 * 优先级：手动指定 > 自动匹配 > 默认占位（调用方 fallback）。
 */
object ServerIconHelper {
    private const val ICON_JSON_URL =
        "https://raw.githubusercontent.com/lige47/lige_icon/main/lige-emby-icon.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // 注意：不要强制 NO_PROXY，让系统代理生效（国内用户可能需要代理访问 GitHub）
        .build()

    /** 单个图标下载上限 5MB */
    private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024

    @Volatile private var iconMap: Map<String, String>? = null
    // 图标库拉取失败的负缓存：60 秒内不再重复请求，避免每次解析图标都打一次网络
    @Volatile private var iconMapFailedAt: Long = 0L

    private fun iconDir(): File {
        val dir = File(System.getProperty("user.home"), ".gome/server_icons")
        dir.mkdirs()
        return dir
    }

    private fun sha256Hex(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 磁盘缓存文件：按图标 URL 的 SHA-256 命名。旧的"服务器名截断 32 字符"会让不同图标
     * 撞到同一个文件（选择器全部预览图相同）；按 URL 哈希则一 URL 一文件，永不冲突。
     */
    private fun cacheFileForUrl(url: String): File =
        File(iconDir(), "url_${sha256Hex(url)}.png")

    /** 拉取图标库 name->url，失败返回空 map（60 秒内负缓存，不重复请求） */
    suspend fun ensureMap(): Map<String, String> {
        iconMap?.let { return it }
        if (System.currentTimeMillis() - iconMapFailedAt < 60_000) return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(ICON_JSON_URL)
                    .header("User-Agent", "GomePC")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        iconMapFailedAt = System.currentTimeMillis()
                        return@withContext emptyMap<String, String>()
                    }
                    val text = resp.body?.string() ?: run {
                        iconMapFailedAt = System.currentTimeMillis()
                        return@withContext emptyMap<String, String>()
                    }
                    val arr = try {
                        org.json.JSONObject(text).getJSONArray("icons")
                    } catch (_: Exception) {
                        // 兼容顶层就是数组的格式
                        try { JSONArray(text) } catch (_: Exception) {
                            iconMapFailedAt = System.currentTimeMillis()
                            return@withContext emptyMap<String, String>()
                        }
                    }
                    val map = mutableMapOf<String, String>()
                    for (i in 0 until arr.length()) {
                        // 逐条容错：一条脏数据不拖垮整个图标库
                        try {
                            val o = arr.getJSONObject(i)
                            val name = o.optString("name", "").trim()
                            val url = o.optString("url", "").trim()
                            if (name.isNotEmpty() && url.isNotEmpty()) {
                                map[name.lowercase()] = url
                            }
                        } catch (_: Exception) { continue }
                    }
                    iconMap = map
                    map
                }
            } catch (_: Exception) {
                iconMapFailedAt = System.currentTimeMillis()
                emptyMap()
            }
        }
    }

    /** 图标库列表（name -> url），用于手动选择器 */
    suspend fun getIconList(): List<Pair<String, String>> {
        val map = ensureMap()
        return map.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    /** 规范化名称用于匹配：小写，去掉 -emby 后缀和非字母数字 */
    private fun norm(s: String): String {
        var r = s.lowercase().trim()
        if (r.endsWith("-emby")) r = r.removeSuffix("-emby")
        return r.replace(Regex("[^a-z0-9\u4e00-\u9fa5]"), "")
    }

    /** 按服务器名找最匹配的图标 URL，找不到返回 null */
    private fun findIconUrl(serverName: String, map: Map<String, String>): String? {
        if (map.isEmpty() || serverName.isBlank()) return null
        val target = norm(serverName)
        if (target.isEmpty()) return null
        for ((name, url) in map) {
            if (norm(name) == target) return url
        }
        // 子串匹配必须有长度保护：短名（如 "a"、"tv"）会被几乎所有名字包含而误匹配
        if (target.length < 3) return null
        for ((name, url) in map) {
            val n = norm(name)
            if (n.length >= 3 && (n.contains(target) || target.contains(n))) return url
        }
        return null
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "GomePC")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val stream = resp.body?.byteStream() ?: return@withContext null
                    stream.use { readUpTo(it, MAX_DOWNLOAD_BYTES) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 带上限读取：超限返回 null（防超大文件撑爆内存） */
    private fun readUpTo(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        return try {
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadFileCached(url: String): ImageBitmap? {
        val cached = cacheFileForUrl(url)
        if (cached.exists()) {
            try {
                val bmp = withContext(Dispatchers.IO) { decode(cached.readBytes()) }
                if (bmp != null) return bmp
                cached.delete()
            } catch (_: Exception) {
                try { cached.delete() } catch (_: Exception) {}
            }
        }
        val bytes = downloadBytes(url) ?: return null
        val bmp = decode(bytes) ?: return null
        try {
            withContext(Dispatchers.IO) { cached.writeBytes(bytes) }
        } catch (_: Exception) {}
        return bmp
    }

    /**
     * 解析服务器图标。优先级：手动指定 > 自动匹配 > 图标库默认（emby 官方 logo）> null。
     * 注意：服务器名在库里无匹配时（如名字含库里没有的字）也要用库里的默认图标，
     * 绝不要让调用方兜底显示服务器名首字（如“垃”）或手绘假图标。
     */
    suspend fun resolveIcon(serverName: String, serverKey: String?): ImageBitmap? {
        // 1. 手动指定的图标（serverKey 为空/非法时跳过，不用旧 key 的缓存）
        if (!serverKey.isNullOrBlank() && isValidServerKey(serverKey)) {
            val customUrl = try { Prefs.getCustomIconUrl(serverKey) } catch (_: Exception) { "" }
            if (customUrl.isNotEmpty()) {
                val bmp = loadFileCached(customUrl)
                if (bmp != null) return bmp
            }
        }
        val map = ensureMap()
        // 2. 自动匹配（按服务器名在图标库里找）
        if (serverName.isNotBlank()) {
            val url = findIconUrl(serverName, map)
            if (url != null) {
                val bmp = loadFileCached(url)
                if (bmp != null) return bmp
            }
        }
        // 3. 图标库默认图标：库里第一个 emby 条目（官方 Emby logo）。
        // 无匹配时用它，不返回 null，不让调用方显示首字兜底。
        val defaultUrl = map["emby"]
        if (!defaultUrl.isNullOrBlank()) {
            val bmp = loadFileCached(defaultUrl)
            if (bmp != null) return bmp
        }
        return null
    }

    /** serverKey 形如 host:port/path/username（path 可为空）；含 .. 或首尾段为空视为非法，不用于查缓存 */
    private fun isValidServerKey(key: String): Boolean {
        if (".." in key) return false
        val parts = key.split("/")
        return parts.size == 3 && parts[0].isNotBlank() && parts[2].isNotBlank()
    }

    /** 从任意 URL 加载图标（选择器预览用），带磁盘缓存 */
    suspend fun loadUrl(url: String, cacheKey: String): ImageBitmap? {
        if (url.isBlank()) return null
        // 缓存文件按 URL 哈希命名（cacheKey 仅保留兼容，不再用于命名，避免截断冲突）
        return loadFileCached(url)
    }
}

/**
 * 服务器图标组件：有自定义/自动匹配图标时显示图片，否则显示 [fallback]。
 */
@Composable
fun ServerIcon(
    serverName: String,
    serverKey: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable () -> Unit = {}
) {
    var bitmap by remember(serverName, serverKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(serverName, serverKey) {
        // 切服务器时先置 null，避免旧服务器图标残留到新图标加载完成
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            ServerIconHelper.resolveIcon(serverName, serverKey)
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "服务器图标",
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(modifier = modifier) { fallback() }
    }
}
