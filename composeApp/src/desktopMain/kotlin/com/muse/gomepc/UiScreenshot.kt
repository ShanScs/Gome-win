package com.muse.gomepc

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * UI 截图验证（Xvfb 下运行）。
 * 用法：DISPLAY=:99 ./gradlew :composeApp:runUiShot -Dui.screen=home|detail|player
 * 截图输出：~/workspace/gome-pc/ui_shot_<screen>.png，完成后自动退出。
 */
fun main() {
    val screen = System.getProperty("ui.screen", "home")
    val outName = "ui_shot_$screen.png"
    // player 需要等 mpv 起播
    val waitMs = if (screen == "player") 9000L else 3500L

    application {
        val state = rememberWindowState(
            width = 1280.dp,
            height = 800.dp,
            position = WindowPosition(0.dp, 0.dp)
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Gome PC"
        ) {
            MaterialTheme {
                GomeApp(
                    onFullscreen = {
                        state.placement = if (state.placement == WindowPlacement.Fullscreen)
                            WindowPlacement.Floating else WindowPlacement.Fullscreen
                    },
                    owner = window
                )
            }
        }

        // 截图线程：等待渲染 → Robot 全屏截图 → 保存 → 退出
        thread(isDaemon = true, name = "ui-shot") {
            try {
                Thread.sleep(waitMs)
                val robot = Robot()
                // 等一帧，确保合成完成
                Thread.sleep(500)
                val size = Toolkit.getDefaultToolkit().screenSize
                val img = robot.createScreenCapture(Rectangle(size))
                val out = java.io.File("/home/hatch/workspace/gome-pc/" + outName)
                ImageIO.write(img, "png", out)
                println("UI_SHOT: saved ${out.absolutePath} ${img.width}x${img.height}")
            } catch (e: Throwable) {
                println("UI_SHOT: FAIL ${e.message}")
                e.printStackTrace()
            } finally {
                // 给 IO 一点时间再退
                Thread.sleep(500)
                kotlin.system.exitProcess(0)
            }
        }
    }
}
