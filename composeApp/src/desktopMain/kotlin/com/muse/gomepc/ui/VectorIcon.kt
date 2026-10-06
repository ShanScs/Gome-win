package com.muse.gomepc.ui

import java.awt.*
import java.awt.geom.*
import javax.swing.*
import java.io.File

/**
 * Android Vector Drawable 解析渲染为 Swing Icon。
 * 支持 stroke/fill 的 pathData (M/L/H/V/C/S/Q/T/A/Z 命令)。
 */
class VectorIcon(
    private val xmlFile: File,
    private val size: Int
) : Icon {

    private data class PathDef(
        val fillColor: Color?,
        val strokeColor: Color?,
        val strokeWidth: Float,
        val pathData: String
    )

    private val paths: List<PathDef>
    private val viewportW: Float
    private val viewportH: Float

    init {
        val text = xmlFile.readText()
        viewportW = Regex("""viewportWidth="([\d.]+)"""").find(text)?.groupValues?.get(1)?.toFloat() ?: 24f
        viewportH = Regex("""viewportHeight="([\d.]+)"""").find(text)?.groupValues?.get(1)?.toFloat() ?: 24f
        val pathList = mutableListOf<PathDef>()
        // 匹配每个 <path ... /> 或 <path ...>
        val pathRegex = Regex("""<path\s+([^>]*?)/?>""")
        for (m in pathRegex.findAll(text)) {
            val attrs = m.groupValues[1]
            fun attr(name: String): String? =
                Regex("""$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)
            val fillStr = attr("fillColor")
            val strokeStr = attr("strokeColor")
            val fill = fillStr?.let { parseColor(it) }
            val stroke = strokeStr?.let { parseColor(it) }
            val sw = attr("strokeWidth")?.toFloatOrNull() ?: 0f
            val d = attr("pathData") ?: continue
            // 跳过完全透明的 fill 且无 stroke 的路径
            if (fill != null && fill.alpha == 0 && stroke == null) continue
            pathList.add(PathDef(fill, stroke, sw, d))
        }
        paths = pathList
    }

    private fun parseColor(s: String): Color? {
        return try {
            val hex = s.removePrefix("#")
            when (hex.length) {
                6 -> Color(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16)
                )
                8 -> Color(
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                    hex.substring(6, 8).toInt(16),
                    hex.substring(0, 2).toInt(16)
                )
                else -> null
            }
        } catch (_: Exception) { null }
    }

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(x.toDouble(), y.toDouble())
            val scale = size / maxOf(viewportW, viewportH)
            g2.scale(scale.toDouble(), scale.toDouble())
            // 居中 viewport
            val dx = (viewportW - minOf(viewportW, viewportH)) / 2
            val dy = (viewportH - minOf(viewportW, viewportH)) / 2
            g2.translate(-dx.toDouble(), -dy.toDouble())

            for (p in paths) {
                val shape = parsePath(p.pathData) ?: continue
                if (p.fillColor != null && p.fillColor.alpha > 0) {
                    g2.color = p.fillColor
                    g2.fill(shape)
                }
                if (p.strokeColor != null && p.strokeWidth > 0) {
                    g2.color = p.strokeColor
                    g2.stroke = BasicStroke(p.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(shape)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    /** 简易 SVG path 解析器 */
    private fun parsePath(d: String): Shape? {
        return try {
            val path = Path2D.Float()
            // 按命令切分
            val tokens = Regex("""([MmLlHhVvCcSsQqTtAaZz])|(-?[\d.]+)""")
                .findAll(d).map { it.value }.toList()
            var i = 0
            var cx = 0f; var cy = 0f
            var sx = 0f; var sy = 0f
            fun num(): Float = tokens[i++].toFloat()

            while (i < tokens.size) {
                val cmd = tokens[i++]
                when (cmd) {
                    "M" -> { cx = num(); cy = num(); path.moveTo(cx, cy); sx = cx; sy = cy }
                    "m" -> { cx += num(); cy += num(); path.moveTo(cx, cy); sx = cx; sy = cy }
                    "L" -> { cx = num(); cy = num(); path.lineTo(cx, cy) }
                    "l" -> { cx += num(); cy += num(); path.lineTo(cx, cy) }
                    "H" -> { cx = num(); path.lineTo(cx, cy) }
                    "h" -> { cx += num(); path.lineTo(cx, cy) }
                    "V" -> { cy = num(); path.lineTo(cx, cy) }
                    "v" -> { cy += num(); path.lineTo(cx, cy) }
                    "C" -> {
                        val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num()
                        cx = num(); cy = num()
                        path.curveTo(x1, y1, x2, y2, cx, cy)
                    }
                    "c" -> {
                        val x1 = cx + num(); val y1 = cy + num()
                        val x2 = cx + num(); val y2 = cy + num()
                        cx += num(); cy += num()
                        path.curveTo(x1, y1, x2, y2, cx, cy)
                    }
                    "S", "s" -> {
                        // 简化为二次曲线
                        val x2 = num(); val y2 = num(); cx = num(); cy = num()
                        path.lineTo(cx, cy)
                    }
                    "Q" -> {
                        val x1 = num(); val y1 = num(); cx = num(); cy = num()
                        path.quadTo(x1, y1, cx, cy)
                    }
                    "q" -> {
                        val x1 = cx + num(); val y1 = cy + num(); cx += num(); cy += num()
                        path.quadTo(x1, y1, cx, cy)
                    }
                    "T", "t" -> { cx = num(); cy = num(); path.lineTo(cx, cy) }
                    "A", "a" -> {
                        val rx = num(); val ry = num(); num() // x-axis-rotation
                        val largeArc = num() != 0f; val sweep = num() != 0f
                        val x = num(); val y = num()
                        val nx = if (cmd == "a") cx + x else x
                        val ny = if (cmd == "a") cy + y else y
                        // 用椭圆弧近似
                        drawArc(path, cx, cy, nx, ny, rx, ry, largeArc, sweep)
                        cx = nx; cy = ny
                    }
                    "Z", "z" -> { path.closePath(); cx = sx; cy = sy }
                    else -> { /* 数字被命令隐含时忽略 */ }
                }
            }
            path
        } catch (_: Exception) { null }
    }

    private fun drawArc(
        path: Path2D.Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        rx: Float, ry: Float, largeArc: Boolean, sweep: Boolean
    ) {
        // 简化：用二次贝塞尔近似圆弧
        if (rx <= 0 || ry <= 0) { path.lineTo(x1, y1); return }
        val dx = x1 - x0; val dy = y1 - y0
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        // 控制点偏移（简化处理）
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 0.01f) { path.lineTo(x1, y1); return }
        val k = if (largeArc) 1.2f else 0.55f
        val s = if (sweep) 1f else -1f
        val cxp = mx - s * k * dy / len * len / 4
        val cyp = my + s * k * dx / len * len / 4
        path.quadTo(cxp, cyp, x1, y1)
    }

    companion object {
        private val cache = mutableMapOf<String, VectorIcon>()
        fun get(name: String, size: Int, drawableDir: File): VectorIcon {
            val key = "$name@$size"
            return cache.getOrPut(key) {
                VectorIcon(File(drawableDir, "$name.xml"), size)
            }
        }
    }
}
