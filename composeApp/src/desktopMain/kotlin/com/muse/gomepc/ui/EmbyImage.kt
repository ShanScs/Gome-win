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
import java.security.MessageDigest

/**
 * Emby 图片加载器（桌面版）。
 * 用 HttpURLConnection 拉取（imageUrl 已带 token 查询参数，无需额外 header），
 * ImageIO/Skia 解码，内存 LRU 缓存。
 * 不引入 Coil/Kamel（避免新依赖的仓库解析问题）。
 * 代理用系统默认（不强制 NO_PROXY，尊重系统代理/VPN，与其它请求统一）。
 */
object EmbyImageLoader {

    private const val MAX_CACHE = 120
    private const val MAX_BYTES = 20 * 1024 * 1024
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            return size > MAX_CACHE
        }
    }
    private val lock = Any()

    fun getCached(url: String): ImageBitmap? = synchronized(lock) { cache[url] }

    private fun putCached(key: String, bmp: ImageBitmap) = synchronized(lock) { cache[key] = bmp }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        getCached(url)?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                // 系统默认代理（不强制 NO_PROXY）
                val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 20000
                }
                try {
                    if (c.responseCode !in 200..299) {
                        // 消费错误流，否则连接无法复用
                        try { c.errorStream?.use { it.readAllBytes() } } catch (_: Exception) { }
                        return@withContext null
                    }
                    val bytes = c.inputStream.use { readUpTo(it, MAX_BYTES) } ?: return@withContext null
                    if (bytes.isEmpty()) return@withContext null
                    // 按字节缓存：同一张图换了 URL（token/尺寸参数不同）时命中内容哈希，不重复解码
                    val hashKey = "sha256:${sha256Hex(bytes)}"
                    getCached(hashKey)?.let {
                        putCached(url, it)
                        return@withContext it
                    }
                    val bmp = try {
                        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                    } catch (_: Exception) { null }
                    if (bmp != null) {
                        putCached(url, bmp)
                        putCached(hashKey, bmp)
                    }
                    bmp
                } finally {
                    c.disconnect()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 带上限读取：超限返回 null（防超大图片撑爆内存） */
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
    // 请求序号：url 变化时旧加载可能仍在 IO 阻塞读，返回后用序号校验丢弃过期结果
    val loadSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    LaunchedEffect(url, fallbackUrl) {
        val mySeq = loadSeq.incrementAndGet()
        fun isCurrent() = loadSeq.get() == mySeq
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        if (bitmap != null) return@LaunchedEffect
        var bmp = EmbyImageLoader.load(url)
        if (!isCurrent()) return@LaunchedEffect
        if (bmp == null && !fallbackUrl.isNullOrBlank()) {
            bmp = EmbyImageLoader.load(fallbackUrl)
            if (!isCurrent()) return@LaunchedEffect
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
