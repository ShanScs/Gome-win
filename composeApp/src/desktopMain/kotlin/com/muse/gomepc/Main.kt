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
import androidx.compose.ui.draw.clip
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
import com.muse.gomepc.ui.CustomTitleBar
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
import com.muse.gomepc.ui.HomeDataCache
import com.muse.gomepc.ui.ResumeListScreen
import com.muse.gomepc.ui.Screen
import com.muse.gomepc.ui.SearchScreen
import com.muse.gomepc.ui.SettingsScreen
import com.muse.gomepc.ui.TitleBarConfig
import com.muse.gomepc.ui.TitleLeftIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.muse.gomepc.ui.UiMediaItem

/** 继续观看的条目是单集，详情页要用剧的 ID */
private fun detailIdFor(item: UiMediaItem): String {
    val result = if (item.type == "Episode" && item.seriesId.isNotEmpty()) item.seriesId else item.id
    com.muse.gomepc.player.DebugLog.d("NAV", "detailIdFor: name=${item.name} type=${item.type} id=${item.id} seriesId=${item.seriesId} -> $result")
    return result
}

// 从列表进详情页：如果单集的 seriesId 为空，先同步取一次再导航，避免拿单集ID进详情页导致选集出不来
private fun openDetail(item: UiMediaItem, setScreen: (Screen) -> Unit) {
    if (item.type == "Episode" && item.seriesId.isEmpty()) {
        kotlinx.coroutines.MainScope().launch {
            val sid = try {
                val d = com.muse.gomepc.emby.YambyClient.getItem(item.id)
                if (d.seriesId.isNotEmpty()) d.seriesId else item.id
            } catch (_: Exception) { item.id }
            com.muse.gomepc.player.DebugLog.d("NAV", "openDetail补seriesId: ${item.name} -> $sid")
            setScreen(Screen.Detail(sid))
        }
    } else {
        setScreen(Screen.Detail(detailIdFor(item)))
    }
}

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
        width = 1200.dp,
        height = 900.dp,
        position = WindowPosition(Alignment.Center)
    )
    var isFullscreen by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Gome PC",
        undecorated = true
    ) {
        // Win11 原生圆角（DWM），不透明窗口防白屏
        androidx.compose.runtime.LaunchedEffect(Unit) {
            try {
                val awtWindow = window
                val hwnd = com.sun.jna.Native.getWindowPointer(awtWindow)
                com.muse.gomepc.ui.WinRoundedCorners.apply(com.sun.jna.Pointer.nativeValue(hwnd))
            } catch (_: Exception) {}
        }
        // 不透明无边框：防白屏
        Box(
            Modifier.fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
        ) {
            MaterialTheme {
                GomeApp(
                    onMinimize = {
                        (window as? java.awt.Frame)?.extendedState = java.awt.Frame.ICONIFIED
                    },
                    onToggleMaximize = {
                        state.placement = if (state.placement == WindowPlacement.Maximized)
                            WindowPlacement.Floating else WindowPlacement.Maximized
                    },
                    isMaximized = state.placement == WindowPlacement.Maximized,
                    onClose = ::exitApplication,
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
}

/** 主界面：内容区全屏 + 底部悬浮 Dock（对齐 Android HostActivity） */

@androidx.compose.runtime.Composable
fun GomeApp(
    onFullscreen: () -> Unit = {},
    owner: java.awt.Window? = null,
    isMaximized: Boolean = false,
    onMinimize: () -> Unit = {},
    onToggleMaximize: () -> Unit = {},
    onClose: () -> Unit = {}
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
            "player" -> Screen.Player("demo-item", "很想很想你", "demo-ep1", 1, 0.0)
            "detail" -> Screen.Detail("demo-item")
            else -> Screen.Home as Screen
        }
    }
    var first by remember { mutableStateOf(true) }
    if (first) {
        first = false
        screen = initial
    }

    // 播放器页：全屏独占（无边栏），叠加统一透明标题栏
    if (screen is Screen.Player) {
        val p = screen as Screen.Player
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            PlayerScreen(
                itemId = p.itemId,
                itemName = p.itemName,
                episodeId = p.episodeId,
                episodeIndex = p.episodeIndex,
                owner = owner ?: throw IllegalStateException("no owner window"),
                startPositionSec = p.startPositionSec,
                onBack = {
                    // 不用外层抓的 p（可能是旧组合的），实时读 screen 拿最新 Player
                    val cur = screen as? Screen.Player
                    val backId = cur?.itemId ?: p.itemId
                    val backName = cur?.itemName ?: p.itemName
                    com.muse.gomepc.player.DebugLog.d("NAV", "Player onBack: backId=$backId backName=$backName (p.itemId=${p.itemId})")
                    HomeDataCache.refreshResumeOnly()
                    screen = Screen.Detail(backId)
                },
                onFullscreen = onFullscreen,
                onSwitchEpisode = { eid, idx, resumeSec ->
                    screen = Screen.Player(p.itemId, p.itemName, eid, idx, resumeSec)
                }
            )
            // 播放器标题栏：App / 服务器 / 返回箭头
            CustomTitleBar(
                window = owner,
                config = TitleBarConfig(
                    left = listOf(
                        TitleLeftIcon.App { screen = Screen.Home },
                        TitleLeftIcon.Server { screen = Screen.Grid },
                        TitleLeftIcon.Back { screen = Screen.Detail(p.itemId) }
                    )
                ),
                isMaximized = isMaximized,
                onMinimize = onMinimize,
                onToggleMaximize = onToggleMaximize,
                onClose = onClose,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
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
    // 媒体库排序请求：标题栏排序图标点击时递增，LibraryScreen 监听后弹排序框
    var libSortRequest by remember { mutableStateOf(0) }
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
                    onItemClick = { openDetail(it) { s -> screen = s } },
                    onResumeMore = { screen = Screen.ResumeList },
                    onServerIconClick = { screen = Screen.Grid },
                    onLibraryClick = { lib -> screen = Screen.Library(lib.id, lib.name) },
                    listState = homeListState
                )
                is Screen.ResumeList -> ResumeListScreen(
                    onItemClick = { openDetail(it) { s -> screen = s } },
                    onBack = { screen = Screen.Home }
                )
                is Screen.Library -> LibraryScreen(
                    libId = s.libId,
                    libName = s.libName,
                    onItemClick = { openDetail(it) { s -> screen = s } },
                    onBack = { screen = Screen.Home },
                    gridState = libraryGridState,
                    sortRequestKey = libSortRequest
                )
                is Screen.Grid -> GridScreen(
                    onItemClick = { openDetail(it) { s -> screen = s } },
                    onServerSelected = { screen = Screen.Home }
                )
                is Screen.Search -> SearchScreen(onItemClick = { openDetail(it) { s -> screen = s } })
                is Screen.Favorites -> FavoritesScreen(onItemClick = { openDetail(it) { s -> screen = s } })
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
                        // 点选集时取这部剧的可续播集（带进度），确保断点续播位置正确
                        // getItem 取不到 UserData，用 getResumableEpisodes（已验证有效）
                        // 用 detailIdFor(item) 计算当前剧ID，不用外层 s.itemId（可能过期）
                        val seriesId = detailIdFor(item)
                        com.muse.gomepc.player.DebugLog.d("NAV", "onPlay: item.name=${item.name} item.id=${item.id} seriesId=$seriesId ep=${ep.name}")
                        kotlinx.coroutines.MainScope().launch {
                            val posSec = try {
                                val resumable = com.muse.gomepc.emby.YambyClient.getResumableEpisodes(seriesId)
                                val r = resumable.firstOrNull { it.id == ep.id }
                                val t = r?.playTicks ?: 0L
                                com.muse.gomepc.player.DebugLog.d("UI", "点播取进度: ${ep.name} resumable playTicks=$t (ep.playTicks=${ep.playTicks})")
                                if (t > 0) t / 10_000_000.0 else ep.playTicks / 10_000_000.0
                            } catch (e: Exception) {
                                com.muse.gomepc.player.DebugLog.d("UI", "点播取进度失败: ${e.message}")
                                ep.playTicks / 10_000_000.0
                            }
                            com.muse.gomepc.player.DebugLog.d("UI", "点播进播放器: startPositionSec=$posSec")
                            screen = Screen.Player(
                                itemId = seriesId,
                                itemName = item.name,
                                episodeId = ep.id,
                                episodeIndex = ep.index,
                                startPositionSec = posSec
                            )
                        }
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
    // 统一透明标题栏配置（各页左侧 3 图标 + 可选中标题）
    val titleConfig: TitleBarConfig = when (val s = screen) {
        is Screen.Home -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Server { screen = Screen.Grid },
                TitleLeftIcon.Favorites { screen = Screen.Favorites }
            )
        )
        is Screen.Detail -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Server { screen = Screen.Grid },
                TitleLeftIcon.Back { screen = Screen.Home }
            )
        )
        is Screen.Library -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Sort { libSortRequest++ },
                TitleLeftIcon.Back { screen = Screen.Home }
            ),
            centerTitle = s.libName
        )
        is Screen.Settings -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Home },
                TitleLeftIcon.Server { screen = Screen.Grid },
                TitleLeftIcon.Favorites { screen = Screen.Favorites }
            )
        )
        else -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Server { screen = Screen.Grid },
                TitleLeftIcon.Back { screen = Screen.Home }
            )
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(GomeTheme.Bg)) {
        Box(Modifier.fillMaxSize()) {
            backgroundContent()
        }
        // 统一透明标题栏：悬浮在内容上（内容延伸到窗口边框）
        CustomTitleBar(
            window = owner,
            config = titleConfig,
            isMaximized = isMaximized,
            onMinimize = onMinimize,
            onToggleMaximize = onToggleMaximize,
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        // Dock已删除（用户要求）
    }
}
