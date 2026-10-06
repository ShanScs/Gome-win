package com.muse.gomepc.ui

import java.awt.*
import javax.swing.*
import com.muse.gomepc.player.MpvPlayer
import com.muse.gomepc.player.DebugLog

/**
 * AWT 原生工具栏面板：走 GlassPane 渲染，不受 mpv HWND 压制。
 * 包含顶栏（返回+标题）和底栏（播放控制）。
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
    private val onSeek: (Double) -> Unit
) : JPanel() {

    private val topBar = JPanel()
    private val bottomBar = JPanel()
    private val playBtn = JButton("⏸")
    private val timeLabel = JLabel("00:00 / 00:00")
    private val progressSlider = JSlider(0, 1000, 0)
    private var dragging = false

    init {
        isOpaque = false
        layout = BorderLayout()

        // 顶栏
        topBar.isOpaque = false
        topBar.layout = FlowLayout(FlowLayout.LEFT)
        val backBtn = JButton("← 返回").apply {
            addActionListener { onBack() }
        }
        val titleLabel = JLabel(itemName).apply {
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD, 16f)
        }
        topBar.add(backBtn)
        topBar.add(titleLabel)

        // 底栏
        bottomBar.isOpaque = false
        bottomBar.layout = BorderLayout()
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply { isOpaque = false }

        playBtn.addActionListener {
            player.togglePause()
        }
        controls.add(playBtn)
        controls.add(timeLabel.apply { foreground = Color.WHITE })

        progressSlider.addChangeListener {
            if (!dragging && progressSlider.valueIsAdjusting) {
                dragging = true
            }
            if (!progressSlider.valueIsAdjusting && dragging) {
                dragging = false
                val dur = getDuration().coerceAtLeast(1.0)
                onSeek(progressSlider.value / 1000.0 * dur)
            }
        }
        controls.add(progressSlider)

        val fsBtn = JButton("全屏").apply {
            addActionListener { onFullscreen() }
        }
        controls.add(fsBtn)

        bottomBar.add(controls, BorderLayout.CENTER)

        add(topBar, BorderLayout.NORTH)
        add(bottomBar, BorderLayout.SOUTH)

        // 定时刷新 UI
        Timer(500) {
            SwingUtilities.invokeLater { refresh() }
        }.start()
    }

    private fun refresh() {
        val visible = isVisibleState()
        isVisible = visible
        if (!visible) return

        playBtn.text = if (getPaused()) "▶" else "⏸"
        val pos = getTimePos()
        val dur = getDuration().coerceAtLeast(1.0)
        timeLabel.text = "${formatTime(pos)} / ${formatTime(dur)}"
        if (!dragging) {
            progressSlider.value = (pos / dur * 1000).toInt().coerceIn(0, 1000)
        }
    }

    private fun formatTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    override fun paintComponent(g: Graphics) {
        // 半透明背景
        val g2 = g as Graphics2D
        g2.color = Color(0, 0, 0, 120)
        // 顶栏背景
        g2.fillRect(0, 0, width, 60)
        // 底栏背景
        g2.fillRect(0, height - 80, width, 80)
        super.paintComponent(g)
    }
}
