package com.muse.gomepc.player

import com.sun.jna.Native
import java.awt.Component
import java.awt.Window

/**
 * 取 AWT 组件的原生窗口句柄。
 * - java.awt.Window：JNA Native.getWindowID（X11 返回 X Window ID，Windows 返回 HWND）
 * - 普通 Component（如 SwingPanel 里的 Canvas）：全反射取 peer.getWindow()
 *   （sun.awt.X11.XWindowPeer，运行时需要 --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED）
 */
object X11Util {

    fun windowId(c: Component): Long {
        if (c is Window) {
            return Native.getWindowID(c)
        }
        try {
            val peerField = Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(c) ?: throw IllegalStateException("peer is null")
            var cls: Class<*>? = peer.javaClass
            while (cls != null) {
                try {
                    val m = cls.getDeclaredMethod("getWindow")
                    m.isAccessible = true
                    val v = m.invoke(peer)
                    if (v is Number) {
                        val id = v.toLong()
                        if (id != 0L) return id
                    }
                } catch (_: Throwable) { }
                cls = cls.superclass
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("cannot get native window id for $c: ${e.message}")
        }
        throw IllegalStateException("cannot get native window id for $c")
    }
}
