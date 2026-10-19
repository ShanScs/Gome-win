package com.muse.gomepc.ui

import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt

/**
 * 磨砂玻璃弹窗：1:1 还原安卓 showFrostedPopup。
 * 参数来自 popup_more_menu.xml / item_more_menu_row.xml / bg_more_popup.xml：
 * - 弹窗宽 210dp，上下 padding 6dp
 * - 背景 #D9FFFFFF（217 透明度），圆角 16dp
 * - 行高 52dp，左右 padding 14dp
 * - "›" 前缀 22sp #B0B0B0，右边距 10dp
 * - 文字 15sp #1A1A1A
 * - 图标 22dp
 * - 分隔线左右边距 14dp，颜色 #FFE5E5EA
 */
class FrostedPopup(
    private val owner: Window,
    private val anchor: Component
) {
    data class Row(
        val label: String,
        val iconName: String? = null,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val action: () -> Unit = {}
    )

    private var dialog: JDialog? = null

    // dp = px (mdpi baseline)
    private val POPUP_WIDTH = 210
    private val ROW_HEIGHT = 52
    private val ROW_PADDING_H = 14
    private val PREFIX_SIZE = 22
    private val PREFIX_MARGIN = 10
    private val LABEL_SIZE = 15
    private val ICON_SIZE = 22
    private val CONTAINER_PADDING_V = 6
    private val CORNER_RADIUS = 16
    private val DIVIDER_MARGIN_H = 14

    /**
     * HiDPI 缩放：之前固定像素（dp = px），高分屏上弹窗只有一半大。
     * 取宿主窗口 GraphicsConfiguration 的默认变换 scale，全部尺寸（宽/行高/padding/字号/圆角）乘以它。
     */
    private fun scaleFactor(): Double {
        return try {
            owner.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        } catch (_: Exception) { 1.0 }
    }

    fun show(rows: List<Row>, width: Int = POPUP_WIDTH) {
        // Swing 组件必须在 EDT 上创建/显示；非 EDT 调用时转交 EDT，避免线程竞争
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { show(rows, width) }
            return
        }
        dismiss()
        // HiDPI：全部固定像素尺寸乘以 scale
        val sc = scaleFactor().coerceIn(1.0, 4.0)
        fun px(v: Int) = (v * sc).roundToInt()
        val scaledWidth = px(width)
        val scaledRowH = px(ROW_HEIGHT)
        val scaledPadH = px(ROW_PADDING_H)
        val scaledPrefixSize = px(PREFIX_SIZE)
        val scaledPrefixMargin = px(PREFIX_MARGIN)
        val scaledLabelSize = px(LABEL_SIZE)
        val scaledIconSize = px(ICON_SIZE)
        val scaledPadV = px(CONTAINER_PADDING_V)
        val scaledRadius = px(CORNER_RADIUS)
        val scaledDividerMargin = px(DIVIDER_MARGIN_H)

        val dlg = JDialog(owner as? Frame, "", false).apply {
            isUndecorated = true
            background = Color(0, 0, 0, 0)
        }

        val content = object : JPanel() {
            init {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = EmptyBorder(scaledPadV, 0, scaledPadV, 0)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // 背景 #D9FFFFFF（spec：217 透明度），16dp 圆角
                g2.color = Color(255, 255, 255, 0xD9)
                g2.fillRoundRect(0, 0, width, height, scaledRadius, scaledRadius)
                super.paintComponent(g)
            }
        }

        rows.forEachIndexed { index, row ->
            val rowPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = EmptyBorder(0, scaledPadH, 0, scaledPadH)
                preferredSize = Dimension(scaledWidth, scaledRowH)
                maximumSize = Dimension(scaledWidth, scaledRowH)
                minimumSize = Dimension(scaledWidth, scaledRowH)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
            }

            // "›" 前缀：安卓布局写死，每行都有
            val prefix = JLabel("›").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, scaledPrefixSize)
                foreground = Color(0xB0, 0xB0, 0xB0)
                border = EmptyBorder(0, 0, 0, scaledPrefixMargin)
            }
            rowPanel.add(prefix)

            // 文字 (weight=1, 居左)
            val textLabel = JLabel(row.label).apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, scaledLabelSize)
                foreground = if (row.enabled) Color(0x1A, 0x1A, 0x1A) else Color(150, 150, 150)
            }
            // 用 glue 实现 weight=1 效果
            val textWrapper = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(textLabel, BorderLayout.WEST)
            }
            rowPanel.add(textWrapper)
            rowPanel.add(Box.createHorizontalGlue())

            // 图标/选中勾：安卓用 ic_check vector，图标统一 #8E8E93 tint
            val iconLabel = JLabel().apply {
                preferredSize = Dimension(scaledIconSize, scaledIconSize)
                maximumSize = Dimension(scaledIconSize, scaledIconSize)
                minimumSize = Dimension(scaledIconSize, scaledIconSize)
                val tintColor = Color(0x8E, 0x8E, 0x93)
                try {
                    icon = when {
                        row.checked -> VectorIcon.get("ic_check", scaledIconSize, tintColor)
                        row.iconName != null -> VectorIcon.get(row.iconName, scaledIconSize, tintColor)
                        else -> null
                    }
                } catch (_: Exception) { }
                if (icon == null) {
                    isVisible = false
                }
            }
            rowPanel.add(iconLabel)

            // 垂直居中
            rowPanel.add(Box.createVerticalGlue())

            if (row.enabled) {
                // Swing 事件不冒泡：只给 rowPanel 加监听时，点文字/图标/前缀会被子组件截获而无响应。
                // 点击监听加到行及其全部后代组件上，保证点行内任意位置都触发；
                // 悬停监听只挂 rowPanel（子组件间移动不触发 rowPanel 的 exited，避免背景闪烁）。
                val clickListener = object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        dismiss()
                        row.action()
                    }
                }
                fun attachAll(c: Component) {
                    c.addMouseListener(clickListener)
                    if (c is Container) {
                        for (child in c.components) attachAll(child)
                    }
                }
                attachAll(rowPanel)
                rowPanel.addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        rowPanel.background = Color(0, 0, 0, 10)
                        rowPanel.isOpaque = true
                        rowPanel.repaint()
                    }
                    override fun mouseExited(e: MouseEvent) {
                        // 悬停离开时复位背景（只关 opaque 不清背景，下次进入前背景残留）
                        rowPanel.background = Color(0, 0, 0, 0)
                        rowPanel.isOpaque = false
                        rowPanel.repaint()
                    }
                })
            } else {
                rowPanel.cursor = Cursor.getDefaultCursor()
            }

            content.add(rowPanel)

            // 分隔线
            if (index < rows.size - 1) {
                val divider = JSeparator().apply {
                    maximumSize = Dimension(scaledWidth - scaledDividerMargin * 2, 1)
                    preferredSize = Dimension(scaledWidth - scaledDividerMargin * 2, 1)
                    foreground = Color(0xE5, 0xE5, 0xEA)
                }
                val divWrapper = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    border = EmptyBorder(0, scaledDividerMargin, 0, scaledDividerMargin)
                    add(divider)
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(scaledWidth, 1)
                }
                content.add(divWrapper)
            }
        }

        dlg.contentPane = content
        dlg.pack()
        dlg.size = Dimension(scaledWidth, dlg.height)
        dlg.validate()

        // 定位：优先锚点上方，上方空间不足时放锚点下方，屏幕钳制（安卓小圆角弹窗的桌面实现）
        // 用锚点所在的屏幕（多屏时不能用默认屏，否则弹窗可能出现在另一台显示器上）
        try {
            val gc = anchor.graphicsConfiguration
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val bounds = gc.bounds
            val anchorLoc = anchor.locationOnScreen
            var x = (anchorLoc.x + anchor.width - width)
                .coerceIn(bounds.x, (bounds.x + bounds.width - width).coerceAtLeast(bounds.x))
            var y = anchorLoc.y - dlg.height - 8
            if (y < bounds.y) {
                // 上方放不下：放锚点下方
                y = anchorLoc.y + anchor.height + 8
            }
            y = y.coerceAtMost((bounds.y + bounds.height - dlg.height).coerceAtLeast(bounds.y))
            dlg.setLocation(x, y)
        } catch (_: Throwable) {
            dlg.setLocationRelativeTo(owner)
        }

        dialog = dlg
        // 点外消失（AWTEventListener 全局监听按下）
        val listener = AWTEventListener { e ->
            if (e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED) {
                val ev = e
                val d = dialog
                SwingUtilities.invokeLater {
                    try {
                        if (d != null && !d.bounds.contains(ev.locationOnScreen)) {
                            dismiss()
                        }
                    } catch (_: Throwable) { }
                }
            }
        }
        outsideListener = listener
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        dlg.isVisible = true
    }

    private var outsideListener: AWTEventListener? = null

    fun dismiss() {
        try {
            outsideListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
            outsideListener = null
        } catch (_: Throwable) { }
        try { dialog?.isVisible = false } catch (_: Throwable) { }
        try { dialog?.dispose() } catch (_: Throwable) { }
        dialog = null
    }
}
