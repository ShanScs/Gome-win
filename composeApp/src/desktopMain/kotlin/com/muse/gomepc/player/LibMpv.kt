package com.muse.gomepc.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * libmpv C API 常量（client.h / render.h 对应）。
 * 只收录 Step 4 需要的子集。
 */
object MpvEventId {
    const val NONE = 0
    const val SHUTDOWN = 1
    const val LOG_MESSAGE = 2
    const val GET_PROPERTY_REPLY = 3
    const val SET_PROPERTY_REPLY = 4
    const val COMMAND_REPLY = 5
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val TRACKS_CHANGED = 9
    const val TRACK_SWITCHED = 10
    const val IDLE = 11
    const val PAUSE = 12
    const val UNPAUSE = 13
    const val TICK = 14
    const val SCRIPT_INPUT_DISPATCH = 15
    const val CLIENT_MESSAGE = 16
    const val VIDEO_RECONFIG = 17
    const val AUDIO_RECONFIG = 18
    const val METADATA_UPDATE = 19
    const val SEEK = 20
    const val PLAYBACK_RESTART = 21
    const val PROPERTY_CHANGE = 22
    const val CHAPTER_CHANGE = 23
    const val QUEUE_OVERFLOW = 24
    const val HOOK = 25
}

/** mpv_event_end_file.reason（client.h：MPV_END_FILE_REASON_*）。 */
object MpvEndFileReason {
    const val EOF = 0
    const val STOP = 1
    const val QUIT = 2
    const val ERROR = 3
}

object MpvFormat {
    const val NONE = 0
    const val STRING = 1
    const val OSD_STRING = 2
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
    const val NODE = 6
}

/** struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; } */
open class MpvEvent(p: Pointer?) : Structure(p) {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
    override fun getFieldOrder(): List<String> =
        listOf("event_id", "error", "reply_userdata", "data")
}

/** struct mpv_event_property { const char *name; mpv_format format; void *data; } */
open class MpvEventProperty(p: Pointer?) : Structure(p) {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
    override fun getFieldOrder(): List<String> =
        listOf("name", "format", "data")
}

/** struct mpv_event_log_message { const char *prefix; const char *level; const char *text; } */
open class MpvEventLogMessage(p: Pointer?) : Structure(p) {
    @JvmField var prefix: String? = null
    @JvmField var level: String? = null
    @JvmField var text: String? = null
    override fun getFieldOrder(): List<String> =
        listOf("prefix", "level", "text")
}

/** struct mpv_event_end_file { int reason; int error; }（reason 见 MpvEndFileReason） */
open class MpvEventEndFile(p: Pointer?) : Structure(p) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    override fun getFieldOrder(): List<String> =
        listOf("reason", "error")
}

/**
 * libmpv JNA 绑定（手写，vlcj 模式，不依赖 vlcj 库）。
 * 函数签名对照 mpv/client.h。
 */
interface LibMpv : Library {

    /** 失败返回 NULL（JNA 映射为 null 或 Pointer.NULL，调用方两者都要判）。 */
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)

    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int

    /** args 必须 NULL 结尾：arrayOf("loadfile", url, "replace", null) */
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int

    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int

    /** 返回 char*，用完必须 mpv_free；失败返回 NULL */
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?

    /** data 为调用方分配的内存（如 Memory(8)），按 format 写入 */
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int

    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_unobserve_property(ctx: Pointer, registered_reply_userdata: Long): Int

    /** 返回 &mpv_event（mpv 拥有的静态内存，不要 free）；超时返回 event_id=NONE；失败返回 NULL */
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?

    fun mpv_free(ptr: Pointer)

    /**
     * 返回 mpv 错误码对应的英文描述（C 返回 UTF-8 字符串；JNA 按默认字符集解码，
     * JDK 18+ 默认 UTF-8）。C 可能返回 NULL，此时 JNA 映射为 null，调用方要判空。
     */
    fun mpv_error_string(error: Int): String?

    /** 启用日志消息事件（minLevel: "info"/"warn"/"error" 等） */
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int

    companion object {
        val INSTANCE: LibMpv by lazy {
            val os = System.getProperty("os.name", "").lowercase()
            val isWindows = os.contains("win")
            val isMac = os.contains("mac") || os.contains("darwin")
            // 按操作系统区分库文件名：Windows libmpv-2.dll / Linux libmpv.so.2 / libmpv.so / macOS libmpv.2.dylib
            val bundledNames: List<String> = when {
                isWindows -> listOf("libmpv-2.dll")
                isMac -> listOf("libmpv.2.dylib", "libmpv.dylib")
                else -> listOf("libmpv.so.2", "libmpv.so")
            }
            val systemNames: List<String> = when {
                isWindows -> listOf("libmpv-2", "mpv")
                isMac -> listOf("libmpv.2", "libmpv", "mpv")
                else -> listOf("libmpv.so.2", "libmpv.so", "libmpv", "mpv")
            }

            // 1. 优先用绝对路径加载 app 目录 / JAR 所在目录 / 工作目录下的打包库（最可靠）
            val dirs = ArrayList<java.io.File>()
            try {
                // 从当前 JAR 位置推导 app 目录（jpackage: app/xxx.jar → 上级即 app 根目录的父级）
                val jarUrl = LibMpv::class.java.protectionDomain?.codeSource?.location
                if (jarUrl != null) {
                    val jarPath = java.io.File(jarUrl.toURI())
                    // jar 在 app/ 下，DLL 在 app/ 的上级目录（Gome/ 根目录），也可能就在 app/ 下
                    jarPath.parentFile?.let { dirs.add(it) }
                    jarPath.parentFile?.parentFile?.let { dirs.add(it) }
                }
            } catch (_: Throwable) { }
            try {
                dirs.add(java.io.File(System.getProperty("user.dir", ".")))
            } catch (_: Throwable) { }
            for (dir in dirs.distinct()) {
                for (name in bundledNames) {
                    val file = java.io.File(dir, name)
                    if (file.isFile) {
                        try {
                            return@lazy Native.load(file.absolutePath, LibMpv::class.java) as LibMpv
                        } catch (_: Throwable) { }
                    }
                }
            }
            // 2. 回退：按库名搜索（系统库路径）
            var lastError: Throwable? = null
            for (name in systemNames) {
                try {
                    return@lazy Native.load(name, LibMpv::class.java) as LibMpv
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            throw IllegalStateException(
                "无法加载 libmpv，已尝试库名: ${systemNames.joinToString()}，" +
                    "请确认已安装 libmpv（Windows: libmpv-2.dll / Linux: libmpv.so.2 / macOS: libmpv.2.dylib）",
                lastError
            )
        }
    }
}
