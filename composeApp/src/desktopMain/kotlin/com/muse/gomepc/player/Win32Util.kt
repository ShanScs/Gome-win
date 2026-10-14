package com.muse.gomepc.player

import com.sun.jna.Native
import java.awt.Component
import java.awt.Window

/**
 * Windows HWND 获取（libmpv wid 嵌入用）。
 *
 * mpv 在 Windows 的 `wid` 选项接受 HWND（十进制字符串）。
 * - java.awt.Window：JNA Native.getWindowID 直接返回 HWND
 * - 普通 Component（如 Canvas/Panel）：反射取 peer.getHWnd()
 *   （sun.awt.windows.WComponentPeer，运行时需要
 *   --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED）
 *
 * 注意：组件必须已经 displayable（isDisplayable()=true），
 * 即窗口已 pack/setVisible，否则 peer 为 null。
 */
object Win32Util {

    fun hwnd(c: Component): Long {
        if (c is Window) {
            val id = Native.getWindowID(c)
            if (id != 0L) return id
        }
        // 非 Window 组件：反射 peer.getHWnd()
        try {
            val peerField = Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(c) ?: throw IllegalStateException("peer is null (component not displayable?)")
            var cls: Class<*>? = peer.javaClass
            while (cls != null) {
                // Windows peer 方法名是 getHWnd
                for (mName in arrayOf("getHWnd", "getHWND", "getWindow")) {
                    try {
                        val m = cls.getDeclaredMethod(mName)
                        m.isAccessible = true
                        val v = m.invoke(peer)
                        if (v is Number) {
                            val id = v.toLong()
                            if (id != 0L) return id
                        }
                    } catch (_: NoSuchMethodException) {
                    } catch (_: Throwable) { }
                }
                cls = cls.superclass
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("cannot get HWND for $c: ${e.message}")
        }
        throw IllegalStateException("cannot get HWND for $c")
    }

    /** 跨平台统一入口：Windows 用 HWND，Linux 用 X11 Window ID */
    fun nativeWindowId(c: Component): Long {
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win")) hwnd(c) else X11Util.windowId(c)
    }
}
