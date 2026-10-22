package com.muse.gomepc.danmaku

import java.awt.FontMetrics
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 弹幕内联表情支持（B 站官方「小黄脸」包）。
 * 弹幕源把表情弹幕拍平成 [名称] 文本且不带图片字段，这里按内置映射（DanmakuEmoteData）
 * 把命中的标记切成“表情段”画成图片；映射外的方括号文本一律按原文显示，不猜图。
 */
object DanmakuEmotes {

    private val tokenRe = Regex("\\[[^\\[\\]]{1,16}\\]")

    sealed class Seg {
        data class Txt(val s: String) : Seg()
        data class Emo(val token: String) : Seg()
    }

    fun hasEmote(text: String): Boolean {
        if (!text.contains('[')) return false
        return tokenRe.findAll(text).any { DanmakuEmoteData.URLS.containsKey(it.value) }
    }

    /** 把文本切成 文本段 / 表情段（仅命中内置映射的标记才是表情段）。 */
    fun split(text: String): List<Seg> {
        if (!text.contains('[')) return listOf(Seg.Txt(text))
        val out = ArrayList<Seg>()
        var last = 0
        for (m in tokenRe.findAll(text)) {
            if (!DanmakuEmoteData.URLS.containsKey(m.value)) continue
            if (m.range.first > last) out.add(Seg.Txt(text.substring(last, m.range.first)))
            out.add(Seg.Emo(m.value))
            last = m.range.last + 1
        }
        if (last < text.length) out.add(Seg.Txt(text.substring(last)))
        if (out.isEmpty()) out.add(Seg.Txt(text))
        return out
    }

    /** 表情格子边长（逻辑 px）：等于整行高度，与文本行贴合。 */
    fun boxPx(fm: FontMetrics): Float = fm.height.toFloat()

    /** 含表情的整条宽度（与光栅化同一套度量：文本段按 fm 测宽，表情段按格子宽）。 */
    fun width(text: String, fm: FontMetrics): Float {
        if (!hasEmote(text)) return fm.stringWidth(text).toFloat()
        var w = 0f
        for (seg in split(text)) {
            w += when (seg) {
                is Seg.Txt -> fm.stringWidth(seg.s).toFloat()
                is Seg.Emo -> boxPx(fm)
            }
        }
        return w
    }

    // ---- 表情图片下载与缓存（只在后台光栅线程调用，可同步等待；失败 10 分钟内不重试） ----
    private val imgCache = ConcurrentHashMap<String, BufferedImage>()
    private val imgFailedAt = ConcurrentHashMap<String, Long>()

    fun imageFor(token: String): BufferedImage? {
        val url = DanmakuEmoteData.URLS[token] ?: return null
        imgCache[url]?.let { return it }
        val lastFail = imgFailedAt[url]
        if (lastFail != null && System.currentTimeMillis() - lastFail < 600_000) return null
        val img = try {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            try {
                if (c.responseCode !in 200..299) null
                else c.inputStream.use { ImageIO.read(it) }
            } finally {
                c.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
        if (img == null) {
            imgFailedAt[url] = System.currentTimeMillis()
            return null
        }
        if (imgCache.size > 300) imgCache.clear()
        imgCache[url] = img
        return img
    }
}
