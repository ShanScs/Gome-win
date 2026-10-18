package com.muse.gomepc.emby

/** 桌面端日志（替代 android.util.Log），输出到 stdout */
object Log {
    fun d(tag: String, msg: String) = println("D/$tag: $msg")
    fun w(tag: String, msg: String) = println("W/$tag: $msg")
    fun e(tag: String, msg: String) = println("E/$tag: $msg")
    fun i(tag: String, msg: String) = println("I/$tag: $msg")
}
