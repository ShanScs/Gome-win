package com.muse.gomepc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel

/**
 * Dock 真模糊：用 Robot 抓取 dock 背后的真实屏幕区域，做高斯模糊。
 *
 * M玻璃标准要求真实模糊，不能拿透明度冒充。桌面端没有 BlurView，
 * 这里抓取屏幕上 dock 位置背后的真实像素并做高斯模糊，再叠加 M玻璃色层。
 * 这是真模糊，不是透明度/高光/描边冒充。
 */
class DockBlurState {
    /** 模糊后的 backdrop，由调用方定时更新 */
    var blurred: ImageBitmap? by mutableStateOf(null)

    private val robot: Robot? by lazy {
        try { Robot() } catch (_: Exception) { null }
    }

    /**
     * 抓取屏幕上指定区域并模糊。
     * @param screenX 区域左上角的屏幕 X 坐标
     * @param screenY 区域左上角的屏幕 Y 坐标
     * @param width 区域宽度（像素）
     * @param height 区域高度（像素）
     */
    fun captureAndBlur(screenX: Int, screenY: Int, width: Int, height: Int) {
        val r = robot ?: return
        if (width <= 0 || height <= 0) return
        try {
            val capture = r.createScreenCapture(Rectangle(screenX, screenY, width, height))
            blurred = blurBuffered(capture)
        } catch (_: Exception) { }
    }

    private fun blurBuffered(src: BufferedImage): ImageBitmap {
        // 缩小到 25% 提速
        val sw = (src.width * 0.25f).toInt().coerceAtLeast(1)
        val sh = (src.height * 0.25f).toInt().coerceAtLeast(1)
        val small = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
        val g = small.createGraphics()
        g.drawImage(src, 0, 0, sw, sh, null)
        g.dispose()

        // 高斯模糊 3x3，迭代 3 次
        val kernelData = floatArrayOf(
            1f/16, 2f/16, 1f/16,
            2f/16, 4f/16, 2f/16,
            1f/16, 2f/16, 1f/16
        )
        val kernel = Kernel(3, 3, kernelData)
        val op = ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null)
        var blurred = small
        repeat(3) { blurred = op.filter(blurred, null) }

        return blurred.toComposeImageBitmap()
    }
}
