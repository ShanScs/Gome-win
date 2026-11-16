package com.muse.gomepc.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows 11 原生圆角（DWMWA_WINDOW_CORNER_PREFERENCE）
 * 不用透明窗口，避免视频白屏
 *
 * 全屏时还要关掉圆角和系统边框：Win11 会给圆角窗口裁圆角（四个角露桌面），
 * 并沿边画一圈系统边框（四边约 1px 白线）。退出全屏时恢复圆角和默认边框颜色，
 * 普通窗口外观不变。
 */
object WinRoundedCorners {
    private interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: Pointer,
            cbAttribute: Int
        ): Int
    }

    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2
    private const val DWMWCP_DONOTROUND = 1
    private const val DWMWA_BORDER_COLOR = 34
    // DWMWA_COLOR_NONE / DWMWA_COLOR_DEFAULT（COLORREF，Int 形式为 -2 / -1）
    private const val DWMWA_COLOR_NONE = -2
    private const val DWMWA_COLOR_DEFAULT = -1

    /** 通用设置：返回 HRESULT，0 为成功；非 Windows 或调用失败返回非 0（不抛异常） */
    private fun setAttribute(hwndLong: Long, attribute: Int, value: Int): Int {
        return try {
            val os = System.getProperty("os.name").lowercase()
            if (!os.contains("win")) return -1
            // 只在 Win11+ 生效，Win10 回退方角
            // Win11 的 os.version 是 10.0，但 build 号 >= 22000
            // 简化：直接尝试调用，失败则忽略
            val dwm = Native.load("dwmapi", DwmApi::class.java)
            val hwnd = WinDef.HWND(Pointer(hwndLong))
            val attr = com.sun.jna.Memory(4)
            attr.setInt(0, value)
            dwm.DwmSetWindowAttribute(hwnd, attribute, attr, 4)
        } catch (_: Exception) {
            -1
        }
    }

    fun apply(hwndLong: Long) {
        val hr = setAttribute(hwndLong, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
        if (hr != 0) {
            // HRESULT 非 0 表示调用失败（如旧版 Windows 不支持该属性），保持方角
            com.muse.gomepc.player.DebugLog.d("UI", "WinRoundedCorners: DwmSetWindowAttribute 失败 hr=$hr")
        }
    }

    /** 系统定时器精度提到 1ms：Windows 默认定时粒度约 15.6ms，33ms 的 Swing Timer
     * 实际约 47ms 一跳，弹幕最多 21 帧；提到 1ms 后 33ms 定时器能按 33ms 跑。
     * winmm timeBeginPeriod，返回 0 为成功；进程退出时系统自动恢复，不用调 timeEndPeriod。
     * 播放器类应用常用做法，只在 Windows 上调一次。 */
    fun requestHighResolutionTimer() {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (!os.contains("win")) return
            val mm = Native.load("winmm", WinMmApi::class.java)
            val res = mm.timeBeginPeriod(1)
            com.muse.gomepc.player.DebugLog.d("UI", "系统定时精度: timeBeginPeriod(1) 返回 $res")
        } catch (_: Exception) {
        }
    }

    private interface WinMmApi : StdCallLibrary {
        fun timeBeginPeriod(uPeriod: Int): Int
    }

    /** 进全屏：关圆角 + 去系统边框（白线和圆角漏光都由这两项引起） */
    fun setFullscreen(hwndLong: Long) {
        val hrCorner = setAttribute(hwndLong, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DONOTROUND)
        val hrBorder = setAttribute(hwndLong, DWMWA_BORDER_COLOR, DWMWA_COLOR_NONE)
        com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "全屏圆角/边框: corner hr=$hrCorner border hr=$hrBorder")
    }

    /** 退全屏：恢复圆角 + 默认边框颜色，普通窗口外观不变 */
    fun restoreFullscreen(hwndLong: Long) {
        val hrCorner = setAttribute(hwndLong, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
        val hrBorder = setAttribute(hwndLong, DWMWA_BORDER_COLOR, DWMWA_COLOR_DEFAULT)
        com.muse.gomepc.player.DebugLog.d("FULLSCREEN", "恢复圆角/边框: corner hr=$hrCorner border hr=$hrBorder")
    }
}
