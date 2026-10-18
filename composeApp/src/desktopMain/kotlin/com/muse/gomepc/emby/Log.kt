package com.muse.gomepc.emby

/** 桌面端日志（替代 android.util.Log），输出到 stdout；统一分级 + 脱敏。 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** 低于该级别的日志不输出（默认 DEBUG，即全部输出，与旧行为一致） */
    @Volatile var minLevel: Level = Level.DEBUG

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
        println("$prefix/$tag: ${sanitizeForLog(msg)}")
    }

    /**
     * 脱敏：替换日志/异常信息里的 Token（X-Emby-Token、api_key、Token=）。
     * 注意：Token= 用了反向否定界定，避免把 AccessToken= 误替换成 Access***。
     */
    fun sanitizeForLog(msg: String): String = msg
        .replace(Regex("(?i)(X-Emby-Token=)[^&\\s]*"), "$1***")
        .replace(Regex("(?i)(api_key=)[^&\\s]*"), "$1***")
        .replace(Regex("(?i)(?<![A-Za-z])(Token=)[^&,\\s\"]*"), "Token=***")
}
