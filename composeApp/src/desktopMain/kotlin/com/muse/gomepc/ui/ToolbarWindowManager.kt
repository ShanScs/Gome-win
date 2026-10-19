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
    /** 最新可见性回调（切换选集时更新） */
    private var currentIsVisible: (() -> Boolean)? = null
    /** 最新音量回调（PlayerScreen 的 volume 是唯一状态源，定时同步到面板） */
    private var currentGetVolume: (() -> Float)? = null

    /** 抽出的同步 Timer 创建（之前两处复制粘贴的相同代码）：同步可见性/位置/音量 */
    private fun startSyncTimer(owner: javax.swing.JFrame) {
        if (syncTimer?.isRunning == true) return
        syncTimer = javax.swing.Timer(200) {
            val w = window ?: return@Timer
            val v = try { currentIsVisible?.invoke() ?: false } catch (_: Throwable) { false }
            // 失焦隐藏：主窗口与工具栏窗口都不在前台时隐藏（切到别的 App 时不悬在上面）
            val anyFocused = try {
                owner.isFocused || w.isFocused
            } catch (_: Throwable) { true }
            val show = v && anyFocused
            if (w.isVisible != show) {
                DebugLog.d("UI", "工具栏可见性变化: $show (请求=$v 聚焦=$anyFocused)")
                w.isVisible = show
                if (!show) {
                    // setVisible(false)可能无效，强制移出屏幕确保隐藏
                    try { w.location = java.awt.Point(-10000, -10000) } catch (_: Throwable) { }
                }
            }
            if (show) {
                try {
                    val b = contentBounds(owner)
                    if (w.bounds != b) w.bounds = b
                } catch (_: Throwable) { }
                // 音量同步：PlayerScreen 的 volume 变化时推到面板音量条
                try {
                    val vol = currentGetVolume?.invoke()
                    if (vol != null) {
                        val panel = w.contentPane.getComponent(0) as? AwtToolbarPanel
                        panel?.updateVolume(vol)
                    }
                } catch (_: Throwable) { }
            }
        }.apply { start() }
    }

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
        onPlaylist: (() -> Unit)? = null,
        onToggleDanmaku: (() -> Unit)? = null,
        isDanmakuEnabled: (() -> Boolean)? = null,
        onDanmakuSearch: ((String) -> Unit)? = null,
        onDanmakuImport: ((String) -> Int)? = null,
        onDanmakuStyleChanged: (() -> Unit)? = null,
        getTopTitle: (() -> String)? = null,
        getVolume: (() -> Float)? = null,
        onVolumeChange: ((Float) -> Unit)? = null
    ) {
        javax.swing.SwingUtilities.invokeLater {
            try {
                currentIsVisible = isVisibleState
                currentGetVolume = getVolume
                if (window == null) {
                    val win = javax.swing.JWindow(owner).apply {
                        // 单窗口下必须 alwaysOnTop，否则会被主窗口盖住
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
                        onPlaylist = onPlaylist,
                        onToggleDanmaku = onToggleDanmaku,
                        isDanmakuEnabled = isDanmakuEnabled,
                        onDanmakuSearch = onDanmakuSearch,
                        onDanmakuImport = onDanmakuImport,
                        onDanmakuStyleChanged = onDanmakuStyleChanged,
                        getTopTitle = getTopTitle
                    )
                    win.contentPane.add(panel)
                    window = win
                    // 定时同步可见性和位置
                    startSyncTimer(owner)
                    DebugLog.d("UI", "工具栏单例窗口已创建")
                }
                // 已存在：只更新位置+显示，不重建；确保 Timer 在跑
                val w = window ?: return@invokeLater
                // 更新面板回调（剧集切换时）
                try {
                    val panel = w.contentPane.getComponent(0) as? AwtToolbarPanel
                    panel?.updatePlayer(player)
                    panel?.updateBackCallback(onBack)
                    panel?.updateItemName(itemName)
                    panel?.updateTopTitleCallback(getTopTitle)
                    panel?.updateEpisodeCallbacks(onPrev, onNext, onPlaylist)
                    panel?.updateSeekCallbacks(getTimePos, getDuration, onSeek)
                    panel?.updateNetSpeedCallback(getNetSpeed)
                    panel?.updateDanmakuCallbacks(onToggleDanmaku, isDanmakuEnabled)
                    panel?.updateDanmakuActionCallbacks(onDanmakuSearch, onDanmakuImport, onDanmakuStyleChanged)
                    panel?.updateVolumeCallback(onVolumeChange)
                    panel?.startRefreshTimer()
                } catch (_: Throwable) { }
                startSyncTimer(owner)
                try {
                    w.bounds = contentBounds(owner)
                } catch (_: Throwable) { }
                w.isVisible = try { currentIsVisible?.invoke() ?: false } catch (_: Throwable) { false }
            } catch (t: Throwable) {
                DebugLog.d("UI", "工具栏显示失败: ${t.message}")
            }
        }
    }

    /** 计算 owner 的内容区（视频界面）在屏幕上的 bounds，不含窗口装饰 */
    private fun contentBounds(owner: java.awt.Window): java.awt.Rectangle {
        return try {
            val p = owner.locationOnScreen
            val insets = owner.insets
            java.awt.Rectangle(
                p.x + insets.left,
                p.y + insets.top,
                owner.width - insets.left - insets.right,
                owner.height - insets.top - insets.bottom
            )
        } catch (_: Throwable) {
            val p = try { owner.locationOnScreen } catch (_: Throwable) { java.awt.Point(0, 0) }
            java.awt.Rectangle(p.x, p.y, owner.width, owner.height)
        }
    }

    @Synchronized
    fun hide() {
        try { syncTimer?.stop() } catch (_: Throwable) { }
        syncTimer = null
        try {
            val panel = window?.contentPane?.getComponent(0) as? AwtToolbarPanel
            panel?.stopRefreshTimer()
        } catch (_: Throwable) { }
        try {
            window?.isVisible = false
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun destroy() {
        try { syncTimer?.stop() } catch (_: Throwable) { }
        syncTimer = null
        try {
            val panel = window?.contentPane?.getComponent(0) as? AwtToolbarPanel
            panel?.stopRefreshTimer()
        } catch (_: Throwable) { }
        try { window?.isVisible = false } catch (_: Throwable) { }
        try { window?.dispose() } catch (_: Throwable) { }
        window = null
    }
}
