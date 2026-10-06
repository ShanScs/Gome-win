package com.muse.gomepc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.muse.gomepc.emby.Prefs
import com.muse.gomepc.ui.DetailScreen
import com.muse.gomepc.ui.GomeTheme
import com.muse.gomepc.ui.GridScreen
import com.muse.gomepc.ui.HomeScreen
import com.muse.gomepc.ui.LoginScreen
import com.muse.gomepc.ui.PlayerScreen
import com.muse.gomepc.ui.Repo
import com.muse.gomepc.ui.Screen
import com.muse.gomepc.ui.SearchScreen
import com.muse.gomepc.ui.SettingsScreen
import com.muse.gomepc.ui.Sidebar

fun main() = application {
    val state = rememberWindowState(
        width = 1280.dp,
        height = 800.dp,
        position = WindowPosition(0.dp, 0.dp)
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

/** 主界面：登录 → 左侧 M玻璃边栏 + 内容区 */
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
            onFullscreen = onFullscreen
        )
        return
    }

    Row(Modifier.fillMaxSize().background(GomeTheme.SidebarBg)) {
        Sidebar(current = screen, onSelect = { screen = it })
        Box(Modifier.weight(1f).fillMaxSize()) {
            when (val s = screen) {
                is Screen.Home -> HomeScreen(onItemClick = { screen = Screen.Detail(it.id) })
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
    }
}
