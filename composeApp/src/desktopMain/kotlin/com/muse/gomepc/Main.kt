package com.muse.gomepc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.muse.gomepc.emby.Prefs
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.muse.gomepc.ui.DetailScreen
import com.muse.gomepc.ui.DockBar
import com.muse.gomepc.ui.DockBlurState
import com.muse.gomepc.ui.FavoritesScreen
import com.muse.gomepc.ui.GomeTheme
import com.muse.gomepc.ui.GridScreen
import com.muse.gomepc.ui.HomeScreen
import com.muse.gomepc.ui.LibraryScreen
import com.muse.gomepc.ui.LoginScreen
import com.muse.gomepc.ui.PlayerScreen
import com.muse.gomepc.ui.Repo
import com.muse.gomepc.ui.ResumeListScreen
import com.muse.gomepc.ui.Screen
import com.muse.gomepc.ui.SearchScreen
import com.muse.gomepc.ui.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Windows下去掉标题栏但不重建HWND（避免MPV崩） */
private interface User32Ext : Library {
    fun GetWindowLongPtr(hWnd: Pointer, nIndex: Int): Long
    fun SetWindowLongPtr(hWnd: Pointer, nIndex: Int, dwNewLong: Long): Long
    fun SetWindowPos(hWnd: Pointer, hWndInsertAfter: Pointer?, x: Int, y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
}

private var savedWindowStyle: Long = 0L
private var savedWindowBounds: java.awt.Rectangle? = null

private fun setWindowBorderless(hwnd: Long, borderless: Boolean) {
    try {
        val user32 = Native.load("user32", User32Ext::class.java)
        val hWnd = Pointer(hwnd)
        val GWL_STYLE = -16
        val WS_CAPTION = 0x00C00000
        val WS_THICKFRAME = 0x00040000
        val SWP_NOMOVE = 0x0002
        val SWP_NOSIZE = 0x0001
        val SWP_NOZORDER = 0x0004
        val SWP_FRAMECHANGED = 0x0020
        if (borderless) {
            // 保存原始样式，进入无边框
            savedWindowStyle = user32.GetWindowLongPtr(hWnd, GWL_STYLE)
            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "保存原始style: 0x${savedWindowStyle.toString(16)}, hwnd=$hwnd")
            val newStyle = (savedWindowStyle.toInt() and WS_CAPTION.inv() and WS_THICKFRAME.inv()).toLong()
            user32.SetWindowLongPtr(hWnd, GWL_STYLE, newStyle)
            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "设置无边框style: 0x${newStyle.toString(16)}")
        } else {
            // 恢复原始样式
            if (savedWindowStyle != 0L) {
                user32.SetWindowLongPtr(hWnd, GWL_STYLE, savedWindowStyle)
                com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "恢复原始style: 0x${savedWindowStyle.toString(16)}")
            }
        }
        // 刷新窗口
        user32.SetWindowPos(
            hWnd, null, 0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED
        )
    } catch (e: Throwable) {
        com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "setWindowBorderless失败: ${e.message}")
        e.printStackTrace()
    }
}

private fun getHwnd(window: java.awt.Window): Long {
    return try {
        val peerField = java.awt.Component::class.java.getDeclaredField("peer")
        peerField.isAccessible = true
        val peer = peerField.get(window)
        val hwndField = peer.javaClass.getDeclaredField("hwnd")
        hwndField.isAccessible = true
        hwndField.getLong(peer)
    } catch (e: Throwable) {
        0L
    }
}

fun main() = application {
    val state = rememberWindowState(
        width = 1280.dp,
        height = 800.dp,
        position = WindowPosition(Alignment.Center)
    )
    var isFullscreen by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Gome PC"
    ) {
        MaterialTheme {
            GomeApp(
                onFullscreen = {
                    val awtWindow = window
                    isFullscreen = !isFullscreen
                    val hwnd = getHwnd(awtWindow)
                    com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "切换全屏: isFullscreen=$isFullscreen, hwnd=$hwnd")
                    if (isFullscreen) {
                        // 保存当前窗口位置
                        savedWindowBounds = awtWindow.bounds
                        com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "保存窗口bounds: $savedWindowBounds")
                        // 去标题栏（不重建HWND）
                        if (hwnd != 0L) setWindowBorderless(hwnd, true)
                        // 用显示器完整bounds覆盖全屏（不是最大化到工作区，避免任务栏出现）
                        try {
                            val gd = awtWindow.graphicsConfiguration.device
                            val bounds = gd.defaultConfiguration.bounds
                            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "显示器bounds: $bounds")
                            awtWindow.bounds = bounds
                        } catch (e: Throwable) {
                            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "设置全屏bounds失败: ${e.message}")
                            state.placement = WindowPlacement.Maximized
                        }
                    } else {
                        // 恢复标题栏
                        if (hwnd != 0L) setWindowBorderless(hwnd, false)
                        // 恢复窗口位置
                        try {
                            savedWindowBounds?.let {
                                awtWindow.bounds = it
                                com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "恢复窗口bounds: $it")
                            }
                            state.placement = WindowPlacement.Floating
                        } catch (e: Throwable) {
                            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "恢复窗口失败: ${e.message}")
                        }
                    }
                    // 工具栏位置由 ToolbarWindowManager 的 200ms 定时器自动同步，无需手动调用
                },
                owner = window
            )
        }
    }
}

/** 主界面：内容区全屏 + 底部悬浮 Dock（对齐 Android HostActivity） */
@androidx.compose.runtime.Composable
fun GomeApp(
    onFullscreen: () -> Unit = {},
    owner: java.awt.Window? = null
) {
    // 登录态：Prefs 有 token 则直接进；演示模式开关（截图测试可用 -Dui.demo=true）
    var loggedIn by remember { mutableStateOf(Prefs.isLoggedIn()) }
    var demoMode by remember {
        mutableStateOf(System.getProperty("ui.demo", "false") == "true")
    }
    Repo.demoMode = demoMode

    if (!loggedIn && !demoMode) {
        LoginScreen(
            onLoggedIn = { loggedIn = true },
            onDemoMode = { demoMode = true }
        )
        return
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // 初始界面（截图测试用）：-Dui.screen=detail|player
    val initial = remember {
        when (System.getProperty("ui.screen", "home")) {
            "player" -> Screen.Player("demo-item", "很想很想你", "demo-ep1", 1)
            "detail" -> Screen.Detail("demo-item")
            else -> Screen.Home as Screen
        }
    }
    var first by remember { mutableStateOf(true) }
    if (first) {
        first = false
        screen = initial
    }

    // 播放器页：全屏独占（无边栏）
    if (screen is Screen.Player) {
        val p = screen as Screen.Player
        PlayerScreen(
            itemId = p.itemId,
            itemName = p.itemName,
            episodeId = p.episodeId,
            episodeIndex = p.episodeIndex,
            owner = owner ?: throw IllegalStateException("no owner window"),
            onBack = { screen = Screen.Detail(p.itemId) },
            onFullscreen = onFullscreen,
            onSwitchEpisode = { eid, idx ->
                screen = Screen.Player(p.itemId, p.itemName, eid, idx)
            }
        )
        return
    }

    // 主界面：内容区 + 底部悬浮 Dock（Dock 永远在最上方，页面切换在 Dock 下面）
    // M玻璃真模糊：定时抓取 dock 背后的屏幕区域做模糊
    val dockBlur = remember { DockBlurState() }
    // dock 在窗口内的位置（像素），用于换算屏幕坐标
    var dockBoundsInWindow by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var windowPos by remember { mutableStateOf<androidx.compose.ui.unit.IntOffset?>(null) }
    Box(Modifier.fillMaxSize().background(GomeTheme.Bg)) {
        Box(Modifier.fillMaxSize()) {
            when (val s = screen) {
                is Screen.Home -> HomeScreen(
                    onItemClick = { screen = Screen.Detail(it.id) },
                    onResumeMore = { screen = Screen.ResumeList },
                    onServerIconClick = { screen = Screen.Grid },
                    onLibraryClick = { lib -> screen = Screen.Library(lib.id, lib.name) }
                )
                is Screen.ResumeList -> ResumeListScreen(
                    onItemClick = { screen = Screen.Detail(it.id) },
                    onBack = { screen = Screen.Home }
                )
                is Screen.Library -> LibraryScreen(
                    libId = s.libId,
                    libName = s.libName,
                    onItemClick = { screen = Screen.Detail(it.id) },
                    onBack = { screen = Screen.Home }
                )
                is Screen.Grid -> GridScreen(onItemClick = { screen = Screen.Detail(it.id) })
                is Screen.Search -> SearchScreen(onItemClick = { screen = Screen.Detail(it.id) })
                is Screen.Favorites -> FavoritesScreen(onItemClick = { screen = Screen.Detail(it.id) })
                is Screen.Settings -> SettingsScreen(
                    onLogout = {
                        loggedIn = false
                        demoMode = false
                        Repo.demoMode = false
                        screen = Screen.Home
                    },
                    onToggleDemo = { enable ->
                        demoMode = enable
                        Repo.demoMode = enable
                        screen = Screen.Home
                    }
                )
                is Screen.Detail -> DetailScreen(
                    itemId = s.itemId,
                    onBack = { screen = Screen.Home },
                    onPlay = { item, ep ->
                        screen = Screen.Player(
                            itemId = item.id,
                            itemName = item.name,
                            episodeId = ep.id,
                            episodeIndex = ep.index
                        )
                    }
                )
                else -> {}
            }
        }
        // 详情页也保留 Dock（对齐 Android：Dock 只在播放器页隐藏）
        if (screen !is Screen.Player) {
            DockBar(
                current = screen,
                onSelect = { screen = it },
                blurredBackdrop = dockBlur.blurred,
                onDockBounds = { dockBoundsInWindow = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 定时抓取 dock 背后的屏幕区域并模糊（真 M玻璃）
        LaunchedEffect(screen, dockBoundsInWindow) {
            val bounds = dockBoundsInWindow ?: return@LaunchedEffect
            // 等内容渲染
            delay(500)
            while (true) {
                try {
                    // 窗口在屏幕上的位置 + dock 在窗口内的位置 = 屏幕坐标
                    // 通过 owner window 获取屏幕位置
                    val win = owner
                    if (win != null) {
                        val loc = win.locationOnScreen
                        val density = win.let {
                            // 从 bounds (dp) 转像素：bounds 已经是像素（boundsInWindow 返回像素）
                            1f
                        }
                        val sx = (loc.x + bounds.left).toInt()
                        val sy = (loc.y + bounds.top).toInt()
                        val sw = bounds.width.toInt()
                        val sh = bounds.height.toInt()
                        if (sw > 0 && sh > 0) {
                            dockBlur.captureAndBlur(sx, sy, sw, sh)
                        }
                    }
                } catch (_: Exception) { }
                delay(2000)
            }
        }
    }
}
