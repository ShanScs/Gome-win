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
import com.muse.gomepc.ui.DetailScreen
import com.muse.gomepc.ui.DockBar
import com.muse.gomepc.ui.DockBlurState
import com.muse.gomepc.ui.GomeTheme
import com.muse.gomepc.ui.GridScreen
import com.muse.gomepc.ui.HomeScreen
import com.muse.gomepc.ui.LoginScreen
import com.muse.gomepc.ui.PlayerScreen
import com.muse.gomepc.ui.Repo
import com.muse.gomepc.ui.ResumeListScreen
import com.muse.gomepc.ui.Screen
import com.muse.gomepc.ui.SearchScreen
import com.muse.gomepc.ui.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() = application {
    val state = rememberWindowState(
        width = 1280.dp,
        height = 800.dp,
        position = WindowPosition(Alignment.Center)
    )
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Gome PC"
    ) {
        MaterialTheme {
            GomeApp(
                onFullscreen = {
                    state.placement = if (state.placement == WindowPlacement.Fullscreen)
                        WindowPlacement.Floating else WindowPlacement.Fullscreen
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
                    onServerIconClick = { screen = Screen.Grid }
                )
                is Screen.ResumeList -> ResumeListScreen(
                    onItemClick = { screen = Screen.Detail(it.id) },
                    onBack = { screen = Screen.Home }
                )
                is Screen.Grid -> GridScreen(onItemClick = { screen = Screen.Detail(it.id) })
                is Screen.Search -> SearchScreen(onItemClick = { screen = Screen.Detail(it.id) })
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
