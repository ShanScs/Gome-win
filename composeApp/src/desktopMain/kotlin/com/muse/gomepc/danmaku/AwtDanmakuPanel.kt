package com.muse.gomepc.danmaku

import java.awt.*
import javax.swing.JPanel
import javax.swing.Timer

/**
 * AWT 版弹幕覆盖层（用于 JWindow 悬浮窗）。
 * Compose 的 ComposePanel 在 JWindow 里文字光栅化异常，改用 Graphics2D 直接绘制。
 * 与 DanmakuOverlay.kt 同样的 DanmakuEngine，共用逻辑。
 */
/**
 * @param videoRectInParent 提供视频区在父容器中的矩形（glasspane 模式用；JWindow 模式传 null 则全画）
 */
class AwtDanmakuPanel(
    private val engine: DanmakuEngine,
    private val videoRectInParent: (() -> java.awt.Rectangle?)? = null,
    private val onMouseActivity: (() -> Unit)? = null
) : JPanel() {

    /** 工具栏可见状态的线程安全镜像（AWT 线程不能直接读 Compose State） */
    @Volatile var controlsVisibleMirror: Boolean = true

    override fun contains(x: Int, y: Int): Boolean {
        // 工具栏可见时穿透（按钮要能点），隐藏时拦截（做鼠标哨兵唤醒工具栏）
        if (controlsVisibleMirror) return false
        return super.contains(x, y)
    }

    init {
        isOpaque = false
        // 鼠标活动 → 唤醒工具栏
        if (onMouseActivity != null) {
            val adapter = object : java.awt.event.MouseAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent?) { onMouseActivity.invoke() }
                override fun mouseDragged(e: java.awt.event.MouseEvent?) { onMouseActivity.invoke() }
                override fun mouseClicked(e: java.awt.event.MouseEvent?) { onMouseActivity.invoke() }
            }
            addMouseListener(adapter)
            addMouseMotionListener(adapter)
        }
    }

    init {
        // 30fps 驱动
        val danmakuFont = try {
            Font("Noto Sans CJK SC", Font.BOLD, 30)
        } catch (_: Throwable) {
            Font("SansSerif", Font.BOLD, 30)
        }
        Timer(33) {
            val vw = videoRectInParent?.invoke()?.width ?: width
            val vh = videoRectInParent?.invoke()?.height ?: height
            if (engine.enabled && vw > 0) {
                engine.viewWidth = vw.toFloat()
                engine.viewHeight = vh.toFloat()
                val fontSize = (engine.fontSp * 1.5f).toInt().coerceAtLeast(12)
                val fm = getFontMetrics(danmakuFont.deriveFont(fontSize.toFloat()))
                engine.step(0.033f) { text -> fm.stringWidth(text).toFloat() }
            }
            repaint()
        }.start()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!engine.enabled) return
        // glasspane 模式：只在视频区画
        val vr = videoRectInParent?.invoke()
        if (vr != null) {
            g.clipRect(vr.x, vr.y, vr.width, vr.height)
        }
        val g2 = g as Graphics2D
        // 字号：与 Compose 版一致（fontSp sp → px，桌面 density≈1）
        val fontSize = (engine.fontSp * 1.5f).toInt().coerceAtLeast(12)
        // 明确指定中文字体
        val baseFont = try {
            Font("Noto Sans CJK SC", Font.BOLD, fontSize)
        } catch (_: Throwable) {
            Font("SansSerif", Font.BOLD, fontSize)
        }
        val dx = vr?.x ?: 0
        val dy = vr?.y ?: 0
        for (d in engine.items) {
            val baselineY = engine.rowBaseline(d.row, fontSize.toFloat())
            val x = (d.x + dx).toInt()
            val y = (baselineY + dy).toInt()
            // 预渲染到 BufferedImage（直接 drawString 在 glasspane 上字形异常）
            try {
                val fm0 = getFontMetrics(baseFont)
                val tw = fm0.stringWidth(d.text).coerceAtLeast(1)
                val th = fm0.height.coerceAtLeast(1)
                val img = java.awt.image.BufferedImage(tw + 8, th + 8,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val ig = img.createGraphics()
                ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                ig.font = baseFont
                val ifm = ig.fontMetrics
                // 阴影
                ig.color = Color(0, 0, 0, 200)
                ig.drawString(d.text, 4 + 2, ifm.ascent + 4 + 2)
                // 白字
                ig.color = Color(255, 255, 255,
                    (255 * engine.alpha).toInt().coerceIn(26, 255))
                ig.drawString(d.text, 4, ifm.ascent + 4)
                ig.dispose()
                if (d.textWidth < 0) d.textWidth = tw.toFloat()
                g2.drawImage(img, x, y - ifm.ascent - 4, null)
            } catch (_: Throwable) { }
        }
    }
}
