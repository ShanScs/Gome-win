package com.muse.gomepc.danmaku

import java.awt.FontMetrics
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 弹幕内联表情支持，两类：
 * 1) B 站官方表情标记 [名称]：按内置映射（DanmakuEmoteData，小黄脸包）换图片；
 * 2) 直接打出的 Unicode emoji（如 😭🔥）：按码点从 Twemoji 图库取彩色图。
 * ♡/☆/♪ 等装饰符号与 B 站一致，仍按文字显示，不替换。
 * 命中才替换；取不到图时回退画原文，不丢内容。
 */
object DanmakuEmotes {

    sealed class Seg {
        data class Txt(val s: String) : Seg()

        /** url = 图片地址，shown = 原文（取图失败时回退绘制用） */
        data class Emo(val url: String, val shown: String) : Seg()
    }

    // ---- Unicode emoji 判定：SMP 表情符号（>=0x1F000）+ 少数 BMP 常用表情 ----
    private val extraEmojiCp = setOf(0x2764, 0x2B50, 0x203C, 0x2049, 0x3297, 0x3299)

    private fun isEmojiBase(cp: Int): Boolean = cp >= 0x1F000 || cp in extraEmojiCp

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun mayContainEmote(text: String): Boolean {
        for (ch in text) {
            if (ch == '[') return true
            if (ch >= '\uD800') return true // 代理对 = SMP 字符
            if (ch.code in extraEmojiCp) return true
        }
        return false
    }

    private fun twemojiUrl(cps: List<Int>): String {
        // Twemoji 资源命名：码点小写十六进制以 - 连接，省略 FE0F
        val name = cps.filter { it != 0xFE0F }.joinToString("-") { it.toString(16) }
        return "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/$name.png"
    }

    /**
     * 若 text 从 index 处开始是一个 emoji 序列，返回 (码点列表, 字符数)，否则 null。
     * 处理：变体选择符 FE0F、肤色修饰、ZWJ 组合、国旗（双区域指示符）。
     */
    private fun emojiSeqAt(text: String, index: Int): Pair<List<Int>, Int>? {
        val first = text.codePointAt(index)
        if (!isEmojiBase(first) && !isRegionalIndicator(first)) return null
        val cps = ArrayList<Int>()
        var i = index
        var cp = first
        while (true) {
            cps.add(cp)
            i += Character.charCount(cp)
            // 国旗：再吃一个区域指示符
            if (isRegionalIndicator(cp) && i < text.length) {
                val n = text.codePointAt(i)
                if (isRegionalIndicator(n)) {
                    cps.add(n)
                    i += Character.charCount(n)
                }
            }
            // 修饰：FE0F / 肤色，可多个
            while (i < text.length) {
                val n = text.codePointAt(i)
                if (n == 0xFE0F || n in 0x1F3FB..0x1F3FF) {
                    cps.add(n)
                    i += Character.charCount(n)
                } else break
            }
            // ZWJ 组合：后面还跟一个 emoji 基础码点就继续吃
            if (i < text.length && text.codePointAt(i) == 0x200D) {
                val after = i + 1
                if (after < text.length) {
                    val n = text.codePointAt(after)
                    if (isEmojiBase(n) || isRegionalIndicator(n)) {
                        cps.add(0x200D)
                        i = after
                        cp = n
                        continue
                    }
                }
            }
            break
        }
        return cps to (i - index)
    }

    /** text[index]=='[' 时找配对 ']'（限 16 字符内、不嵌套），返回 ']' 下标，无则 -1。 */
    private fun bracketEnd(text: String, index: Int): Int {
        val max = minOf(text.length, index + 18)
        var j = index + 1
        while (j < max) {
            if (text[j] == ']') return j
            if (text[j] == '[') return -1
            j++
        }
        return -1
    }

    /** 把文本切成 文本段 / 表情段。 */
    fun split(text: String): List<Seg> {
        if (!mayContainEmote(text)) return listOf(Seg.Txt(text))
        val out = ArrayList<Seg>()
        var runStart = 0
        var i = 0
        fun flush(end: Int) {
            if (end > runStart) out.add(Seg.Txt(text.substring(runStart, end)))
        }
        while (i < text.length) {
            if (text[i] == '[') {
                val end = bracketEnd(text, i)
                if (end > i) {
                    val token = text.substring(i, end + 1)
                    val url = DanmakuEmoteData.URLS[token]
                    if (url != null) {
                        flush(i)
                        out.add(Seg.Emo(url, token))
                        i = end + 1
                        runStart = i
                        continue
                    }
                }
                i++
                continue
            }
            val seq = emojiSeqAt(text, i)
            if (seq != null) {
                val (cps, charCount) = seq
                flush(i)
                out.add(Seg.Emo(twemojiUrl(cps), text.substring(i, i + charCount)))
                i += charCount
                runStart = i
                continue
            }
            i += Character.charCount(text.codePointAt(i))
        }
        flush(text.length)
        if (out.isEmpty()) out.add(Seg.Txt(text))
        return out
    }

    fun hasEmote(text: String): Boolean {
        if (!mayContainEmote(text)) return false
        return split(text).any { it is Seg.Emo }
    }

    /** 表情格子边长（逻辑 px）：等于整行高度，与文本行贴合。 */
    fun boxPx(fm: FontMetrics): Float = fm.height.toFloat()

    /** 含表情的整条宽度（与光栅化同一套度量：文本段按 fm 测宽，表情段按格子宽）。 */
    fun width(text: String, fm: FontMetrics): Float {
        if (!mayContainEmote(text)) return fm.stringWidth(text).toFloat()
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

    fun imageFor(url: String): BufferedImage? {
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
