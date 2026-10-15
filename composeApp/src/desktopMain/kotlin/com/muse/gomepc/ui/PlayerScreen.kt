package com.muse.gomepc.ui

import androidx.compose.ui.awt.SwingPanel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.gomepc.danmaku.DanmakuEngine
import com.muse.gomepc.player.MpvPlayer
import kotlinx.coroutines.launch
import com.muse.gomepc.player.Win32Util
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

fun formatTime(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).roundToInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** 播放/暂停小按钮（Canvas 绘制，避免字体缺字形） */
@Composable
private fun PlayPauseButton(paused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
    ) {
        val c = size.minDimension
        if (paused) {
            // 三角形播放
            val p = Path().apply {
                moveTo(c * 0.38f, c * 0.28f)
                lineTo(c * 0.38f, c * 0.72f)
                lineTo(c * 0.68f, c * 0.5f)
                close()
            }
            drawPath(p, Color.White)
        } else {
            // 双竖条暂停
            val bw = c * 0.12f
            drawRect(Color.White, topLeft = Offset(c * 0.36f, c * 0.3f),
                size = androidx.compose.ui.geometry.Size(bw, c * 0.4f))
            drawRect(Color.White, topLeft = Offset(c * 0.54f, c * 0.3f),
                size = androidx.compose.ui.geometry.Size(bw, c * 0.4f))
        }
    }
}

/**
 * 播放器页。
 *
 * 结构：顶栏 / 视频区(SwingPanel+AWT Canvas, mpv wid 嵌入) / 底控制条(M玻璃)。
 * 注意：AWT Canvas 是 heavyweight，Compose 的覆盖层会被压在视频下面，
 * 所以顶栏/控制条采用上下布局（不悬浮压在视频上）；弹幕用独立透明 JWindow
 * 悬浮在视频区上方（owner=主窗口，随主窗口移动）。
 *
 * @param vo/hwdec 演示默认 x11/no（Xvfb 无 GPU）；真机用 gpu-next/auto。
 *   Windows 默认 gpu-next/d3d11va（平台自适应，见 defaultVo/defaultHwdec）。
 */
private fun isWindows(): Boolean =
    System.getProperty("os.name", "").lowercase().contains("win")

/** 平台自适应默认 vo：Windows→gpu-next（HDR 直通），Linux→x11（Xvfb 测试）/真机可传参覆盖 */
private fun defaultVo(): String =
    System.getProperty("ui.vo") ?: if (isWindows()) "gpu-next" else "x11"

/** 平台自适应默认 hwdec：Windows→d3d11va，Linux→no（Xvfb 无 GPU） */
private fun defaultHwdec(): String =
    System.getProperty("ui.hwdec") ?: if (isWindows()) "d3d11va" else "no"

/**
 * 视频渲染区独立组件：与工具栏显隐状态隔离，避免 controlsVisible 变化时触发重构
 * 导致 SwingPanel 重建、HWND 被 Windows DWM 拉到顶层压死 Compose 轻量级 UI。
 */
@Composable
private fun VideoCanvasArea(
    controlsVisibleState: androidx.compose.runtime.MutableState<Boolean>,
    lastInteraction: java.util.concurrent.atomic.AtomicLong,
    onCanvasReadyOnce: (java.awt.Canvas) -> Unit,
    modifier: Modifier = Modifier
) {
    var hasNotified by remember { mutableStateOf(false) }

    SwingPanel(
        background = Color.Black,
        factory = {
            java.awt.Canvas().apply {
                background = java.awt.Color.BLACK
                val canvas = this
                com.muse.gomepc.player.DebugLog.d("UI", "Canvas 已创建: ${width}x${height}")
                addComponentListener(object : ComponentAdapter() {
                    private fun tryNotify() {
                        if (width > 0 && height > 0 && !hasNotified) {
                            hasNotified = true
                            onCanvasReadyOnce(canvas)
                        }
                    }

                    override fun componentResized(e: ComponentEvent) {
                        tryNotify()
                    }
                    override fun componentShown(e: ComponentEvent) {
                        tryNotify()
                    }
                })
                val playerMouseListener = object : java.awt.event.MouseAdapter() {
                    private fun wakeUpControls() {
                        lastInteraction.set(System.currentTimeMillis())
                        if (!controlsVisibleState.value) {
                            SwingUtilities.invokeLater {
                                controlsVisibleState.value = true
                            }
                        }
                    }

                    override fun mouseMoved(e: java.awt.event.MouseEvent?) {
                        wakeUpControls()
                    }

                    override fun mouseDragged(e: java.awt.event.MouseEvent?) {
                        wakeUpControls()
                    }

                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        wakeUpControls()
                    }
                }
                addMouseListener(playerMouseListener)
                addMouseMotionListener(playerMouseListener)
            }
        },
        update = { /* init 由外部 LaunchedEffect(canvasReady) 触发 */ },
        modifier = modifier.fillMaxSize()
    )
}


@Composable
fun PlayerScreen(
    itemId: String,
    itemName: String,
    episodeId: String,
    episodeIndex: Int,
    owner: java.awt.Window,
    vo: String = defaultVo(),
    hwdec: String = defaultHwdec(),
    /** 断点续播位置（秒），0=从头播 */
    startPositionSec: Double = 0.0,
    onBack: () -> Unit,
    onFullscreen: () -> Unit,
    onSwitchEpisode: ((episodeId: String, episodeIndex: Int, resumeSec: Double) -> Unit)? = null
) {
    val player = remember(episodeId) { MpvPlayer() }
    val prefs = remember { com.muse.gomepc.emby.Prefs }
    /** 把 Prefs 里的弹幕样式应用到引擎（1:1 Android applyDanmakuStyle） */
    fun applyDanmakuStyleToEngine(e: DanmakuEngine) {
        e.setAreaRatio(prefs.danmakuArea)
        e.setPosition(prefs.danmakuPosition)
        e.setSpeedFactor(prefs.danmakuSpeed / 100f)
        e.setDelaySec(prefs.danmakuDelay)
        e.applyStyle(prefs.danmakuFontSize, prefs.danmakuStroke, prefs.danmakuOpacity)
    }
    val engine = remember {
        DanmakuEngine().apply {
            applyDanmakuStyleToEngine(this)
            // 1:1 Android：总开关 && 非永久禁用
            if (prefs.danmakuEnabled) prefs.danmakuDisabled = false
            val on = prefs.danmakuEnabled && !prefs.danmakuDisabled
            setEnabled(on)
            onError = { msg ->
                com.muse.gomepc.player.DebugLog.d("Danmaku", "加载失败: $msg")
            }
            val allSources = prefs.danmakuSources
            com.muse.gomepc.player.DebugLog.d("Danmaku", "所有弹幕源: $allSources")
            val urls = prefs.enabledDanmakuUrls()
            com.muse.gomepc.player.DebugLog.d("Danmaku", "启用的URL: $urls")
            if (urls.isNotEmpty()) loadFromApiMulti(urls, itemName)
        }
    }
    var inited by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var timePos by remember { mutableStateOf(0.0) }
    // 用于退出时上报：DisposableEffect 捕获的是旧值，用 ref 存最新位置
    val timePosRef = remember { java.util.concurrent.atomic.AtomicReference(0.0) }
    androidx.compose.runtime.LaunchedEffect(timePos) { timePosRef.set(timePos) }
    var duration by remember { mutableStateOf(0.0) }
    // 跳过片头/片尾状态
    var showSkipIntro by remember { mutableStateOf(false) }
    var showNextEp by remember { mutableStateOf(false) }
    var nextEpCountdown by remember { mutableStateOf(5) }
    var introSkipped by remember(episodeId) { mutableStateOf(false) }
    // 监听播放进度，更新跳过按钮显示
    androidx.compose.runtime.LaunchedEffect(timePos, duration) {
        val seriesId = itemId // 用 itemId 作为 seriesId
        val manualIntro = prefs.getSkipIntroSec(seriesId)
        val manualOutro = prefs.getSkipOutroSec(seriesId)
        // 片头：未跳过且在片头范围内
        showSkipIntro = !introSkipped && manualIntro > 0 && timePos < manualIntro && timePos >= 0
        // 片尾：剩余时间小于设定值且有下一集
        val remain = duration - timePos
        showNextEp = manualOutro > 0 && duration > 0 && remain < manualOutro && remain > 0 && onSwitchEpisode != null
    }
    // 下一集倒计时
    androidx.compose.runtime.LaunchedEffect(showNextEp) {
        if (showNextEp) {
            nextEpCountdown = 5
            while (nextEpCountdown > 0 && showNextEp) {
                kotlinx.coroutines.delay(1000)
                nextEpCountdown--
            }
            if (showNextEp && nextEpCountdown <= 0) {
                // 自动跳下一集
                onSwitchEpisode?.invoke("", episodeIndex + 1, 0.0)
            }
        }
    }
    var volume by remember { mutableStateOf(80f) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0f) }
    val lastTimePosUpdate = remember { java.util.concurrent.atomic.AtomicLong(0) }
    // 控制条自动隐藏 - 状态对象单独持有，供视频区独立组件使用（避免重构连带）
    val controlsVisibleState = remember { mutableStateOf(true) }
    var controlsVisible by controlsVisibleState
    // 线程安全的可见性（供 Swing EDT 的工具栏 Timer 读取，避免 Compose State 跨线程读不到更新）
    // 用 remember 持有 AtomicBoolean，避免重组时重置
    val toolbarVisibleFlag = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    // 最后交互时间戳，每次鼠标动刷新，3秒无交互自动隐藏
    val lastInteraction = remember { java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()) }
    // 显示后3秒自动隐藏（时间戳方式，每次交互重置）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(100)
            val visible = controlsVisibleState.value
            toolbarVisibleFlag.set(visible)
            if (visible && !dragging) {
                if (System.currentTimeMillis() - lastInteraction.get() > 3000) {
                    com.muse.gomepc.player.DebugLog.d("UI", "controlsVisible 自动隐藏")
                    controlsVisibleState.value = false
                }
            }
        }
    }
    // 兼容旧日志：controlsVisible变化时打日志
    LaunchedEffect(controlsVisible) {
        com.muse.gomepc.player.DebugLog.d("UI", "controlsVisible 变化: $controlsVisible")
    }
    val mpvLogs = remember { mutableStateListOf<String>() }
    var showLogs by remember { mutableStateOf(false) }
    var urlTestResult by remember { mutableStateOf<String?>(null) }
    // 💡 用 remember(episodeId) 确保切集时状态彻底重置，时序不乱
    var fileLoaded by remember(episodeId) { mutableStateOf(false) }
    // 断点续播：文件加载完成后只应用一次（1:1 Android pendingSeek 逻辑）；
    // 接近片尾（剩余不足60秒）则从头开始
    var resumeApplied by remember(episodeId) { mutableStateOf(false) }
    LaunchedEffect(fileLoaded, duration) {
        // 💡 只有当这一集真正的文件加载完成、并且有了真实的 duration 之后，才允许执行续播 seek
        // 监听 duration：fileLoaded 先变 true 时 duration 可能还是 0，等 duration 有了再触发一次
        com.muse.gomepc.player.DebugLog.d("UI", "续播检查: fileLoaded=$fileLoaded resumeApplied=$resumeApplied startPositionSec=$startPositionSec duration=$duration")
        if (fileLoaded && !resumeApplied && startPositionSec > 0) {
            val dur = duration
            if (dur > 0) { // 确保拿到了真实的视频总长度
                resumeApplied = true
                if (startPositionSec < dur - 60) {
                    player.seek(startPositionSec)
                    com.muse.gomepc.player.DebugLog.d("UI", "断点续播成功 seek 到 $startPositionSec 秒")
                } else {
                    com.muse.gomepc.player.DebugLog.d("UI", "续播跳过: 接近片尾 startPositionSec=$startPositionSec dur=$dur")
                }
            } else {
                com.muse.gomepc.player.DebugLog.d("UI", "续播跳过: duration=0")
            }
        }
    }
    // 弹幕开关统一状态（工具栏按钮 / 底栏文字 / D键 共用，1:1 Android 会话语义）
    var danmakuUiOn by remember { mutableStateOf(engine.isDanmakuOn()) }
    // 真实播放地址（演示模式走 Repo.playbackUrls 的测试视频）
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }
    // 待初始化的 Canvas（remember 保存，跨重构不丢失）：等 videoUrl 就绪后触发播放
    var pendingCanvas by remember { mutableStateOf<java.awt.Canvas?>(null) }
    var danmakuPanelRef by remember { mutableStateOf<com.muse.gomepc.danmaku.AwtDanmakuPanel?>(null) }
    var danmakuWindowRef by remember { mutableStateOf<javax.swing.JWindow?>(null) }
    // 退出播放器时关闭弹幕窗口
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            try {
                danmakuWindowRef?.isVisible = false
                danmakuWindowRef?.dispose()
            } catch (_: Throwable) { }
            danmakuWindowRef = null
        }
    }
    /** 切换弹幕：永久禁用后再次启用=解除；否则翻转引擎开关 */
    fun toggleDanmaku() {
        if (prefs.danmakuDisabled) {
            prefs.danmakuDisabled = false
            engine.setEnabled(true)
            danmakuUiOn = true
        } else {
            val on = !engine.isDanmakuOn()
            engine.setEnabled(on)
            danmakuUiOn = on
        }
        javax.swing.SwingUtilities.invokeLater {
            danmakuPanelRef?.isVisible = danmakuUiOn
        }
    }
    /** 弹幕样式变化后重新应用（工具栏弹幕设置面板调用） */
    fun refreshDanmakuStyle() {
        applyDanmakuStyleToEngine(engine)
    }
    var danmakuPosition by remember { mutableStateOf(prefs.danmakuPosition) }
    var mpvInitDone by remember { mutableStateOf(false) }

    // D 键直接开关弹幕（1:1 Android；输入框聚焦时不触发）
    androidx.compose.runtime.DisposableEffect(Unit) {
        val dispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id == java.awt.event.KeyEvent.KEY_PRESSED &&
                e.keyCode == java.awt.event.KeyEvent.VK_D &&
                !e.isControlDown && !e.isAltDown && !e.isMetaDown
            ) {
                val focusOwner =
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (focusOwner is javax.swing.text.JTextComponent) return@KeyEventDispatcher false
                javax.swing.SwingUtilities.invokeLater { toggleDanmaku() }
                return@KeyEventDispatcher true
            }
            false
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(dispatcher)
        onDispose {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }


    // 取播放地址
    LaunchedEffect(episodeId) {
        try {
            // 上报正在播放（Emby 播放记录）
            Repo.reportPlaying(episodeId)
            val urls = Repo.playbackUrls(episodeId)
            if (urls.isEmpty()) {
                urlError = "没有可用播放地址"
            } else {
                videoUrl = urls.first()
            }
        } catch (e: Exception) {
            urlError = "获取播放地址失败：${e.message?.take(120)}"
        }
    }

    // 退出时上报停止（含最终进度），并更新本地缓存（详情页只刷新进度不重拉）
    androidx.compose.runtime.DisposableEffect(episodeId) {
        onDispose {
            try {
                val ticks = (timePosRef.get() * 10_000_000).toLong()
                if (ticks > 0) {
                    kotlinx.coroutines.runBlocking {
                        com.muse.gomepc.emby.YambyClient.reportStopped(episodeId, ticks)
                    }
                    // 只更新缓存里的进度，不清缓存不调服务器
                    com.muse.gomepc.ui.Repo.updateEpisodeProgress(itemId, episodeId, ticks)
                }
            } catch (_: Exception) { }
        }
    }

    // 取剧集列表（带缓存）
    var episodeList by remember { mutableStateOf<List<UiEpisode>>(emptyList()) }
    LaunchedEffect(itemId) {
        try {
            val data = Repo.episodes(itemId)
            episodeList = data.episodes
        } catch (_: Exception) { }
    }

    // 网速监测：用 mpv 的音视频码率估算
    var netSpeedText by remember { mutableStateOf("") }
    LaunchedEffect(videoUrl) {
        if (videoUrl == null) return@LaunchedEffect
        while (true) {
            try {
                kotlinx.coroutines.delay(2000)
                val v = player.getPropertyDouble("video-bitrate") ?: 0.0
                val a = player.getPropertyDouble("audio-bitrate") ?: 0.0
                val total = v + a
                netSpeedText = if (total > 0) {
                    when {
                        total > 1024 * 1024 -> "%.1f MB/s".format(total / 1024 / 1024)
                        total > 1024 -> "%.0f KB/s".format(total / 1024)
                        else -> "%.0f B/s".format(total)
                    }
                } else ""
            } catch (_: Exception) { break }
        }
    }

    // canvas 有实际尺寸 + 拿到播放地址后才 init mpv（0x0 时无法渲染）
    // 监听器跟随当前 episode 的 player：切集时旧 player 销毁、新 player 重新挂监听
    var initializing by remember { mutableStateOf(false) }
    // 提取 listener 设置为函数，确保 tryInitPlayer 里也能调用（避免时序问题）
    fun setupPlayerListener(p: MpvPlayer) {
        p.listener = object : MpvPlayer.Listener {
            override fun onFileLoaded() {
                SwingUtilities.invokeLater { fileLoaded = true }
            }
            override fun onEndFile() {}
            override fun onError(msg: String) {
                SwingUtilities.invokeLater { initError = msg }
            }
            override fun onTimePos(sec: Double, dur: Double) {
                SwingUtilities.invokeLater {
                    // 1. 如果用户正在拖动，绝对不要让底层传来的旧进度覆盖 UI
                    if (dragging) return@invokeLater
                    // 2. 节流：避免高频更新卡UI
                    val now = System.currentTimeMillis()
                    if (now - lastTimePosUpdate.get() < 500) return@invokeLater
                    lastTimePosUpdate.set(now)
                    // 3. 严格的 NaN 和有效值保护
                    if (!sec.isNaN() && sec >= 0) {
                        timePos = sec
                    }
                    if (!dur.isNaN() && dur > 0) {
                        duration = dur
                    }
                }
            }
            override fun onPause(pausedState: Boolean) {
                SwingUtilities.invokeLater { paused = pausedState }
            }
            override fun onLog(prefix: String, level: String, text: String) {
                SwingUtilities.invokeLater {
                    mpvLogs.add("[$level][$prefix] $text")
                    if (mpvLogs.size > 50) mpvLogs.removeAt(0)
                }
            }
        }
    }
    DisposableEffect(episodeId) {
        val p = player
        setupPlayerListener(p)
        onDispose {
            // 上报停止（1:1 Android）：切集或退出时把当前进度同步到服务器
            try {
                val ticks = (timePos * 10_000_000).toLong()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        com.muse.gomepc.emby.YambyClient.reportStopped(episodeId, ticks)
                    } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
            try { p.destroy() } catch (_: Throwable) { }
        }
    }

    // 尝试初始化播放器：canvas 和 videoUrl 都就绪且未初始化过才执行
    fun tryInitPlayer() {
        val canvas = pendingCanvas
        val url = videoUrl
        if (mpvInitDone || initializing || canvas == null || url == null) return
        mpvInitDone = true
        initializing = true
        com.muse.gomepc.player.DebugLog.d("UI", "开始初始化播放器, url=$url")
        Thread {
            val wid = try {
                Win32Util.nativeWindowId(canvas)
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    initError = "wid: ${e.message}"
                    initializing = false
                }
                return@Thread
            }
            val err = try {
                val prefs = com.muse.gomepc.emby.Prefs
                player.init(wid, vo = vo, hwdec = hwdec, cacheEnabled = prefs.cacheEnabled, cacheSizeGb = prefs.cacheSizeGb)
            } catch (e: Throwable) {
                "init异常: ${e.message}"
            }
            SwingUtilities.invokeLater {
                initializing = false
                if (err != null) {
                    initError = err
                    com.muse.gomepc.player.DebugLog.d("UI", "播放器初始化失败: $err")
                } else {
                    inited = true
                    player.setVolume(volume.toDouble())
                    if (System.getProperty("ui.loop", "false") == "true") {
                        player.setLoop(true)
                    }
                    // 确保 listener 已设置（避免 DisposableEffect 时序问题导致 onTimePos 不回调）
                    try { setupPlayerListener(player) } catch (_: Throwable) { }
                    val playErr = try {
                        player.play(url)
                    } catch (e: Throwable) {
                        "play异常: ${e.message}"
                    }
                    if (playErr != null) {
                        initError = "play: $playErr"
                    } else {
                        com.muse.gomepc.player.DebugLog.d("UI", "开始播放")
                        // 上报开始播放（1:1 Android）
                        try {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                try {
                                    com.muse.gomepc.player.DebugLog.d("UI", "上报开始播放: episodeId=$episodeId")
                                    val err = com.muse.gomepc.emby.YambyClient.reportPlaying(episodeId)
                                    if (err != null) {
                                        com.muse.gomepc.player.DebugLog.d("UI", "上报开始播放失败: $err")
                                    } else {
                                        com.muse.gomepc.player.DebugLog.d("UI", "上报开始播放成功")
                                    }
                                } catch (e: Throwable) {
                                    com.muse.gomepc.player.DebugLog.d("UI", "上报开始播放异常: ${e.message}")
                                }
                            }
                        } catch (_: Throwable) { }
                    }
                }
            }
        }.start()
    }

    // videoUrl 就绪后尝试初始化（canvas 可能先就绪）
    LaunchedEffect(videoUrl) {
        tryInitPlayer()
    }

    // 定期上报播放进度（1:1 Android：每10秒上报一次）
    LaunchedEffect(episodeId) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            try {
                if (inited && initError.isNullOrEmpty()) {
                    val ticks = (timePos * 10_000_000).toLong()
                    val isPaused = paused
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val err = com.muse.gomepc.emby.YambyClient.reportProgress(episodeId, ticks, isPaused)
                            if (err != null) {
                                com.muse.gomepc.player.DebugLog.d("UI", "定期上报进度失败: $err")
                            } else {
                                com.muse.gomepc.player.DebugLog.d("UI", "定期上报进度成功: ticks=$ticks")
                            }
                        }
                        // 同步更新本地剧集缓存（详情页只刷新进度用，不依赖退出时上报）
                        try {
                            com.muse.gomepc.ui.Repo.updateEpisodeProgress(itemId, episodeId, ticks)
                        } catch (_: Exception) { }
                    } catch (e: Throwable) {
                        com.muse.gomepc.player.DebugLog.d("UI", "定期上报进度异常: ${e.message}")
                    }
                }
            } catch (_: Throwable) { }
        }
    }

    // 退出播放器时隐藏工具栏（单例复用，不销毁）
    DisposableEffect(Unit) {
        onDispose {
            try {
                ToolbarWindowManager.hide()
                com.muse.gomepc.player.DebugLog.d("UI", "工具栏已隐藏")
            } catch (_: Throwable) { }
        }
    }

    // 稳定回调：remember 固定实例，防止父重构导致 Canvas 重建

    // 浮动工具栏 root（切集时复用刷新回调）
    var toolbarRoot by remember { mutableStateOf<javax.swing.JFrame?>(null) }
    /** 刷新浮动工具栏（首建/切集时调用；单例窗口只更新回调与 player 引用） */
    fun refreshToolbar() {
        val root = toolbarRoot ?: return
        ToolbarWindowManager.show(
        owner = root,
        player = player,
        itemName = itemName,
        getTopTitle = {
            episodeList.find { it.id == episodeId }?.name?.takeIf { it.isNotBlank() } ?: itemName
        },
        onBack = {
            // 返回前先把当前进度写进本地缓存（详情页直接读，不等退出上报）
            try {
                val ticks = (timePosRef.get() * 10_000_000).toLong()
                if (ticks > 0) {
                    com.muse.gomepc.ui.Repo.updateEpisodeProgress(itemId, episodeId, ticks)
                }
            } catch (_: Exception) { }
            // 立即隐藏弹幕窗口，不等 DisposableEffect
            try {
                danmakuWindowRef?.isVisible = false
            } catch (_: Throwable) { }
            onBack()
        },
        onFullscreen = { onFullscreen() },
        isVisibleState = { toolbarVisibleFlag.get() },
        getPaused = { paused },
        getTimePos = { if (dragging) dragPos.toDouble() else timePos },
        getDuration = { duration },
        onSeek = { player.seek(it) },
        getNetSpeed = { netSpeedText },
        onToggleDanmaku = { toggleDanmaku() },
        isDanmakuEnabled = { danmakuUiOn },
        onDanmakuPosition = { pos ->
            danmakuPosition = pos
            prefs.danmakuPosition = pos
            engine.setPosition(pos)
        },
        getDanmakuPosition = {
            danmakuPosition
        },
        onDanmakuSearch = { kw ->
            val url = prefs.enabledDanmakuUrls().firstOrNull()
            if (url != null) {
                engine.loadFromApi(url, kw)
                if (!engine.isDanmakuOn()) {
                    prefs.danmakuDisabled = false
                    engine.setEnabled(true)
                    danmakuUiOn = true
                    javax.swing.SwingUtilities.invokeLater {
                        danmakuPanelRef?.isVisible = true
                    }
                }
            }
        },
        onDanmakuImport = { text ->
            val n = engine.importFromText(text)
            if (n > 0 && !engine.isDanmakuOn()) {
                prefs.danmakuDisabled = false
                engine.setEnabled(true)
                danmakuUiOn = true
                javax.swing.SwingUtilities.invokeLater {
                    danmakuPanelRef?.isVisible = true
                }
            }
            n
        },
        onDanmakuStyleChanged = { refreshDanmakuStyle() },
        onPrev = if (onSwitchEpisode != null) {
            {
                val curIdx = episodeList.indexOfFirst { it.id == episodeId }
                val prev = if (curIdx > 0) episodeList.getOrNull(curIdx - 1) else null
                if (prev != null) onSwitchEpisode(prev.id, curIdx - 1, prev.playTicks / 10_000_000.0)
            }
        } else null,
        onNext = if (onSwitchEpisode != null) {
            {
                val curIdx = episodeList.indexOfFirst { it.id == episodeId }
                val next = if (curIdx >= 0) episodeList.getOrNull(curIdx + 1) else null
                if (next != null) onSwitchEpisode(next.id, curIdx + 1, next.playTicks / 10_000_000.0)
            }
        } else null,
        onPlaylist = if (onSwitchEpisode != null && episodeList.isNotEmpty()) {
            {
                javax.swing.SwingUtilities.invokeLater {
                    val infos = episodeList.map {
                        EpisodePanel.EpisodeInfo(
                            id = it.id, index = it.index, name = it.name,
                            width = it.width, height = it.height,
                            durationTicks = it.runTicks, sizeBytes = it.sizeBytes,
                            playTicks = it.playTicks
                        )
                    }
                    val curPos = infos.indexOfFirst { it.id == episodeId }.takeIf { it >= 0 } ?: 0
                    EpisodePanel(root, infos, curPos) { ep, idx ->
                        onSwitchEpisode(ep.id, idx, ep.playTicks / 10_000_000.0)
                    }.show()
                }
            }
        } else null
        )
    }
    // 切集：重置本集播放状态 + 刷新浮动工具栏（新 player/新上下集回调）。
    // 与取地址的 LaunchedEffect(episodeId) 并发执行：重置是同步的，先于网络返回完成。
    LaunchedEffect(episodeId) {
        mpvInitDone = false
        inited = false
        initializing = false
        fileLoaded = false
        initError = null
        urlError = null
        videoUrl = null
        timePos = 0.0
        duration = 0.0
        paused = false
        dragging = false
        dragPos = 0f
        controlsVisible = true
        toolbarVisibleFlag.set(true)
        lastInteraction.set(System.currentTimeMillis())
        refreshToolbar()
    }

    val onCanvasReadyStable = remember {
        { canvas: java.awt.Canvas ->
            try {
                val root = javax.swing.SwingUtilities.getRoot(owner) as? javax.swing.JFrame
                    ?: owner as? javax.swing.JFrame
                if (root != null) {
                    // 弹幕用独立透明 JWindow（GlassPane 在某些窗口结构下不绘制）
                    val danmakuWindow = javax.swing.JWindow(root)
                    danmakuWindow.background = java.awt.Color(0, 0, 0, 0)
                    val danmakuPanel = com.muse.gomepc.danmaku.AwtDanmakuPanel(engine, null) {
                        lastInteraction.set(System.currentTimeMillis())
                        javax.swing.SwingUtilities.invokeLater {
                            if (!controlsVisibleState.value) {
                                controlsVisibleState.value = true
                            }
                        }
                    }
                    danmakuWindow.contentPane.add(danmakuPanel)
                    // 跟随视频 Canvas 位置
                    val updatePos = {
                        try {
                            if (canvas.isShowing) {
                                val p = canvas.locationOnScreen
                                danmakuWindow.setBounds(p.x, p.y, canvas.width, canvas.height)
                                if (!danmakuWindow.isVisible) danmakuWindow.isVisible = true
                            }
                        } catch (_: Throwable) { }
                    }
                    updatePos()
                    // 定时同步位置（窗口移动时）
                    val posTimer = javax.swing.Timer(500) { updatePos() }
                    posTimer.start()
                    danmakuPanelRef = danmakuPanel
                    danmakuWindowRef = danmakuWindow
                    com.muse.gomepc.player.DebugLog.d("UI", "弹幕 JWindow 已设置")

                    // 工具栏：单例复用，首建/切集都走 refreshToolbar 刷新回调
                    toolbarRoot = root
                    refreshToolbar()
                }
            } catch (_: Throwable) { }
            pendingCanvas = canvas
            tryInitPlayer()
        }
    }

    // 视频全屏，顶栏/底栏浮在上面（工具栏显隐不改变视频尺寸）
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 视频区独立组件：不受 controlsVisible 重构影响，防止 HWND 顶层压死 UI
        VideoCanvasArea(
            controlsVisibleState = controlsVisibleState,
            lastInteraction = lastInteraction,
            onCanvasReadyOnce = onCanvasReadyStable,
        )

        // 跳过片头按钮（右下角，控制条上方）
        if (showSkipIntro) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000))
                    .clickable {
                        val seriesId = itemId
                        val sec = prefs.getSkipIntroSec(seriesId)
                        if (sec > 0) {
                            player.seek(sec.toDouble())
                            introSkipped = true
                            showSkipIntro = false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("跳过片头", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 设片头按钮（前3分钟内显示，右下角）
        val showIntroMark = timePos < 180 && timePos >= 0
        if (showIntroMark) {
            val curIntroSet = prefs.getSkipIntroSec(itemId) > 0
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000).copy(alpha = if (curIntroSet) 1f else 0.7f))
                    .clickable {
                        val cur = prefs.getSkipIntroSec(itemId)
                        if (cur > 0) {
                            prefs.setSkipIntroSec(itemId, 0)
                        } else {
                            val sec = timePos.toInt().coerceAtLeast(0)
                            if (sec > 0) prefs.setSkipIntroSec(itemId, sec)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (curIntroSet) "片头✓" else "设片头", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 设片尾按钮（剩余3分钟内显示，右下角）
        val showOutroMark = duration > 0 && (duration - timePos) < 180 && (duration - timePos) >= 0
        if (showOutroMark) {
            val curOutroSet = prefs.getSkipOutroSec(itemId) > 0
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 190.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000).copy(alpha = if (curOutroSet) 1f else 0.7f))
                    .clickable {
                        val cur = prefs.getSkipOutroSec(itemId)
                        if (cur > 0) {
                            prefs.setSkipOutroSec(itemId, 0)
                        } else {
                            val remain = (duration - timePos).toInt().coerceAtLeast(0)
                            if (remain > 0) prefs.setSkipOutroSec(itemId, remain)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (curOutroSet) "片尾✓" else "设片尾", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 下一集按钮（右下角，带倒计时）
        if (showNextEp) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000))
                    .clickable {
                        showNextEp = false
                        onSwitchEpisode?.invoke("", episodeIndex + 1, 0.0)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (nextEpCountdown > 0) "跳到下一集 ($nextEpCountdown)" else "跳到下一集",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 底控制条（M玻璃，可自动隐藏）— 浮在视频上
        if (controlsVisible) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
        .pointerInput(lastInteraction) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Move) {
                        lastInteraction.set(System.currentTimeMillis())
                    }
                }
            }
        }) {
            MGlassBox(Modifier.fillMaxWidth(), corner = 18.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayPauseButton(paused = paused, onClick = { player.togglePause() })
                    Spacer(Modifier.width(12.dp))
                    Text(formatTime(if (dragging) dragPos.toDouble() else timePos),
                        color = Color(0xFF1A1A1A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    key(episodeId) {
                    Slider(
                        value = (if (dragging) dragPos else timePos.toFloat()).let { v -> if (v.isNaN()) 0f else v }.coerceIn(0f, duration.toFloat().let { d -> if (d.isNaN()) 1f else d }.coerceAtLeast(1f)),
                        onValueChange = { dragging = true; dragPos = it },
                        onValueChangeFinished = {
                            dragging = false
                            try {
                                val pos = dragPos.toDouble()
                                com.muse.gomepc.player.DebugLog.d("UI", "进度条拖动 seek 到 $pos 秒")
                                player.seek(pos)
                            } catch (e: Throwable) {
                                com.muse.gomepc.player.DebugLog.d("UI", "进度条 seek 失败: ${e.message}")
                            }
                        },
                        valueRange = 0f..duration.toFloat().let { d -> if (d.isNaN()) 1f else d }.coerceAtLeast(1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    } // key(episodeId) - Slider
                    Text(formatTime(duration), color = Color(0xFF555555), fontSize = 13.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("音量", color = Color(0xFF555555), fontSize = 12.sp)
                    Slider(
                        value = volume,
                        onValueChange = { volume = it; player.setVolume(it.toDouble()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.width(100.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (danmakuUiOn) "弹幕开" else "弹幕关",
                        color = Color(0xFF1A1A1A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { toggleDanmaku() }.padding(8.dp)
                    )
                    Text(
                        "全屏",
                        color = Color(0xFF1A1A1A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onFullscreen).padding(8.dp)
                    )
                    Text(
                        "日志",
                        color = Color(0xFF1A1A1A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { showLogs = !showLogs }.padding(8.dp)
                    )
                }
            }
        }
        } // if (controlsVisible) 底控制条

        // 顶栏（可自动隐藏）— 浮在视频上
        if (controlsVisible) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ 返回",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(8.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$itemName 第${episodeIndex}集",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (urlError != null) {
                Text(urlError!!, color = Color(0xFFFF5252), fontSize = 12.sp)
            } else if (initError != null) {
                Text("mpv: $initError", color = Color(0xFFFF5252), fontSize = 12.sp)
            } else if (videoUrl == null) {
                Text("获取播放地址中…", color = Color(0xFFBBBBBB), fontSize = 12.sp)
            }
        }
        } // if (controlsVisible) 顶栏

        // mpv 日志浮层（诊断用）
        if (showLogs) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { showLogs = false }
            ) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("mpv 日志（点击关闭）", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "URL: ${videoUrl?.take(120) ?: "null"}",
                        color = Color(0xFF88CCFF),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    Text(
                        "fileLoaded: $fileLoaded, inited: $inited",
                        color = Color(0xFF88CCFF),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    if (initError != null) {
                        Text(
                            "initError: $initError",
                            color = Color(0xFFFF8888),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    if (urlError != null) {
                        Text(
                            "urlError: $urlError",
                            color = Color(0xFFFF8888),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    // URL 连通性测试
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            "测试URL连通",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(Color(0xFF555555), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .clickable {
                                    urlTestResult = "测试中..."
                                    val testUrl = videoUrl
                                    kotlin.concurrent.thread {
                                        val result = try {
                                            if (testUrl == null) throw Exception("URL为空")
                                            val conn = java.net.URL(testUrl).openConnection() as java.net.HttpURLConnection
                                            conn.requestMethod = "HEAD"
                                            conn.connectTimeout = 8000
                                            conn.readTimeout = 8000
                                            val code = conn.responseCode
                                            val len = conn.getHeaderField("Content-Length") ?: "未知"
                                            "HTTP $code, 长度: $len"
                                        } catch (e: Exception) {
                                            "失败: ${e.message}"
                                        }
                                        javax.swing.SwingUtilities.invokeLater {
                                            urlTestResult = result
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Text(
                            "测公网视频",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(Color(0xFF555555), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .clickable {
                                    // 用公网示例视频测试 mpv 本体
                                    val testUrl = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"
                                    val err = player.play(testUrl)
                                    urlTestResult = if (err != null) "公网测试 play 失败: $err" else "已发送公网视频，看 fileLoaded 变不变"
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        if (urlTestResult != null) {
                            Text(
                                urlTestResult!!,
                                color = Color(0xFF88CCFF),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                        items(mpvLogs.size) { i ->
                            Text(
                                mpvLogs[i],
                                color = Color(0xFFCCCCCC),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
