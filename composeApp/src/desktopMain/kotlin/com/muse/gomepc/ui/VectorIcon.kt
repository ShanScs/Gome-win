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
    private val size: Int,
    private val tint: Color? = null
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
            // viewport 非正方形时 X/Y 分别缩放，否则图标会被压扁
            g2.scale((size / viewportW).toDouble(), (size / viewportH).toDouble())
            for (p in paths) {
                val shape = parsePath(p.pathData) ?: continue
                if (p.fillColor != null && p.fillColor.alpha > 0) {
                    g2.color = tint ?: p.fillColor
                    g2.fill(shape)
                }
                if (p.strokeColor != null && p.strokeWidth > 0) {
                    g2.color = tint ?: p.strokeColor
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
            // 数字支持 .5 / -.5 / 科学计数；命令字母单独分词
            val tokens = Regex("([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)")
                .findAll(d).map { it.value }.toList()
            var i = 0
            var cx = 0f
            var cy = 0f
            var sx = 0f
            var sy = 0f
            // 上一段三次/二次贝塞尔的第二控制点（供 S/T 做镜像反射）
            var prevCubicCx = 0f
            var prevCubicCy = 0f
            var prevQuadCx = 0f
            var prevQuadCy = 0f
            var prevCmd = ' '
            fun peekIsNumber(): Boolean = i < tokens.size && tokens[i][0] !in "MmLlHhVvCcSsQqTtAaZz"
            fun num(): Float = tokens[i++].toFloat()
            // 命令后的连续数字组是隐式重复命令（SVG 规范）：M 后为 L，C 后为 C，依此类推
            var cmd = ' '
            while (i < tokens.size) {
                if (!peekIsNumber()) {
                    cmd = tokens[i++][0]
                }
                when (cmd) {
                    'M', 'm' -> {
                        // 首对坐标 Move，后续对坐标隐式 LineTo
                        var first = true
                        while (peekIsNumber()) {
                            val nx = num(); val ny = num()
                            if (cmd == 'm') { cx += nx; cy += ny } else { cx = nx; cy = ny }
                            if (first) { path.moveTo(cx, cy); sx = cx; sy = cy; first = false } else path.lineTo(cx, cy)
                        }
                        prevCmd = 'M'
                    }
                    'L', 'l' -> {
                        while (peekIsNumber()) {
                            val nx = num(); val ny = num()
                            if (cmd == 'l') { cx += nx; cy += ny } else { cx = nx; cy = ny }
                            path.lineTo(cx, cy)
                        }
                        prevCmd = 'L'
                    }
                    'H', 'h' -> {
                        while (peekIsNumber()) {
                            val nx = num()
                            cx = if (cmd == 'h') cx + nx else nx
                            path.lineTo(cx, cy)
                        }
                        prevCmd = 'H'
                    }
                    'V', 'v' -> {
                        while (peekIsNumber()) {
                            val ny = num()
                            cy = if (cmd == 'v') cy + ny else ny
                            path.lineTo(cx, cy)
                        }
                        prevCmd = 'V'
                    }
                    'C', 'c' -> {
                        while (peekIsNumber()) {
                            val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num()
                            val ex = num(); val ey = num()
                            val ax1 = if (cmd == 'c') cx + x1 else x1
                            val ay1 = if (cmd == 'c') cy + y1 else y1
                            val ax2 = if (cmd == 'c') cx + x2 else x2
                            val ay2 = if (cmd == 'c') cy + y2 else y2
                            cx = if (cmd == 'c') cx + ex else ex
                            cy = if (cmd == 'c') cy + ey else ey
                            path.curveTo(ax1, ay1, ax2, ay2, cx, cy)
                            prevCubicCx = ax2; prevCubicCy = ay2
                        }
                        prevCmd = 'C'
                    }
                    'S', 's' -> {
                        while (peekIsNumber()) {
                            // 第一个控制点 = 上一段第二控制点关于当前点的镜像（上一段不是 C/S 则与当前点重合）
                            val x1 = if (prevCmd == 'C' || prevCmd == 'S') 2 * cx - prevCubicCx else cx
                            val y1 = if (prevCmd == 'C' || prevCmd == 'S') 2 * cy - prevCubicCy else cy
                            val x2 = num(); val y2 = num()
                            val ex = num(); val ey = num()
                            val ax2 = if (cmd == 's') cx + x2 else x2
                            val ay2 = if (cmd == 's') cy + y2 else y2
                            cx = if (cmd == 's') cx + ex else ex
                            cy = if (cmd == 's') cy + ey else ey
                            path.curveTo(x1, y1, ax2, ay2, cx, cy)
                            prevCubicCx = ax2; prevCubicCy = ay2
                        }
                        prevCmd = 'S'
                    }
                    'Q', 'q' -> {
                        while (peekIsNumber()) {
                            val x1 = num(); val y1 = num(); val ex = num(); val ey = num()
                            val ax1 = if (cmd == 'q') cx + x1 else x1
                            val ay1 = if (cmd == 'q') cy + y1 else y1
                            cx = if (cmd == 'q') cx + ex else ex
                            cy = if (cmd == 'q') cy + ey else ey
                            path.quadTo(ax1, ay1, cx, cy)
                            prevQuadCx = ax1; prevQuadCy = ay1
                        }
                        prevCmd = 'Q'
                    }
                    'T', 't' -> {
                        while (peekIsNumber()) {
                            // 控制点 = 上一段二次控制点关于当前点的镜像（上一段不是 Q/T 则与当前点重合）
                            val ax1 = if (prevCmd == 'Q' || prevCmd == 'T') 2 * cx - prevQuadCx else cx
                            val ay1 = if (prevCmd == 'Q' || prevCmd == 'T') 2 * cy - prevQuadCy else cy
                            val ex = num(); val ey = num()
                            cx = if (cmd == 't') cx + ex else ex
                            cy = if (cmd == 't') cy + ey else ey
                            path.quadTo(ax1, ay1, cx, cy)
                            prevQuadCx = ax1; prevQuadCy = ay1
                        }
                        prevCmd = 'T'
                    }
                    'A', 'a' -> {
                        while (peekIsNumber()) {
                            val rx = num(); val ry = num(); num()
                            val largeArc = num() != 0f; val sweep = num() != 0f
                            val ex = num(); val ey = num()
                            val nx = if (cmd == 'a') cx + ex else ex
                            val ny = if (cmd == 'a') cy + ey else ey
                            arcTo(path, cx, cy, nx, ny, rx, ry, largeArc, sweep)
                            cx = nx; cy = ny
                        }
                        prevCmd = 'A'
                    }
                    'Z', 'z' -> { path.closePath(); cx = sx; cy = sy; prevCmd = 'Z' }
                    else -> { i++ }
                }
            }
            path
        } catch (_: Exception) { null }
    }

    /**
     * SVG 规范弧线（F.6.5 端点→圆心参数化）：修正半径、求圆心、算起止角，
     * 再按每段 ≤90° 切成三次贝塞尔逼近，避免旧的二次贝塞尔魔法系数变形。
     */
    private fun arcTo(
        path: Path2D.Float,
        x0: Float, y0: Float, x1: Float, y1: Float,
        rxIn: Float, ryIn: Float, largeArc: Boolean, sweep: Boolean
    ) {
        if (rxIn <= 0 || ryIn <= 0) { path.lineTo(x1, y1); return }
        val dx = (x1 - x0) / 2.0
        val dy = (y1 - y0) / 2.0
        if (dx == 0.0 && dy == 0.0) return
        var rx = kotlin.math.abs(rxIn.toDouble())
        var ry = kotlin.math.abs(ryIn.toDouble())
        // 修正半径：若椭圆太小放不下两点，等比放大（规范 F.6.5.2）
        val lambda = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)
        if (lambda > 1.0) {
            val s = kotlin.math.sqrt(lambda)
            rx *= s
            ry *= s
        }
        // 圆心（规范 F.6.5.3）：largeArc/sweep 决定取两个候选圆心中的哪一个
        val num = rx * rx * ry * ry - rx * rx * dy * dy - ry * ry * dx * dx
        val den = rx * rx * dy * dy + ry * ry * dx * dx
        var co = if (den == 0.0) 0.0 else kotlin.math.sqrt(kotlin.math.max(0.0, num / den))
        if (largeArc == sweep) co = -co
        val cxp = co * rx * dy / ry
        val cyp = -co * ry * dx / rx
        val cx = (x0 + x1) / 2.0 + cxp
        val cy = (y0 + y1) / 2.0 + cyp
        // 起止角（规范 F.6.5.5）
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = kotlin.math.sqrt(ux * ux + uy * uy) * kotlin.math.sqrt(vx * vx + vy * vy)
            var a = kotlin.math.acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
        val ux = (dx - cxp) / rx
        val uy = (dy - cyp) / ry
        val vx = (-dx - cxp) / rx
        val vy = (-dy - cyp) / ry
        var startAngle = angle(1.0, 0.0, ux, uy)
        var sweepAngle = angle(ux, uy, vx, vy)
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI
        if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI
        // 按 ≤90° 切段，每段用三次贝塞尔逼近圆弧
        val segments = kotlin.math.max(1, kotlin.math.ceil(kotlin.math.abs(sweepAngle) / (Math.PI / 2)).toInt())
        val delta = sweepAngle / segments
        var theta = startAngle
        var px = cx + rx * kotlin.math.cos(theta)
        var py = cy + ry * kotlin.math.sin(theta)
        repeat(segments) {
            val nextTheta = theta + delta
            val qx = cx + rx * kotlin.math.cos(nextTheta)
            val qy = cy + ry * kotlin.math.sin(nextTheta)
            // 贝塞尔控制点系数 alpha = 4/3 * tan(delta/4)
            val alpha = 4.0 / 3.0 * kotlin.math.tan(delta / 4.0)
            val c1x = px - alpha * rx * kotlin.math.sin(theta)
            val c1y = py + alpha * ry * kotlin.math.cos(theta)
            val c2x = qx + alpha * rx * kotlin.math.sin(nextTheta)
            val c2y = qy - alpha * ry * kotlin.math.cos(nextTheta)
            path.curveTo(c1x.toFloat(), c1y.toFloat(), c2x.toFloat(), c2y.toFloat(), qx.toFloat(), qy.toFloat())
            theta = nextTheta
            px = qx
            py = qy
        }
    }

    companion object {
        // 同步 Map：VectorIcon.get 可在 EDT/Compose 等多线程调用
        private val cache = java.util.Collections.synchronizedMap(mutableMapOf<String, VectorIcon>())
        @JvmStatic
        fun get(name: String, size: Int, tint: Color? = null): VectorIcon {
            val key = "$name@$size@${tint?.rgb ?: 0}"
            return cache.getOrPut(key) {
                val stream = VectorIcon::class.java.getResourceAsStream("/drawable/$name.xml")
                    ?: throw IllegalArgumentException("icon not found: $name")
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                VectorIcon(text, size, tint)
            }
        }
    }
}
