package com.muse.gomepc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 真 backdrop 模糊 dock 背景（PC 自研）。
 *
 * 原理：把背景内容渲染两遍。一遍正常显示，一遍用 Modifier.blur(24.dp)
 * 做高斯模糊后，通过 Alignment.BottomCenter 对齐到底部 dock 位置，
 * 裁成圆角。模糊层与背后内容自动对齐，无需抓屏。
 *
 * 注意：模糊层共享主内容的滚动状态，滚动时自动实时同步（VOD 教训）。
 * dock 有 12dp 底边距，模糊内容需下移 12dp 补偿对齐。
 *
 * @param backgroundContent 背景内容（会被调用两次，Compose 会复用状态）
 * @param screenW 屏幕宽度（用于全屏渲染模糊层）
 * @param screenH 屏幕高度（用于全屏渲染模糊层）
 */
@Composable
fun TrueBlurDockBackground(
    backgroundContent: @Composable () -> Unit,
    screenW: androidx.compose.ui.unit.Dp,
    screenH: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 30.dp
) {
    val shape = RoundedCornerShape(corner)

    // 模糊背景层：全屏渲染 + blur + 底部对齐 + 圆角裁剪
    // 注意：不要加 offset——BottomCenter 对齐后采样已与真实内容像素对齐，
    // 加 offset 会导致上下分层、空白区串色（2026-10-08 教训）
    // pointerInput 吞掉所有指针事件：模糊副本不接收滚轮/点击，避免与主内容冲突
    Box(
        modifier = modifier
            .clip(shape)
    ) {
        Box(
            modifier = Modifier
                .size(screenW, screenH)
                .align(Alignment.BottomCenter)
                .blur(24.dp)
                .pointerInput(Unit) {
                    // 消费所有指针事件，阻止模糊副本截获滚轮
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach {
                                it.consume()
                            }
                        }
                    }
                }
        ) {
            backgroundContent()
        }

        // M玻璃底色 #55FFFFFF
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MGlass.Tint)
        )

        // 顶部高光 #99FFFFFF → #00FFFFFF
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to MGlass.HighlightTop,
                        0.35f to MGlass.HighlightBottom,
                        1.0f to MGlass.HighlightBottom
                    )
                )
        )
    }

    // 白色描边 #AAFFFFFF（在裁剪外绘制，避免被切掉）
    Box(
        modifier = modifier
            .border(1.dp, MGlass.Stroke, shape)
    )
}
