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
        .build()

    @Volatile private var iconMap: Map<String, String>? = null

    private fun iconDir(): File {
        val dir = File(System.getProperty("user.home"), ".gome/server_icons")
        dir.mkdirs()
        return dir
    }

    private fun cacheFileFor(serverName: String): File {
        val safe = serverName.replace(Regex("[^a-zA-Z0-9\u4e00-\u9fa5]"), "_")
            .take(32).ifEmpty { "default" }
        return File(iconDir(), "$safe.png")
    }

    private fun cacheFileForManual(key: String): File {
        val safe = key.replace(Regex("[^a-zA-Z0-9]"), "_").take(32)
        return File(iconDir(), "manual_$safe.png")
    }

    /** 拉取图标库 name->url，失败返回空 map */
    suspend fun ensureMap(): Map<String, String> {
        iconMap?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(ICON_JSON_URL)
                    .header("User-Agent", "Yamby/2.1.0.11")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyMap<String, String>()
                    val text = resp.body?.string() ?: return@withContext emptyMap<String, String>()
                    val arr = try {
                        org.json.JSONObject(text).getJSONArray("icons")
                    } catch (_: Exception) {
                        // 兼容顶层就是数组的格式
                        try { JSONArray(text) } catch (_: Exception) { return@withContext emptyMap<String, String>() }
                    }
                    val map = mutableMapOf<String, String>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = o.optString("name", "").trim()
                        val url = o.optString("url", "").trim()
                        if (name.isNotEmpty() && url.isNotEmpty()) {
                            map[name.lowercase()] = url
                        }
                    }
                    iconMap = map
                    map
                }
            } catch (_: Exception) {
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
        for ((name, url) in map) {
            val n = norm(name)
            if (n.isNotEmpty() && (n.contains(target) || target.contains(n))) return url
        }
        return null
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Yamby/2.1.0.11")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.bytes()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        return try {
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadFileCached(url: String, cached: File): ImageBitmap? {
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
     * 解析服务器图标。优先级：手动指定 > 自动匹配 > null（调用方显示默认占位）。
     */
    suspend fun resolveIcon(serverName: String, serverKey: String?): ImageBitmap? {
        // 1. 手动指定的图标
        if (!serverKey.isNullOrBlank()) {
            val customUrl = try { Prefs.getCustomIconUrl(serverKey) } catch (_: Exception) { "" }
            if (customUrl.isNotEmpty()) {
                val bmp = loadFileCached(customUrl, cacheFileForManual(serverKey))
                if (bmp != null) return bmp
            }
        }
        // 2. 自动匹配
        if (serverName.isBlank()) return null
        val map = ensureMap()
        val url = findIconUrl(serverName, map) ?: return null
        return loadFileCached(url, cacheFileFor(serverName))
    }

    /** 从任意 URL 加载图标（选择器预览用），带磁盘缓存 */
    suspend fun loadUrl(url: String, cacheKey: String): ImageBitmap? {
        if (url.isBlank()) return null
        return loadFileCached(url, cacheFileForManual(cacheKey))
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
