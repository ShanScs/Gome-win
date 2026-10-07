package com.muse.gomepc.ui

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val isDanmakuEnabled: (() -> Boolean)? = null,
    private val onDanmakuPosition: ((Int) -> Unit)? = null,
    private val getDanmakuPosition: (() -> Int)? = null
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

    private lateinit var titleLabel: ShadowLabel
    private lateinit var speedLabel: ShadowLabel
    private lateinit var posLabel: ShadowLabel
    private lateinit var durLabel: ShadowLabel
    private lateinit var seekBar: SeekBar

    private lateinit var playBtn: PlButton
    private var isLocked = false
    private val speedOptions = listOf(0.5, 1.0, 1.5, 2.0)
    private var speedIndex = 1
    private val aspectModes = listOf("-1" to "自适应", "16:9" to "16:9", "4:3" to "4:3")
    private var aspectIndex = 0
    private val rotateModes = listOf(0, 90, 180, 270)
    private var rotateIndex = 0

    // 可更新的回调（单例复用时更新）
    private var onPrevCb: (() -> Unit)? = onPrev
    private var onNextCb: (() -> Unit)? = onNext
    private var onPlaylistCb: (() -> Unit)? = onPlaylist
    private var getNetSpeedCb: (() -> String)? = getNetSpeed

    private lateinit var prevBtn: PlButton
    private lateinit var nextBtn: PlButton
    private lateinit var playlistBtn: PlButton

    /** 单例复用时更新剧集导航回调 */
    fun updateEpisodeCallbacks(
        onPrev: (() -> Unit)?,
        onNext: (() -> Unit)?,
        onPlaylist: (() -> Unit)?
    ) {
        onPrevCb = onPrev
        onNextCb = onNext
        onPlaylistCb = onPlaylist
        if (::prevBtn.isInitialized) {
            prevBtn.putClientProperty("baseEnabled", onPrev != null)
            nextBtn.putClientProperty("baseEnabled", onNext != null)
            playlistBtn.putClientProperty("baseEnabled", onPlaylist != null)
            applyEnabledStates()
        }
    }

    fun updateNetSpeedCallback(getNetSpeed: (() -> String)?) {
        getNetSpeedCb = getNetSpeed
    }

    private var onToggleDanmakuCb: (() -> Unit)? = onToggleDanmaku
    private var isDanmakuEnabledCb: (() -> Boolean)? = isDanmakuEnabled
    private var onDanmakuPositionCb: ((Int) -> Unit)? = onDanmakuPosition
    private var getDanmakuPositionCb: (() -> Int)? = getDanmakuPosition
    private lateinit var danmakuBtn: PlButton

    fun updateDanmakuCallbacks(
        onToggleDanmaku: (() -> Unit)?,
        isDanmakuEnabled: (() -> Boolean)?,
        onDanmakuPosition: ((Int) -> Unit)?,
        getDanmakuPosition: (() -> Int)?
    ) {
        onToggleDanmakuCb = onToggleDanmaku
        isDanmakuEnabledCb = isDanmakuEnabled
        onDanmakuPositionCb = onDanmakuPosition
        getDanmakuPositionCb = getDanmakuPosition
        updateDanmakuBtnState()
    }

    private fun updateDanmakuBtnState() {
        if (!::danmakuBtn.isInitialized) return
        val on = try { isDanmakuEnabledCb?.invoke() } catch (_: Exception) { null } ?: true
        // 安卓：D 按钮关闭时 dim (alpha 0.4)，仍可点击
        danmakuBtn.dimmed = !on
        danmakuBtn.repaint()
    }

    /** 弹幕菜单（1:1 安卓） */
    private fun showDanmakuMenu() {
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        val anchor = danmakuBtn
        val enabled = try { isDanmakuEnabledCb?.invoke() } catch (_: Exception) { null } ?: true
        val pos = try { getDanmakuPositionCb?.invoke() } catch (_: Exception) { null } ?: 0
        val posLabels = listOf("顶部", "半屏", "全屏")
        val apiUrl = getDanmakuApiUrl()
        val apiLabel = if (apiUrl.isBlank()) "API-1（未设置）" else "API-1（已设置）"
        val rows = listOf(
            FrostedPopup.Row(
                label = "搜索弹幕",
                iconName = "ic_pl_search",
                action = { showDanmakuSearch() }
            ),
            FrostedPopup.Row(
                label = "本地导入",
                iconName = "ic_pl_import",
                action = { importDanmakuFile() }
            ),
            FrostedPopup.Row(
                label = "＞ $apiLabel",
                iconName = "ic_pl_api",
                action = { showDanmakuApiList() }
            ),
            FrostedPopup.Row(
                label = if (enabled) "禁用弹幕" else "启用弹幕",
                iconName = "ic_pl_danmaku",
                action = {
                    onToggleDanmakuCb?.invoke()
                    updateDanmakuBtnState()
                }
            ),
            FrostedPopup.Row(
                label = "＞ 弹幕位置：${posLabels.getOrNull(pos) ?: "顶部"}",
                iconName = "ic_pl_position",
                action = { showDanmakuPositionMenu() }
            ),
            FrostedPopup.Row(
                label = "弹幕设置",
                iconName = "ic_pl_setting",
                action = { showDanmakuSettings() }
            )
        )
        FrostedPopup(owner, anchor).show(rows, width = 260)
    }

    // 弹幕 API URL 存储
    private fun getDanmakuApiUrl(): String {
        return try {
            java.util.prefs.Preferences.userRoot().node("gome/danmaku").get("apiUrl", "")
        } catch (_: Exception) { "" }
    }

    private fun setDanmakuApiUrl(url: String) {
        try {
            java.util.prefs.Preferences.userRoot().node("gome/danmaku").put("apiUrl", url)
        } catch (_: Exception) { }
    }

    /** API 选择子菜单 */
    private fun showDanmakuApiList() {
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        val anchor = danmakuBtn
        val cur = getDanmakuApiUrl()
        val rows = listOf(
            FrostedPopup.Row(
                label = if (cur.isBlank()) "API-1：未设置" else "API-1：已设置",
                checked = cur.isNotBlank(),
                action = { showDanmakuApiInput() }
            ),
            FrostedPopup.Row(
                label = "清除API",
                iconName = "ic_pl_delete",
                action = {
                    setDanmakuApiUrl("")
                    showTip("API已清除")
                }
            )
        )
        FrostedPopup(owner, anchor).show(rows, width = 260)
    }

    /** API 输入对话框 */
    private fun showDanmakuApiInput() {
        val cur = getDanmakuApiUrl()
        val input = JTextField(cur, 30)
        val result = JOptionPane.showConfirmDialog(
            this, input, "设置弹幕API",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result == JOptionPane.OK_OPTION) {
            val url = input.text.trim()
            setDanmakuApiUrl(url)
            showTip(if (url.isBlank()) "API已清除" else "API已设置")
        }
    }

    /** 搜索弹幕：输入关键词 */
    private fun showDanmakuSearch() {
        val apiUrl = getDanmakuApiUrl()
        if (apiUrl.isBlank()) {
            showTip("请先在 API-1 中设置弹幕API")
            showDanmakuApiInput()
            return
        }
        val input = JTextField(30).apply {
            // hint 效果
        }
        val result = JOptionPane.showConfirmDialog(
            this, input, "搜索弹幕",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result == JOptionPane.OK_OPTION) {
            val kw = input.text.trim()
            if (kw.isNotEmpty()) {
                showTip("弹幕搜索功能开发中（需要弹幕后端支持）")
                DebugLog.d("UI", "搜索弹幕: $kw")
            }
        }
    }

    /** 本地导入：文件选择器 */
    private fun importDanmakuFile() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择弹幕文件"
        }
        val result = chooser.showOpenDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            showTip("弹幕导入功能开发中（需要弹幕解析器支持）")
            DebugLog.d("UI", "导入弹幕文件: ${file.absolutePath}")
        }
    }

    /** 弹幕设置 */
    private fun showDanmakuSettings() {
        showTip("弹幕设置：可在弹幕位置子菜单中调整显示区域")
    }

    /** 弹幕位置子菜单 */
    private fun showDanmakuPositionMenu() {
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        val anchor = danmakuBtn
        val current = try { getDanmakuPositionCb?.invoke() } catch (_: Exception) { null } ?: 0
        val labels = listOf("顶部", "半屏", "全屏")
        val rows = labels.mapIndexed { i, label ->
            FrostedPopup.Row(
                label = label,
                checked = current == i,
                action = {
                    onDanmakuPositionCb?.invoke(i)
                    DebugLog.d("UI", "弹幕位置: $label")
                }
            )
        }
        FrostedPopup(owner, anchor).show(rows, width = 220)
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

    private fun toolButton(
        iconName: String,
        tooltip: String,
        isLockButton: Boolean = false,
        onClick: (() -> Unit)?
    ): PlButton {
        return PlButton(iconName, tooltip, onClick, isLockButton)
    }

    private fun buildTopBar() {
        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(8, 8, 8, 8)
        }

        // 左：关闭 32dp（ic_pl_close 无圆圈，borderless）
        val backBtn = toolButton("ic_pl_close", "关闭", onClick = onBack)
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(backBtn)
        }

        // 中：标题 20sp 白加粗居中，maxWidth 280dp，阴影
        titleLabel = ShadowLabel(itemName, 20, bold = true).apply {
            horizontalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(280, 36)
            maximumSize = Dimension(280, 36)
        }
        val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            add(titleLabel)
        }

        // 右：网速 15sp 白加粗（电池图标已删）
        speedLabel = ShadowLabel("", 15, bold = true)
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(speedLabel)
        }

        topBar.add(leftPanel, BorderLayout.WEST)
        topBar.add(centerPanel, BorderLayout.CENTER)
        topBar.add(rightPanel, BorderLayout.EAST)
        add(topBar, BorderLayout.NORTH)
    }

    private fun buildBottomBar() {
        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(8, 12, 12, 12)
        }

        // 进度行：18dp info图标 + 12sp当前时间 + SeekBar(6dp三色轨道) + 12sp时长
        posLabel = ShadowLabel("00:00", 12, bold = false)
        durLabel = ShadowLabel("-00:00", 12, bold = false)
        val progressRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }
        val infoIcon = JLabel(icon("ic_pl_info", 18))
        infoIcon.border = EmptyBorder(0, 0, 0, 6)
        infoIcon.alignmentY = Component.CENTER_ALIGNMENT
        progressRow.add(infoIcon)
        progressRow.add(posLabel)

        seekBar = SeekBar { frac ->
            val dur = getDuration().coerceAtLeast(1.0)
            onSeek(frac.toDouble() * dur)
        }
        progressRow.add(seekBar)
        progressRow.add(durLabel)

        // 控制行：左5 / 中3 / 右5，全部 32×32dp 自定义绘制按钮
        val controlRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(4, 0, 0, 0)
        }

        // 左组：全屏 + 音量（用户要求）
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        leftGroup.add(toolButton("ic_pl_fullscreen", "全屏") { onFullscreen() })
        // 音量条（样式同进度条）
        val volumeBar = VolumeBar { vol ->
            player.setProperty("volume", vol.toString())
        }
        leftGroup.add(volumeBar)

        // 中组：上一集 / 播放暂停 / 下一集
        val centerGroup = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply { isOpaque = false }
        prevBtn = toolButton("ic_pl_prev", "上一集") { onPrevCb?.invoke() }
        centerGroup.add(prevBtn)
        playBtn = toolButton("ic_pl_play", "播放/暂停") { player.togglePause() }
        centerGroup.add(playBtn)
        nextBtn = toolButton("ic_pl_next", "下一集") { onNextCb?.invoke() }
        centerGroup.add(nextBtn)

        // 右组：音频 / 字幕 / 弹幕(D) / 更多 / 剧集列表
        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        rightGroup.add(toolButton("ic_pl_audio", "音频") { showAudioDialog() })
        rightGroup.add(toolButton("ic_pl_subtitle", "字幕") { showSubtitleDialog() })
        danmakuBtn = toolButton("ic_pl_definition", "弹幕") { showDanmakuMenu() }
        rightGroup.add(danmakuBtn)
        rightGroup.add(toolButton("ic_pl_more", "更多") { showMoreMenu() })
        playlistBtn = toolButton("ic_pl_playlist", "剧集列表") { onPlaylistCb?.invoke() }
        rightGroup.add(playlistBtn)

        controlRow.add(leftGroup, BorderLayout.WEST)
        controlRow.add(centerGroup, BorderLayout.CENTER)
        controlRow.add(rightGroup, BorderLayout.EAST)

        bottomBar.add(progressRow, BorderLayout.NORTH)
        bottomBar.add(controlRow, BorderLayout.CENTER)
        add(bottomBar, BorderLayout.SOUTH)

        applyEnabledStates()
        updateDanmakuBtnState()
    }

    private fun refresh() {
        val visible = isVisibleState()
        isVisible = visible
        if (!visible) return

        // 播放/暂停图标（一体绘制，直接换 icon 重画）
        try {
            playBtn.setIcon(if (getPaused()) "ic_pl_play" else "ic_pl_pause")
        } catch (_: Exception) { }

        // 时间 + 进度（6dp 三色轨道：已播白 / 缓冲黄 / 未播半透明白）
        val pos = getTimePos()
        val dur = getDuration().coerceAtLeast(1.0)
        val dispPos = if (seekBar.dragging) seekBar.dragFraction.toDouble() * dur else pos
        posLabel.text = formatTime(dispPos)
        durLabel.text = "-${formatTime((dur - dispPos).coerceAtLeast(0.0))}"
        if (!seekBar.dragging) {
            seekBar.posFrac = (pos / dur).toFloat().coerceIn(0f, 1f)
        }
        try {
            val cacheDur = player.getPropertyDouble("demuxer-cache-duration") ?: 0.0
            val bufEnd = (pos + cacheDur).coerceIn(0.0, dur)
            seekBar.bufFrac = (bufEnd / dur).toFloat().coerceIn(0f, 1f)
        } catch (_: Exception) { }
        seekBar.repaint()

        // 标题（280dp 截断省略）
        try {
            val fm = titleLabel.getFontMetrics(titleLabel.font)
            var t = itemName
            if (fm.stringWidth(t) > 280) {
                while (t.isNotEmpty() && fm.stringWidth("$t…") > 280) t = t.dropLast(1)
                t += "…"
            }
            if (titleLabel.text != t) titleLabel.text = t
        } catch (_: Exception) { }

        // 网速
        try {
            val s = getNetSpeedCb?.invoke() ?: ""
            if (speedLabel.text != s) speedLabel.text = s
        } catch (_: Exception) { }
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
            val label = "> ${t.title}${if (t.lang.isNotEmpty()) " (${t.lang})" else ""}"
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

    /** 字幕弹窗（1:1 安卓：关闭字幕 + 字幕大小） */
    private fun showSubtitleDialog() {
        val tracks = getTracks("sub")
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        val anchor = findButtonByTooltip("字幕") ?: this
        val currentSid = player.getPropertyString("sid")
        val rows = mutableListOf<FrostedPopup.Row>()

        // 关闭字幕
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
        // 字幕轨
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
        // 字幕大小（安卓原版：直接跟在轨道后面，无标题行）
        val sizes = listOf("小" to 36, "中" to 48, "大" to 60, "特大" to 72)
        val currentSize = player.getPropertyDouble("sub-font-size")?.toInt() ?: 48
        sizes.forEach { (label, size) ->
            rows.add(
                FrostedPopup.Row(
                    label = label,
                    checked = currentSize == size,
                    action = {
                        player.setProperty("sub-font-size", size.toString())
                        DebugLog.d("UI", "字幕大小: $label")
                    }
                )
            )
        }
        FrostedPopup(owner, anchor).show(rows, width = 260)
    }

    private fun findButtonByTooltip(tooltip: String): PlButton? {
        fun search(c: Container): PlButton? {
            for (child in c.components) {
                if (child is PlButton && child.toolTipText == tooltip) return child
                if (child is Container) {
                    val f = search(child)
                    if (f != null) return f
                }
            }
            return null
        }
        return search(this)
    }

    private var tipPopup: FrostedPopup? = null

    /** 轻提示（安卓 Toast 行为）：160dp 宽，1.5 秒自动消失，点外也消失 */
    private fun showTip(msg: String, anchorTooltip: String? = null) {
        try {
            val owner = SwingUtilities.getWindowAncestor(this) ?: return
            tipPopup?.dismiss()
            tipPopup = null
            val anchor = anchorTooltip?.let { findButtonByTooltip(it) } ?: this
            val popup = FrostedPopup(owner, anchor)
            popup.show(listOf(FrostedPopup.Row(label = msg, enabled = false)), width = 160)
            tipPopup = popup
            Timer(1500) {
                try {
                    if (tipPopup === popup) {
                        popup.dismiss()
                        tipPopup = null
                    }
                } catch (_: Exception) { }
            }.apply {
                isRepeats = false
                start()
            }
        } catch (_: Exception) { }
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
        // 规格 7.3：控制栏无背景阴影，直接压视频上，保持透明（文字自带阴影保证可读）
        super.paintComponent(g)
    }

    // ================= 自定义绘制组件（圆圈+图标一体绘制） =================

    /** 32×32dp 播放器按钮：圆圈与图标在一次 paintComponent 内画完，不可能错位 */
    private inner class PlButton(
        private var iconName: String,
        tooltip: String,
        private val onClick: (() -> Unit)?,
        val isLockButton: Boolean = false
    ) : JComponent() {
        var dimmed: Boolean = false
        private var pressed = false

        init {
            preferredSize = Dimension(32, 32)
            minimumSize = Dimension(32, 32)
            maximumSize = Dimension(32, 32)
            alignmentY = Component.CENTER_ALIGNMENT
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = false
            putClientProperty("baseEnabled", onClick != null)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!isEnabled) return
                    if (isLocked && !isLockButton) return
                    onClick?.invoke()
                }
                override fun mousePressed(e: MouseEvent) {
                    pressed = true
                    repaint()
                }
                override fun mouseReleased(e: MouseEvent) {
                    pressed = false
                    repaint()
                }
            })
        }

        fun setIcon(name: String) {
            if (iconName != name) {
                iconName = name
                repaint()
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                var alpha = 1f
                if (!isEnabled || dimmed) alpha = 0.4f
                if (pressed) alpha *= 0.65f
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                // 半透明背板：整个32×32都可点，不只线条
                g2.color = Color(0, 0, 0, 1)
                g2.fillOval(0, 0, width, height)
                // 圆圈+字形同一坐标系一次画完（留2px边距防裁边）
                try {
                    VectorIcon.get(iconName, 28).paintIcon(this, g2, 2, 2)
                } catch (_: Exception) { }
            } finally {
                g2.dispose()
            }
        }

        // 整个 32×32 都是点击区域，不只线条
        override fun contains(x: Int, y: Int): Boolean {
            return x in 0..width && y in 0..height
        }
    }

    /** 6dp 三色进度条：未播 #80FFFFFF / 缓冲 #FFD600 / 已播 #FFFFFF + 14dp 白圆 thumb */
    private inner class SeekBar(
        private val onSeekFrac: (Float) -> Unit
    ) : JComponent() {
        private val pad = 8
        var posFrac: Float = 0f
        var bufFrac: Float = 0f
        var dragging = false
        var dragFraction: Float = 0f
            private set

        init {
            preferredSize = Dimension(120, 28)
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            alignmentY = Component.CENTER_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = false
            val adapter = object : MouseAdapter() {
                private fun fracAt(x: Int): Float =
                    ((x - pad).toFloat() / (width - pad * 2).coerceAtLeast(1)).coerceIn(0f, 1f)
                override fun mousePressed(e: MouseEvent) {
                    if (!isEnabled) return
                    dragging = true
                    dragFraction = fracAt(e.x)
                    repaint()
                }
                override fun mouseDragged(e: MouseEvent) {
                    if (!dragging) return
                    dragFraction = fracAt(e.x)
                    repaint()
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (!dragging) return
                    dragging = false
                    dragFraction = fracAt(e.x)
                    onSeekFrac(dragFraction)
                    repaint()
                }
            }
            addMouseListener(adapter)
            addMouseMotionListener(adapter)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val trackH = 6
                val cy = height / 2f
                val y = (cy - trackH / 2f).toInt()
                val x0 = pad
                val w = (width - pad * 2).coerceAtLeast(1)
                val frac = if (dragging) dragFraction else posFrac
                // 未播 #80FFFFFF
                g2.color = Color(255, 255, 255, 128)
                g2.fillRoundRect(x0, y, w, trackH, trackH, trackH)
                // 缓冲 #FFD600
                val bw = (w * bufFrac).toInt()
                if (bw > 2) {
                    g2.color = Color(255, 214, 0)
                    g2.fillRoundRect(x0, y, bw, trackH, trackH, trackH)
                }
                // 已播 #FFFFFF
                val pw = (w * frac).toInt()
                if (pw > 2) {
                    g2.color = Color.WHITE
                    g2.fillRoundRect(x0, y, pw, trackH, trackH, trackH)
                }
                // thumb 14dp 白圆
                val tx = x0 + w * frac
                g2.color = if (isEnabled) Color.WHITE else Color(160, 160, 160)
                g2.fillOval((tx - 7).toInt(), (cy - 7).toInt(), 14, 14)
            } finally {
                g2.dispose()
            }
        }
    }

    /** 音量条：样式同 SeekBar（6dp轨道+14dp白圆），拖动调音量 */
    private inner class VolumeBar(
        private val onVolume: (Int) -> Unit
    ) : JComponent() {
        private val pad = 8
        var volFrac: Float = 1f
        private var dragging = false
        private var dragFraction: Float = 1f

        init {
            preferredSize = Dimension(100, 28)
            maximumSize = Dimension(100, 28)
            alignmentY = Component.CENTER_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isOpaque = false
            val adapter = object : MouseAdapter() {
                private fun fracAt(x: Int): Float =
                    ((x - pad).toFloat() / (width - pad * 2).coerceAtLeast(1)).coerceIn(0f, 1f)
                override fun mousePressed(e: MouseEvent) {
                    dragging = true
                    dragFraction = fracAt(e.x)
                    volFrac = dragFraction
                    repaint()
                }
                override fun mouseDragged(e: MouseEvent) {
                    if (!dragging) return
                    dragFraction = fracAt(e.x)
                    volFrac = dragFraction
                    repaint()
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (!dragging) return
                    dragging = false
                    dragFraction = fracAt(e.x)
                    volFrac = dragFraction
                    onVolume((volFrac * 100).toInt())
                    repaint()
                }
            }
            addMouseListener(adapter)
            addMouseMotionListener(adapter)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val trackH = 6
                val cy = height / 2f
                val y = (cy - trackH / 2f).toInt()
                val x0 = pad
                val w = (width - pad * 2).coerceAtLeast(1)
                val frac = if (dragging) dragFraction else volFrac
                g2.color = Color(255, 255, 255, 128)
                g2.fillRoundRect(x0, y, w, trackH, trackH, trackH)
                val pw = (w * frac).toInt()
                if (pw > 2) {
                    g2.color = Color.WHITE
                    g2.fillRoundRect(x0, y, pw, trackH, trackH, trackH)
                }
                val tx = x0 + w * frac
                g2.color = Color.WHITE
                g2.fillOval((tx - 7).toInt(), (cy - 7).toInt(), 14, 14)
            } finally {
                g2.dispose()
            }
        }

        override fun contains(x: Int, y: Int): Boolean {
            return x in 0..width && y in 0..height
        }
    }

    /** 带阴影文字（#CC000000 dy1）：顶栏/时间文字全部加阴影 */
    private class ShadowLabel(text: String, size: Int, bold: Boolean) : JLabel(text) {
        init {
            foreground = Color.WHITE
            font = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, size)
            isOpaque = false
            alignmentY = Component.CENTER_ALIGNMENT
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.font = font
                val fm = g2.fontMetrics
                val t = text ?: ""
                val tw = fm.stringWidth(t)
                val x = when (horizontalAlignment) {
                    SwingConstants.CENTER -> (width - tw) / 2
                    SwingConstants.RIGHT -> width - tw - 2
                    else -> 2
                }
                val y = (height - fm.height) / 2 + fm.ascent
                g2.color = Color(0, 0, 0, 204)
                g2.drawString(t, x, y + 1)
                g2.color = foreground
                g2.drawString(t, x, y)
            } finally {
                g2.dispose()
            }
        }
    }

    // ================= 按钮行为 =================

    private fun forEachPlButton(action: (PlButton) -> Unit) {
        fun search(c: Container) {
            for (child in c.components) {
                if (child is PlButton) action(child)
                if (child is Container) search(child)
            }
        }
        search(this)
    }

    /** 按锁定状态 + 各按钮自身可用状态刷新 enable */
    private fun applyEnabledStates() {
        forEachPlButton { btn ->
            val base = (btn.getClientProperty("baseEnabled") as? Boolean) ?: true
            btn.isEnabled = if (btn.isLockButton) true else (base && !isLocked)
            btn.repaint()
        }
        if (::seekBar.isInitialized) {
            seekBar.isEnabled = !isLocked
            seekBar.repaint()
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        applyEnabledStates()
        showTip(if (isLocked) "已锁定" else "已解锁", "锁定")
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speedOptions.size
        val s = speedOptions[speedIndex]
        player.setProperty("speed", s.toString())
        showTip("倍速：${s}x", "倍速")
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % aspectModes.size
        val (v, label) = aspectModes[aspectIndex]
        player.setProperty("video-aspect-override", v)
        showTip("画面比例：$label", "画面比例")
    }

    private fun cycleRotate() {
        rotateIndex = (rotateIndex + 1) % rotateModes.size
        player.setProperty("video-rotate", rotateModes[rotateIndex].toString())
        showTip("旋转：${rotateModes[rotateIndex]}°", "旋转")
    }

    private fun showCastDialog() {
        val owner = SwingUtilities.getWindowAncestor(this) ?: return
        val anchor = findButtonByTooltip("投屏") ?: this
        val rows = listOf(
            FrostedPopup.Row(label = "正在搜索设备…", enabled = false),
            FrostedPopup.Row(
                label = "重新搜索",
                iconName = "ic_pl_search",
                action = { showTip("正在搜索投屏设备…", "投屏") }
            )
        )
        FrostedPopup(owner, anchor).show(rows, width = 250)
    }
}
