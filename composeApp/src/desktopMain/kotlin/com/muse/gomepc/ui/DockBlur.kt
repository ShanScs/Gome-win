package com.muse.gomepc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Window
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel

/**
 * Dock 真模糊（PC 版）：用 Window.paint() 把窗口内容渲染到 BufferedImage，
 * 裁出 dock 区域做高斯模糊。M玻璃标准要求真实模糊，不能拿透明度冒充。
 *
 * 不用 Robot 抓屏（之前抓到黑色导致 dock 发黑）。
 */
class DockBlurState {
    /** 模糊后的 backdrop，由调用方定时更新 */
    var blurred: ImageBitmap? by mutableStateOf(null)

    /**
     * 抓取窗口内指定区域并模糊。
     * @param window 应用窗口
     * @param x 区域左上角的窗口内 X 坐标（像素）
     * @param y 区域左上角的窗口内 Y 坐标（像素）
     * @param width 区域宽度（像素）
     * @param height 区域高度（像素）
     */
    fun captureAndBlur(window: Window?, x: Int, y: Int, width: Int, height: Int) {
        if (window == null) return
        if (width <= 0 || height <= 0) return
        if (x < 0 || y < 0) return
        try {
            // 把整个窗口渲染到 BufferedImage
            val full = BufferedImage(
                window.width.coerceAtLeast(1),
                window.height.coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB
            )
            val g = full.createGraphics()
            try {
                window.paint(g)
            } finally {
                g.dispose()
            }
            // 裁出 dock 区域
            val cx = x.coerceIn(0, full.width - 1)
            val cy = y.coerceIn(0, full.height - 1)
            val cw = width.coerceAtMost(full.width - cx).coerceAtLeast(1)
            val ch = height.coerceAtMost(full.height - cy).coerceAtLeast(1)
            val cropped = full.getSubimage(cx, cy, cw, ch)
            blurred = blurBuffered(cropped)
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
