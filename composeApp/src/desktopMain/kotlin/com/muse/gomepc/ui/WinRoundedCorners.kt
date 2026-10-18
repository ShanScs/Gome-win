package com.muse.gomepc.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows 11 原生圆角（DWMWA_WINDOW_CORNER_PREFERENCE）
 * 不用透明窗口，避免视频白屏
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

    fun apply(hwndLong: Long) {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (!os.contains("win")) return
            // 只在 Win11+ 生效，Win10 回退方角
            // Win11 的 os.version 是 10.0，但 build 号 >= 22000
            // 简化：直接尝试调用，失败则忽略
            val dwm = Native.load("dwmapi", DwmApi::class.java)
            val hwnd = WinDef.HWND(Pointer(hwndLong))
            val attr = com.sun.jna.Memory(4)
            attr.setInt(0, DWMWCP_ROUND)
            val hr = dwm.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, attr, 4)
            if (hr != 0) {
                // HRESULT 非 0 表示调用失败（如旧版 Windows 不支持该属性），保持方角
                com.muse.gomepc.player.DebugLog.d("UI", "WinRoundedCorners: DwmSetWindowAttribute 失败 hr=$hr")
            }
        } catch (_: Exception) {
            // 忽略：Win10 或调用失败时保持方角
        }
    }
}
