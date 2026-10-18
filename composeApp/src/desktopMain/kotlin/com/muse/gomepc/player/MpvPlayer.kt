package com.muse.gomepc.player

import com.sun.jna.Memory
import com.sun.jna.Pointer

/** 诊断日志：写文件供用户拖回分析 */
object DebugLog {
    private val logFile by lazy {
        val home = System.getProperty("user.home") ?: "."
        val dir = java.io.File(home, ".gome").apply { mkdirs() }
        java.io.File(dir, "gome-debug.log").apply {
            if (exists()) delete()
            createNewFile()
        }
    }
    @Synchronized
    fun d(tag: String, msg: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())
            logFile.appendText("[$ts][$tag] $msg\n")
        } catch (_: Throwable) { }
    }
    fun path(): String = try { logFile.absolutePath } catch (_: Throwable) { "unknown" }
}

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
    /** 本文件是否已执行过自动选字幕（只选一次，用户手动切后不再覆盖）。 */
    @Volatile private var autoSubSelectedForFile = false

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
    fun init(wid: Long, vo: String = "gpu-next", hwdec: String = "auto", cacheEnabled: Boolean = true, cacheSizeGb: Int = 1): String? {
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
        opt("msg-level", "all=info")?.let { /* 非致命，忽略 */ }
        opt("terminal", "no")?.let { /* 非致命，忽略 */ }
        opt("tls-verify", "no")?.let { /* 非致命，忽略：自签证书 */ }

        // 允许 mpv 拥有正常的窗口碰撞实体（不要 no），但解除它的快捷键和自带控制条
        opt("input-cursor", "no")?.let { /* 非致命，忽略 */ }
        opt("input-vo-keyboard", "no")?.let { /* 非致命，忽略 */ }
        opt("input-default-bindings", "no")?.let { /* 非致命，忽略 */ }
        opt("osc", "no")?.let { /* 非致命，忽略 */ }

        // 缓存设置（对齐安卓：cache=yes + demuxer-max-bytes=1G/2G/3G）
        if (cacheEnabled) {
            opt("cache", "yes")?.let { /* 非致命，忽略 */ }
            opt("demuxer-max-bytes", "${cacheSizeGb}GiB")?.let { /* 非致命，忽略 */ }
        } else {
            opt("cache", "no")?.let { /* 非致命，忽略 */ }
        }

        // 请求日志消息必须在 initialize 之前（mpv 要求）
        try { lib.mpv_request_log_messages(ctx, "info") } catch (_: Throwable) { }

        val r = lib.mpv_initialize(ctx)
        if (r < 0) {
            destroy()
            return "mpv_initialize failed: ${lib.mpv_error_string(r)}"
        }

        // 鼠标哨兵 Lua 脚本已移除：AWT Canvas 已有 mouseMoved/mouseDragged/mouseClicked 监听，
        // 哨兵的 mouse_move 高频触发会导致工具栏无法自动隐藏（刚隐藏 278ms 就被唤醒）。

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
        autoSubSelectedForFile = false // 新文件：允许自动选一次默认字幕
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

    /** 通用属性设置（供工具栏等调用）。 */
    fun setProperty(name: String, value: String) {
        handle?.let {
            LibMpv.INSTANCE.mpv_set_property_string(it, name, value)
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

    /**
     * 自动选默认简体中文字幕，每个文件只执行一次，绝不覆盖用户手动切换。
     *
     * 优先级：简体中文（100）> 通用中文（50）> 繁体中文（10）> 其他（0，不选）。
     *  - 简体：lang 为 chi-Hans / zh-Hans / zh-CN / cmn-Hans 等，或标题含“简体”/“简中”/“Simplified”/“CHS”
     *  - 通用：lang 为 zh / chi / zho 等，或标题含“中文”/“Chinese”（且不含繁体标记）
     *  - 繁体：lang 为 zh-Hant / zh-TW / zh-HK 等，或标题含“繁体”/“繁體”/“Traditional”/“CHT”
     * 无中文轨时不动（保持 mpv 原行为，不强制）。
     * 轨道列表尚未就绪（count==0）时返回 false，不置标志，等 TRACKS_CHANGED 再试。
     */
    fun selectDefaultChineseSubtitle(): Boolean {
        if (autoSubSelectedForFile) return true
        val ctx = handle ?: return false
        return try {
            val count = getPropertyDouble("track-list/count")?.toInt() ?: 0
            if (count <= 0) return false // 轨道还没就绪，等 TRACKS_CHANGED 再试
            val currentSidId = getPropertyString("sid")?.toIntOrNull()
            var currentScore = 0
            var bestId: Int? = null
            var bestScore = 0
            for (i in 0 until count) {
                if (getPropertyString("track-list/$i/type") != "sub") continue
                val id = getPropertyDouble("track-list/$i/id")?.toInt() ?: continue
                val lang = getPropertyString("track-list/$i/lang") ?: ""
                val title = getPropertyString("track-list/$i/title") ?: ""
                val score = scoreChineseSub(lang, title)
                if (id == currentSidId) currentScore = score
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }
            autoSubSelectedForFile = true
            if (bestId == null || bestScore <= 0) {
                DebugLog.d("Sub", "无中文字幕轨，保持原选择")
                return false
            }
            if (bestId == currentSidId || currentScore >= 100) {
                // 当前已是简体中文（mpv 自己已选对），不重复设置
                DebugLog.d("Sub", "当前字幕已是简体中文 (sid=$currentSidId)，不重复设置")
                return true
            }
            LibMpv.INSTANCE.mpv_set_property_string(ctx, "sid", bestId.toString())
            DebugLog.d("Sub", "自动选默认简体中文字幕: sid=$bestId (score=$bestScore)")
            true
        } catch (e: Throwable) {
            DebugLog.d("Sub", "自动选字幕失败: ${e.message}")
            false
        }
    }

    /** 中文字幕评分：简体 100 > 通用中文 50 > 繁体 10 > 其他 0。 */
    private fun scoreChineseSub(lang: String, title: String): Int {
        val l = lang.trim().lowercase().replace('_', '-')
        val t = title
        // 简体：先判，避免“中文简体”被通用中文抢走
        val simplifiedLang = l in setOf(
            "chi-hans", "zh-hans", "zh-cn", "zh-hans-cn", "chi-hans-cn",
            "cmn-hans", "zh-sg", "zh-my"
        )
        val simplifiedTitle = t.contains("简体") || t.contains("简中") ||
            t.contains("Simplified", ignoreCase = true) ||
            t.contains("CHS", ignoreCase = true)
        if (simplifiedLang || simplifiedTitle) return 100
        // 繁体：后判，避免“中文繁体”被通用中文抢走
        val traditionalLang = l in setOf(
            "zh-hant", "chi-hant", "zh-tw", "zh-hk", "zh-mo", "cmn-hant"
        )
        val traditionalTitle = t.contains("繁体") || t.contains("繁體") ||
            t.contains("Traditional", ignoreCase = true) ||
            t.contains("CHT", ignoreCase = true)
        if (traditionalLang || traditionalTitle) return 10
        // 通用中文
        val genericLang = l in setOf("zh", "chi", "zho", "cn", "chinese") ||
            l.startsWith("zh-") || l.startsWith("chi-") ||
            l.startsWith("zho-") || l.startsWith("cmn-")
        val genericTitle = t.contains("中文") || t.contains("Chinese", ignoreCase = true)
        if (genericLang || genericTitle) return 50
        return 0
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
                MpvEventId.FILE_LOADED -> {
                    selectDefaultChineseSubtitle()
                    listener?.onFileLoaded()
                }
                MpvEventId.TRACKS_CHANGED -> {
                    // 外挂字幕可能晚于 FILE_LOADED 加载；只在还没选过时补一次
                    selectDefaultChineseSubtitle()
                }
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
                        val text = (lm.text ?: "").trim()

                        // 其他常规日志正常派发
                        listener?.onLog(
                            lm.prefix ?: "",
                            lm.level ?: "",
                            text
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
