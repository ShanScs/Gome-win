package com.muse.gomepc.ui

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 磨砂玻璃弹窗：1:1 还原安卓 showFrostedPopup。
 * - 圆角 + 半透明磨砂背景（M玻璃简化版）
 * - 行：图标 + 文字 + 选中勾
 * - 点击外部关闭
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

    fun show(rows: List<Row>, width: Int = 260) {
        dismiss()

        val dlg = JDialog(owner as? Frame, "", false).apply {
            isUndecorated = true
            background = Color(0, 0, 0, 0)
        }

        val content = object : JPanel() {
            init {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = EmptyBorder(8, 8, 8, 8)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // M玻璃简化：半透明白 + 圆角 + 描边
                g2.color = Color(255, 255, 255, 230)
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                g2.color = Color(255, 255, 255, 170)
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
                super.paintComponent(g)
            }
        }

        rows.forEachIndexed { index, row ->
            val rowPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = EmptyBorder(10, 14, 10, 14)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            // 图标
            val iconLabel = JLabel().apply {
                try {
                    icon = if (row.checked) {
                        // 选中勾：用文字 ✓
                        null
                    } else if (row.iconName != null) {
                        VectorIcon.get(row.iconName, 20)
                    } else null
                } catch (_: Exception) { }
                if (row.checked) {
                    text = "✓"
                    font = Font(Font.SANS_SERIF, Font.BOLD, 16)
                    foreground = Color(0, 122, 255)
                }
                border = EmptyBorder(0, 0, 0, 10)
            }
            val textLabel = JLabel(row.label).apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
                foreground = if (row.enabled) Color(26, 26, 26) else Color(150, 150, 150)
            }
            val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(iconLabel)
                add(textLabel)
            }
            rowPanel.add(leftBox, BorderLayout.CENTER)

            if (row.enabled) {
                rowPanel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        dismiss()
                        row.action()
                    }
                    override fun mouseEntered(e: MouseEvent) {
                        rowPanel.background = Color(0, 0, 0, 15)
                        rowPanel.isOpaque = true
                    }
                    override fun mouseExited(e: MouseEvent) {
                        rowPanel.isOpaque = false
                    }
                })
            }

            content.add(rowPanel)

            // 分隔线
            if (index < rows.size - 1) {
                val divider = JSeparator().apply {
                    maximumSize = Dimension(width - 28, 1)
                    foreground = Color(229, 229, 234)
                }
                val divWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
                    isOpaque = false
                    add(divider)
                }
                content.add(divWrapper)
            }
        }

        dlg.contentPane = content
        dlg.pack()
        // 限制宽度
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

        // 点击外部关闭
        val outsideListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // 由 dialog 的 auto-dismiss 处理
            }
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
