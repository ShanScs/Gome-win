package com.muse.gomepc.ui

import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import com.muse.gomepc.player.MpvPlayer
import com.muse.gomepc.player.DebugLog

/**
 * AWT 原生工具栏面板：1:1 移植安卓端 activity_player.xml。
 *
 * 顶栏：返回(X) + 标题(居中20sp粗体) + 右上网速/电量
 * 底栏：进度行(信息图标+当前时间+SeekBar+总时长) + 控制行(左5/中3/右5)
 */
class AwtToolbarPanel(
    private val player: MpvPlayer,
    private val itemName: String,
    private val onBack: () -> Unit,
    private val onFullscreen: () -> Unit,
    private val isVisibleState: () -> Boolean,
    private val getPaused: () -> Boolean,
    private val getTimePos: () -> Double,
    private val getDuration: () -> Double,
    private val onSeek: (Double) -> Unit,
    private val onAspect: (() -> Unit)? = null,
    private val onSpeed: (() -> Unit)? = null,
    private val onAudio: (() -> Unit)? = null,
    private val onSubtitle: (() -> Unit)? = null,
    private val onDanmakuMenu: (() -> Unit)? = null,
    private val onMore: (() -> Unit)? = null,
    private val onPlaylist: (() -> Unit)? = null,
    private val onPrev: (() -> Unit)? = null,
    private val onNext: (() -> Unit)? = null,
    private val getNetSpeed: (() -> String)? = null,
    private val onToggleDanmaku: (() -> Unit)? = null,
    private val isDanmakuEnabled: (() -> Boolean)? = null
) : JPanel() {

    private fun icon(name: String, size: Int): Icon {
        return try {
            VectorIcon.get(name, size)
        } catch (_: Exception) {
            // 兜底：空图标
            object : Icon {
                override fun getIconWidth() = size
                override fun getIconHeight() = size
                override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {}
            }
        }
    }

    private val titleLabel = JLabel(itemName).apply {
        foreground = Color.WHITE
        font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        horizontalAlignment = SwingConstants.CENTER
    }
    private val speedLabel = JLabel("").apply {
        foreground = Color.WHITE
        font = Font(Font.SANS_SERIF, Font.BOLD, 15)
    }
    private val posLabel = JLabel("00:00").apply {
        foreground = Color.WHITE
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }
    private val durLabel = JLabel("-00:00").apply {
        foreground = Color.WHITE
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }
    private val seekBar = JSlider(0, 1000, 0)
    private var dragging = false

    private lateinit var playBtn: JButton
    private lateinit var lockBtn: JButton
    private var isLocked = false
    private val speedOptions = listOf(0.5, 1.0, 1.5, 2.0)
    private var speedIndex = 1

    // 可更新的回调（单例复用时更新）
    private var onPrevCb: (() -> Unit)? = onPrev
    private var onNextCb: (() -> Unit)? = onNext
    private var onPlaylistCb: (() -> Unit)? = onPlaylist
    private var getNetSpeedCb: (() -> String)? = getNetSpeed

    private lateinit var prevBtn: JButton
    private lateinit var nextBtn: JButton
    private lateinit var playlistBtn: JButton

    /** 单例复用时更新剧集导航回调 */
    fun updateEpisodeCallbacks(
        onPrev: (() -> Unit)?,
        onNext: (() -> Unit)?,
        onPlaylist: (() -> Unit)?
    ) {
        onPrevCb = onPrev
        onNextCb = onNext
        onPlaylistCb = onPlaylist
        prevBtn.isEnabled = onPrev != null
        nextBtn.isEnabled = onNext != null
        playlistBtn.isEnabled = onPlaylist != null
    }

    fun updateNetSpeedCallback(getNetSpeed: (() -> String)?) {
        getNetSpeedCb = getNetSpeed
    }

    private var onToggleDanmakuCb: (() -> Unit)? = onToggleDanmaku
    private var isDanmakuEnabledCb: (() -> Boolean)? = isDanmakuEnabled
    private lateinit var danmakuBtn: JButton

    fun updateDanmakuCallbacks(
        onToggleDanmaku: (() -> Unit)?,
        isDanmakuEnabled: (() -> Boolean)?
    ) {
        onToggleDanmakuCb = onToggleDanmaku
        isDanmakuEnabledCb = isDanmakuEnabled
        updateDanmakuBtnState()
    }

    private fun updateDanmakuBtnState() {
        if (!::danmakuBtn.isInitialized) return
        val on = try { isDanmakuEnabledCb?.invoke() } catch (_: Exception) { null } ?: true
        // 安卓：关闭时变暗 (alpha 0.4)
        danmakuBtn.isEnabled = true  // 保持可点，用图标透明度表示状态
    }

    data class TrackInfo(val id: Int, val title: String, val lang: String)

    private fun getTracks(type: String): List<TrackInfo> {
        val result = mutableListOf<TrackInfo>()
        return try {
            val count = player.getPropertyDouble("track-list/count")?.toInt() ?: 0
            for (i in 0 until count) {
                val t = player.getPropertyString("track-list/$i/type") ?: continue
                if (t != type) continue
                val id = player.getPropertyDouble("track-list/$i/id")?.toInt() ?: continue
                val title = player.getPropertyString("track-list/$i/title") ?: ""
                val lang = player.getPropertyString("track-list/$i/lang") ?: ""
                result.add(TrackInfo(id, title.ifEmpty { "$type $id" }, lang))
            }
            result
        } catch (_: Exception) {
            result
        }
    }

    init {
        isOpaque = false
        layout = BorderLayout()
        buildTopBar()
        buildBottomBar()

        // 定时刷新 UI
        Timer(500) {
            SwingUtilities.invokeLater { refresh() }
        }.start()
    }

    private fun toolButton(iconName: String, tooltip: String, onClick: (() -> Unit)?): JButton {
        return JButton(icon(iconName, 32)).apply {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            preferredSize = Dimension(40, 40)
            minimumSize = Dimension(40, 40)
            maximumSize = Dimension(40, 40)
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            if (onClick != null) {
                addActionListener { onClick() }
            } else {
                isEnabled = false
            }
        }
    }

    private fun buildTopBar() {
        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(8, 8, 8, 8)
        }

        // 左：返回
        val backBtn = toolButton("ic_pl_close", "退出", onBack)
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(backBtn)
        }

        // 中：标题居中
        val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            add(titleLabel)
        }

        // 右：网速
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(speedLabel)
        }

        topBar.add(leftPanel, BorderLayout.WEST)
        topBar.add(centerPanel, BorderLayout.CENTER)
        topBar.add(rightPanel, BorderLayout.EAST)
        add(topBar, BorderLayout.NORTH)
    }

    private fun buildBottomBar() {
        val bottomBar = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(8, 12, 12, 12)
        }

        // 进度行：信息图标 + 当前时间 + SeekBar + 总时长
        val progressRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        val infoIcon = JLabel(icon("ic_pl_info", 18))
        infoIcon.border = EmptyBorder(0, 0, 0, 6)
        progressRow.add(infoIcon)
        progressRow.add(posLabel)

        seekBar.apply {
            isOpaque = false
            preferredSize = Dimension(100, 24)
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            addChangeListener {
                if (!dragging && valueIsAdjusting) {
                    dragging = true
                }
                if (!valueIsAdjusting && dragging) {
                    dragging = false
                    val dur = getDuration().coerceAtLeast(1.0)
                    onSeek(value / 1000.0 * dur)
                }
            }
        }
        val seekWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(0, 8, 0, 8)
            add(seekBar, BorderLayout.CENTER)
        }
        progressRow.add(seekWrapper)
        progressRow.add(durLabel)

        // 控制行：左5 / 中3 / 右5
        val controlRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(4, 0, 0, 0)
        }

        // 左组：全屏 / 锁 / 倍速（PC 不需要视频缩放，缩放改为全屏）
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        // 全屏
        leftGroup.add(toolButton("ic_pl_aspect", "全屏") {
            onFullscreen()
        })
        // 锁
        lockBtn = toolButton("ic_pl_lock", "锁定", null).apply {
            addActionListener {
                isLocked = !isLocked
                isEnabled = !isLocked
            }
        }
        leftGroup.add(lockBtn)
        // 倍速：0.5x / 1.0x / 1.5x / 2.0x 循环
        leftGroup.add(toolButton("ic_pl_speed", "倍速") {
            speedIndex = (speedIndex + 1) % speedOptions.size
            val s = speedOptions[speedIndex]
            player.setProperty("speed", s.toString())
            DebugLog.d("UI", "倍速切换: ${s}x")
        })

        // 中组：上一集 / 播放暂停 / 下一集
        val centerGroup = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply { isOpaque = false }
        prevBtn = toolButton("ic_pl_prev", "上一集", null).apply {
            addActionListener { onPrevCb?.invoke() }
            isEnabled = onPrevCb != null
        }
        centerGroup.add(prevBtn)
        playBtn = toolButton("ic_pl_play", "播放/暂停", null).apply {
            addActionListener { player.togglePause() }
        }
        centerGroup.add(playBtn)
        nextBtn = toolButton("ic_pl_next", "下一集", null).apply {
            addActionListener { onNextCb?.invoke() }
            isEnabled = onNextCb != null
        }
        centerGroup.add(nextBtn)

        // 右组：音频 / 字幕 / 清晰度(弹幕菜单) / 更多 / 剧集列表
        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        rightGroup.add(toolButton("ic_pl_audio", "音频") { showAudioDialog() })
        rightGroup.add(toolButton("ic_pl_subtitle", "字幕") { showSubtitleDialog() })
        danmakuBtn = toolButton("ic_pl_definition", "弹幕") {
            onToggleDanmakuCb?.invoke()
            updateDanmakuBtnState()
        }
        rightGroup.add(danmakuBtn)
        rightGroup.add(toolButton("ic_pl_more", "更多") { showMoreMenu() })
        playlistBtn = toolButton("ic_pl_playlist", "剧集列表", null).apply {
            addActionListener { onPlaylistCb?.invoke() }
            isEnabled = onPlaylistCb != null
        }
        rightGroup.add(playlistBtn)

        controlRow.add(leftGroup, BorderLayout.WEST)
        controlRow.add(centerGroup, BorderLayout.CENTER)
        controlRow.add(rightGroup, BorderLayout.EAST)

        bottomBar.add(progressRow)
        bottomBar.add(controlRow)
        add(bottomBar, BorderLayout.SOUTH)
    }

    private fun refresh() {
        val visible = isVisibleState()
        isVisible = visible
        if (!visible) return

        // 播放/暂停图标
        try {
            val iconName = if (getPaused()) "ic_pl_play" else "ic_pl_pause"
            playBtn.icon = icon(iconName, 32)
        } catch (_: Exception) { }

        // 时间
        val pos = getTimePos()
        val dur = getDuration().coerceAtLeast(1.0)
        posLabel.text = formatTime(pos)
        durLabel.text = "-${formatTime((dur - pos).coerceAtLeast(0.0))}"
        if (!dragging) {
            seekBar.value = (pos / dur * 1000).toInt().coerceIn(0, 1000)
        }

        // 网速
        try {
            speedLabel.text = getNetSpeedCb?.invoke() ?: ""
        } catch (_: Exception) { }

        // 缓存进度：用 demuxer-cache-duration 显示缓冲条
        try {
            val cacheDur = player.getPropertyDouble("demuxer-cache-duration") ?: 0.0
            if (cacheDur > 0 && dur > 0) {
                val bufferedEnd = (pos + cacheDur).coerceAtMost(dur)
                // JSlider 不直接支持次进度，用 ToolTip 显示
                seekBar.toolTipText = "已缓冲: ${formatTime(bufferedEnd)}"
            }
        } catch (_: Exception) { }

        // 锁定时隐藏控制按钮（安卓行为）
        // 注：PC 端简化，暂不实现锁定隐藏
    }

    private fun formatTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        val h = s / 3600
        return if (h > 0) {
            "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60)
        } else {
            "%02d:%02d".format(s / 60, s % 60)
        }
    }

    /** 音频轨选择弹窗（磨砂风格，1:1 安卓） */
    private fun showAudioDialog() {
        val tracks = getTracks("audio")
        if (tracks.isEmpty()) {
            showTip("没有可用音频轨")
            return
        }
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        // 找到音频按钮作为锚点
        val anchor = findButtonByTooltip("音频") ?: this
        val currentAid = player.getPropertyString("aid")
        val rows = tracks.map { t ->
            val label = "${t.title}${if (t.lang.isNotEmpty()) " (${t.lang})" else ""}"
            FrostedPopup.Row(
                label = label,
                checked = currentAid == t.id.toString(),
                action = {
                    player.setProperty("aid", t.id.toString())
                    DebugLog.d("UI", "音频轨切换: ${t.id}")
                }
            )
        }
        FrostedPopup(owner, anchor).show(rows, width = 260)
    }

    /** 字幕轨选择弹窗（磨砂风格，1:1 安卓） */
    private fun showSubtitleDialog() {
        val tracks = getTracks("sub")
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        val anchor = findButtonByTooltip("字幕") ?: this
        val currentSid = player.getPropertyString("sid")
        val rows = mutableListOf<FrostedPopup.Row>()
        rows.add(
            FrostedPopup.Row(
                label = "关闭字幕",
                checked = currentSid == "no" || currentSid == null,
                action = {
                    player.setProperty("sid", "no")
                    DebugLog.d("UI", "字幕关闭")
                }
            )
        )
        tracks.forEach { t ->
            val label = "${t.title}${if (t.lang.isNotEmpty()) " (${t.lang})" else ""}"
            rows.add(
                FrostedPopup.Row(
                    label = label,
                    checked = currentSid == t.id.toString(),
                    action = {
                        player.setProperty("sid", t.id.toString())
                        DebugLog.d("UI", "字幕轨切换: ${t.id}")
                    }
                )
            )
        }
        FrostedPopup(owner, anchor).show(rows, width = 260)
    }

    private fun findButtonByTooltip(tooltip: String): JButton? {
        return components.flatMap {
            when (it) {
                is JButton -> listOf(it)
                is Container -> it.components.filterIsInstance<JButton>()
                else -> emptyList()
            }
        }.firstOrNull { it.toolTipText == tooltip }
    }

    private fun showTip(msg: String) {
        JOptionPane.showMessageDialog(this, msg, "提示", JOptionPane.INFORMATION_MESSAGE)
    }

    /** 弹幕菜单（安卓 btnDefinition 对应弹幕菜单） */
    private fun showDanmakuMenu() {
        val options = arrayOf("启用/禁用弹幕", "弹幕设置")
        val selected = JOptionPane.showInputDialog(
            this, "弹幕", "弹幕",
            JOptionPane.PLAIN_MESSAGE, null, options, options[0]
        ) as? String ?: return
        when (selected) {
            "启用/禁用弹幕" -> {
                // TODO: 接入 PC 弹幕开关
                JOptionPane.showMessageDialog(this, "弹幕开关功能开发中", "弹幕", JOptionPane.INFORMATION_MESSAGE)
            }
            "弹幕设置" -> {
                JOptionPane.showMessageDialog(this, "弹幕设置功能开发中", "弹幕", JOptionPane.INFORMATION_MESSAGE)
            }
        }
    }

    /** 更多菜单（磨砂风格，1:1 安卓） */
    private fun showMoreMenu() {
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        val anchor = findButtonByTooltip("更多") ?: this
        val rows = listOf(
            FrostedPopup.Row(
                label = "视频信息",
                iconName = "ic_pl_info",
                action = { showMediaInfo() }
            ),
            FrostedPopup.Row(
                label = "打开日志目录",
                iconName = "ic_pl_info",
                action = {
                    try {
                        java.awt.Desktop.getDesktop().open(java.io.File(System.getProperty("user.home"), ".gome"))
                    } catch (e: Exception) {
                        showTip("无法打开: ${e.message}")
                    }
                }
            )
        )
        FrostedPopup(owner, anchor).show(rows)
    }

    /** 视频信息弹窗 */
    private fun showMediaInfo() {
        val info = StringBuilder()
        info.append("标题: $itemName\n")
        info.append("时长: ${formatTime(getDuration())}\n")
        info.append("当前: ${formatTime(getTimePos())}\n")
        info.append("速度: ${player.getPropertyDouble("speed") ?: 1.0}x\n")
        info.append("音量: ${(player.getPropertyDouble("volume") ?: 100.0).toInt()}%\n")
        player.getPropertyString("video-codec")?.let { info.append("视频编码: $it\n") }
        player.getPropertyString("audio-codec")?.let { info.append("音频编码: $it\n") }
        player.getPropertyDouble("width")?.let { w ->
            player.getPropertyDouble("height")?.let { h ->
                info.append("分辨率: ${w.toInt()}x${h.toInt()}\n")
            }
        }
        JOptionPane.showMessageDialog(this, info.toString(), "视频信息", JOptionPane.INFORMATION_MESSAGE)
    }

    override fun paintComponent(g: Graphics) {
        // 安卓：无阴影背景（注释写"无阴影"），直接透明
        // 保留轻微渐变以保证可读性
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // 顶栏：顶部渐变黑
        val topH = 64
        val topGrad = GradientPaint(0f, 0f, Color(0, 0, 0, 140), 0f, topH.toFloat(), Color(0, 0, 0, 0))
        g2.paint = topGrad
        g2.fillRect(0, 0, width, topH)
        // 底栏：底部渐变黑
        val botH = 110
        val botGrad = GradientPaint(0f, (height - botH).toFloat(), Color(0, 0, 0, 0), 0f, height.toFloat(), Color(0, 0, 0, 140))
        g2.paint = botGrad
        g2.fillRect(0, height - botH, width, botH)
        super.paintComponent(g)
    }
}
