package com.muse.gomepc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
            NavKind.FAVORITE -> {
                // 收藏：心形（描边）
                val heart = Path().apply {
                    val w = s.width; val h = s.height
                    moveTo(w * 0.5f, h * 0.88f)
                    cubicTo(w * 0.1f, h * 0.55f, w * 0.05f, h * 0.3f, w * 0.25f, h * 0.18f)
                    cubicTo(w * 0.38f, h * 0.1f, w * 0.48f, h * 0.16f, w * 0.5f, h * 0.26f)
                    cubicTo(w * 0.52f, h * 0.16f, w * 0.62f, h * 0.1f, w * 0.75f, h * 0.18f)
                    cubicTo(w * 0.95f, h * 0.3f, w * 0.9f, h * 0.55f, w * 0.5f, h * 0.88f)
                    close()
                }
                drawPath(heart, tint, style = stroke)
            }
        }
    }
}

enum class NavKind { HOME, GRID, SEARCH, FAVORITE, SETTINGS }

/**
 * 垃圾桶图标（对齐 Android ic_trash_gray：#8A8A8A 描边，2dp 线宽，圆角端点）。
 * pathData: M5,7 h14 M9.5,7 V5.5 a1,1 0 0 1 1,-1 h3 a1,1 0 0 1 1,1 V7 M7,7 l1,13 a1,1 0 0 0 1,1 h6 a1,1 0 0 0 1,-1 l1,-13
 */
@Composable
fun TrashIcon(
    tint: Color = Color(0xFF8A8A8A),
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.minDimension
        val strokeW = s * 2f / 24f
        val stroke = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        // 顶横线 M5,7 h14
        drawLine(tint, Offset(s * 5f / 24f, s * 7f / 24f), Offset(s * 19f / 24f, s * 7f / 24f), strokeWidth = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        // 提手 M9.5,7 V5.5 a1,1 0 0 1 1,-1 h3 a1,1 0 0 1 1,1 V7
        val handle = Path().apply {
            moveTo(s * 9.5f / 24f, s * 7f / 24f)
            lineTo(s * 9.5f / 24f, s * 5.5f / 24f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(s * 9.5f / 24f, s * 3.5f / 24f, s * 11.5f / 24f, s * 5.5f / 24f),
                startAngleDegrees = 180f, sweepAngleDegrees = 90f, forceMoveTo = false
            )
            lineTo(s * 12.5f / 24f, s * 4.5f / 24f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(s * 12.5f / 24f, s * 3.5f / 24f, s * 14.5f / 24f, s * 5.5f / 24f),
                startAngleDegrees = 270f, sweepAngleDegrees = 90f, forceMoveTo = false
            )
            lineTo(s * 14.5f / 24f, s * 7f / 24f)
        }
        drawPath(handle, tint, style = stroke)
        // 桶身 M7,7 l1,13 a1,1 0 0 0 1,1 h6 a1,1 0 0 0 1,-1 l1,-13
        val body = Path().apply {
            moveTo(s * 7f / 24f, s * 7f / 24f)
            lineTo(s * 8f / 24f, s * 20f / 24f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(s * 8f / 24f, s * 19f / 24f, s * 10f / 24f, s * 21f / 24f),
                startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false
            )
            lineTo(s * 14f / 24f, s * 21f / 24f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(s * 14f / 24f, s * 19f / 24f, s * 16f / 24f, s * 21f / 24f),
                startAngleDegrees = 90f, sweepAngleDegrees = -90f, forceMoveTo = false
            )
            lineTo(s * 17f / 24f, s * 7f / 24f)
        }
        drawPath(body, tint, style = stroke)
    }
}

/** 关闭 X 图标（对齐 Android ic_close） */
@Composable
fun CloseIcon(
    tint: Color = Color(0xFF8A8A8A),
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.minDimension
        val strokeW = s * 2f / 24f
        drawLine(
            tint,
            Offset(s * 6f / 24f, s * 6f / 24f),
            Offset(s * 18f / 24f, s * 18f / 24f),
            strokeWidth = strokeW,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            tint,
            Offset(s * 18f / 24f, s * 6f / 24f),
            Offset(s * 6f / 24f, s * 18f / 24f),
            strokeWidth = strokeW,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

/** 应用锁图标（对齐 Android ic_pl_lock：viewport 40，stroke 2.4，圆头圆角） */
@Composable
fun LockIcon(
    tint: Color = Color(0xFF2E7CF6),
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.minDimension
        val k = s / 40f
        val strokeW = 2.4f * k
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 外圈 M20 3a17 17 0 1 0 0.001 0z
        drawCircle(tint, radius = 17f * k, center = Offset(20f * k, 20f * k), style = stroke)
        // 锁体圆角矩形 (14.5,19)-(25.5,29)，圆角 2
        drawRoundRect(
            tint,
            topLeft = Offset(14.5f * k, 19f * k),
            size = Size(11f * k, 10f * k),
            cornerRadius = CornerRadius(2f * k),
            style = stroke
        )
        // 锁梁 M17 19v-2.5a3 3 0 0 1 6 0V19
        val shackle = Path().apply {
            moveTo(17f * k, 19f * k)
            lineTo(17f * k, 16.5f * k)
            arcTo(
                rect = Rect(17f * k, 13.5f * k, 23f * k, 19.5f * k),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            lineTo(23f * k, 19f * k)
        }
        drawPath(shackle, tint, style = stroke)
    }
}

/** 更多（竖三点）图标（对齐 Android ic_more_dots：24 viewport，填充圆点 r=2） */
@Composable
fun MoreDotsIcon(
    tint: Color = Color(0xFF2E7CF6),
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.minDimension
        val k = s / 24f
        for (cy in listOf(6f, 12f, 18f)) {
            drawCircle(tint, radius = 2f * k, center = Offset(12f * k, cy * k))
        }
    }
}

/** 放大镜图标（对齐 Android ic_search：填充圆环 + 手柄，fill #5F6368） */
@Composable
fun SearchIcon(
    tint: Color = Color(0xFF5F6368),
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.minDimension
        val k = s / 24f
        // 圆环：EvenOdd，外 r=4.75 / 内 r=2.75，中心 (9.5,9.5)
        val ring = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(center = Offset(9.5f * k, 9.5f * k), radius = 4.75f * k))
            addOval(Rect(center = Offset(9.5f * k, 9.5f * k), radius = 2.75f * k))
        }
        drawPath(ring, tint)
        // 手柄：(13.0,13.0)->(19.5,19.5)，宽 2.4，圆头
        drawLine(
            tint,
            Offset(13.0f * k, 13.0f * k),
            Offset(19.5f * k, 19.5f * k),
            strokeWidth = 2.4f * k,
            cap = StrokeCap.Round
        )
    }
}

/** 胶片图标（对齐 Android ic_film：24 viewport，stroke 1.8，圆头） */
@Composable
fun FilmIcon(
    tint: Color = Color(0xFF8A7B6C),
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.minDimension
        val k = s / 24f
        val strokeW = 1.8f * k
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round)
        // 外框 (2,5)-(22,19)，圆角 2
        drawRoundRect(
            tint,
            topLeft = Offset(2f * k, 5f * k),
            size = Size(20f * k, 14f * k),
            cornerRadius = CornerRadius(2f * k),
            style = stroke
        )
        // 竖线 x=7 / x=17，y 5->19
        drawLine(tint, Offset(7f * k, 5f * k), Offset(7f * k, 19f * k), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(tint, Offset(17f * k, 5f * k), Offset(17f * k, 19f * k), strokeWidth = strokeW, cap = StrokeCap.Round)
        // 横齿
        for ((x1, x2, y) in listOf(
            Triple(2f, 7f, 9f), Triple(2f, 7f, 15f),
            Triple(17f, 22f, 9f), Triple(17f, 22f, 15f)
        )) {
            drawLine(tint, Offset(x1 * k, y * k), Offset(x2 * k, y * k), strokeWidth = strokeW, cap = StrokeCap.Round)
        }
    }
}

/** 电视图标（对齐 Android ic_tv：24 viewport，stroke 1.8，圆头） */
@Composable
fun TvIcon(
    tint: Color = Color(0xFF8A7B6C),
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val s = size.minDimension
        val k = s / 24f
        val strokeW = 1.8f * k
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round)
        // 外框 (2,6)-(22,18)，圆角 2
        drawRoundRect(
            tint,
            topLeft = Offset(2f * k, 6f * k),
            size = Size(20f * k, 12f * k),
            cornerRadius = CornerRadius(2f * k),
            style = stroke
        )
        // 底座 M9,21 h6
        drawLine(tint, Offset(9f * k, 21f * k), Offset(15f * k, 21f * k), strokeWidth = strokeW, cap = StrokeCap.Round)
    }
}
