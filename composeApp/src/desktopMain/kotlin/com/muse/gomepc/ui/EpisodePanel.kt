package com.muse.gomepc.ui

import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.*
import javax.swing.border.EmptyBorder
import com.muse.gomepc.emby.YambyClient
import com.muse.gomepc.player.DebugLog

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
        val playTicks: Long = 0L,
        val played: Boolean = false
    )

    private var dialog: JDialog? = null
    private var outsideClickListener: AWTEventListener? = null
    /** 键盘监听（方向键/回车/ESC）：面板关闭时移除，避免泄漏 */
    private var panelKeyListener: AWTEventListener? = null
    /** 滚轮余数：触控板小增量不再被 .toInt() 截断丢掉，攒够 1px 再滚 */
    private var wheelRemainder = 0.0
    // 缩略图加载：固定线程池 + 任务列表（关闭面板时取消未完成的）+ URL→图标缓存
    private val thumbFutures = mutableListOf<Future<*>>()
    // 缩略图统计（本面板一次 rebuild）：汇总日志证明加载是否真正发起，
    // 区分“客户端没发起加载”（bug）与“服务端 404 无图”（服务端缺图）
    private var thumbStatHit = 0
    private var thumbStatMiss = 0

    companion object {
        private val thumbExecutor = Executors.newFixedThreadPool(4)
        private val thumbCache = ConcurrentHashMap<String, ImageIcon>()
        private const val THUMB_MAX_BYTES = 10 * 1024 * 1024
    }

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
        thumbFutures.clear()
        val dlg = JDialog(owner as? Frame, "剧集", false).apply {
            isUndecorated = true
            background = Color(0, 0, 0, 0)
            // 不用 isAlwaysOnTop：owned 对话框天然在宿主窗口之上；alwaysOnTop 会导致
            // 切到别的 App 时本面板仍压在最上面。滚轮由本面板的显式处理器消费，不冒泡。
            isAlwaysOnTop = false
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
        // 搜索框：按集名过滤（之前无搜索，集多时只能滚）
        var filterText = ""
        fun currentFiltered(): List<EpisodeInfo> =
            if (filterText.isBlank()) episodes
            else episodes.filter { it.name.contains(filterText, ignoreCase = true) }
        fun rebuildList() {
            thumbStatHit = 0
            thumbStatMiss = 0
            listPanel.removeAll()
            for (ep in currentFiltered()) {
                val i = episodes.indexOf(ep)
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
            // 汇总日志：证明本次打开面板时缩略图加载是否真正发起。
            // 若这里“发起加载”>0 但随后全是 404，即服务端该剧无集缩略图，非客户端 bug。
            DebugLog.d("Thumb", "缩略图：共 ${currentFiltered().size} 集，缓存命中 $thumbStatHit，本次发起加载 $thumbStatMiss")
            listPanel.revalidate()
            listPanel.repaint()
        }
        val searchField = JTextField().apply {
            border = javax.swing.border.EmptyBorder(4, 20, 4, 20)
        }
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun onChange() { filterText = searchField.text; rebuildList() }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { onChange() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { onChange() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { onChange() }
        })
        content.add(JLabel("搜索剧集").apply {
            font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            foreground = Color(0x8A, 0x8A, 0x8A)
            border = EmptyBorder(2, 20, 0, 20)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        content.add(searchField)
        rebuildList()
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
            // 滚轮：显式处理，关默认。默认处理器在缩略图异步加载（revalidate）
            // 与弹幕 JWindow 周期性 setBounds 重定位的共同作用下，滚动路径会中途
            // 被抢走/改变，滑着滑着方向看起来就翻了。这里只留一个处理器：方向
            // 固定为标准（滚轮向下 wheelRotation>0 → bar.value 增大 → 看后面的集），
            // 事件消费掉不冒泡，值钳在合法范围。
            // 注意：1.0.342 的显式处理用了减号（取反），那是错的；这里用加号。
            isWheelScrollingEnabled = false
            addMouseWheelListener { e ->
                val bar = verticalScrollBar
                val delta = if (e.scrollType == java.awt.event.MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
                    e.wheelRotation * bar.visibleAmount
                } else {
                    val units = if (e.preciseWheelRotation != 0.0) e.preciseWheelRotation else e.unitsToScroll.toDouble()
                    // 触控板小增量（如 0.3）直接 .toInt() 会截成 0、永远滚不动：攒余数
                    val raw = units * bar.unitIncrement + wheelRemainder
                    val d = raw.toInt()
                    wheelRemainder = raw - d
                    d
                }
                if (delta != 0) {
                    val lo = bar.minimum
                    val hi = (bar.maximum - bar.visibleAmount).coerceAtLeast(lo)
                    val next = (bar.value + delta).coerceIn(lo, hi)
                    if (next != bar.value) {
                        bar.value = next
                        e.consume()
                    }
                }
            }
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

        // 键盘导航 + 全局 ESC：方向键移动选中、回车播放、ESC 关闭（之前 ESC 只在面板聚焦时生效，
        // 焦点在主窗口时按 ESC 关不掉；点外部关闭仍保留）
        var keyboardIndex = currentIndex
        fun highlightKeyboard() {
            for (ci in 0 until listPanel.componentCount) {
                val comp = listPanel.getComponent(ci)
                if (comp is JPanel) {
                    val rowIdx = ci / 2
                    comp.border = if (rowIdx == keyboardIndex) {
                        javax.swing.border.LineBorder(Color(0x2F, 0x7C, 0xF6), 2)
                    } else null
                }
            }
        }
        panelKeyListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        panelKeyListener = AWTEventListener { event ->
            if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED && dialog != null) {
                val filtered = currentFiltered()
                when (event.keyCode) {
                    KeyEvent.VK_ESCAPE -> SwingUtilities.invokeLater { dismiss() }
                    KeyEvent.VK_UP -> {
                        if (filtered.isNotEmpty()) {
                            keyboardIndex = (keyboardIndex - 1).coerceAtLeast(0)
                            highlightKeyboard()
                        }
                    }
                    KeyEvent.VK_DOWN -> {
                        if (filtered.isNotEmpty()) {
                            keyboardIndex = (keyboardIndex + 1).coerceAtMost(filtered.size - 1)
                            highlightKeyboard()
                        }
                    }
                    KeyEvent.VK_ENTER -> {
                        val ep = filtered.getOrNull(keyboardIndex)
                        if (ep != null) {
                            dismiss()
                            onSelect(ep, episodes.indexOf(ep))
                        }
                    }
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(panelKeyListener, AWTEvent.KEY_EVENT_MASK)

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

        // 缩略图 (221x124, arc 36)，居中，白框/当前蓝框
        // 白框用浅灰以便在白底上可见
        // 边框与图片用同一个圆角形状（同一 rect + 同一 arc）：旧代码边框画在
        // (1,1,226,129) arc 36，图片却放在 (3,3,221,124) arc 30，两个圆角
        // 不同心不等大，边框四个角与图片角错开，看起来像批锋/毛刺。
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
            override fun paint(g: Graphics) {
                super.paint(g)
                // 在子组件之后画边框：边框完整压在图片边缘上，与图片同心同弧，
                // 选中/未选中都用同一形状，只变颜色和线宽。
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = borderColor
                g2.stroke = BasicStroke(borderWidth.toFloat())
                g2.drawRoundRect(4, 4, THUMB_W, THUMB_H, THUMB_RADIUS, THUMB_RADIUS)
            }
        }
        val thumbLabel = JLabel().apply {
            setBounds(4, 4, THUMB_W, THUMB_H)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            // 无占位图：加载中/失败保持空白（1.0.354 备份样式）
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
        // 异步加载并圆角裁剪：固定线程池 + 连接/读取超时 + 面板关闭时取消 + 内存缓存
        // 加载开关查缓存是否命中，不用 `thumbLabel.icon == null`：1.0.355 曾因预置占位图
        // 导致 icon 恒非 null、加载永远不跑；查缓存同时提供命中/未命中统计。
        val thumbUrl = YambyClient.imageUrl(ep.id, "Primary", 400)
        val cachedThumb = thumbCache[thumbUrl]
        if (cachedThumb != null) {
            thumbStatHit++
            thumbLabel.icon = cachedThumb
        } else {
            thumbStatMiss++
            val future = thumbExecutor.submit {
                try {
                    val conn = java.net.URL(thumbUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000
                    // 认证走 Header：imageUrl 不再带 Token
                    conn.setRequestProperty("X-Emby-Authorization", YambyClient.authHeader())
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            // 失败必须记日志且说清定性：404 = 服务端该集无缩略图（属服务端缺图，
                            // 非客户端 bug，Emby 未生成集缩略图时即返回 404）；401 = 认证失败；
                            // 其余按 HTTP 码排查。URL 不含 Token，可直接记。
                            if (code == 404) {
                                DebugLog.d("Thumb", "缩略图：服务端该集无缩略图（HTTP 404，属服务端缺图，非客户端 bug）：集=${ep.name} id=${ep.id} url=$thumbUrl")
                            } else if (code == 401) {
                                DebugLog.d("Thumb", "缩略图加载失败：认证失败（HTTP 401），请重新登录：集=${ep.name} id=${ep.id} url=$thumbUrl")
                            } else {
                                DebugLog.d("Thumb", "缩略图加载失败：集=${ep.name} id=${ep.id} url=$thumbUrl HTTP=$code")
                            }
                            return@submit
                        }
                        val bytes = readUpTo(conn.inputStream, THUMB_MAX_BYTES)
                        if (bytes == null) {
                            DebugLog.d("Thumb", "缩略图加载失败：集=${ep.name} id=${ep.id} url=$thumbUrl 原因=响应超限或读取失败")
                            return@submit
                        }
                        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                        if (img == null) {
                            DebugLog.d("Thumb", "缩略图解码失败：集=${ep.name} id=${ep.id} url=$thumbUrl 原因=ImageIO 返回 null")
                            return@submit
                        }
                        // 内图与边框用同一圆角（同一 rect + 同一 arc = THUMB_RADIUS），同心无批锋
                        val rounded = makeRounded(img, THUMB_W, THUMB_H, THUMB_RADIUS)
                        val icon = ImageIcon(rounded)
                        // 缩略图缓存上限 200 条：之前无界增长
                        if (thumbCache.size >= 200) {
                            thumbCache.keys.firstOrNull()?.let { thumbCache.remove(it) }
                        }
                        thumbCache[thumbUrl] = icon
                        SwingUtilities.invokeLater {
                            // 面板已关闭则丢弃，避免往已 dispose 的组件写图标
                            if (dialog != null) thumbLabel.icon = icon
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    // 加载失败：保持空白（1.0.354 备份样式，无占位图），并记日志（URL、原因），不再静默吞掉
                    DebugLog.d("Thumb", "缩略图加载异常：集=${ep.name} id=${ep.id} url=$thumbUrl 原因=${e.message}")
                }
            }
            thumbFutures.add(future)
        }

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

        // 元信息行：时长 / 已看进度 / 大小 / 已看完（之前只显示集名，无进度/已看/时长）
        val metaParts = mutableListOf<String>()
        if (ep.durationTicks > 0) metaParts.add(YambyClient.durShort(ep.durationTicks))
        if (ep.playTicks > 0 && ep.durationTicks > 0) {
            metaParts.add("已看 ${(ep.playTicks * 100 / ep.durationTicks).toInt()}%")
        }
        if (ep.sizeBytes > 0) {
            YambyClient.sizeLabel(ep.sizeBytes).takeIf { it.isNotEmpty() }?.let { metaParts.add(it) }
        }
        if (ep.played) metaParts.add("已看完")
        if (metaParts.isNotEmpty()) {
            val metaLabel = JLabel(metaParts.joinToString(" · ")).apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                foreground = Color(0x8A, 0x8A, 0x8A)
                alignmentX = Component.CENTER_ALIGNMENT
                horizontalAlignment = SwingConstants.CENTER
            }
            val metaWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
                isOpaque = false
                alignmentX = Component.CENTER_ALIGNMENT
                maximumSize = Dimension(THUMB_W, 18)
            }
            metaWrapper.add(metaLabel)
            row.add(metaWrapper)
        }
        // 不设 row.toolTipText：悬停提示会在行上方弹出一个浮动灰色气泡（带尖角），
        // 看起来像卡片上面多了一块来路不明的灰色东西，容易误认成 UI 元素。完整集名在详情页/播放器可见。

        // Swing 事件不冒泡：点击监听加到行及其全部后代组件上（缩略图/徽标/标题都会截获事件）
        val rowClickListener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                dismiss()
                onSelect(ep, index)
            }
        }
        fun attachAll(c: Component) {
            c.addMouseListener(rowClickListener)
            if (c is Container) {
                for (child in c.components) attachAll(child)
            }
        }
        attachAll(row)

        return row
    }

    /** 带上限读取：超限返回 null（防超大图片撑爆内存） */
    private fun readUpTo(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Exception) { null }
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

    fun dismiss() {
        // 取消未完成的缩略图加载任务，避免关闭后仍在后台下载/写已销毁组件
        for (f in thumbFutures) {
            try { f.cancel(true) } catch (_: Exception) { }
        }
        thumbFutures.clear()
        try {
            outsideClickListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        } catch (_: Throwable) { }
        outsideClickListener = null
        try {
            panelKeyListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        } catch (_: Throwable) { }
        panelKeyListener = null
        try { dialog?.isVisible = false } catch (_: Throwable) { }
        try { dialog?.dispose() } catch (_: Throwable) { }
        dialog = null
    }
}
