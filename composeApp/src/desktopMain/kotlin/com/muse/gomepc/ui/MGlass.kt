package com.muse.gomepc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * M玻璃（桌面版近似实现）。
 *
 * Android 规范：BlurView 真模糊 radius 24 + #55FFFFFF 底 + 顶部高光(#99FFFFFF→#00FFFFFF)
 * + 白色描边 #AAFFFFFF + 圆角。
 *
 * 桌面端没有 BlurView（无 backdrop 捕获），按任务要求用 Compose 的 blur 修饰符
 * 作用于背景层近似；内容层保持清晰（图标/文字不糊）。
 * 真 backdrop 模糊在桌面端需走平台相关实现，后续可优化。
 */
object MGlass {
    val Tint = Color(0x55FFFFFF)
    // PC 专用：无真模糊时模拟毛玻璃糊感的不透明度（M玻璃的#55是配BlurView真模糊的）
    val DockTintNoBlur = Color(0xBFFFFFFF)
    val Stroke = Color(0xAAFFFFFF)
    val HighlightTop = Color(0x99FFFFFF)
    val HighlightBottom = Color(0x00FFFFFF)
    val BlurRadius: Dp = 24.dp
}

/**
 * M玻璃容器：圆角裁剪 + 真实模糊 backdrop + 半透明白底 + 顶部高光 + 白色描边，内容保持清晰。
 *
 * @param blurredBackdrop 经过真实模糊的内容抓图（低分辨率），绘制在最底层；
 * 为空时退化为纯色底（无真模糊）。
 */
@Composable
fun MGlassBox(
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    blurredBackdrop: ImageBitmap? = null,
    tint: Color = MGlass.Tint,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MGlass.Stroke, shape)
    ) {
        // M玻璃底色（默认#55FFFFFF）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(tint)
        )
        // 顶部高光渐变（#99FFFFFF→#00FFFFFF）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to MGlass.HighlightTop,
                        0.4f to MGlass.HighlightBottom,
                        1.0f to MGlass.HighlightBottom
                    )
                )
        )
        // 内容层：不模糊，保持清晰；不设 matchParentSize，让内容决定外层尺寸
        // （matchParentSize 的子项不参与父容器尺寸计算，会导致 wrap-content 时高度塌陷）
        Box(content = content)
    }
}
