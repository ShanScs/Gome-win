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

    // 海报主色相缓存（渐变主题用）：URL → 主色相（度，0..360）；NaN 表示该图是黑白灰、没有主色
    private val hueCache = mutableMapOf<String, Float>()

    fun getHue(url: String): Float? = synchronized(lock) { hueCache[url]?.takeUnless { it.isNaN() } }

    /** 海报主色相（渐变主题用）：加载图后取色并缓存；拿不到或图是黑白灰返回 null。 */
    suspend fun loadHue(url: String): Float? {
        if (url.isBlank()) return null
        synchronized(lock) { hueCache[url] }?.let { return if (it.isNaN()) null else it }
        val bmp = load(url) ?: return null
        val h = withContext(Dispatchers.IO) { posterHue(bmp) }
        val kind = when {
            url.contains("Thumb") -> "Thumb"
            url.contains("Backdrop") -> "Backdrop"
            url.contains("Primary") -> "Primary"
            else -> "?"
        }
        com.muse.gomepc.player.DebugLog.d("Hue", "取色 $kind ${bmp.width}x${bmp.height} -> ${h?.let { "%.1f".format(it) } ?: "无色"}")
        synchronized(lock) { hueCache[url] = h ?: Float.NaN }
        return h
    }

    /**
     * 候选链里第一张展示图的主色（跟展示用的图一致：竖图在 landscapeOnly 时跳过；
     * 灰图返回 null，不再往后找——展示的就是这张灰图，背景也该跟它走）。
     * 图加载失败才往下一张找。
     */
    suspend fun firstHue(urls: List<String>, landscapeOnly: Boolean = false): Float? {
        for (u in urls) {
            val bmp = load(u) ?: continue
            if (landscapeOnly && bmp.height > bmp.width) continue
            return loadHue(u)
        }
        return null
    }

    /**
     * 海报主色相：先取底部 20%（背景接在海报底边，底色跟底边走才没有缝）；
     * 底部没颜色再取整张（人脸头发的暖色多在海报中间，底部取不到时靠整张兜底）；
     * 整张也没颜色就是黑白海报，返回 null。
     * 黑白灰不算颜色：过暗（黑与暗阴影，亮度低于 100 的色偏只是阴影的色调）/过亮像素跳过，
     * 饱和度低于 0.25 的带色偏灰像素也跳过；底部那条有效色点太少（少于 24 个）也算没颜色，
     * 免得几个阴影杂点就定了整页的色；整张有效色点少于 8 个才算没颜色。
     * 网格要密：人脸只占海报很小一块，8x8 的稀疏网格可能一个点都落不到脸上，取色就取空了。
     */
    private fun posterHue(bmp: ImageBitmap): Float? {
        return sampleHue(bmp, 0.8f, 1.0f, 24) ?: sampleHue(bmp, 0.0f, 1.0f, 8)
    }

    /** 在 yFrom 到 yTo 的竖条里采样色相：把竖条一次读进内存，在 64x64 网格上取点；没颜色返回 null。
     * 只取颜色最多的那一家：把通过的像素按色相分 12 档（每档 30 度），取像素最多的那一档，
     * 再把这一档里的所有像素按饱和度加权平均出一个色相——不同颜色的平均会造出海报里没有的颜色，
     * 比如暖肤色和冷阴影平均出紫色，所以只取最多的一家。
     * 整块读不到就退回小块采样（398 的读法，已验证可用），免得一次读失败就整张取空。 */
    private fun sampleHue(bmp: ImageBitmap, yFrom: Float, yTo: Float, minCount: Int): Float? {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 32) return null
        val stripTop = (h * yFrom).toInt()
        val stripH = (h * (yTo - yFrom)).toInt().coerceAtLeast(1)
        val bucketCount = IntArray(12)
        val bucketSx = DoubleArray(12)
        val bucketSy = DoubleArray(12)
        val bucketWsum = DoubleArray(12)
        fun accept(r: Int, g: Int, b: Int) {
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (mx < 100 || mn > 235) return
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            if (sat < 0.25f) return
            val hue = rgbHue(r, g, b)
            val bucket = ((hue / 30f).toInt()).coerceIn(0, 11)
            val rad = Math.toRadians(hue.toDouble())
            val wt = sat.toDouble()
            bucketCount[bucket]++
            bucketSx[bucket] += Math.cos(rad) * wt
            bucketSy[bucket] += Math.sin(rad) * wt
            bucketWsum[bucket] += wt
        }
        val cols = 64
        val rows = 64
        val buf = IntArray(w * stripH)
        val bulkOk = try {
            bmp.readPixels(buf, 0, stripTop, w, stripH, 0, w)
            true
        } catch (_: Exception) {
            false
        }
        if (bulkOk) {
            for (gy in 0 until rows) {
                val y = ((gy + 0.5f) * stripH / rows).toInt().coerceIn(0, stripH - 1)
                for (gx in 0 until cols) {
                    val x = ((gx + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
                    val px = buf[y * w + x]
                    accept((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
                }
            }
        } else {
            com.muse.gomepc.player.DebugLog.d("Hue", "整块读失败，退回小块采样 yFrom=$yFrom")
            val patch = 4
            val pbuf = IntArray(patch * patch)
            for (gy in 0 until rows) {
                for (gx in 0 until cols) {
                    val cx = ((gx + 0.5f) * w / cols).toInt().coerceIn(patch / 2, w - patch / 2 - 1)
                    val cy = (stripTop + ((gy + 0.5f) * stripH / rows).toInt()).coerceIn(stripTop + patch / 2, stripTop + stripH - patch / 2 - 1)
                    try {
                        bmp.readPixels(pbuf, cx - patch / 2, cy - patch / 2, patch, patch, 0, patch)
                    } catch (_: Exception) { continue }
                    for (px in pbuf) {
                        accept((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
                    }
                }
            }
        }
        var best = -1
        var bestCount = 0
        for (i in 0 until 12) {
            if (bucketCount[i] > bestCount) {
                bestCount = bucketCount[i]
                best = i
            }
        }
        if (best < 0 || bestCount < minCount || bucketWsum[best] < 2.0) {
            val ws = if (best >= 0) bucketWsum[best] else 0.0
            com.muse.gomepc.player.DebugLog.d("Hue", "取色空 yFrom=$yFrom 最多档=$best 点数=$bestCount 权重=${"%.1f".format(ws)}")
            return null
        }
        var hue = Math.toDegrees(Math.atan2(bucketSy[best], bucketSx[best])).toFloat()
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
