package com.muse.gomepc.player

import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Step 4 冒烟测试：JNA 绑定 + wid 窗口嵌入 + 播放 DV P8.1 测试文件。
 *
 * 用法（需 Xvfb）：DISPLAY=:99 ./gradlew :composeApp:runMpvSmokeTest
 * 可选参数：<video> <vo> <hwdec>（默认 /tmp/dvtest/dv_p81.mp4 x11 no）
 *
 * 输出 MPV_SMOKE: ... 标记行；退出码 0=通过。
 */
fun main(args: Array<String>) {
    val video = args.getOrElse(0) { "/tmp/dvtest/dv_p81.mp4" }
    val vo = args.getOrElse(1) { "x11" }
    val hwdec = args.getOrElse(2) { "no" }
    var exitCode = 0

    fun mark(s: String) = println("MPV_SMOKE: $s")

    mark("creating frame")
    val frame = Frame("GomePC mpv smoke")
    frame.isUndecorated = true
    frame.layout = BorderLayout()
    val canvas = Canvas()
    frame.add(canvas, BorderLayout.CENTER)
    frame.setSize(640, 360)
    frame.setLocation(0, 0)
    frame.isVisible = true

    // 等待 native peer 创建
    val t0 = System.currentTimeMillis()
    while (!frame.isDisplayable && System.currentTimeMillis() - t0 < 5000) {
        Thread.sleep(100)
    }
    if (!frame.isDisplayable) {
        mark("FAIL frame not displayable")
        System.exit(5)
    }

    val wid = try {
        X11Util.windowId(frame)
    } catch (e: Throwable) {
        mark("FAIL getWindowID: ${e.message}")
        System.exit(6)
        -1L
    }
    mark("wid=$wid vo=$vo hwdec=$hwdec")

    val fileLoaded = CountDownLatch(1)
    val gotError = AtomicReference<String?>(null)
    val endFile = AtomicBoolean(false)

    val player = MpvPlayer()
    player.listener = object : MpvPlayer.Listener {
        override fun onFileLoaded() {
            mark("FILE_LOADED")
            fileLoaded.countDown()
        }
        override fun onEndFile() {
            mark("END_FILE")
            endFile.set(true)
        }
        override fun onError(msg: String) {
            mark("ERROR $msg")
            gotError.set(msg)
        }
        override fun onTimePos(sec: Double, duration: Double) { /* 太频繁，不打印 */ }
        override fun onPause(paused: Boolean) {
            mark("PAUSE=$paused")
        }
    }

    val initErr = player.init(wid, vo = vo, hwdec = hwdec)
    if (initErr != null) {
        mark("FAIL init: $initErr")
        player.destroy()
        frame.dispose()
        System.exit(2)
    }
    mark("init ok")

    val playErr = player.play(video)
    if (playErr != null) {
        mark("FAIL play: $playErr")
        exitCode = 3
    } else {
        mark("play command sent")
        if (!fileLoaded.await(15, TimeUnit.SECONDS)) {
            mark("FAIL file_loaded timeout")
            exitCode = 4
        } else {
            // 播放中采样 time-pos
            repeat(8) { i ->
                Thread.sleep(1000)
                val tp = player.getPropertyDouble("time-pos")
                val du = player.getPropertyDouble("duration")
                mark("TPOS t=${tp?.let { "%.2f".format(it) }} dur=${du?.let { "%.2f".format(it) }}")
            }
            // 暂停/seek/恢复
            player.setPaused(true)
            Thread.sleep(600)
            mark("paused_check=${player.isPaused}")
            player.seek(1.0)
            player.setPaused(false)
            Thread.sleep(1500)
            val tp2 = player.getPropertyDouble("time-pos")
            mark("after_seek t=${tp2?.let { "%.2f".format(it) }} paused=${player.isPaused}")
            val v = player.getPropertyString("video-codec")
            mark("video_codec=$v")
            if (gotError.get() == null) {
                mark("DONE")
            } else {
                mark("DONE_WITH_NOTES err=${gotError.get()}")
            }
        }
    }

    player.destroy()
    frame.dispose()
    mark("exit=$exitCode")
    System.exit(exitCode)
}
