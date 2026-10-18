package com.muse.gomepc.player

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/** 诊断日志：写文件供用户拖回分析；启动时轮转，保留最近 5 份旧日志，不直接删除。 */
object DebugLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_ROTATED = 5
    private val logFile by lazy {
        val home = System.getProperty("user.home") ?: "."
        val dir = java.io.File(home, ".gome").apply { mkdirs() }
        val file = java.io.File(dir, "gome-debug.log")
        try {
            // 启动时把旧日志轮转为 .1（保留，不删除），再建新文件
            if (file.exists() && file.length() > 0) rotateLogs(file)
            if (!file.exists()) file.createNewFile()
        } catch (_: Throwable) { }
        file
    }

    /** 把 current 轮转为 .1，旧的 .i 顺延为 .i+1，只丢弃超出保留份数的最旧一份。 */
    private fun rotateLogs(current: java.io.File) {
        try {
            val dir = current.parentFile ?: return
            val oldest = java.io.File(dir, "gome-debug.log.$MAX_ROTATED")
            if (oldest.exists()) oldest.delete()
            for (i in MAX_ROTATED - 1 downTo 1) {
                val src = java.io.File(dir, "gome-debug.log.$i")
                if (src.exists()) src.renameTo(java.io.File(dir, "gome-debug.log.${i + 1}"))
            }
            current.renameTo(java.io.File(dir, "gome-debug.log.1"))
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        try {
            if (logFile.exists() && logFile.length() > MAX_BYTES) rotateLogs(logFile)
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
 * 事件循环跑在独立 daemon 线程，用 mpv_wait_event(timeout=0.5) 轮询，
 * 所有 Listener 回调都经 SwingUtilities.invokeLater 分发到 EDT。
 */
class MpvPlayer : Closeable {

    interface Listener {
        fun onFileLoaded()
        fun onEndFile()
        fun onError(msg: String)
        fun onTimePos(sec: Double, duration: Double)
        fun onPause(paused: Boolean)
        fun onLog(prefix: String, level: String, text: String) {}
        /** mpv 收到 SHUTDOWN 事件（播放器正在关闭）。默认空实现。 */
        fun onShutdown() {}
    }

    /** 轨道信息（listTracks 返回）。 */
    data class TrackInfo(val id: Int, val type: String, val lang: String, val title: String)

    @Volatile var listener: Listener? = null

    @Volatile private var handle: Pointer? = null
    @Volatile private var running = false
    @Volatile private var eventThread: Thread? = null
    /** 串行化 init/destroy，避免重初始化与释放竞态。 */
    private val initLock = Any()
    /** 保护 time-pos/duration 成对读写，使快照原子。 */
    private val snapshotLock = Any()

    @Volatile private var lastTimePos = 0.0
    @Volatile private var lastDuration = 0.0
    @Volatile private var lastPaused = false
    /** 本文件是否已执行过自动选字幕（仅真正选到才置位，用户手动切后不再覆盖）。 */
    @Volatile private var autoSubSelectedForFile = false
    /** 文件代际号：play()/destroy() 递增，后台选字幕线程用它丢弃过期文件的结果。 */
    private val fileGeneration = AtomicInteger(0)
    /** 选字幕后台任务是否在途，避免 FILE_LOADED/TRACKS_CHANGED 重复拉起多个线程。 */
    private val subSelectInFlight = AtomicBoolean(false)
    /** getProperty* 复用线程本地内存，避免每次分配 Memory。 */
    private val doubleMem: ThreadLocal<Memory> = ThreadLocal.withInitial { Memory(8) }
    private val longMem: ThreadLocal<Memory> = ThreadLocal.withInitial { Memory(8) }
    private val intMem: ThreadLocal<Memory> = ThreadLocal.withInitial { Memory(8) }

    val timePos: Double get() = lastTimePos
    val duration: Double get() = lastDuration
    val isPaused: Boolean get() = lastPaused
    val isInitialized: Boolean get() = handle != null

    /** time-pos/duration 原子快照（避免两次 volatile 读之间被事件线程改写）。 */
    fun timePosSnapshot(): Pair<Double, Double> = synchronized(snapshotLock) { lastTimePos to lastDuration }

    /**
     * @param wid  X11 Window ID（Linux）/ HWND（Windows），<=0 表示让 mpv 自己建窗口
     * @param vo   Xvfb/无 GPU 测试用 "x11"；真机用 "gpu-next"
     * @param hwdec "auto" / "no"，VM 无 GPU 时用 "no"
     * @return null=成功，否则为错误信息
     */
    fun init(wid: Long, vo: String = "gpu-next", hwdec: String = "auto", cacheEnabled: Boolean = true, cacheSizeGb: Int = 1): String? =
        synchronized(initLock) { initLocked(wid, vo, hwdec, cacheEnabled, cacheSizeGb) }

    private fun initLocked(wid: Long, vo: String, hwdec: String, cacheEnabled: Boolean, cacheSizeGb: Int): String? {
        if (handle != null) return "already initialized"
        val lib = try {
            LibMpv.INSTANCE
        } catch (e: Throwable) {
            return "load libmpv failed: ${e.message}"
        }
        val rawCtx: Pointer? = try {
            lib.mpv_create()
        } catch (e: Throwable) {
            return "mpv_create failed: ${e.message}"
        }
        if (rawCtx == null || rawCtx == Pointer.NULL) return "mpv_create returned NULL"
        val ctx: Pointer = rawCtx
        handle = ctx
        synchronized(snapshotLock) { lastTimePos = 0.0; lastDuration = 0.0 }
        lastPaused = false
        autoSubSelectedForFile = false

        // 失败时只释放本次创建的 ctx（不走 destroy()，避免递归/重复释放）。
        fun releaseAndFail(msg: String): String? {
            releaseCtxLocked(ctx)
            return msg
        }
        // 只返回错误码，不 destroy：非致命选项失败时 ctx 必须继续可用（旧代码在 opt() 内
        // destroy 后调用方继续用已释放 ctx，造成 use-after-free）。
        fun opt(name: String, value: String): Int {
            return try {
                lib.mpv_set_option_string(ctx, name, value)
            } catch (e: Throwable) {
                DebugLog.d("MPV", "set_option $name=$value 异常: ${e.message}")
                -1
            }
        }
        /** 非致命选项：失败只记日志，不中断初始化。 */
        fun optChecked(name: String, value: String) {
            val r = opt(name, value)
            if (r < 0) DebugLog.d("MPV", "set_option $name=$value 失败(非致命): ${libErrorString(lib, r)}")
        }
        /** 致命选项：失败则释放 ctx 并返回错误。 */
        fun requireOpt(name: String, value: String): String? {
            val r = opt(name, value)
            if (r < 0) return releaseAndFail("set_option $name=$value failed: ${libErrorString(lib, r)}")
            return null
        }

        requireOpt("config", "no")?.let { return it }          // 不读用户配置文件
        // vo 降级：首选失败时依次尝试 gpu / x11
        run {
            var voOk = false
            for (cand in listOf(vo, "gpu", "x11").distinct()) {
                val r = opt("vo", cand)
                if (r >= 0) {
                    voOk = true
                    if (cand != vo) DebugLog.d("MPV", "vo 回退到 $cand（首选 $vo 失败）")
                    break
                }
                DebugLog.d("MPV", "set_option vo=$cand 失败: ${libErrorString(lib, r)}")
            }
            if (!voOk) return releaseAndFail("set_option vo failed: 首选 $vo 及回退 gpu/x11 均失败")
        }
        // hwdec 回退：首选 -> auto -> no
        run {
            var hwOk = false
            for (cand in listOf(hwdec, "auto", "no").distinct()) {
                val r = opt("hwdec", cand)
                if (r >= 0) {
                    hwOk = true
                    if (cand != hwdec) DebugLog.d("MPV", "hwdec 回退到 $cand（首选 $hwdec 失败）")
                    break
                }
                DebugLog.d("MPV", "set_option hwdec=$cand 失败: ${libErrorString(lib, r)}")
            }
            if (!hwOk) return releaseAndFail("set_option hwdec failed")
        }
        optChecked("osd-level", "0")                          // 关自带 OSD（UI 自己画控制条）
        optChecked("osd-bar", "no")
        if (wid > 0) requireOpt("wid", wid.toString())?.let { return it }
        optChecked("msg-level", "all=warn")                   // 默认 warn，避免日志洪泛
        optChecked("terminal", "no")
        optChecked("tls-verify", "yes")                       // 默认校验 TLS，不全局关闭
        optChecked("input-cursor", "no")
        optChecked("input-vo-keyboard", "no")
        optChecked("input-default-bindings", "no")
        optChecked("osc", "no")

        // 保持窗口/空闲行为：无文件时不退出，EOF 后保持窗口
        optChecked("idle", "yes")
        optChecked("keep-open", "yes")
        if (wid > 0) optChecked("force-window", "yes")

        // HDR / Dolby Vision 默认：色调映射 + 色彩空间提示 + 峰值计算
        optChecked("tone-mapping", "hable")
        optChecked("target-colorspace-hint", "yes")
        optChecked("hdr-compute-peak", "yes")
        optChecked("gamut-mapping-mode", "perceptual")

        // 缓存设置（对齐安卓：cache=yes + demuxer-max-bytes=1G/2G/3G），容量钳制到 1..8 GiB
        val cacheGb = cacheSizeGb.coerceIn(1, 8)
        if (cacheGb != cacheSizeGb) DebugLog.d("MPV", "cacheSizeGb 越界钳制: $cacheSizeGb -> $cacheGb")
        if (cacheEnabled) {
            optChecked("cache", "yes")
            optChecked("demuxer-max-bytes", "${cacheGb}GiB")
        } else {
            optChecked("cache", "no")
        }

        // 请求日志消息必须在 initialize 之前（mpv 要求）
        val logR = try { lib.mpv_request_log_messages(ctx, "warn") } catch (_: Throwable) { -1 }
        if (logR < 0) DebugLog.d("MPV", "mpv_request_log_messages 失败: ${libErrorString(lib, logR)}")

        val r = try {
            lib.mpv_initialize(ctx)
        } catch (e: Throwable) {
            return releaseAndFail("mpv_initialize failed: ${e.message}")
        }
        if (r < 0) return releaseAndFail("mpv_initialize failed: ${libErrorString(lib, r)}")

        // OSD 关掉（UI 自己画控制条；set_option 在某些构建不生效，改用 property），判返回值
        val osdR = try { lib.mpv_set_property_string(ctx, "osd-level", "0") } catch (_: Throwable) { -1 }
        if (osdR < 0) DebugLog.d("MPV", "set_property osd-level=0 失败(非致命): ${libErrorString(lib, osdR)}")

        // 观察常用属性（userdata 仅用于区分，事件里直接读 name），判返回值
        val observed = listOf(
            Triple(1L, "time-pos", MpvFormat.DOUBLE),
            Triple(2L, "duration", MpvFormat.DOUBLE),
            Triple(3L, "pause", MpvFormat.FLAG)
        )
        for ((userdata, name, format) in observed) {
            val rr = try { lib.mpv_observe_property(ctx, userdata, name, format) } catch (_: Throwable) { -1 }
            if (rr < 0) DebugLog.d("MPV", "observe_property $name 失败: ${libErrorString(lib, rr)}（对应回调可能收不到）")
        }

        running = true
        eventThread = Thread(::eventLoop, "mpv-event").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /** 释放本次 init 创建的 ctx（仅 init 失败路径用；不碰 eventThread，事件线程尚未启动）。 */
    private fun releaseCtxLocked(ctx: Pointer) {
        if (handle == ctx) handle = null
        running = false
        try { LibMpv.INSTANCE.mpv_terminate_destroy(ctx) } catch (_: Throwable) { }
    }

    private fun libErrorString(lib: LibMpv, code: Int): String =
        try { lib.mpv_error_string(code) } catch (_: Throwable) { null } ?: "mpv error $code"

    private fun runCommandString(cmd: String): Boolean {
        val r = synchronized(initLock) {
            val ctx = handle ?: return false
            try { LibMpv.INSTANCE.mpv_command_string(ctx, cmd) } catch (_: Throwable) { -1 }
        }
        return r >= 0
    }

    private fun setPropertyInternal(name: String, value: String): Boolean {
        val r = synchronized(initLock) {
            val ctx = handle ?: return false
            try { LibMpv.INSTANCE.mpv_set_property_string(ctx, name, value) } catch (_: Throwable) { -1 }
        }
        return r >= 0
    }

    /** 播放。返回 null=命令已发送，否则为错误信息。会重置旧文件状态并递增文件代际号。 */
    fun play(url: String): String? {
        val lib = try {
            LibMpv.INSTANCE
        } catch (e: Throwable) {
            return "load libmpv failed: ${e.message}"
        }
        fileGeneration.incrementAndGet()
        synchronized(snapshotLock) { lastTimePos = 0.0; lastDuration = 0.0 }
        lastPaused = false
        autoSubSelectedForFile = false // 新文件：允许自动选一次默认字幕
        val r = synchronized(initLock) {
            val ctx = handle ?: return "not initialized"
            try {
                lib.mpv_command(ctx, arrayOf("loadfile", url, "replace", null))
            } catch (e: Throwable) {
                return "loadfile failed: ${e.message}"
            }
        }
        return if (r < 0) libErrorString(lib, r) else null
    }

    /** 停止当前文件（mpv 进入 idle）。成功时重置播放状态并递增文件代际号。 */
    fun stop(): Boolean {
        val ok = runCommandString("stop")
        if (ok) {
            fileGeneration.incrementAndGet()
            synchronized(snapshotLock) { lastTimePos = 0.0; lastDuration = 0.0 }
            lastPaused = false
            autoSubSelectedForFile = false
        }
        return ok
    }

    fun setPaused(p: Boolean): Boolean = setPropertyInternal("pause", if (p) "yes" else "no")

    fun togglePause(): Boolean = runCommandString("cycle pause")

    /** 绝对 seek（秒）。非法值返回 false；超出 [0, duration] 时钳制（duration 未知时只钳下界）。 */
    fun seek(sec: Double): Boolean {
        if (sec.isNaN() || sec.isInfinite()) return false
        val dur = synchronized(snapshotLock) { lastDuration }
        val target = if (dur > 0) sec.coerceIn(0.0, dur) else sec.coerceAtLeast(0.0)
        if (target != sec) DebugLog.d("MPV", "seek 钳制: $sec -> $target (duration=$dur)")
        return runCommandString("seek $target absolute")
    }

    fun setVolume(v: Double): Boolean = setPropertyInternal("volume", v.coerceIn(0.0, 100.0).toString())

    /** 循环播放（演示/截图用）。 */
    fun setLoop(loop: Boolean): Boolean = setPropertyInternal("loop-file", if (loop) "inf" else "no")

    /** 通用属性设置（供工具栏等调用）。返回是否成功。 */
    fun setProperty(name: String, value: String): Boolean = setPropertyInternal(name, value)

    /** 同步读 double 属性，失败返回 null。 */
    fun getPropertyDouble(name: String): Double? {
        val mem = doubleMem.get()
        val r = synchronized(initLock) {
            val ctx = handle ?: return null
            try { LibMpv.INSTANCE.mpv_get_property(ctx, name, MpvFormat.DOUBLE, mem) } catch (_: Throwable) { -1 }
        }
        return if (r < 0) null else mem.getDouble(0)
    }

    /** 同步读 int64 属性（track-list/count、track-list/N/id 等整数属性必须用 INT64），失败返回 null。 */
    fun getPropertyLong(name: String): Long? {
        val mem = longMem.get()
        val r = synchronized(initLock) {
            val ctx = handle ?: return null
            try { LibMpv.INSTANCE.mpv_get_property(ctx, name, MpvFormat.INT64, mem) } catch (_: Throwable) { -1 }
        }
        return if (r < 0) null else mem.getLong(0)
    }

    /** 同步读 flag/bool 属性，失败返回 null。 */
    fun getPropertyBoolean(name: String): Boolean? {
        val mem = intMem.get()
        val r = synchronized(initLock) {
            val ctx = handle ?: return null
            try { LibMpv.INSTANCE.mpv_get_property(ctx, name, MpvFormat.FLAG, mem) } catch (_: Throwable) { -1 }
        }
        return if (r < 0) null else mem.getInt(0) != 0
    }

    /** 同步读 string 属性，失败返回 null。判 Pointer.NULL，按 UTF-8 解码。 */
    fun getPropertyString(name: String): String? {
        val ptr: Pointer? = synchronized(initLock) {
            val ctx = handle ?: return null
            try { LibMpv.INSTANCE.mpv_get_property_string(ctx, name) } catch (_: Throwable) { null }
        }
        if (ptr == null || ptr == Pointer.NULL) return null
        return try {
            ptr.getString(0, "UTF-8")
        } catch (_: Throwable) {
            null
        } finally {
            try { LibMpv.INSTANCE.mpv_free(ptr) } catch (_: Throwable) { }
        }
    }

    /** 轨道列表（失败返回空列表）。 */
    fun listTracks(): List<TrackInfo> {
        val count = getPropertyLong("track-list/count") ?: return emptyList()
        if (count <= 0) return emptyList()
        val out = ArrayList<TrackInfo>()
        val n = minOf(count, 512L)
        for (i in 0 until n) {
            val type = getPropertyString("track-list/$i/type") ?: continue
            val id = getPropertyLong("track-list/$i/id") ?: continue
            val lang = getPropertyString("track-list/$i/lang") ?: ""
            val title = getPropertyString("track-list/$i/title") ?: ""
            out.add(TrackInfo(id.toInt(), type, lang, title))
        }
        return out
    }

    /** 选音频轨：id < 0 表示关闭音频（aid=no）。 */
    fun setAudioTrack(id: Int): Boolean =
        setPropertyInternal("aid", if (id < 0) "no" else id.toString())

    /**
     * 选字幕轨：id < 0 表示关闭字幕（sid=no）。
     * 手动选轨成功后置位 autoSubSelectedForFile，自动选字幕不再覆盖手动选择。
     */
    fun setSubtitleTrack(id: Int): Boolean {
        val ok = setPropertyInternal("sid", if (id < 0) "no" else id.toString())
        if (ok) autoSubSelectedForFile = true
        return ok
    }

    /**
     * 自动选默认简体中文字幕，每个文件只执行一次，绝不覆盖用户手动切换。
     *
     * 异步执行：在独立线程做，不阻塞事件循环；带文件代际号，切集/destroy 后过期结果不会生效。
     * 仅当真正选到（已设为最佳中文轨，或当前已是简体中文）才置位 autoSubSelectedForFile；
     * 无中文轨 / 轨道未就绪时不置位，等 TRACKS_CHANGED 再试。
     *
     * @return true=本文件已选好；false=尚未选好（已在后台尝试 / 无中文轨 / 未初始化）
     */
    fun selectDefaultChineseSubtitle(): Boolean {
        if (autoSubSelectedForFile) return true
        if (!subSelectInFlight.compareAndSet(false, true)) return false
        val gen = fileGeneration.get()
        val t = Thread({
            try {
                selectDefaultChineseSubtitleSync(gen)
            } catch (e: Throwable) {
                DebugLog.d("Sub", "自动选字幕失败: ${e.message}")
            } finally {
                subSelectInFlight.set(false)
            }
        }, "mpv-sub-select")
        t.isDaemon = true
        t.start()
        return false
    }

    private fun selectDefaultChineseSubtitleSync(gen: Int): Boolean {
        // 轨道可能晚于 FILE_LOADED 就绪（外挂字幕）：最多等 3 次，每次 300ms
        for (attempt in 0 until 3) {
            if (gen != fileGeneration.get()) return false
            if (handle == null) return false
            val tracks = listTracks()
            if (tracks.isNotEmpty()) {
                val subs = tracks.filter { it.type == "sub" }
                if (subs.isEmpty()) {
                    DebugLog.d("Sub", "无字幕轨，保持原选择")
                    return false
                }
                val currentSidId = getPropertyString("sid")?.toIntOrNull()
                var currentScore = 0
                var best: TrackInfo? = null
                var bestScore = 0
                for (track in subs) {
                    val score = scoreChineseSub(track.lang, track.title)
                    if (track.id == currentSidId) currentScore = score
                    if (score > bestScore) {
                        bestScore = score
                        best = track
                    }
                }
                val bestTrack = best
                if (bestTrack == null || bestScore <= 0) {
                    DebugLog.d("Sub", "无中文字幕轨，保持原选择")
                    return false
                }
                if (bestTrack.id == currentSidId || currentScore >= 100) {
                    // 当前已是最佳中文轨 / 简体中文（mpv 自己已选对），不重复设置
                    autoSubSelectedForFile = true
                    DebugLog.d("Sub", "当前字幕已是最佳中文轨 (sid=$currentSidId)，不重复设置")
                    return true
                }
                if (gen != fileGeneration.get() || handle == null) return false
                val ok = setPropertyInternal("sid", bestTrack.id.toString())
                if (ok) {
                    autoSubSelectedForFile = true
                    DebugLog.d("Sub", "自动选默认简体中文字幕: sid=${bestTrack.id} (score=$bestScore)")
                    return true
                }
                DebugLog.d("Sub", "设置 sid=${bestTrack.id} 失败")
                return false
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
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
            containsWord(t, "Simplified") ||
            containsWord(t, "CHS")
        if (simplifiedLang || simplifiedTitle) return 100
        // 繁体：后判，避免“中文繁体”被通用中文抢走
        val traditionalLang = l in setOf(
            "zh-hant", "chi-hant", "zh-tw", "zh-hk", "zh-mo", "cmn-hant"
        )
        val traditionalTitle = t.contains("繁体") || t.contains("繁體") ||
            containsWord(t, "Traditional") ||
            containsWord(t, "CHT")
        if (traditionalLang || traditionalTitle) return 10
        // 通用中文
        val genericLang = l in setOf("zh", "chi", "zho", "cn", "chinese") ||
            l.startsWith("zh-") || l.startsWith("chi-") ||
            l.startsWith("zho-") || l.startsWith("cmn-")
        val genericTitle = t.contains("中文") || containsWord(t, "Chinese")
        if (genericLang || genericTitle) return 50
        return 0
    }

    /** 单词边界匹配：避免 "CHS" 误判 "SCHS"、"Chinese" 误判 "ChineseX" 这类子串。 */
    private fun containsWord(text: String, word: String): Boolean {
        val pattern = Regex(
            "(?<![A-Za-z0-9])" + Regex.escape(word) + "(?![A-Za-z0-9])",
            RegexOption.IGNORE_CASE
        )
        return pattern.containsMatchIn(text)
    }

    private fun dispatchOnEdt(block: () -> Unit) {
        try {
            if (SwingUtilities.isEventDispatchThread()) block()
            else SwingUtilities.invokeLater(block)
        } catch (_: Throwable) {
            try { block() } catch (_: Throwable) { }
        }
    }

    private fun reportError(msg: String) {
        val l = listener ?: return
        dispatchOnEdt {
            try { l.onError(msg) } catch (_: Throwable) { }
        }
    }

    private fun eventLoop() {
        val lib = try {
            LibMpv.INSTANCE
        } catch (e: Throwable) {
            reportError("加载 libmpv 失败: ${e.message}")
            return
        }
        val ctx = handle ?: return
        try {
            while (running) {
                val cur = handle
                if (cur == null || cur != ctx) break
                val evPtr: Pointer? = try {
                    lib.mpv_wait_event(ctx, 0.5)
                } catch (e: Throwable) {
                    reportError("mpv_wait_event 失败: ${e.message}")
                    break
                }
                if (evPtr == null || evPtr == Pointer.NULL) continue
                val ev = MpvEvent(evPtr)
                try {
                    ev.read()
                } catch (_: Throwable) {
                    continue
                }
                when (ev.event_id) {
                    MpvEventId.NONE -> { /* 超时 */ }
                    MpvEventId.SHUTDOWN -> {
                        running = false
                        if (handle == ctx) handle = null
                        val l = listener
                        dispatchOnEdt {
                            try { l?.onShutdown() } catch (_: Throwable) { }
                        }
                        break
                    }
                    MpvEventId.FILE_LOADED -> {
                        selectDefaultChineseSubtitle()
                        val l = listener
                        dispatchOnEdt {
                            try { l?.onFileLoaded() } catch (_: Throwable) { }
                        }
                    }
                    MpvEventId.TRACKS_CHANGED -> {
                        // 外挂字幕可能晚于 FILE_LOADED 加载；只在还没选过时补一次
                        selectDefaultChineseSubtitle()
                    }
                    MpvEventId.END_FILE -> {
                        var reason = -1
                        var errCode = 0
                        val dp = ev.data
                        if (dp != null && dp != Pointer.NULL) {
                            try {
                                val ef = MpvEventEndFile(dp)
                                ef.read()
                                reason = ef.reason
                                errCode = ef.error
                            } catch (_: Throwable) { }
                        }
                        val l = listener
                        dispatchOnEdt {
                            try { l?.onEndFile() } catch (_: Throwable) { }
                        }
                        if (reason == MpvEndFileReason.ERROR) {
                            reportError("播放失败 (END_FILE reason=error): ${libErrorString(lib, errCode)}")
                        }
                    }
                    MpvEventId.PAUSE -> {
                        lastPaused = true
                        val l = listener
                        dispatchOnEdt {
                            try { l?.onPause(true) } catch (_: Throwable) { }
                        }
                    }
                    MpvEventId.UNPAUSE -> {
                        lastPaused = false
                        val l = listener
                        dispatchOnEdt {
                            try { l?.onPause(false) } catch (_: Throwable) { }
                        }
                    }
                    MpvEventId.PROPERTY_CHANGE -> {
                        val dp = ev.data
                        if (dp == null || dp == Pointer.NULL) continue
                        val prop = MpvEventProperty(dp)
                        try {
                            prop.read()
                        } catch (_: Throwable) {
                            continue
                        }
                        val pdata = prop.data
                        if (pdata == null || pdata == Pointer.NULL) continue
                        try {
                            when (prop.name) {
                                "time-pos" -> {
                                    if (prop.format != MpvFormat.DOUBLE) {
                                        DebugLog.d("MPV", "time-pos format 非 DOUBLE: ${prop.format}，忽略")
                                    } else {
                                        val v = pdata.getDouble(0)
                                        val dur = synchronized(snapshotLock) {
                                            lastTimePos = v
                                            lastDuration
                                        }
                                        val l = listener
                                        dispatchOnEdt {
                                            try { l?.onTimePos(v, dur) } catch (_: Throwable) { }
                                        }
                                    }
                                }
                                "duration" -> {
                                    if (prop.format != MpvFormat.DOUBLE) {
                                        DebugLog.d("MPV", "duration format 非 DOUBLE: ${prop.format}，忽略")
                                    } else {
                                        synchronized(snapshotLock) { lastDuration = pdata.getDouble(0) }
                                    }
                                }
                                "pause" -> {
                                    if (prop.format != MpvFormat.FLAG) {
                                        DebugLog.d("MPV", "pause format 非 FLAG: ${prop.format}，忽略")
                                    } else {
                                        lastPaused = pdata.getInt(0) != 0
                                        val paused = lastPaused
                                        val l = listener
                                        dispatchOnEdt {
                                            try { l?.onPause(paused) } catch (_: Throwable) { }
                                        }
                                    }
                                }
                                else -> { /* 其他属性暂不处理 */ }
                            }
                        } catch (_: Throwable) {
                            // 属性 data 解析失败，忽略
                        }
                    }
                    MpvEventId.LOG_MESSAGE -> {
                        val dp = ev.data
                        if (dp == null || dp == Pointer.NULL) continue
                        try {
                            val lm = MpvEventLogMessage(dp)
                            lm.read()
                            val text = (lm.text ?: "").trim()
                            val prefix = lm.prefix ?: ""
                            val level = lm.level ?: ""
                            val l = listener
                            dispatchOnEdt {
                                try { l?.onLog(prefix, level, text) } catch (_: Throwable) { }
                            }
                        } catch (_: Throwable) { }
                    }
                    MpvEventId.SEEK -> { /* seek 完成，无需处理 */ }
                    MpvEventId.QUEUE_OVERFLOW -> {
                        reportError("mpv 事件队列溢出，部分事件丢失")
                    }
                    MpvEventId.START_FILE, MpvEventId.TRACK_SWITCHED, MpvEventId.IDLE,
                    MpvEventId.PLAYBACK_RESTART, MpvEventId.CHAPTER_CHANGE,
                    MpvEventId.VIDEO_RECONFIG, MpvEventId.AUDIO_RECONFIG, MpvEventId.TICK,
                    MpvEventId.GET_PROPERTY_REPLY, MpvEventId.SET_PROPERTY_REPLY,
                    MpvEventId.COMMAND_REPLY, MpvEventId.SCRIPT_INPUT_DISPATCH,
                    MpvEventId.CLIENT_MESSAGE, MpvEventId.METADATA_UPDATE,
                    MpvEventId.HOOK -> { /* 已知事件，暂无处理 */ }
                    else -> DebugLog.d("MPV", "未处理事件 id=${ev.event_id}")
                }
            }
        } catch (e: Throwable) {
            // 事件线程异常不再静默死亡：上报给监听方
            reportError("mpv 事件循环异常: ${e.message}")
        }
    }

    /**
     * 释放播放器。同步（与 init 互斥）、幂等、只释放一次。
     * 顺序：先 mpv_terminate_destroy 再 join 事件线程；destroy 在事件线程自身调用时不 join（防自 join 死锁）。
     * 释放后清理播放状态并解绑 listener；之后可重新 init。
     */
    fun destroy() {
        val state: Pair<Pointer, Thread?>? = synchronized(initLock) {
            val cur = handle ?: return@synchronized null
            handle = null
            running = false
            val t = eventThread
            eventThread = null
            listener = null
            synchronized(snapshotLock) { lastTimePos = 0.0; lastDuration = 0.0 }
            lastPaused = false
            autoSubSelectedForFile = false
            fileGeneration.incrementAndGet()
            Pair(cur, t)
        }
        val (ctx, thread) = state ?: return
        try {
            LibMpv.INSTANCE.mpv_terminate_destroy(ctx)
        } catch (_: Throwable) { }
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(2000)
            } catch (_: Throwable) { }
            if (thread.isAlive) {
                try { thread.interrupt() } catch (_: Throwable) { }
            }
        }
    }

    override fun close() = destroy()
}
