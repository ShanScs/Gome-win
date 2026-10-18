package com.muse.gomepc.ui

import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
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
        val sizeBytes: Long = 0L,
        val playTicks: Long = 0L
    )

    private var dialog: JDialog? = null
    private var outsideClickListener: AWTEventListener? = null

    // dp = px
    private val THUMB_W = 221
    private val THUMB_H = 124
    private val THUMB_RADIUS = 36
    private val ROW_RADIUS = 36
    private val ROW_PAD_H = 3
    private val ROW_PAD_V = 4
    private val TITLE_SIZE = 14
    private val TITLE_MARGIN_TOP = 2
    private val PANEL_W = 265
    private val CARD_SPACING = 6

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
                // 不透明背景 #FFFFFF 16dp圆角
                g2.color = Color(255, 255, 255)
                g2.fillRoundRect(0, 0, width, height, 36, 36)
                super.paintComponent(g)
            }
        }
        content.isOpaque = false

        // 标题
        val titleLabel = JLabel("剧集").apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 17)
            foreground = Color(0x1A, 0x1A, 0x1A)
            border = EmptyBorder(4, 20, 4, 20)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        content.add(titleLabel)

        // 剧集列表：用垂直BoxLayout，每行固定高度不拉伸
        val listPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        episodes.forEachIndexed { i, ep ->
            val row = createEpisodeRow(ep, i, i == currentIndex)
            // 包装一层防止拉伸
            val wrapper = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(PANEL_W - 12, row.preferredSize.height + 8)
                add(row, BorderLayout.CENTER)
            }
            listPanel.add(wrapper)
            listPanel.add(Box.createVerticalStrut(CARD_SPACING))
        }
        val scroll = JScrollPane(listPanel).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = EmptyBorder(0, 6, 8, 6)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            // 隐藏滚动条但保留滚轮滚动功能
            verticalScrollBar.preferredSize = java.awt.Dimension(0, 0)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(320, 333)
            // 鼠标滚轮加速
            verticalScrollBar.unitIncrement = 40
        }
        content.add(scroll)

        dlg.contentPane = content
        dlg.pack()
        dlg.size = Dimension(PANEL_W, 400)

        try {
            val ownerLoc = owner.locationOnScreen
            val x = ownerLoc.x + owner.width - dlg.width - 12
            val y = ownerLoc.y + (owner.height - dlg.height) / 2
            dlg.setLocation(x, y)
        } catch (_: Throwable) {
            dlg.setLocationRelativeTo(owner)
        }

        // ESC 关闭面板
        dlg.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closePanel")
        dlg.rootPane.actionMap.put("closePanel", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { dismiss() }
        })

        // 点击面板外部关闭（对齐安卓底部弹窗点外部关闭）
        outsideClickListener = AWTEventListener { event ->
            if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) {
                val d = dialog
                if (d != null && d.isVisible) {
                    try {
                        if (!d.bounds.contains(event.locationOnScreen)) {
                            SwingUtilities.invokeLater { dismiss() }
                        }
                    } catch (_: Throwable) { }
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(outsideClickListener, AWTEvent.MOUSE_EVENT_MASK)

        dialog = dlg
        // 先算好高度再显示，避免显示后resize导致卡死
        try {
            val ownerH = owner.height
            // 背板上下到窗口边框都是 80px
            val topBottomMargin = 80
            val panelH = (ownerH - topBottomMargin * 2).coerceAtLeast(300)
            val ownerLoc = owner.locationOnScreen
            val x = ownerLoc.x + owner.width - PANEL_W - 12
            val y = ownerLoc.y + topBottomMargin
            dlg.setSize(PANEL_W, panelH)
            dlg.setLocation(x, y)
        } catch (_: Throwable) {
            try { dlg.setLocationRelativeTo(owner) } catch (_: Throwable) { }
        }
        dlg.isVisible = true
        // 只做滚动，不调尺寸
        SwingUtilities.invokeLater {
            try {
                val compIndex = currentIndex * 2
                if (compIndex < listPanel.componentCount) {
                    val comp = listPanel.getComponent(compIndex)
                    val rect = comp.bounds
                    if (rect.height > 0) {
                        val viewRect = scroll.viewport.viewRect
                        val y = rect.y - (viewRect.height - rect.height) / 2
                        scroll.viewport.viewPosition = java.awt.Point(0, y.coerceAtLeast(0))
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun createEpisodeRow(ep: EpisodeInfo, index: Int, isCurrent: Boolean): JPanel {
        val bgColor = if (isCurrent) Color(0x2F, 0x7C, 0xF6) else Color.WHITE
        val titleColor = if (isCurrent) Color(0x2F, 0x7C, 0xF6) else Color(0x1A, 0x1A, 0x1A)
        val row = object : JPanel() {
            init {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = EmptyBorder(ROW_PAD_V, ROW_PAD_H, ROW_PAD_V, ROW_PAD_H)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
                // 卡片高度：缩略图112 + 间距8 + 标题约20 + 上下padding
                val rowH = ROW_PAD_V * 2 + THUMB_H + TITLE_MARGIN_TOP + 22
                preferredSize = Dimension(PANEL_W - 12, rowH)
                maximumSize = Dimension(PANEL_W - 12, rowH)
                minimumSize = Dimension(PANEL_W - 12, rowH)
            }
            override fun paintComponent(g: Graphics) {
                // 取消小卡片背景：透明，不画圆角矩形
                super.paintComponent(g)
            }
        }

        // 缩略图 (200x112, 56px圆角)，居中，白框/当前蓝框
        // 白框用浅灰以便在白底上可见
        val borderColor = if (isCurrent) Color(0x2F, 0x7C, 0xF6) else Color(0xE0, 0xE0, 0xE0)
        val borderWidth = if (isCurrent) 3 else 2
        val thumbPanel = object : JPanel(null) {
            init {
                isOpaque = false
                preferredSize = Dimension(THUMB_W + 8, THUMB_H + 8)
                maximumSize = Dimension(THUMB_W + 8, THUMB_H + 8)
                minimumSize = Dimension(THUMB_W + 8, THUMB_H + 8)
                alignmentX = Component.CENTER_ALIGNMENT
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = borderColor
                g2.stroke = BasicStroke(borderWidth.toFloat())
                val inset = borderWidth / 2
                g2.drawRoundRect(inset, inset, width - borderWidth, height - borderWidth, THUMB_RADIUS, THUMB_RADIUS)
            }
        }
        val thumbLabel = JLabel().apply {
            setBounds(3, 3, THUMB_W, THUMB_H)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        thumbPanel.add(thumbLabel)
        // 当前集：缩略图左上角"播放中"徽标
        if (isCurrent) {
            val badge = JLabel("播放中").apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, 9)
                foreground = Color(0x2F, 0x7C, 0xF6)
                background = Color(255, 255, 255)
                isOpaque = true
                border = EmptyBorder(2, 7, 2, 7)
                setBounds(6, 6, 44, 18)
            }
            thumbPanel.add(badge)
        }
        // 异步加载并圆角裁剪
        Thread {
            try {
                val url = YambyClient.imageUrl(ep.id, "Primary", 400)
                val img = javax.imageio.ImageIO.read(java.net.URL(url))
                if (img != null) {
                    // 内图圆角与边框内沿同心（边框3px时内沿圆角约30）
                    val rounded = makeRounded(img, THUMB_W, THUMB_H, THUMB_RADIUS - 6)
                    SwingUtilities.invokeLater { thumbLabel.icon = ImageIcon(rounded) }
                }
            } catch (_: Exception) {
                // 加载失败：保持空白，不显示占位符
            }
        }.start()

        row.add(thumbPanel)
        row.add(Box.createVerticalStrut(TITLE_MARGIN_TOP))

        // 文字：只显示第几集的剧集名字，居中
        val titleText = truncateTo10(ep.name)
        val titleLabel = JLabel(titleText).apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, TITLE_SIZE)
            foreground = titleColor
            alignmentX = Component.CENTER_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
        }
        // 限制宽度，居中
        val titleWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            maximumSize = Dimension(THUMB_W, 24)
        }
        titleWrapper.add(titleLabel)
        row.add(titleWrapper)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                dismiss()
                onSelect(ep, index)
            }
        })

        return row
    }

    /** 截断到10个汉字宽度，超出加省略号；去重"第X集"前缀 */
    private fun truncateTo10(s: String): String {
        // 去重：如果名字里已经有"第X集"，只保留一次
        var text = s
        val dupPattern = Regex("^第\\d+集\\s+第\\s*\\d+\\s*集")
        if (dupPattern.containsMatchIn(text)) {
            // 取后面的"第 X 集"部分
            val m = Regex("第\\s*\\d+\\s*集.*$").find(text)
            if (m != null) text = m.value
        }
        var width = 0.0
        val sb = StringBuilder()
        for (c in text) {
            // 汉字/全角算1，半角算0.5
            val w = if (c.code > 0xFF) 1.0 else 0.5
            if (width + w > 10) {
                sb.append("…")
                break
            }
            sb.append(c)
            width += w
        }
        return sb.toString()
    }

    private fun makeRounded(src: Image, w: Int, h: Int, radius: Int): BufferedImage {
        // 同步缩放到目标尺寸（getScaledInstance 是异步的，直接画会有残缺）
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val sg = scaled.createGraphics()
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        sg.drawImage(src, 0, 0, w, h, null)
        sg.dispose()
        // 先画白色圆角遮罩，再用 SrcAtop 把图片画进遮罩（边缘抗锯齿，比 clip 平滑）
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = out.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = java.awt.Color.WHITE
        g2.fillRoundRect(0, 0, w, h, radius, radius)
        g2.composite = java.awt.AlphaComposite.SrcAtop
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
        try {
            outsideClickListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        } catch (_: Throwable) { }
        outsideClickListener = null
        try { dialog?.isVisible = false } catch (_: Throwable) { }
        try { dialog?.dispose() } catch (_: Throwable) { }
        dialog = null
    }
}
