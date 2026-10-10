package com.muse.gomepc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.muse.gomepc.ui.DetailScreen
import com.muse.gomepc.ui.DockBar
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
import com.muse.gomepc.ui.UiMediaItem

/** 继续观看的条目是单集，详情页要用剧的 ID */
private fun detailIdFor(item: UiMediaItem): String =
    if (item.type == "Episode" && item.seriesId.isNotEmpty()) item.seriesId else item.id

/** Windows下去掉标题栏但不重建HWND（避免MPV崩） */
private var savedWindowStyle: Long = 0L
private var savedWindowBounds: java.awt.Rectangle? = null

private fun setWindowBorderless(hwnd: Long, borderless: Boolean) {
    try {
        val user32 = User32.INSTANCE
        val hWnd = WinDef.HWND(Pointer(hwnd))
        val GWL_STYLE = -16
        val WS_CAPTION = 0x00C00000
        val WS_THICKFRAME = 0x00040000
        if (borderless) {
            // 保存原始样式，进入无边框
            savedWindowStyle = user32.GetWindowLongPtr(hWnd, GWL_STYLE).toLong()
            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "保存原始style: 0x${savedWindowStyle.toString(16)}, hwnd=$hwnd")
            val newStyle = (savedWindowStyle.toInt() and WS_CAPTION.inv() and WS_THICKFRAME.inv()).toLong()
            user32.SetWindowLongPtr(hWnd, GWL_STYLE, BaseTSD.LONG_PTR(newStyle).toPointer())
            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "设置无边框style: 0x${newStyle.toString(16)}")
        } else {
            // 恢复原始样式
            if (savedWindowStyle != 0L) {
                user32.SetWindowLongPtr(hWnd, GWL_STYLE, BaseTSD.LONG_PTR(savedWindowStyle).toPointer())
                com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "恢复原始style: 0x${savedWindowStyle.toString(16)}")
            }
        }
        // 刷新窗口
        user32.SetWindowPos(
            hWnd, null, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED
        )
    } catch (e: Throwable) {
        com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "setWindowBorderless失败: ${e.message}")
        e.printStackTrace()
    }
}

private fun getHwnd(window: java.awt.Window): Long {
    return try {
        // JNA 的 Native.getWindowPointer 取 HWND（不用反射，JDK 17 模块限制反射 peer 字段）
        val p = Native.getWindowPointer(window)
        Pointer.nativeValue(p)
    } catch (e: Throwable) {
        com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "取HWND失败: ${e.message}")
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
    // 真 backdrop 模糊（自研双渲染）：背景内容 lambda 供 dock 模糊层复用
    // 注意：dock 模糊层用独立的滚动状态，手动同步位置（共享状态对象会导致滚轮冲突）
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val libraryGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val dockHomeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val dockLibraryGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 滚动时把主内容的位置同步到 dock 模糊层（手动，不共享对象）
    androidx.compose.runtime.LaunchedEffect(homeListState) {
        androidx.compose.runtime.snapshotFlow {
            homeListState.firstVisibleItemIndex to homeListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            try { dockHomeListState.scrollToItem(index, offset) } catch (_: Exception) {}
        }
    }
    androidx.compose.runtime.LaunchedEffect(libraryGridState) {
        androidx.compose.runtime.snapshotFlow {
            libraryGridState.firstVisibleItemIndex to libraryGridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            try { dockLibraryGridState.scrollToItem(index, offset) } catch (_: Exception) {}
        }
    }
    // 滚动版本号：滚动时递增，触发 dock 模糊层重组（实时同步）
    var scrollVersion by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(homeListState, libraryGridState) {
        kotlinx.coroutines.flow.combine(
            androidx.compose.runtime.snapshotFlow {
                homeListState.firstVisibleItemIndex * 100000 + homeListState.firstVisibleItemScrollOffset / 50
            },
            androidx.compose.runtime.snapshotFlow {
                libraryGridState.firstVisibleItemIndex * 100000 + libraryGridState.firstVisibleItemScrollOffset / 50
            }
        ) { a, b -> a + b }.collect { scrollVersion++ }
    }
    val backgroundContent: @Composable () -> Unit = {
        when (val s = screen) {
                is Screen.Home -> HomeScreen(
                    onItemClick = { screen = Screen.Detail(detailIdFor(it)) },
                    onResumeMore = { screen = Screen.ResumeList },
                    onServerIconClick = { screen = Screen.Grid },
                    onLibraryClick = { lib -> screen = Screen.Library(lib.id, lib.name) },
                    listState = homeListState
                )
                is Screen.ResumeList -> ResumeListScreen(
                    onItemClick = { screen = Screen.Detail(detailIdFor(it)) },
                    onBack = { screen = Screen.Home }
                )
                is Screen.Library -> LibraryScreen(
                    libId = s.libId,
                    libName = s.libName,
                    onItemClick = { screen = Screen.Detail(detailIdFor(it)) },
                    onBack = { screen = Screen.Home },
                    gridState = libraryGridState
                )
                is Screen.Grid -> GridScreen(
                    onItemClick = { screen = Screen.Detail(detailIdFor(it)) },
                    onServerSelected = { screen = Screen.Home }
                )
                is Screen.Search -> SearchScreen(onItemClick = { screen = Screen.Detail(detailIdFor(it)) })
                is Screen.Favorites -> FavoritesScreen(onItemClick = { screen = Screen.Detail(detailIdFor(it)) })
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
    // Dock 模糊层专用背景（不共享滚动状态，避免滚轮冲突）
    val dockBackgroundContent: @Composable () -> Unit = {
        when (val s = screen) {
            is Screen.Home -> HomeScreen(
                onItemClick = {},
                onResumeMore = {},
                onServerIconClick = {},
                onLibraryClick = {},
                listState = dockHomeListState  // 独立状态，手动同步位置
            )
            is Screen.Library -> LibraryScreen(
                libId = s.libId,
                libName = s.libName,
                onItemClick = {},
                onBack = {},
                gridState = dockLibraryGridState  // 独立状态，手动同步位置
            )
            else -> backgroundContent()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(GomeTheme.Bg)) {
        Box(Modifier.fillMaxSize()) {
            backgroundContent()
        }
        // 详情页也保留 Dock（对齐 Android：Dock 只在播放器页隐藏）
        if (screen !is Screen.Player) {
            DockBar(
                current = screen,
                onSelect = { screen = it },
                backgroundContent = dockBackgroundContent,
                screenW = maxWidth,
                screenH = maxHeight,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
