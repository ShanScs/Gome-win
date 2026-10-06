package com.muse.gomepc.danmaku

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * Step 5 测试：桌面弹幕覆盖层。
 *
 * 用法（需 Xvfb）：DISPLAY=:99 ./gradlew :composeApp:runDanmakuTest
 *
 * 流程：打开 1280x720 黑底窗口 → 注入测试弹幕 → 跑 6 秒 →
 * Robot 截图到 /tmp/danmaku_test.png → 分析白色像素（文字清晰度/位置）→ 退出码。
 *
 * 输出 DANMAKU_TEST: ... 标记行；退出码 0=通过。
 */
fun main() {
    val engineRef = AtomicReference<DanmakuEngine>()
    val windowRef = AtomicReference<java.awt.Window>()
    var exitCode = 0

    fun mark(s: String) = println("DANMAKU_TEST: $s")

    val testTexts = listOf(
        "前方高能！！！",
        "哈哈哈哈哈哈哈",
        "这波啊，这波是教科书级别",
        "233333333333",
        "火钳刘明",
        "下次一定",
        "测试弹幕 ABC 123",
        "爷爷你关注的UP主更新了",
        "弹幕护体",
        "awsl"
    )

    // 后台线程：6 秒后截图分析并退出
    thread(isDaemon = true) {
        try {
            Thread.sleep(6000)
            val win = windowRef.get()
            val engine = engineRef.get()
            if (win == null || engine == null) {
                mark("FAIL window/engine not ready")
                System.exit(5)
            }
            mark("items_on_screen=${engine.items.size} pool=${engine.pool.size} rows=${engine.rows}")

            val robot = Robot()
            val loc = win.locationOnScreen
            val rect = Rectangle(loc.x, loc.y, win.width, win.height)
            val img = robot.createScreenCapture(rect)
            val outFile = File("/tmp/danmaku_test.png")
            ImageIO.write(img, "png", outFile)
            mark("screenshot=${outFile.absolutePath} ${img.width}x${img.height}")

            // 分析：统计白色像素（文字）
            var whiteCount = 0
            var topHalfWhite = 0
            // 采样步长 2，加速
            for (y in 0 until img.height step 2) {
                for (x in 0 until img.width step 2) {
                    val rgb = img.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    if (r > 200 && g > 200 && b > 200) {
                        whiteCount++
                        if (y < img.height / 2) topHalfWhite++
                    }
                }
            }
            // 还原为全像素估计
            whiteCount *= 4
            topHalfWhite *= 4
            mark("white_pixels=$whiteCount top_half_white=$topHalfWhite")

            // 判定：有足够白色像素（文字清晰）且主要集中在上半屏（position=1 半屏）
            if (whiteCount < 500) {
                mark("FAIL too few white pixels, text may not be rendering")
                exitCode = 2
            } else if (topHalfWhite < (whiteCount * 0.6).toInt()) {
                mark("FAIL text not concentrated in top half (position=1)")
                exitCode = 3
            } else {
                mark("PASS text clear and positioned in top half")
            }

            // 阴影检查：找白色像素附近的深灰像素（阴影 blur 产物）
            var shadowHint = 0
            outer@ for (y in 2 until img.height - 2 step 4) {
                for (x in 2 until img.width - 2 step 4) {
                    val rgb = img.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    // 深灰（40~120）且周围有白色 → 可能是阴影/抗锯齿边缘
                    if (r in 40..120 && g in 40..120 && b in 40..120) {
                        var nearWhite = false
                        for (dy in -3..3 step 3) {
                            for (dx in -3..3 step 3) {
                                val n = img.getRGB(x + dx, y + dy)
                                val nr = (n shr 16) and 0xFF
                                if (nr > 200) { nearWhite = true; break }
                            }
                            if (nearWhite) break
                        }
                        if (nearWhite) {
                            shadowHint++
                            if (shadowHint > 50) break@outer
                        }
                    }
                }
            }
            mark("shadow_hint_pixels=$shadowHint (edge/antialias near white text)")
            mark("exit=$exitCode")
            System.exit(exitCode)
        } catch (e: Exception) {
            mark("FAIL exception: ${e.message}")
            e.printStackTrace()
            System.exit(9)
        }
    }

    application {
        val engine = remember {
            DanmakuEngine().apply {
                applyStyle(fontSp = 28, strokePx = 0f, opacityPct = 100)
                setPosition(1) // 半屏
                setSpeedFactor(1f)
                setDanmakuList(testTexts)
                setEnabled(true)
            }
        }
        engineRef.set(engine)

        val state = rememberWindowState(
            size = DpSize(1280.dp, 720.dp),
            position = WindowPosition(0.dp, 0.dp)
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "GomePC Danmaku Test",
            undecorated = true
        ) {
            windowRef.set(window)
            // 黑底模拟播放器画面
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A))
            ) {
                DanmakuOverlay(
                    engine = engine,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
