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

    // 加载失败日志节流：最多 5 秒记一行（带被压掉的计数），否则首页一片图失败就刷屏。
    // 之前所有失败都静默返回 null，日志里查不到图片为什么出不来。
    @Volatile private var lastFailLogMs = 0L
    private val suppressedFails = java.util.concurrent.atomic.AtomicInteger(0)

    private fun logLoadFail(url: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailLogMs >= 5000) {
            lastFailLogMs = now
            val sup = suppressedFails.getAndSet(0)
            com.muse.gomepc.player.DebugLog.d("Image", "加载失败($reason): $url" + if (sup > 0) "（另有 $sup 条失败未记）" else "")
        } else {
            suppressedFails.incrementAndGet()
        }
    }

    fun getCached(url: String): ImageBitmap? = synchronized(lock) { cache[url] }

    private fun putCached(key: String, bmp: ImageBitmap) = synchronized(lock) { cache[key] = bmp }

    // 海报主色相缓存（渐变主题用）：URL → 主色相（度，0..360）
    private val hueCache = mutableMapOf<String, Float>()

    fun getHue(url: String): Float? = synchronized(lock) { hueCache[url] }

    /** 海报底部主色相（渐变主题用）：加载图后从海报底部取色相并缓存；拿不到返回 null。 */
    suspend fun loadHue(url: String): Float? {
        if (url.isBlank()) return null
        synchronized(lock) { hueCache[url] }?.let { return it }
        val bmp = load(url) ?: return null
        val h = posterBottomHue(bmp)
        synchronized(lock) { hueCache[url] = h }
        return h
    }

    /** 候选链里第一张能拿到底部主色的（详情头图/轮播横幅用）。 */
    suspend fun firstHue(urls: List<String>): Float? {
        for (u in urls) {
            val h = loadHue(u)
            if (h != null) return h
        }
        return null
    }

    /**
     * 海报底部主色相：只取海报底部 20% 的色（8 列 x 2 行小块采样，共 64 像素），
     * 跳过近灰/过暗/过亮像素，按饱和度加权环形平均色相（防 0/360 跳变）；
     * 无彩色像素时回默认 210（蓝）。背景就接在海报下面，底色跟底边走才没有缝，
     * 取整张图的平均色会把海报上半部分的色带进来，背景就跟底边对不上。
     */
    private fun posterBottomHue(bmp: ImageBitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 32) return 210f
        val patch = 4
        val buf = IntArray(patch * patch)
        var sx = 0.0
        var sy = 0.0
        var wsum = 0.0
        val stripTop = (h * 0.8f).toInt()
        val stripH = h - stripTop
        for (gy in 0 until 2) {
            for (gx in 0 until 8) {
                val cx = ((gx + 0.5f) * w / 8).toInt().coerceIn(patch / 2, w - patch / 2 - 1)
                val cy = (stripTop + ((gy + 0.5f) * stripH / 2).toInt()).coerceIn(stripTop + patch / 2, h - patch / 2 - 1)
                try {
                    bmp.readPixels(buf, cx - patch / 2, cy - patch / 2, patch, patch, 0, patch)
                } catch (_: Exception) { continue }
                for (px in buf) {
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    val mx = maxOf(r, g, b)
                    val mn = minOf(r, g, b)
                    if (mx < 24 || mn > 235) continue
                    val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
                    if (sat < 0.15f) continue
                    val hue = rgbHue(r, g, b)
                    val rad = Math.toRadians(hue.toDouble())
                    val wt = sat.toDouble()
                    sx += Math.cos(rad) * wt
                    sy += Math.sin(rad) * wt
                    wsum += wt
                }
            }
        }
        if (wsum <= 0.0) return 210f
        var hue = Math.toDegrees(Math.atan2(sy, sx)).toFloat()
        if (hue < 0f) hue += 360f
        return hue
    }

    /** RGB → 色相（度，0..360），标准公式。 */
    private fun rgbHue(r: Int, g: Int, b: Int): Float {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (d == 0) return 0f
        val h = if (mx == r) {
            var t = (g - b).toFloat() / d
            if (t < 0) t += 6f
            t
        } else if (mx == g) {
            (b - r).toFloat() / d + 2f
        } else {
            (r - g).toFloat() / d + 4f
        }
        return h * 60f
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
                // 系统默认代理（不强制 NO_PROXY）
                val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 20000
                }
                try {
                    if (c.responseCode !in 200..299) {
                        logLoadFail(url, "HTTP ${c.responseCode}")
                        // 消费错误流，否则连接无法复用
                        try { c.errorStream?.use { it.readAllBytes() } } catch (_: Exception) { }
                        return@withContext null
                    }
                    val bytes = c.inputStream.use { readUpTo(it, MAX_BYTES) } ?: run {
                        logLoadFail(url, "读取失败或超限")
                        return@withContext null
                    }
                    if (bytes.isEmpty()) {
                        logLoadFail(url, "空响应")
                        return@withContext null
                    }
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
                    } else {
                        logLoadFail(url, "解码失败 ${bytes.size}B")
                    }
                    bmp
                } finally {
                    c.disconnect()
                }
            }
        } catch (e: Exception) {
            logLoadFail(url, "异常 ${e.javaClass.simpleName}: ${e.message}")
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
 * [fallbackUrls]：fallbackUrl 之后继续尝试的兜底链（按顺序），例如横板海报(Thumb) →
 * 剧照(Backdrop) → 竖版海报(Primary) 的三级兜底。
 */
@Composable
fun EmbyImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackUrl: String? = null,
    fallbackUrls: List<String> = emptyList(),
    // 横图模式（详情头图/首页轮播）：只接受横图（宽>=高）；服务器上 Thumb 存成竖版海报时
    // （如某剧 Thumb 与 Primary 是同一个竖版文件），竖图视为未命中，继续试下一个，
    // 全是竖图时退回第一张成功的图（总比空白好）。
    landscapeOnly: Boolean = false,
    fallback: @Composable () -> Unit = {}
) {
    var bitmap by remember(url, fallbackUrl, fallbackUrls, landscapeOnly) { mutableStateOf(EmbyImageLoader.getCached(url ?: "")) }
    var failed by remember(url, fallbackUrl, fallbackUrls, landscapeOnly) { mutableStateOf(false) }
    // 请求序号：url 变化时旧加载可能仍在 IO 阻塞读，返回后用序号校验丢弃过期结果
    val loadSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    LaunchedEffect(url, fallbackUrl, fallbackUrls, landscapeOnly) {
        val mySeq = loadSeq.incrementAndGet()
        fun isCurrent() = loadSeq.get() == mySeq
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        // 已缓存的主图：横图模式下竖图不算命中，继续走兜底链（否则直接显示缓存）
        val cached = bitmap
        if (cached != null && (!landscapeOnly || cached.width >= cached.height)) return@LaunchedEffect
        if (cached != null) bitmap = null
        // 依次尝试候选链；横图模式下优先横图，竖图跳过；全是竖图时退回第一张成功的图
        var firstOk: ImageBitmap? = null
        var bmp: ImageBitmap? = null
        val candidates = listOfNotNull(url, fallbackUrl) + fallbackUrls
        for (u in candidates) {
            if (u.isBlank()) continue
            val b = EmbyImageLoader.load(u)
            if (!isCurrent()) return@LaunchedEffect
            if (b == null) continue
            if (firstOk == null) firstOk = b
            if (!landscapeOnly || b.width >= b.height) { bmp = b; break }
        }
        if (bmp == null) bmp = firstOk
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
