package com.muse.gomepc.player

import com.sun.jna.Native
import java.awt.Component
import java.awt.Window

/**
 * Windows HWND 获取（libmpv wid 嵌入用）。
 *
 * mpv 在 Windows 的 `wid` 选项接受 HWND（十进制字符串）。
 * - java.awt.Window：优先用 JNA Native.getWindowID 直接返回 HWND（无需反射）
 * - 普通 Component（如 Canvas/Panel）：反射取 peer.getHWnd()
 *   （sun.awt.windows.WComponentPeer）。
 *
 * 反射需要的 --add-opens（注意 peer 字段声明在 java.awt.Component 中，不是 sun.awt 包下）：
 *   --add-opens java.desktop/java.awt=ALL-UNNAMED            （Component.peer 字段）
 *   --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED     （peer 的 getHWnd 方法）
 * 旧注释只写了第二条，JDK17+ 下取 Component.peer 仍会因模块封装失败。
 *
 * 注意：组件必须已经 displayable（isDisplayable()=true），
 * 即窗口已 pack/setVisible，否则 peer 为 null。
 */
object Win32Util {

    fun hwnd(c: Component): Long {
        if (!c.isDisplayable) {
            throw IllegalStateException("component not displayable, peer 不存在: $c（需先 pack/setVisible）")
        }
        if (c is Window) {
            // 兼容路径：优先 Native.getWindowID，无需反射；失败/返回 0 再走反射兜底
            try {
                val id = Native.getWindowID(c)
                if (id != 0L) return id
                DebugLog.d("Wid", "Native.getWindowID 返回 0，改走 peer 反射取 HWND")
            } catch (e: Throwable) {
                DebugLog.d("Wid", "Native.getWindowID 失败，改走 peer 反射取 HWND: ${e.message}")
            }
        }
        // 非 Window 组件：反射 peer.getHWnd()
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
                        // 该类无此方法，继续试下一个方法/父类
                    } catch (e: java.lang.reflect.InaccessibleObjectException) {
                        throw IllegalStateException(
                            "无法访问 peer 方法 $mName（需要 --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED）: ${e.message}",
                            e
                        )
                    } catch (_: Throwable) {
                        // 单个方法调用失败，继续试下一个
                    }
                }
                cls = cls.superclass
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("cannot get HWND for $c: ${e.message}", e)
        }
        throw IllegalStateException("cannot get HWND for $c")
    }

    /** 跨平台统一入口：Windows 用 HWND，macOS 用原生窗口 ID，Linux 用 X11 Window ID。 */
    fun nativeWindowId(c: Component): Long {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) return hwnd(c)
        if (os.contains("mac") || os.contains("darwin")) {
            // macOS 分支：Window 用 Native.getWindowID；普通 Component 无可靠取 NSView 路径，
            // 返回 0 让 mpv 自建窗口（而不是抛异常导致无法播放）。
            if (c is Window) {
                val id = try {
                    Native.getWindowID(c)
                } catch (e: Throwable) {
                    throw IllegalStateException("Native.getWindowID 失败 (macOS): ${e.message}", e)
                }
                if (id == 0L) throw IllegalStateException("Native.getWindowID 返回 0 (macOS)")
                return id
            }
            DebugLog.d("Wid", "macOS 非 Window 组件无法取原生句柄，返回 0 让 mpv 自建窗口")
            return 0L
        }
        if (isWaylandSession()) {
            // Wayland 下没有 X11 Window ID，X11 路径拿不到可用 wid，返回 0 让 mpv 自建窗口
            DebugLog.d("Wid", "检测到 Wayland 会话，X11 Window ID 不可用，返回 0 让 mpv 自建窗口")
            return 0L
        }
        return X11Util.windowId(c)
    }
}
