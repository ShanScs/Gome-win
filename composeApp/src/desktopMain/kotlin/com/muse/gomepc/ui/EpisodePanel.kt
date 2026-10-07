package com.muse.gomepc.ui

import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.border.EmptyBorder
import com.muse.gomepc.emby.YambyClient

/**
 * 剧集面板：1:1 还原安卓 item_episode_row.xml。
 * 参数：
 * - 缩略图 110x62dp，12dp 圆角
 * - 行 padding：左右 12dp，上下 10dp
 * - 行背景：透明 16dp 圆角；当前集 #B3FFFFFF 16dp 圆角
 * - 标题 14sp 粗体 #1A1A1A，单行
 * - meta 12sp #8E8E93，上边距 4dp，单行
 * - 文字区左边距 12dp
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

    // dp = px
    private val THUMB_W = 110
    private val THUMB_H = 62
    private val THUMB_RADIUS = 12
    private val ROW_RADIUS = 16
    private val ROW_PAD_H = 12
    private val ROW_PAD_V = 10
    private val TEXT_MARGIN_START = 12
    private val TITLE_SIZE = 14
    private val META_SIZE = 12
    private val META_MARGIN_TOP = 4

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
                // 磨砂背景 #D9FFFFFF 16dp圆角（与弹窗一致）
                g2.color = Color(255, 255, 255, 217)
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                super.paintComponent(g)
            }
        }
        content.isOpaque = false

        // 标题
        val titleLabel = JLabel("剧集").apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 17)
            foreground = Color(0x1A, 0x1A, 0x1A)
            border = EmptyBorder(20, 20, 12, 20)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        content.add(titleLabel)

        // 剧集列表
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
            preferredSize = Dimension(360, 500)
        }
        content.add(scroll)

        dlg.contentPane = content
        dlg.pack()
        dlg.size = Dimension(380, 600)

        try {
            val ownerLoc = owner.locationOnScreen
            val x = ownerLoc.x + owner.width - dlg.width - 12
            val y = ownerLoc.y + 12
            dlg.setLocation(x, y)
            dlg.size = Dimension(380, owner.height - 24)
        } catch (_: Throwable) {
            dlg.setLocationRelativeTo(owner)
        }

        dialog = dlg
        dlg.isVisible = true
    }

    private fun createEpisodeRow(ep: EpisodeInfo, index: Int, isCurrent: Boolean): JPanel {
        val row = object : JPanel() {
            init {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = EmptyBorder(ROW_PAD_V, ROW_PAD_H, ROW_PAD_V, ROW_PAD_H)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // bg_ep_row: 透明 16dp圆角；bg_ep_current: #B3FFFFFF 16dp圆角
                if (isCurrent) {
                    g2.color = Color(255, 255, 255, 179) // B3 = 179
                    g2.fillRoundRect(0, 0, width, height, ROW_RADIUS, ROW_RADIUS)
                }
                super.paintComponent(g)
            }
        }

        // 缩略图 (110x62, 12dp圆角)
        val thumbLabel = JLabel().apply {
            preferredSize = Dimension(THUMB_W, THUMB_H)
            maximumSize = Dimension(THUMB_W, THUMB_H)
            minimumSize = Dimension(THUMB_W, THUMB_H)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        // 异步加载并圆角裁剪
        Thread {
            try {
                val url = YambyClient.imageUrl(ep.id, "Primary", 300)
                val img = javax.imageio.ImageIO.read(java.net.URL(url))
                if (img != null) {
                    val rounded = makeRounded(img, THUMB_W, THUMB_H, THUMB_RADIUS)
                    SwingUtilities.invokeLater { thumbLabel.icon = ImageIcon(rounded) }
                }
            } catch (_: Exception) {
                // 加载失败：保持空白，不显示占位符
            }
        }.start()

        row.add(thumbLabel)
        row.add(Box.createHorizontalStrut(TEXT_MARGIN_START))

        // 文字区
        val textPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        val titleLabel = JLabel(ep.name).apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, TITLE_SIZE)
            foreground = Color(0x1A, 0x1A, 0x1A)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val metaLabel = JLabel(buildInfoLine(ep)).apply {
            font = Font(Font.SANS_SERIF, Font.PLAIN, META_SIZE)
            foreground = Color(0x8E, 0x8E, 0x93)
            border = EmptyBorder(META_MARGIN_TOP, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        textPanel.add(titleLabel)
        textPanel.add(metaLabel)
        // 垂直居中
        val textWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(textPanel, BorderLayout.WEST)
        }
        row.add(textWrapper)
        row.add(Box.createHorizontalGlue())

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                dismiss()
                onSelect(ep, index)
            }
        })

        return row
    }

    private fun makeRounded(src: Image, w: Int, h: Int, radius: Int): BufferedImage {
        val scaled = src.getScaledInstance(w, h, Image.SCALE_SMOOTH)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = out.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.clip = RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), radius.toFloat(), radius.toFloat())
        g2.drawImage(scaled, 0, 0, null)
        g2.dispose()
        return out
    }

    private fun buildInfoLine(ep: EpisodeInfo): String {
        val parts = mutableListOf<String>()
        if (ep.height > 0) {
            parts.add("${ep.height}P")
        } else if (ep.width > 0) {
            parts.add("${ep.width}P")
        }
        if (ep.durationTicks > 0) {
            val sec = (ep.durationTicks / 10_000_000).toInt()
            parts.add("%02d:%02d".format(sec / 60, sec % 60))
        }
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
