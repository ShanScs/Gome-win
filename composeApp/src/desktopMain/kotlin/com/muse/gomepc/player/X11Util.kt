package com.muse.gomepc.player

import com.sun.jna.Native
import java.awt.Component
import java.awt.Window

/** Wayland 会话检测：有 WAYLAND_DISPLAY，或 XDG_SESSION_TYPE=wayland。Wayland 下没有 X11 Window ID。 */
internal fun isWaylandSession(): Boolean {
    return try {
        val waylandDisplay = System.getenv("WAYLAND_DISPLAY")
        if (!waylandDisplay.isNullOrBlank()) return true
        System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true
    } catch (_: Throwable) {
        false
    }
}

/**
 * 取 AWT 组件的原生窗口句柄。
 * - java.awt.Window：优先用 JNA Native.getWindowID（X11 返回 X Window ID，Windows 返回 HWND），无需反射
 * - 普通 Component（如 SwingPanel 里的 Canvas）：反射取 peer.getWindow()
 *   （sun.awt.X11.XWindowPeer）
 *
 * 反射需要的 --add-opens（注意 peer 字段声明在 java.awt.Component 中，不是 sun.awt 包下）：
 *   --add-opens java.desktop/java.awt=ALL-UNNAMED        （Component.peer 字段）
 *   --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED     （peer 的 getWindow 方法）
 * 旧注释只写了第二条，JDK17+ 下取 Component.peer 仍会因模块封装失败。
 *
 * Wayland 会话下没有 X11 Window ID，调用方应改用 wid<=0 让 mpv 自建窗口。
 */
object X11Util {

    fun windowId(c: Component): Long {
        if (isWaylandSession()) {
            throw IllegalStateException("Wayland 会话下无 X11 Window ID（WAYLAND_DISPLAY/XDG_SESSION_TYPE=wayland），请用 wid<=0 让 mpv 自建窗口")
        }
        if (!c.isDisplayable) {
            throw IllegalStateException("component not displayable, peer 不存在: $c（需先 pack/setVisible）")
        }
        if (c is Window) {
            // 兼容路径：优先 Native.getWindowID，无需反射；返回 0 是无效 wid，必须报错而非静默返回
            val id = try {
                Native.getWindowID(c)
            } catch (e: Throwable) {
                throw IllegalStateException("Native.getWindowID 失败 (X11): ${e.message}", e)
            }
            if (id == 0L) throw IllegalStateException("Native.getWindowID 返回 0 (X11)，无效 wid")
            return id
        }
        val peerField = try {
            Component::class.java.getDeclaredField("peer")
        } catch (e: Throwable) {
            throw IllegalStateException(
                "无法访问 Component.peer（需要 --add-opens java.desktop/java.awt=ALL-UNNAMED）: ${e.message}",
                e
            )
        }
        try {
            peerField.isAccessible = true
        } catch (e: java.lang.reflect.InaccessibleObjectException) {
            throw IllegalStateException(
                "无法打开 Component.peer（需要 --add-opens java.desktop/java.awt=ALL-UNNAMED）: ${e.message}",
                e
            )
        }
        try {
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
                } catch (_: NoSuchMethodException) {
                    // 该类无 getWindow，继续试父类
                } catch (e: java.lang.reflect.InaccessibleObjectException) {
                    throw IllegalStateException(
                        "无法访问 peer 方法 getWindow（需要 --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED）: ${e.message}",
                        e
                    )
                } catch (_: Throwable) {
                    // 单个方法调用失败，继续试父类
                }
                cls = cls.superclass
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("cannot get native window id for $c: ${e.message}", e)
        }
        throw IllegalStateException("cannot get native window id for $c")
    }
}
