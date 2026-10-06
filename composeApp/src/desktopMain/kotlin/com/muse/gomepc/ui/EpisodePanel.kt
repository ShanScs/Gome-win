package com.muse.gomepc.ui

import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import com.muse.gomepc.emby.YambyClient

/**
 * 剧集面板：1:1 还原安卓 episodePanel。
 * 右侧浮层，缩略图 + 剧集名 + 分辨率·时长·大小。
 */
class EpisodePanel(
    private val owner: Window,
    private val episodes: List<EpisodeInfo>,
    private val currentIndex: Int,
    private val onSelect: (EpisodeInfo, Int) -> Unit
) {
    data class EpisodeInfo(
        val id: String,
        val index: Int,
        val name: String,
        val width: Int = 0,
        val height: Int = 0,
        val durationTicks: Long = 0L,
        val sizeBytes: Long = 0L
    )

    private var dialog: JDialog? = null

    fun show() {
        dismiss()
        val dlg = JDialog(owner as? Frame, "剧集", false).apply {
            isUndecorated = true
            background = Color(0, 0, 0, 0)
        }

        val content = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = EmptyBorder(0, 0, 0, 0)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // 磨砂背景
                g2.color = Color(245, 245, 247, 240)
                g2.fillRoundRect(0, 0, width, height, 20, 20)
                super.paintComponent(g)
            }
        }
        content.isOpaque = false

        // 标题
        val titleLabel = JLabel("剧集").apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 17)
            foreground = Color(26, 26, 26)
            border = EmptyBorder(20, 20, 12, 20)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        content.add(titleLabel)

        // 剧集列表（可滚动）
        val listPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        episodes.forEachIndexed { i, ep ->
            listPanel.add(createEpisodeRow(ep, i, i == currentIndex))
        }
        val scroll = JScrollPane(listPanel).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = EmptyBorder(0, 12, 12, 12)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(340, 500)
        }
        content.add(scroll)

        dlg.contentPane = content
        dlg.pack()
        dlg.size = Dimension(360, 600)

        // 定位：右侧，上下留边
        try {
            val ownerLoc = owner.locationOnScreen
            val x = ownerLoc.x + owner.width - dlg.width - 12
            val y = ownerLoc.y + 12
            dlg.setLocation(x, y)
            dlg.size = Dimension(360, owner.height - 24)
        } catch (_: Throwable) {
            dlg.setLocationRelativeTo(owner)
        }

        dialog = dlg
        dlg.isVisible = true
    }

    private fun createEpisodeRow(ep: EpisodeInfo, index: Int, isCurrent: Boolean): JPanel {
        val row = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            border = EmptyBorder(8, 8, 8, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // 当前集高亮
            if (isCurrent) {
                background = Color(0, 122, 255, 30)
                isOpaque = true
            }
        }

        // 缩略图
        val thumbLabel = JLabel().apply {
            preferredSize = Dimension(120, 68)
            horizontalAlignment = SwingConstants.CENTER
            // 异步加载缩略图
            Thread {
                try {
                    val url = YambyClient.imageUrl(ep.id, "Primary", 240)
                    val img = javax.imageio.ImageIO.read(java.net.URL(url))
                    if (img != null) {
                        val scaled = img.getScaledInstance(120, 68, Image.SCALE_SMOOTH)
                        SwingUtilities.invokeLater {
                            icon = ImageIcon(scaled)
                        }
                    }
                } catch (_: Exception) {
                    // 占位
                    SwingUtilities.invokeLater {
                        text = "🎬"
                        font = Font(Font.SANS_SERIF, Font.PLAIN, 24)
                    }
                }
            }.start()
        }
        // 圆角缩略图容器
        val thumbWrapper = object : JPanel() {
            init {
                isOpaque = false
                layout = BorderLayout()
                add(thumbLabel, BorderLayout.CENTER)
                preferredSize = Dimension(120, 68)
            }
        }

        // 文字信息
        val textPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        val nameLabel = JLabel(ep.name).apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            foreground = Color(26, 26, 26)
        }
        val infoLabel = JLabel(buildInfoLine(ep)).apply {
            font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            foreground = Color(120, 120, 120)
        }
        textPanel.add(nameLabel)
        textPanel.add(Box.createVerticalStrut(4))
        textPanel.add(infoLabel)

        row.add(thumbWrapper, BorderLayout.WEST)
        row.add(textPanel, BorderLayout.CENTER)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                dismiss()
                onSelect(ep, index)
            }
        })

        return row
    }

    private fun buildInfoLine(ep: EpisodeInfo): String {
        val parts = mutableListOf<String>()
        // 分辨率
        if (ep.height > 0) {
            parts.add("${ep.height}P")
        } else if (ep.width > 0) {
            parts.add("${ep.width}P")
        }
        // 时长
        if (ep.durationTicks > 0) {
            val sec = (ep.durationTicks / 10_000_000).toInt()
            parts.add("%02d:%02d".format(sec / 60, sec % 60))
        }
        // 大小
        if (ep.sizeBytes > 0) {
            val mb = ep.sizeBytes / 1024.0 / 1024.0
            parts.add(if (mb > 1024) "%.1fG".format(mb / 1024) else "%.0fM".format(mb))
        }
        return parts.joinToString(" · ")
    }

    fun dismiss() {
        try { dialog?.isVisible = false } catch (_: Throwable) { }
        try { dialog?.dispose() } catch (_: Throwable) { }
        dialog = null
    }
}
