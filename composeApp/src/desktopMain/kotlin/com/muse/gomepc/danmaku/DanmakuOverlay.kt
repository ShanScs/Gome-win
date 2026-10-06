package com.muse.gomepc.danmaku

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * 弹幕覆盖层（Compose Desktop）。
 *
 * 与 Android DanmakuView 的关键差异：
 * 1. 文本绘制：Android 用 Paint.drawText(x, baselineY)；Compose 用
 *    DrawScope.drawText(textMeasurer, text, topLeft, style)，topLeft 是左上角。
 *    用 TextLayoutResult.firstBaseline 把 Android 的基线 Y 换算成 top：
 *    top = baselineY - firstBaseline。
 * 2. 文本测量：Android paint.measureText()；Compose textMeasurer.measure(text, style).size.width。
 *    宽度缓存在 Danmaku.textWidth，避免每帧重复测量。
 * 3. 阴影：Android setShadowLayer(6, 2, 2, BLACK) →
 *    Compose Shadow(color=Black, offset=Offset(2,2), blurRadius=6)。
 * 4. 描边：Android FILL_AND_STROKE；Compose 无直接等价，用"先 Fill 再 Stroke"画两遍模拟。
 * 5. 字号：Android fontSp * scaledDensity；Compose 用 sp 单位，density 自动换算。
 * 6. 动画：Android postDelayed(33ms)；Compose LaunchedEffect + withFrameNanos（跟随显示器 VSync）。
 * 7. 透明度：Android paint.alpha(26~255)；Compose Color.White.copy(alpha=...)。
 */
@Composable
fun DanmakuOverlay(
    engine: DanmakuEngine,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 每帧自增，触发 Canvas 重绘
    var frameTick by remember { mutableIntStateOf(0) }

    // 文本样式（随样式设置变化重建）
    val textStyle = remember(engine.fontSp, engine.strokePx, engine.opacityPct) {
        TextStyle(
            color = Color.White.copy(alpha = engine.alpha),
            fontSize = engine.fontSp.sp,
            shadow = if (engine.strokePx > 0) {
                null
            } else {
                // 与 Android setShadowLayer(6f, 2f, 2f, BLACK) 对应
                Shadow(
                    color = Color.Black,
                    offset = Offset(2f, 2f),
                    blurRadius = 6f
                )
            }
        )
    }
    val strokeStyle = remember(engine.fontSp, engine.strokePx, engine.opacityPct) {
        if (engine.strokePx > 0) {
            textStyle.copy(
                drawStyle = Stroke(width = engine.strokePx),
                shadow = null
            )
        } else null
    }

    // 动画循环：VSync 驱动步进
    LaunchedEffect(engine) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L && engine.enabled && engine.viewWidth > 0) {
                    val dt = ((nanos - lastNanos) / 1_000_000_000.0).toFloat()
                        .coerceIn(0f, 0.1f) // 钳制，避免切后台后跳变
                    engine.step(dt) { text ->
                        textMeasurer.measure(text, textStyle).size.width.toFloat()
                    }
                    frameTick++
                }
                lastNanos = nanos
            }
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { size ->
            engine.viewWidth = size.width.toFloat()
            engine.viewHeight = size.height.toFloat()

            val fontSizePx = with(density) { engine.fontSp.sp.toPx() }
            engine.recalcRows(fontSizePx)
        }
    ) {
        if (!engine.enabled) return@Canvas
        // 读取 frameTick 以建立重组依赖（Canvas lambda 捕获它）
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        val fontSizePx = with(density) { engine.fontSp.sp.toPx() }
        for (d in engine.items) {
            val baselineY = engine.rowBaseline(d.row, fontSizePx)
            // Android 基线 → Compose 左上角：top = baseline - firstBaseline
            val layout = textMeasurer.measure(d.text, textStyle)
            val top = baselineY - layout.firstBaseline
            val topLeft = Offset(d.x, top)
            if (strokeStyle != null) {
                // 模拟 Android FILL_AND_STROKE：先填色再描边
                drawText(textMeasurer, d.text, topLeft, textStyle)
                drawText(textMeasurer, d.text, topLeft, strokeStyle)
            } else {
                drawText(textMeasurer, d.text, topLeft, textStyle)
            }
        }
    }
}
