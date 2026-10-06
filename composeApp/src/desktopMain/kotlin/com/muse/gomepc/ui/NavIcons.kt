package com.muse.gomepc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 侧边栏图标：简单几何线条图标（扁平风，与 Gome 播放器图标风格一致）。
 */
@Composable
fun NavIcon(kind: NavKind, tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 24.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size
        val stroke = Stroke(width = s.width * 0.09f)
        when (kind) {
            NavKind.HOME -> {
                // 房子：屋顶 + 方身
                val roof = Path().apply {
                    moveTo(s.width * 0.1f, s.height * 0.52f)
                    lineTo(s.width * 0.5f, s.height * 0.14f)
                    lineTo(s.width * 0.9f, s.height * 0.52f)
                }
                drawPath(roof, tint, style = stroke)
                drawRoundRect(
                    tint,
                    topLeft = Offset(s.width * 0.24f, s.height * 0.48f),
                    size = androidx.compose.ui.geometry.Size(s.width * 0.52f, s.height * 0.4f),
                    cornerRadius = CornerRadius(s.width * 0.05f),
                    style = stroke
                )
            }
            NavKind.GRID -> {
                // 宫格：四个圆角方块
                val gap = s.width * 0.08f
                val cell = (s.width - gap * 3) / 2
                val r = cell * 0.25f
                val xs = listOf(gap, gap * 2 + cell)
                for (x in xs) for (y in xs) {
                    drawRoundRect(
                        tint,
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                        cornerRadius = CornerRadius(r),
                        style = stroke
                    )
                }
            }
            NavKind.SEARCH -> {
                // 放大镜：圆 + 手柄
                drawCircle(
                    tint,
                    radius = s.width * 0.3f,
                    center = Offset(s.width * 0.42f, s.height * 0.42f),
                    style = stroke
                )
                drawLine(
                    tint,
                    start = Offset(s.width * 0.64f, s.height * 0.64f),
                    end = Offset(s.width * 0.88f, s.height * 0.88f),
                    strokeWidth = s.width * 0.09f
                )
            }
            NavKind.SETTINGS -> {
                // 设置：三个滑杆
                val rows = listOf(0.25f, 0.5f, 0.75f)
                rows.forEachIndexed { i, fy ->
                    val y = s.height * fy
                    drawLine(
                        tint,
                        start = Offset(s.width * 0.12f, y),
                        end = Offset(s.width * 0.88f, y),
                        strokeWidth = s.width * 0.07f
                    )
                    val kx = s.width * (0.3f + 0.25f * ((i + 1) % 3))
                    drawCircle(tint, radius = s.width * 0.11f, center = Offset(kx, y))
                    drawCircle(Color.White, radius = s.width * 0.055f, center = Offset(kx, y))
                }
            }
        }
    }
}

enum class NavKind { HOME, GRID, SEARCH, SETTINGS }
