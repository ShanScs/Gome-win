package com.muse.gomepc.player

import com.sun.jna.Memory
import com.sun.jna.Pointer

/**
 * libmpv 播放器封装（JNA）。
 *
 * 初始化顺序（wid 必须在 initialize 之前）：
 *   mpv_create → set_option(vo/hwdec/wid/...) → mpv_initialize
 *   → observe_property(time-pos/duration/pause) → 启动事件线程
 *
 * 事件循环跑在独立 daemon 线程，用 mpv_wait_event(timeout=0.5) 轮询。
 */
class MpvPlayer {

    interface Listener {
        fun onFileLoaded()
        fun onEndFile()
        fun onError(msg: String)
        fun onTimePos(sec: Double, duration: Double)
        fun onPause(paused: Boolean)
        fun onLog(prefix: String, level: String, text: String) {}
    }

    var listener: Listener? = null

    private var handle: Pointer? = null
    @Volatile private var running = false
    private var eventThread: Thread? = null

    @Volatile private var lastTimePos = 0.0
    @Volatile private var lastDuration = 0.0
    @Volatile private var lastPaused = false

    val timePos: Double get() = lastTimePos
    val duration: Double get() = lastDuration
    val isPaused: Boolean get() = lastPaused
    val isInitialized: Boolean get() = handle != null

    /**
     * @param wid  X11 Window ID（Linux）/ HWND（Windows），<=0 表示让 mpv 自己建窗口
     * @param vo   Xvfb/无 GPU 测试用 "x11"；真机用 "gpu-next"
     * @param hwdec "auto" / "no"，VM 无 GPU 时用 "no"
     * @return null=成功，否则为错误信息
     */
    fun init(wid: Long, vo: String = "gpu-next", hwdec: String = "auto"): String? {
        if (handle != null) return "already initialized"
        val lib = LibMpv.INSTANCE
        val ctx: Pointer = try {
            lib.mpv_create()
        } catch (e: Throwable) {
            return "mpv_create failed: ${e.message}"
        }
        handle = ctx

        fun opt(name: String, value: String): String? {
            val r = lib.mpv_set_option_string(ctx, name, value)
            if (r < 0) {
                destroy()
                return "set_option $name=$value failed: ${lib.mpv_error_string(r)}"
            }
            return null
        }

        opt("config", "no")?.let { return it }          // 不读用户配置文件
        opt("vo", vo)?.let { return it }
        opt("hwdec", hwdec)?.let { return it }
        opt("osd-level", "0")?.let { return it }        // 关自带 OSD（UI 自己画控制条）
        opt("osd-bar", "no")?.let { /* 非致命，忽略 */ }
        if (wid > 0) opt("wid", wid.toString())?.let { return it }
        opt("msg-level", "all=warn")?.let { /* 非致命，忽略 */ }
        opt("terminal", "no")?.let { /* 非致命，忽略 */ }

        val r = lib.mpv_initialize(ctx)
        if (r < 0) {
            destroy()
            return "mpv_initialize failed: ${lib.mpv_error_string(r)}"
        }
        // 请求日志消息（诊断用，UI 可展示）
        try { lib.mpv_request_log_messages(ctx, "warn") } catch (_: Throwable) { }

        // OSD 关掉（UI 自己画控制条；set_option 在某些构建不生效，改用 property）
        lib.mpv_set_property_string(ctx, "osd-level", "0")

        // 观察常用属性（userdata 仅用于区分，事件里直接读 name）
        lib.mpv_observe_property(ctx, 1L, "time-pos", MpvFormat.DOUBLE)
        lib.mpv_observe_property(ctx, 2L, "duration", MpvFormat.DOUBLE)
        lib.mpv_observe_property(ctx, 3L, "pause", MpvFormat.FLAG)

        running = true
        eventThread = Thread(::eventLoop, "mpv-event").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /** 播放。返回 null=命令已发送，否则为错误信息。 */
    fun play(url: String): String? {
        val ctx = handle ?: return "not initialized"
        val lib = LibMpv.INSTANCE
        val r = lib.mpv_command(ctx, arrayOf("loadfile", url, "replace", null))
        return if (r < 0) lib.mpv_error_string(r) else null
    }

    fun setPaused(p: Boolean) {
        handle?.let { LibMpv.INSTANCE.mpv_set_property_string(it, "pause", if (p) "yes" else "no") }
    }

    fun togglePause() {
        handle?.let { LibMpv.INSTANCE.mpv_command_string(it, "cycle pause") }
    }

    /** 绝对 seek（秒）。 */
    fun seek(sec: Double) {
        handle?.let { LibMpv.INSTANCE.mpv_command_string(it, "seek $sec absolute") }
    }

    fun setVolume(v: Double) {
        handle?.let {
            LibMpv.INSTANCE.mpv_set_property_string(it, "volume", v.coerceIn(0.0, 100.0).toString())
        }
    }

    /** 循环播放（演示/截图用）。 */
    fun setLoop(loop: Boolean) {
        handle?.let {
            LibMpv.INSTANCE.mpv_set_property_string(it, "loop-file", if (loop) "inf" else "no")
        }
    }

    /** 同步读 double 属性，失败返回 null。 */
    fun getPropertyDouble(name: String): Double? {
        val ctx = handle ?: return null
        val mem = Memory(8)
        val r = LibMpv.INSTANCE.mpv_get_property(ctx, name, MpvFormat.DOUBLE, mem)
        return if (r < 0) null else mem.getDouble(0)
    }

    /** 同步读 string 属性，失败返回 null。 */
    fun getPropertyString(name: String): String? {
        val ctx = handle ?: return null
        val ptr = LibMpv.INSTANCE.mpv_get_property_string(ctx, name) ?: return null
        return try {
            ptr.getString(0)
        } finally {
            LibMpv.INSTANCE.mpv_free(ptr)
        }
    }

    private fun eventLoop() {
        val lib = LibMpv.INSTANCE
        val ctx = handle ?: return
        while (running) {
            val evPtr: Pointer = try {
                lib.mpv_wait_event(ctx, 0.5)
            } catch (e: Throwable) {
                break
            }
            if (evPtr == Pointer.NULL) continue
            val ev = MpvEvent(evPtr)
            try {
                ev.read()
            } catch (e: Throwable) {
                continue
            }
            when (ev.event_id) {
                MpvEventId.NONE -> { /* 超时 */ }
                MpvEventId.SHUTDOWN -> running = false
                MpvEventId.FILE_LOADED -> listener?.onFileLoaded()
                MpvEventId.END_FILE -> listener?.onEndFile()
                MpvEventId.PAUSE -> {
                    lastPaused = true
                    listener?.onPause(true)
                }
                MpvEventId.UNPAUSE -> {
                    lastPaused = false
                    listener?.onPause(false)
                }
                MpvEventId.PROPERTY_CHANGE -> {
                    val dp = ev.data ?: continue
                    val prop = MpvEventProperty(dp)
                    try {
                        prop.read()
                    } catch (e: Throwable) {
                        continue
                    }
                    val pdata = prop.data ?: continue
                    try {
                        when (prop.name) {
                            "time-pos" -> {
                                lastTimePos = pdata.getDouble(0)
                                listener?.onTimePos(lastTimePos, lastDuration)
                            }
                            "duration" -> lastDuration = pdata.getDouble(0)
                            "pause" -> {
                                lastPaused = pdata.getInt(0) != 0
                                listener?.onPause(lastPaused)
                            }
                        }
                    } catch (e: Throwable) {
                        // 属性 data 解析失败，忽略
                    }
                }
                MpvEventId.LOG_MESSAGE -> {
                    try {
                        val lm = MpvEventLogMessage(ev.data)
                        lm.read()
                        listener?.onLog(
                            lm.prefix ?: "",
                            lm.level ?: "",
                            (lm.text ?: "").trim()
                        )
                    } catch (_: Throwable) { }
                }
            }
        }
    }

    fun destroy() {
        running = false
        try {
            eventThread?.join(2000)
        } catch (e: Throwable) { }
        eventThread = null
        handle?.let {
            try {
                LibMpv.INSTANCE.mpv_terminate_destroy(it)
            } catch (e: Throwable) { }
        }
        handle = null
    }
}
