package com.muse.gomepc.ui

import com.muse.gomepc.player.DebugLog
import com.muse.gomepc.player.MpvPlayer

/**
 * 工具栏窗口单例管理器：应用级生命周期，只创建一次，复用不重建。
 * Player 进入时显示+同步位置，退出时隐藏（不 dispose），应用退出时才 dispose。
 * 不管 invokeLater 怎么排队，永远只有一个窗口，从根上杜绝泄漏。
 */
object ToolbarWindowManager {
    private var window: javax.swing.JWindow? = null
    private var syncTimer: javax.swing.Timer? = null

    @Synchronized
    fun show(
        owner: javax.swing.JFrame,
        player: MpvPlayer,
        itemName: String,
        onBack: () -> Unit,
        onFullscreen: () -> Unit,
        isVisibleState: () -> Boolean,
        getPaused: () -> Boolean,
        getTimePos: () -> Double,
        getDuration: () -> Double,
        onSeek: (Double) -> Unit,
        getNetSpeed: (() -> String)? = null,
        onPrev: (() -> Unit)? = null,
        onNext: (() -> Unit)? = null,
        onPlaylist: (() -> Unit)? = null
    ) {
        javax.swing.SwingUtilities.invokeLater {
            try {
                if (window == null) {
                    val win = javax.swing.JWindow(owner).apply {
                        isAlwaysOnTop = true
                        background = java.awt.Color(0, 0, 0, 0)
                    }
                    val panel = AwtToolbarPanel(
                        player = player,
                        itemName = itemName,
                        onBack = {
                            // 不在这里 hide，避免死锁；交给 DisposableEffect 统一隐藏
                            onBack()
                        },
                        onFullscreen = onFullscreen,
                        isVisibleState = isVisibleState,
                        getPaused = getPaused,
                        getTimePos = getTimePos,
                        getDuration = getDuration,
                        onSeek = onSeek,
                        getNetSpeed = getNetSpeed,
                        onPrev = onPrev,
                        onNext = onNext,
                        onPlaylist = onPlaylist
                    )
                    win.contentPane.add(panel)
                    window = win
                    // 定时同步可见性和位置
                    syncTimer = javax.swing.Timer(200) {
                        val w = window ?: return@Timer
                        val v = try { isVisibleState() } catch (_: Throwable) { false }
                        if (w.isVisible != v) {
                            w.isVisible = v
                        }
                        if (v) {
                            try {
                                val p = owner.locationOnScreen
                                val b = java.awt.Rectangle(p.x, p.y, owner.width, owner.height)
                                if (w.bounds != b) w.bounds = b
                            } catch (_: Throwable) { }
                        }
                    }.apply { start() }
                    DebugLog.d("UI", "工具栏单例窗口已创建")
                }
                // 已存在：只更新位置+显示，不重建；确保 Timer 在跑
                val w = window ?: return@invokeLater
                // 更新面板回调（剧集切换时）
                try {
                    val panel = w.contentPane.getComponent(0) as? AwtToolbarPanel
                    panel?.updateEpisodeCallbacks(onPrev, onNext, onPlaylist)
                    panel?.updateNetSpeedCallback(getNetSpeed)
                } catch (_: Throwable) { }
                if (syncTimer == null) {
                    syncTimer = javax.swing.Timer(200) {
                        val ww = window ?: return@Timer
                        val v = try { isVisibleState() } catch (_: Throwable) { false }
                        if (ww.isVisible != v) {
                            ww.isVisible = v
                        }
                        if (v) {
                            try {
                                val p = owner.locationOnScreen
                                val b = java.awt.Rectangle(p.x, p.y, owner.width, owner.height)
                                if (ww.bounds != b) ww.bounds = b
                            } catch (_: Throwable) { }
                        }
                    }.apply { start() }
                }
                try {
                    val p = owner.locationOnScreen
                    w.bounds = java.awt.Rectangle(p.x, p.y, owner.width, owner.height)
                } catch (_: Throwable) { }
                w.isVisible = try { isVisibleState() } catch (_: Throwable) { false }
            } catch (t: Throwable) {
                DebugLog.d("UI", "工具栏显示失败: ${t.message}")
            }
        }
    }

    @Synchronized
    fun hide() {
        try { syncTimer?.stop() } catch (_: Throwable) { }
        syncTimer = null
        try {
            window?.isVisible = false
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun destroy() {
        try { syncTimer?.stop() } catch (_: Throwable) { }
        syncTimer = null
        try { window?.isVisible = false } catch (_: Throwable) { }
        try { window?.dispose() } catch (_: Throwable) { }
        window = null
    }
}
