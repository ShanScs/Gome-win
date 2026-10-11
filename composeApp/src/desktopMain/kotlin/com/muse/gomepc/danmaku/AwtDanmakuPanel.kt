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
    private val onMouseActivity: () -> Unit
) : JPanel() {

    override fun contains(x: Int, y: Int): Boolean {
        return super.contains(x, y)
    }

    init {
        isOpaque = false

        val mouseAdapter = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent?) {
                com.muse.gomepc.player.DebugLog.d("DANMAKU", "弹幕面板收到 mouseMoved")
                onMouseActivity()
                // 悬停也要透传，否则底层控件的 hover 状态失效
                dispatchToUnderlying(e)
            }

            override fun mouseDragged(e: java.awt.event.MouseEvent?) {
                onMouseActivity()
                // 拖动也要透传，否则进度条 Slider 无法拖动
                dispatchToUnderlying(e)
            }

            private var pressTarget: java.awt.Component? = null

            /** 标准 Swing GlassPane 透传：在 contentPane 里做 hit-test，找到光标下最深的组件并把事件派给它 */
            private fun findTargetUnder(e: java.awt.event.MouseEvent): java.awt.Component? {
                val glassPane = this@AwtDanmakuPanel
                val window = javax.swing.SwingUtilities.getWindowAncestor(glassPane) as? javax.swing.JFrame
                    ?: return null
                val contentPane = window.contentPane as? java.awt.Container ?: return null
                val contentPoint = javax.swing.SwingUtilities.convertPoint(glassPane, e.point, contentPane)
                return javax.swing.SwingUtilities.getDeepestComponentAt(
                    contentPane, contentPoint.x, contentPoint.y
                )
            }

            private fun redispatch(e: java.awt.event.MouseEvent, target: java.awt.Component) {
                val targetPoint = javax.swing.SwingUtilities.convertPoint(
                    this@AwtDanmakuPanel, e.point, target
                )
                val newEvent = java.awt.event.MouseEvent(
                    target,
                    e.id,
                    e.`when`,
                    e.modifiers,
                    targetPoint.x,
                    targetPoint.y,
                    e.xOnScreen,
                    e.yOnScreen,
                    e.clickCount,
                    e.isPopupTrigger,
                    e.button
                )
                target.dispatchEvent(newEvent)
            }

            private fun dispatchToUnderlying(e: java.awt.event.MouseEvent?) {
                if (e == null) return
                try {
                    when (e.id) {
                        java.awt.event.MouseEvent.MOUSE_PRESSED -> {
                            val target = findTargetUnder(e)
                            pressTarget = target
                            if (target != null) redispatch(e, target)
                        }
                        java.awt.event.MouseEvent.MOUSE_DRAGGED,
                        java.awt.event.MouseEvent.MOUSE_RELEASED -> {
                            // 拖动/释放必须发给按下时的同一个组件，否则 Slider 抓不住拖动手势
                            val target = pressTarget ?: findTargetUnder(e)
                            if (target != null) redispatch(e, target)
                            if (e.id == java.awt.event.MouseEvent.MOUSE_RELEASED) pressTarget = null
                        }
                        else -> {
                            val target = findTargetUnder(e)
                            if (target != null) redispatch(e, target)
                        }
                    }
                } catch (_: Throwable) { }
            }

            override fun mousePressed(e: java.awt.event.MouseEvent?) {
                onMouseActivity()
                dispatchToUnderlying(e)
            }

            override fun mouseReleased(e: java.awt.event.MouseEvent?) {
                dispatchToUnderlying(e)
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                dispatchToUnderlying(e)
            }
        }
        addMouseListener(mouseAdapter)
        addMouseMotionListener(mouseAdapter)
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
        // 💥 【全场唯一核心修复】：给 Windows 操作系统垫一块肉眼看不见的空气垫（Alpha=1）
        val g2 = g as Graphics2D
        g2.color = java.awt.Color(0, 0, 0, 1)
        g2.fillRect(0, 0, width, height)

        if (!engine.enabled) return
        // glasspane 模式：只在视频区画
        val vr = videoRectInParent?.invoke()
        if (vr != null) {
            g.clipRect(vr.x, vr.y, vr.width, vr.height)
        }
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
