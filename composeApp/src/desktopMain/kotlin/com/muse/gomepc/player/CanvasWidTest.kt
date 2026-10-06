package com.muse.gomepc.player

import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Canvas wid 验证：Frame 里放 Canvas，取 Canvas 的 native id 给 mpv。
 * 用法：DISPLAY=:99 ./gradlew :composeApp:runCanvasWidTest
 */
fun main() {
    fun mark(s: String) = println("CANVAS_WID: $s")

    val frame = Frame("canvas wid test")
    frame.isUndecorated = true
    frame.layout = BorderLayout()
    val canvas = Canvas()
    frame.add(canvas, BorderLayout.CENTER)
    frame.setSize(640, 360)
    frame.setLocation(0, 0)
    frame.isVisible = true

    val t0 = System.currentTimeMillis()
    while ((!canvas.isDisplayable || canvas.width == 0) && System.currentTimeMillis() - t0 < 5000) {
        Thread.sleep(100)
    }
    mark("canvas displayable=${canvas.isDisplayable} size=${canvas.width}x${canvas.height}")

    val widCanvas = try {
        X11Util.windowId(canvas)
    } catch (e: Throwable) {
        mark("FAIL canvas wid: ${e.message}")
        System.exit(6)
        -1L
    }
    val widFrame = try {
        X11Util.windowId(frame)
    } catch (e: Throwable) {
        mark("FAIL frame wid: ${e.message}")
        System.exit(7)
        -1L
    }
    mark("widCanvas=$widCanvas widFrame=$widFrame same=${widCanvas == widFrame}")

    val loaded = CountDownLatch(1)
    val player = MpvPlayer()
    player.listener = object : MpvPlayer.Listener {
        override fun onFileLoaded() { mark("FILE_LOADED"); loaded.countDown() }
        override fun onEndFile() { mark("END_FILE") }
        override fun onError(msg: String) { mark("ERROR $msg") }
        override fun onTimePos(sec: Double, duration: Double) {}
        override fun onPause(paused: Boolean) {}
    }

    // 用 Canvas 的 wid，loop 方便截图
    val initErr = player.init(widCanvas, vo = "x11", hwdec = "no")
    if (initErr != null) {
        mark("FAIL init: $initErr")
        System.exit(2)
    }
    // 设置循环
    try {
        val lib = LibMpv.INSTANCE
        val handleField = MpvPlayer::class.java.getDeclaredField("handle")
        handleField.isAccessible = true
        val handle = handleField.get(player) as com.sun.jna.Pointer
        lib.mpv_set_property_string(handle, "loop-file", "inf")
        mark("loop set")
    } catch (e: Throwable) {
        mark("loop set failed: ${e.message}")
    }

    player.play("/tmp/dvtest/dv_p81.mp4")
    if (!loaded.await(15, TimeUnit.SECONDS)) {
        mark("FAIL timeout")
        System.exit(4)
    }
    // 播 4 秒，截图验证
    Thread.sleep(4000)
    try {
        val robot = java.awt.Robot()
        val loc = canvas.locationOnScreen
        val img = robot.createScreenCapture(java.awt.Rectangle(loc.x, loc.y, canvas.width, canvas.height))
        val out = java.io.File("/home/hatch/workspace/gome-pc/canvas_wid_test.png")
        javax.imageio.ImageIO.write(img, "png", out)
        // 像素分析：是否有非黑内容
        var nonBlack = 0
        val total = img.width * img.height
        for (y in 0 until img.height step 4) {
            for (x in 0 until img.width step 4) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (r + g + b > 60) nonBlack++
            }
        }
        val pct = nonBlack * 100.0 / (total / 16)
        mark("screenshot saved, nonBlack=${"%.1f".format(pct)}%")
        mark(if (pct > 10) "PASS video rendering into Canvas" else "FAIL canvas is black")
    } catch (e: Throwable) {
        mark("FAIL shot: ${e.message}")
    }
    player.destroy()
    frame.dispose()
    System.exit(0)
}
