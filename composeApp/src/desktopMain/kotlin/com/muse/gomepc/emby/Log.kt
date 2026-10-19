package com.muse.gomepc.emby

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面端日志（替代 android.util.Log）。
 * 输出到 stdout，同时追加到 ~/.gome/gome.log（带时间戳，按天滚动，超 5MB 截断重建）。
 * 统一分级 + 脱敏：Token / api_key 永不进日志。
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** 低于该级别的日志不输出（默认 DEBUG，即全部输出，与旧行为一致） */
    @Volatile var minLevel: Level = Level.DEBUG

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val fileLock = Any()
    private const val MAX_LOG_BYTES = 5L * 1024 * 1024

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    private fun log(level: Level, tag: String, msg: String) {
        if (level.ordinal < minLevel.ordinal) return
        val prefix = when (level) {
            Level.DEBUG -> "D"
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ERROR -> "E"
        }
        val line = "${dateFormat.get().format(Date())} $prefix/$tag: ${sanitizeForLog(msg)}"
        println(line)
        appendToFile(line)
    }

    private fun appendToFile(line: String) {
        try {
            synchronized(fileLock) {
                val home = System.getProperty("user.home") ?: return
                val dir = File(home, ".gome").apply { mkdirs() }
                val f = File(dir, "gome.log")
                // 按天滚动：文件日期不是今天则重建；超 5MB 则截断重建
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val fileDay = if (f.exists()) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(f.lastModified())) else ""
                if (fileDay != today || f.length() > MAX_LOG_BYTES) {
                    f.writeText("")
                }
                f.appendText(line + "\n")
            }
        } catch (_: Exception) {
            // 日志落盘失败绝不能影响业务：静默丢弃
        }
    }

    /**
     * 脱敏：替换日志/异常信息里的 Token（X-Emby-Token、api_key、Token=）。
     * 注意：Token= 用了反向否定界定，避免把 AccessToken= 误替换。
     */
    fun sanitizeForLog(msg: String): String = msg
        .replace(Regex("(?i)(X-Emby-Token=)[^&\\s\"']+"), "$1***")
        .replace(Regex("(?i)(api_key=)[^&\\s\"']+"), "$1***")
        .replace(Regex("(?i)(?<![A-Za-z])(Token=)[^&\\s\",}]+"), "$1***")
}
