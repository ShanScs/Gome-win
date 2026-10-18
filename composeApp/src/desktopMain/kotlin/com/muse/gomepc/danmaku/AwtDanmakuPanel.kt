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

    // 弹幕文本渲染缓存：同一文本只渲染一次，避免每帧9遍描边绘制卡顿
    private val textCache = mutableMapOf<String, java.awt.image.BufferedImage>()
    private var cachedFontSize = -1
    private var cachedAlpha = -1
    // 上一帧是否有弹幕：用于弹幕清空时隐藏窗口
    private var hadItems = false
    // 供外部查询：面板是否希望窗口可见（有弹幕且启用）
    var wantVisible = false
        private set

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
        // 30fps 驱动；无弹幕/未启用时不重绘，避免透明 JWindow 空转拖慢 DWM 合成
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
                // 有弹幕才显示窗口并重绘；无弹幕时隐藏窗口，DWM 零开销
                val hasItems = engine.enabled && engine.items.isNotEmpty()
                wantVisible = hasItems
                val win = javax.swing.SwingUtilities.getWindowAncestor(this@AwtDanmakuPanel)
                if (hasItems) {
                    if (win != null && !win.isVisible) win.isVisible = true
                    repaint()
                } else if (hadItems) {
                    // 弹幕刚清空：隐藏窗口（无需清屏，不可见即无残留）
                    if (win != null && win.isVisible) win.isVisible = false
                }
                hadItems = hasItems
            }
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
        // 字号/透明度变了清缓存
        val alphaInt = (255 * engine.alpha).toInt().coerceIn(26, 255)
        if (fontSize != cachedFontSize || alphaInt != cachedAlpha) {
            textCache.clear()
            cachedFontSize = fontSize
            cachedAlpha = alphaInt
        }
        // 防止缓存无限增长
        if (textCache.size > 500) textCache.clear()
        // ascent 每帧只算一次，别在循环里调 getFontMetrics
        val ascent = getFontMetrics(baseFont).ascent
        for (d in engine.items) {
            val baselineY = engine.rowBaseline(d.row, fontSize.toFloat())
            val x = (d.x + dx).toInt()
            val y = (baselineY + dy).toInt()
            try {
                // 缓存命中直接画，未命中才渲染（9遍描边只做一次）
                val img = textCache.getOrPut(d.text) {
                    val fm0 = getFontMetrics(baseFont)
                    val tw = fm0.stringWidth(d.text).coerceAtLeast(1)
                    val th = fm0.height.coerceAtLeast(1)
                    val bi = java.awt.image.BufferedImage(tw + 8, th + 8,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    val ig = bi.createGraphics()
                    ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    ig.font = baseFont
                    val ifm = ig.fontMetrics
                    // 硬描边（8方向1px黑）：比偏移阴影清晰，标准弹幕样式
                    ig.color = Color(0, 0, 0, 230)
                    val bx = 4
                    val by = ifm.ascent + 4
                    ig.drawString(d.text, bx - 1, by)
                    ig.drawString(d.text, bx + 1, by)
                    ig.drawString(d.text, bx, by - 1)
                    ig.drawString(d.text, bx, by + 1)
                    ig.drawString(d.text, bx - 1, by - 1)
                    ig.drawString(d.text, bx - 1, by + 1)
                    ig.drawString(d.text, bx + 1, by - 1)
                    ig.drawString(d.text, bx + 1, by + 1)
                    // 白字
                    ig.color = Color(255, 255, 255, alphaInt)
                    ig.drawString(d.text, bx, by)
                    ig.dispose()
                    if (d.textWidth < 0) d.textWidth = tw.toFloat()
                    bi
                }
                g2.drawImage(img, x, y - ascent - 4, null)
            } catch (_: Throwable) { }
        }
    }
}
