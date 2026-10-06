package com.muse.gomepc.ui

import java.awt.*
import java.awt.geom.*
import javax.swing.*

/**
 * Android Vector Drawable parser/render for Swing Icon.
 * Loads from classpath drawable resources (bundled in app).
 */
class VectorIcon private constructor(
    private val xmlText: String,
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
        val text = xmlText
        viewportW = Regex("viewportWidth=\"([\\d.]+)\"").find(text)?.groupValues?.get(1)?.toFloat() ?: 24f
        viewportH = Regex("viewportHeight=\"([\\d.]+)\"").find(text)?.groupValues?.get(1)?.toFloat() ?: 24f
        val pathList = mutableListOf<PathDef>()
        val pathRegex = Regex("<path\\s+([^>]*?)/?>")
        for (m in pathRegex.findAll(text)) {
            val attrs = m.groupValues[1]
            fun attr(name: String): String? =
                Regex("$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
            val fill = attr("fillColor")?.let { parseColor(it) }
            val stroke = attr("strokeColor")?.let { parseColor(it) }
            val sw = attr("strokeWidth")?.toFloatOrNull() ?: 0f
            val d = attr("pathData") ?: continue
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

    private fun parsePath(d: String): Shape? {
        return try {
            val path = Path2D.Float()
            val tokens = Regex("([MmLlHhVvCcSsQqTtAaZz])|(-?[\\d.]+)")
                .findAll(d).map { it.value }.toList()
            var i = 0
            var cx = 0f
            var cy = 0f
            var sx = 0f
            var sy = 0f
            fun num(): Float = tokens[i++].toFloat()
            while (i < tokens.size) {
                when (val cmd = tokens[i++]) {
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
                    "Q" -> {
                        val x1 = num(); val y1 = num(); cx = num(); cy = num()
                        path.quadTo(x1, y1, cx, cy)
                    }
                    "q" -> {
                        val x1 = cx + num(); val y1 = cy + num(); cx += num(); cy += num()
                        path.quadTo(x1, y1, cx, cy)
                    }
                    "A", "a" -> {
                        val rx = num(); val ry = num(); num()
                        val largeArc = num() != 0f; val sweep = num() != 0f
                        val ex = num(); val ey = num()
                        val nx = if (cmd == "a") cx + ex else ex
                        val ny = if (cmd == "a") cy + ey else ey
                        arcTo(path, cx, cy, nx, ny, rx, ry, largeArc, sweep)
                        cx = nx; cy = ny
                    }
                    "Z", "z" -> { path.closePath(); cx = sx; cy = sy }
                    else -> { }
                }
            }
            path
        } catch (_: Exception) { null }
    }

    private fun arcTo(
        path: Path2D.Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        rx: Float, ry: Float, largeArc: Boolean, sweep: Boolean
    ) {
        if (rx <= 0 || ry <= 0) { path.lineTo(x1, y1); return }
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        // 整圆：起点终点重合且 largeArc=1，用椭圆直接画
        if (len < 0.01f && largeArc) {
            // 圆心在起点下方 rx 处（SVG 弧线整圆的常见画法）
            path.append(
                java.awt.geom.Ellipse2D.Float(x0 - rx, y0 - ry, rx * 2, ry * 2),
                false
            )
            return
        }
        if (len < 0.01f) { path.lineTo(x1, y1); return }
        val mx = (x0 + x1) / 2
        val my = (y0 + y1) / 2
        val k = if (largeArc) 1.2f else 0.55f
        val s = if (sweep) 1f else -1f
        val qx = mx - s * k * dy
        val qy = my + s * k * dx
        path.quadTo(qx, qy, x1, y1)
    }

    companion object {
        private val cache = mutableMapOf<String, VectorIcon>()
        @JvmStatic
        fun get(name: String, size: Int): VectorIcon {
            val key = "$name@$size"
            return cache.getOrPut(key) {
                val stream = VectorIcon::class.java.getResourceAsStream("/drawable/$name.xml")
                    ?: throw IllegalArgumentException("icon not found: $name")
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                VectorIcon(text, size)
            }
        }
    }
}
