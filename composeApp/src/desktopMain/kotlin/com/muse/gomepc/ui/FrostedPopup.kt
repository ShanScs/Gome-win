package com.muse.gomepc.ui

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 磨砂玻璃弹窗：1:1 还原安卓 showFrostedPopup。
 * 参数来自 popup_more_menu.xml / item_more_menu_row.xml / bg_more_popup.xml：
 * - 弹窗宽 210dp，上下 padding 6dp
 * - 背景 #D9FFFFFF，圆角 16dp
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

    fun show(rows: List<Row>, width: Int = POPUP_WIDTH) {
        dismiss()

        val dlg = JDialog(owner as? Frame, "", false).apply {
            isUndecorated = true
            background = Color(0, 0, 0, 0)
        }

        val content = object : JPanel() {
            init {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = EmptyBorder(CONTAINER_PADDING_V, 0, CONTAINER_PADDING_V, 0)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // bg_more_popup: #D9FFFFFF, 16dp 圆角
                g2.color = Color(255, 255, 255, 217) // D9 = 217
                g2.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS)
                super.paintComponent(g)
            }
        }

        rows.forEachIndexed { index, row ->
            val rowPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = EmptyBorder(0, ROW_PADDING_H, 0, ROW_PADDING_H)
                preferredSize = Dimension(width, ROW_HEIGHT)
                maximumSize = Dimension(width, ROW_HEIGHT)
                minimumSize = Dimension(width, ROW_HEIGHT)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
            }

            // "›" 前缀：安卓布局写死，每行都有
            val prefix = JLabel("›").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, PREFIX_SIZE)
                foreground = Color(0xB0, 0xB0, 0xB0)
                border = EmptyBorder(0, 0, 0, PREFIX_MARGIN)
            }
            rowPanel.add(prefix)

            // 文字 (weight=1, 居左)
            val textLabel = JLabel(row.label).apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, LABEL_SIZE)
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
                preferredSize = Dimension(ICON_SIZE, ICON_SIZE)
                maximumSize = Dimension(ICON_SIZE, ICON_SIZE)
                minimumSize = Dimension(ICON_SIZE, ICON_SIZE)
                val tintColor = Color(0x8E, 0x8E, 0x93)
                try {
                    icon = when {
                        row.checked -> VectorIcon.get("ic_check", ICON_SIZE, tintColor)
                        row.iconName != null -> VectorIcon.get(row.iconName, ICON_SIZE, tintColor)
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
                rowPanel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        dismiss()
                        row.action()
                    }
                    override fun mouseEntered(e: MouseEvent) {
                        rowPanel.background = Color(0, 0, 0, 10)
                        rowPanel.isOpaque = true
                    }
                    override fun mouseExited(e: MouseEvent) {
                        rowPanel.isOpaque = false
                    }
                })
            } else {
                rowPanel.cursor = Cursor.getDefaultCursor()
            }

            content.add(rowPanel)

            // 分隔线
            if (index < rows.size - 1) {
                val divider = JSeparator().apply {
                    maximumSize = Dimension(width - DIVIDER_MARGIN_H * 2, 1)
                    preferredSize = Dimension(width - DIVIDER_MARGIN_H * 2, 1)
                    foreground = Color(0xE5, 0xE5, 0xEA)
                }
                val divWrapper = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    border = EmptyBorder(0, DIVIDER_MARGIN_H, 0, DIVIDER_MARGIN_H)
                    add(divider)
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(width, 1)
                }
                content.add(divWrapper)
            }
        }

        dlg.contentPane = content
        dlg.pack()
        dlg.size = Dimension(width, dlg.height)

        // 定位：锚定按钮上方，右对齐
        try {
            val anchorLoc = anchor.locationOnScreen
            val x = anchorLoc.x + anchor.width - width
            val y = anchorLoc.y - dlg.height - 8
            dlg.setLocation(x.coerceAtLeast(0), y.coerceAtLeast(0))
        } catch (_: Throwable) {
            dlg.setLocationRelativeTo(owner)
        }

        dialog = dlg
        dlg.isVisible = true
    }

    fun dismiss() {
        try { dialog?.isVisible = false } catch (_: Throwable) { }
        try { dialog?.dispose() } catch (_: Throwable) { }
        dialog = null
    }
}
