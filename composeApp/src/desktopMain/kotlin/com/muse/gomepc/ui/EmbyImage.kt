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
 * 用 HttpURLConnection 拉取，认证走 X-Emby-Authorization 头（imageUrl 不再带 Token 查询参数，
 * Token 进 query 会进缓存 key/日志/代理记录）；内存 LRU 缓存（按条数 + 总字节双上限）+ 磁盘缓存。
 * 不引入 Coil/Kamel（避免新依赖的仓库解析问题）。
 * 代理用系统默认（不强制 NO_PROXY，尊重系统代理/VPN，与其它请求统一）。
 */
object EmbyImageLoader {

    private const val MAX_CACHE = 120
    /** 内存缓存总字节上限 64MB：之前只按条数（120 条）限，大图能占数百 MB */
    private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    private const val MAX_BYTES = 20 * 1024 * 1024
    /** 磁盘缓存：~/.gome/img_cache，单个文件 7 天过期，总量超 200MB 时删最旧的 */
    private const val DISK_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val DISK_MAX_BYTES = 200L * 1024 * 1024
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            return size > MAX_CACHE
        }
    }
    private val lock = Any()
    /** 内存缓存当前总字节（width*height*4 估算） */
    private var cacheBytes = 0L

    fun getCached(url: String): ImageBitmap? = synchronized(lock) { cache[url] }

    private fun putCached(key: String, bmp: ImageBitmap) {
        synchronized(lock) {
            val bytes = (bmp.width.toLong() * bmp.height * 4).coerceAtLeast(0L)
            // 同 key 覆盖时先扣旧字节
            cache[key]?.let { cacheBytes -= (it.width.toLong() * it.height * 4).coerceAtLeast(0L) }
            cache[key] = bmp
            cacheBytes += bytes
            // 按总字节淘汰最旧条目，直到回到上限
            while (cacheBytes > MAX_CACHE_BYTES && cache.isNotEmpty()) {
                val eldest = cache.entries.first()
                cacheBytes -= (eldest.value.width.toLong() * eldest.value.height * 4).coerceAtLeast(0L)
                cache.remove(eldest.key)
            }
        }
    }

    private fun diskDir(): java.io.File {
        val home = System.getProperty("user.home") ?: "."
        return java.io.File(home, ".gome/img_cache").apply { mkdirs() }
    }

    private fun diskFileFor(url: String): java.io.File {
        // 文件名用 URL 的 SHA-256（URL 已不含 Token，可直接哈希）
        val hash = sha256Hex(url.toByteArray(Charsets.UTF_8))
        return java.io.File(diskDir(), hash)
    }

    private fun loadFromDisk(url: String): ByteArray? {
        return try {
            val f = diskFileFor(url)
            if (!f.exists()) return null
            if (System.currentTimeMillis() - f.lastModified() > DISK_MAX_AGE_MS) {
                try { f.delete() } catch (_: Exception) { }
                return null
            }
            f.readBytes()
        } catch (_: Exception) { null }
    }

    private fun saveToDisk(url: String, bytes: ByteArray) {
        try {
            val dir = diskDir()
            val f = diskFileFor(url)
            f.writeBytes(bytes)
            // 总量超限：删最旧文件直到回到上限
            var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
            if (total > DISK_MAX_BYTES) {
                dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
                    if (total <= DISK_MAX_BYTES) return@forEach
                    total -= file.length()
                    try { file.delete() } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        getCached(url)?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                // 先查磁盘缓存：命中则不发网络请求
                val diskBytes = loadFromDisk(url)
                val bytesFromDisk = diskBytes?.takeIf { it.isNotEmpty() }?.let { raw ->
                    try {
                        SkiaImage.makeFromEncoded(raw).toComposeImageBitmap()
                    } catch (_: Exception) { null }
                }
                if (bytesFromDisk != null) {
                    putCached(url, bytesFromDisk)
                    return@withContext bytesFromDisk
                }
                // 系统默认代理（不强制 NO_PROXY）
                val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 20000
                    // 认证走 Header：URL 里不再带 Token
                    setRequestProperty(
                        "X-Emby-Authorization",
                        com.muse.gomepc.emby.YambyClient.authHeader()
                    )
                }
                try {
                    if (c.responseCode !in 200..299) {
                        // 消费错误流，否则连接无法复用
                        try { c.errorStream?.use { it.readAllBytes() } } catch (_: Exception) { }
                        return@withContext null
                    }
                    val bytes = c.inputStream.use { readUpTo(it, MAX_BYTES) } ?: return@withContext null
                    if (bytes.isEmpty()) return@withContext null
                    // 下载成功写磁盘缓存
                    saveToDisk(url, bytes)
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

    fun clear() = synchronized(lock) {
        cache.clear()
        cacheBytes = 0L
        try { diskDir().deleteRecursively() } catch (_: Exception) { }
    }
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
    // 请求序号：url 变化时旧加载可能仍在 IO 阻塞读，返回后用序号校验丢弃过期结果
    val loadSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    LaunchedEffect(url, fallbackUrl) {
        val mySeq = loadSeq.incrementAndGet()
        fun isCurrent() = loadSeq.get() == mySeq
        if (url.isNullOrBlank()) {
            return@LaunchedEffect
        }
        if (bitmap != null) return@LaunchedEffect
        var bmp = EmbyImageLoader.load(url)
        if (!isCurrent()) return@LaunchedEffect
        if (bmp == null && !fallbackUrl.isNullOrBlank()) {
            bmp = EmbyImageLoader.load(fallbackUrl)
            if (!isCurrent()) return@LaunchedEffect
        }
        if (bmp != null) bitmap = bmp
        // 失败时 bitmap 保持 null，显示 fallback（之前 failed 字段只写不读，是死状态）
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
