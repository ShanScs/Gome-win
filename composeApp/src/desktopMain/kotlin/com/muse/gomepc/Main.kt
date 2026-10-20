package com.muse.gomepc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.muse.gomepc.ui.HomeScreen
import com.muse.gomepc.ui.LibraryScreen
import com.muse.gomepc.ui.LoginScreen
import com.muse.gomepc.ui.ModernSidebar
import com.muse.gomepc.ui.PlayerScreen
import com.muse.gomepc.ui.Repo
import com.muse.gomepc.ui.HomeDataCache
import com.muse.gomepc.ui.ResumeListScreen
import com.muse.gomepc.ui.Screen
import com.muse.gomepc.ui.SearchScreen
import com.muse.gomepc.ui.LibSortDialog
import com.muse.gomepc.ui.ServerPopupDialog
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
// 样式是否已保存：重复进入全屏时不再重复保存，否则永久丢失原始样式
private var windowStyleSaved: Boolean = false
private var savedWindowBounds: java.awt.Rectangle? = null
// 进入全屏前的 placement（最大化/悬浮），退出时恢复，而不是强制 Floating
private var savedPlacement: WindowPlacement? = null

private fun setWindowBorderless(hwnd: Long, borderless: Boolean) {
    try {
        val user32 = User32.INSTANCE
        val hWnd = WinDef.HWND(Pointer(hwnd))
        val GWL_STYLE = -16
        val WS_CAPTION = 0x00C00000L
        val WS_THICKFRAME = 0x00040000L
        if (borderless) {
            // 只在未保存时保存原始样式；全程用 Long，不经 toInt() 截断 64 位样式高位
            if (!windowStyleSaved) {
                savedWindowStyle = user32.GetWindowLongPtr(hWnd, GWL_STYLE).toLong()
                windowStyleSaved = true
            }
            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "保存原始style: 0x${savedWindowStyle.toString(16)}, hwnd=$hwnd")
            val newStyle = savedWindowStyle and WS_CAPTION.inv() and WS_THICKFRAME.inv()
            user32.SetWindowLongPtr(hWnd, GWL_STYLE, BaseTSD.LONG_PTR(newStyle).toPointer())
            com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "设置无边框style: 0x${newStyle.toString(16)}")
        } else {
            // 恢复原始样式，并清除已保存标记，下次进入全屏重新保存
            if (windowStyleSaved) {
                user32.SetWindowLongPtr(hWnd, GWL_STYLE, BaseTSD.LONG_PTR(savedWindowStyle).toPointer())
                com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "恢复原始style: 0x${savedWindowStyle.toString(16)}")
                windowStyleSaved = false
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
                        // 保存当前窗口位置与 placement（退出全屏时恢复原 placement，不强制 Floating）
                        savedWindowBounds = awtWindow.bounds
                        savedPlacement = state.placement
                        com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "保存窗口bounds: $savedWindowBounds placement=$savedPlacement")
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
                        // 恢复窗口位置与进入全屏前的 placement（最大化进全屏，退出后仍是最大化）
                        try {
                            savedWindowBounds?.let {
                                awtWindow.bounds = it
                                com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "恢复窗口bounds: $it")
                            }
                            state.placement = savedPlacement ?: WindowPlacement.Floating
                            savedPlacement = null
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
    // 首页服务器图标弹窗状态
    var showServerPopup by remember { mutableStateOf(false) }
    // 媒体库排序弹窗状态
    var showLibSort by remember { mutableStateOf(false) }
    // 初始界面（截图测试用）：-Dui.screen=detail|player
    val initial = remember {
        when (System.getProperty("ui.screen", "home")) {
            "player" -> Screen.Player("demo-item", "很想很想你", "demo-ep1", 1, 0.0)
            "detail" -> Screen.Detail("demo-item")
            else -> Screen.Home as Screen
        }
    }
    var first by remember { mutableStateOf(true) }
    // 组合期间不写状态：首次导航放 LaunchedEffect（组合期间写状态会触发额外重组/警告）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (first) {
            first = false
            screen = initial
        }
    }

    // 播放器页：全屏独占（无边栏），叠加统一透明标题栏
    if (screen is Screen.Player) {
        val p = screen as Screen.Player
        // owner 为空时不抛异常，显示错误占位（组合期间抛异常会炸掉整个界面）
        if (owner == null) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    "播放器需要窗口宿主，无法初始化",
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
            return
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            PlayerScreen(
                itemId = p.itemId,
                itemName = p.itemName,
                episodeId = p.episodeId,
                episodeIndex = p.episodeIndex,
                owner = owner,
                mediaSourceId = p.mediaSourceId,
                audioIndex = p.audioIndex,
                subtitleIndex = p.subtitleIndex,
                startPositionSec = p.startPositionSec,
                onBack = {
                    // 不用外层抓的 p（可能是旧组合的），实时读 screen 拿最新 Player
                    val cur = screen as? Screen.Player
                    val backId = cur?.itemId ?: p.itemId
                    val backName = cur?.itemName ?: p.itemName
                    com.muse.gomepc.player.DebugLog.d("NAV", "Player onBack: backId=$backId backName=$backName (p.itemId=${p.itemId})")
                    HomeDataCache.refreshResumeOnly()
                    screen = Screen.Detail(backId, cur?.episodeId ?: p.episodeId)
                },
                onFullscreen = onFullscreen,
                onSwitchEpisode = { eid, idx, resumeSec ->
                    // 实时读 screen，不用外层抓的 p（快速切集时 p 可能是旧组合的）
                    val cur = screen as? Screen.Player
                    // 切集后版本/音轨/字幕选择失效（MediaSourceId 是按集的），用默认值
                    screen = Screen.Player(
                        cur?.itemId ?: p.itemId,
                        cur?.itemName ?: p.itemName,
                        eid, idx, resumeSec
                    )
                }
            )
            // 播放器标题栏：App / 服务器 / 返回箭头
            CustomTitleBar(
                window = owner,
                config = TitleBarConfig(
                    left = listOf(
                        TitleLeftIcon.App { screen = Screen.Settings },
                        TitleLeftIcon.Server { screen = Screen.Home },
                        TitleLeftIcon.Back { screen = Screen.Detail(p.itemId, p.episodeId) }
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
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val libraryGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 媒体库排序请求：标题栏排序图标点击时递增，LibraryScreen 监听后弹排序框
    var libSortRequest by remember { mutableStateOf(0) }
    // 点播导航 Job：快速连点时取消旧导航，只保留最后一次（防多路并发导航）
    var playNavJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val backgroundContent: @Composable () -> Unit = {
        when (val s = screen) {
                is Screen.Home -> HomeScreen(
                    onItemClick = { openDetail(it) { s -> screen = s } },
                    onResumeMore = { screen = Screen.ResumeList },
                    onServerIconClick = { screen = Screen.Settings },
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
                is Screen.Search -> SearchScreen(onItemClick = { openDetail(it) { s -> screen = s } })
                is Screen.Favorites -> FavoritesScreen(onItemClick = { openDetail(it) { s -> screen = s } })
                is Screen.Settings -> SettingsScreen(
                    onServerSelected = { screen = Screen.Home },
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
                    currentEpisodeId = s.currentEpisodeId,
                    onBack = { screen = Screen.Home },
                    onPlay = { item, ep, selection ->
                        // 点选集时取这部剧的可续播集（带进度），确保断点续播位置正确
                        // getItem 取不到 UserData，用 getResumableEpisodes（已验证有效）
                        // 用 detailIdFor(item) 计算当前剧ID，不用外层 s.itemId（可能过期）
                        val seriesId = detailIdFor(item)
                        com.muse.gomepc.player.DebugLog.d("NAV", "onPlay: item.name=${item.name} item.id=${item.id} seriesId=$seriesId ep=${ep.name}")
                        // 防抖：取消旧的点播导航，只保留最后一次点击
                        playNavJob?.cancel()
                        playNavJob = kotlinx.coroutines.MainScope().launch {
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
                                startPositionSec = posSec,
                                mediaSourceId = selection.mediaSourceId,
                                audioIndex = selection.audioIndex,
                                subtitleIndex = selection.subtitleIndex
                            )
                        }
                    }
                )
                else -> {}
            }
    }
    // Dock 已删除（用户要求）：dock 模糊层专用背景/同步代码是死代码，已删
    // 统一透明标题栏配置（各页左侧 3 图标 + 可选中标题）
    val titleConfig: TitleBarConfig = when (val s = screen) {
        is Screen.Home -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Server { showServerPopup = true },
                TitleLeftIcon.Favorites { screen = Screen.Favorites }
            )
        )
        is Screen.Detail -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Server { screen = Screen.Home },
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
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Server { screen = Screen.Home },
                TitleLeftIcon.Favorites { screen = Screen.Favorites }
            )
        )
        else -> TitleBarConfig(
            left = listOf(
                TitleLeftIcon.App { screen = Screen.Settings },
                TitleLeftIcon.Server { screen = Screen.Home },
                TitleLeftIcon.Back { screen = Screen.Home }
            )
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(GomeTheme.Bg)) {
        if (GomeTheme.modern) {
            // 现代主题：左侧边导航 + 右侧内容区（页面内容本身不变）
            Row(Modifier.fillMaxSize()) {
                ModernSidebar(
                    current = screen,
                    onHome = { screen = Screen.Home },
                    onSearch = { screen = Screen.Search },
                    onFavorites = { screen = Screen.Favorites },
                    onSettings = { screen = Screen.Settings },
                    onServer = { showServerPopup = true }
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    backgroundContent()
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                backgroundContent()
            }
        }
        // 统一透明标题栏：悬浮在内容上（内容延伸到窗口边框）
        // 现代主题：左侧图标与侧边栏重叠，只保留居中标题与右侧窗口按钮；
        // 拖动靠标题栏整条空白层（titleBarDrag 只响应未被消费的按下），与左侧图标无关，双主题均可拖
        CustomTitleBar(
            window = owner,
            config = if (GomeTheme.modern) titleConfig.copy(left = emptyList()) else titleConfig,
            isMaximized = isMaximized,
            onMinimize = onMinimize,
            onToggleMaximize = onToggleMaximize,
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        // Dock已删除（用户要求）
    }
    // 首页服务器图标弹窗：媒体库排序 + 切换服务器
    if (showServerPopup) {
        ServerPopupDialog(
            onDismiss = { showServerPopup = false },
            onServerSelected = { screen = Screen.Home },
            onSortClick = {
                showServerPopup = false
                showLibSort = true
            }
        )
    }
    // 媒体库排序弹窗
    if (showLibSort) {
        LibSortDialog(onDismiss = { showLibSort = false })
    }
}
