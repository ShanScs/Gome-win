package com.muse.gomepc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel

/**
 * Dock 真模糊（PC 版）：用 Robot 抓取 dock 背后的屏幕区域，做高斯模糊。
 * M玻璃标准要求真实模糊，不能拿透明度冒充。
 *
 * 坐标：window.locationOnScreen + dock 在窗口内的 bounds = 屏幕坐标。
 */
class DockBlurState {
    /** 模糊后的 backdrop，由调用方定时更新 */
    var blurred: ImageBitmap? by mutableStateOf(null)

    private val robot: Robot? by lazy {
        try { Robot() } catch (_: Exception) { null }
    }

    /**
     * 抓取 dock 背后的屏幕区域并模糊。
     * @param window 应用窗口（用于换算屏幕坐标）
     * @param x dock 在窗口内的 X（像素）
     * @param y dock 在窗口内的 Y（像素）
     * @param width dock 宽度（像素）
     * @param height dock 高度（像素）
     */
    fun captureAndBlur(window: Window?, x: Int, y: Int, width: Int, height: Int) {
        val r = robot ?: return
        if (window == null) return
        if (width <= 0 || height <= 0) return
        try {
            val winPos = window.locationOnScreen ?: return
            val sx = winPos.x + x
            val sy = winPos.y + y
            if (sx < 0 || sy < 0) return
            val capture = r.createScreenCapture(Rectangle(sx, sy, width, height))
            // 检查是否全黑（抓失败），全黑则不更新
            if (isAllBlack(capture)) return
            blurred = blurBuffered(capture)
        } catch (_: Exception) { }
    }

    /** 检测是否全黑（抓图失败时 Robot 可能返回黑色） */
    private fun isAllBlack(img: BufferedImage): Boolean {
        val w = img.width
        val h = img.height
        // 采样 20 个点
        var nonBlack = 0
        var i = 0
        while (i < 20) {
            val px = img.getRGB((i * 97 % w), (i * 57 % h))
            // 忽略 alpha，看 RGB
            if ((px and 0x00FFFFFF) != 0) nonBlack++
            i++
        }
        return nonBlack == 0
    }

    private fun blurBuffered(src: BufferedImage): ImageBitmap {
        // 缩小到 25% 提速
        val sw = (src.width * 0.25f).toInt().coerceAtLeast(1)
        val sh = (src.height * 0.25f).toInt().coerceAtLeast(1)
        val small = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
        val g = small.createGraphics()
        g.drawImage(src, 0, 0, sw, sh, null)
        g.dispose()

        // 高斯模糊 3x3，迭代 3 次（约等于 radius 24 的糊感）
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
