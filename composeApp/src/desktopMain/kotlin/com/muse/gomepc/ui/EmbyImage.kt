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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * Emby 图片加载器（桌面版）。
 * 用 HttpURLConnection 拉取（imageUrl 已带 token 查询参数，无需额外 header），
 * ImageIO/Skia 解码，内存 LRU 缓存。
 * 不引入 Coil/Kamel（避免新依赖的仓库解析问题）。
 * 注意：不用 OkHttp——部分服务器上 OkHttp 会 unexpected end of stream。
 */
object EmbyImageLoader {

    private const val MAX_CACHE = 120
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            return size > MAX_CACHE
        }
    }
    private val lock = Any()

    fun getCached(url: String): ImageBitmap? = synchronized(lock) { cache[url] }

    private fun putCached(url: String, bmp: ImageBitmap) = synchronized(lock) { cache[url] = bmp }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        getCached(url)?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                // 用 HttpURLConnection（OkHttp 在部分服务器上 unexpected end of stream）
                val c = (java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 20000
                }
                try {
                    if (c.responseCode !in 200..299) return@withContext null
                    val bytes = c.inputStream.readBytes()
                    if (bytes.isEmpty()) return@withContext null
                    val bmp = try {
                        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                    } catch (_: Exception) { null }
                    if (bmp != null) putCached(url, bmp)
                    bmp
                } finally {
                    c.disconnect()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() = synchronized(lock) { cache.clear() }
}

/**
 * 带缓存的图片组件。url 为空或加载失败时显示 [fallback]。
 * [fallbackUrl]：主 url 加载失败（404 等）时尝试的兜底图 URL（1:1 安卓 Glide .thumbnail 语义，
 * 例如详情页头图先试 Backdrop、没有则用 Primary 海报），都失败才显示 [fallback]。
 */
@Composable
fun EmbyImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackUrl: String? = null,
    fallback: @Composable () -> Unit = {}
) {
    var bitmap by remember(url, fallbackUrl) { mutableStateOf(EmbyImageLoader.getCached(url ?: "")) }
    var failed by remember(url, fallbackUrl) { mutableStateOf(false) }

    LaunchedEffect(url, fallbackUrl) {
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        if (bitmap != null) return@LaunchedEffect
        var bmp = EmbyImageLoader.load(url)
        if (bmp == null && !fallbackUrl.isNullOrBlank()) {
            bmp = EmbyImageLoader.load(fallbackUrl)
        }
        if (bmp != null) bitmap = bmp else failed = true
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        // 加载中也显示 fallback（占位），避免空白闪烁由调用方决定
        Box(modifier) { fallback() }
    }
}
