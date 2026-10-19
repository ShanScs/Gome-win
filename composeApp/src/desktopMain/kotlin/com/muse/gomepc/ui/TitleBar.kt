package com.muse.gomepc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.gomepc.emby.Prefs
import kotlin.math.abs
import androidx.compose.ui.text.style.TextOverflow

/**
 * 自定义透明标题栏（无边框窗口用）。
 *
 * 设计（用户 2026-10-11 确认效果图）：
 * - 标题栏透明，悬浮在内容上，内容延伸到窗口边框
 * - 左侧：一个椭圆毛玻璃背板，内含 3 个图标，标题栏内垂直居中
 * - 中间：可选居中标题（如媒体库名）
 * - 右侧：一个椭圆毛玻璃背板，内含最小化/最大化/关闭，垂直居中
 * - 拖动标题栏空白处移动窗口；双击切换最大化
 */
sealed interface TitleLeftIcon {
    data class App(val onClick: () -> Unit) : TitleLeftIcon
    data class Server(val onClick: () -> Unit) : TitleLeftIcon
    data class Favorites(val onClick: () -> Unit) : TitleLeftIcon
    data class Back(val onClick: () -> Unit) : TitleLeftIcon
    data class Sort(val onClick: () -> Unit) : TitleLeftIcon
}

data class TitleBarConfig(
    /** 左侧背板图标（3 个） */
    val left: List<TitleLeftIcon>,
    /** 中间居中标题（媒体库名），为空不显示 */
    val centerTitle: String? = null
)

/** 标题栏拖动：按住空白处移动窗口；双击切换最大化。最大化时不拖动，但仍响应双击还原。 */
fun Modifier.titleBarDrag(
    window: java.awt.Window,
    enabled: Boolean,
    onDoubleClick: () -> Unit,
    isMaximized: Boolean = false
): Modifier = pointerInput(window, enabled, isMaximized) {
    var lastDownMs = 0L
    awaitEachGesture {
        val down = awaitFirstDown() // 只响应未被子组件消费的按下（图标不触发拖动）
        val now = System.currentTimeMillis()
        val isDouble = now - lastDownMs < 400
        lastDownMs = now
        if (!enabled) return@awaitEachGesture
        // 用 AWT 屏幕像素坐标，避免 Compose 与 AWT 缩放不一致导致窗口飞掉
        val mouseInfo = try { java.awt.MouseInfo.getPointerInfo() } catch (_: Exception) { null }
        val startMouse = mouseInfo?.location
        val startWin = try { window.locationOnScreen } catch (_: Exception) { null }
        if (startMouse == null || startWin == null) return@awaitEachGesture
        var moved = false
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull()
            if (ch == null || !ch.pressed) break
            ch.consume()
            if (isMaximized) continue // 最大化时只判断双击，不拖动窗口
            try {
                val curMouse = java.awt.MouseInfo.getPointerInfo()?.location ?: break
                val dx = curMouse.x - startMouse.x
                val dy = curMouse.y - startMouse.y
                if (!moved && (abs(dx) + abs(dy) > 3)) moved = true
                if (moved) {
                    val nx = startWin.x + dx
                    val ny = startWin.y + dy
                    javax.swing.SwingUtilities.invokeLater {
                        try { window.setLocation(nx, ny) } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { break }
        }
        if (!moved && isDouble) onDoubleClick()
    }
}

@Composable
fun CustomTitleBar(
    window: java.awt.Window?,
    config: TitleBarConfig,
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // 拖动层只铺空白区：放在最底层，图标背板在其上层先命中并消费点击，
        // 点图标不会触发拖动，点空白处才拖动/双击。
        if (window != null) {
            Box(
                Modifier.fillMaxSize()
                    .titleBarDrag(window, true, onToggleMaximize, isMaximized)
            )
        }
        // 左侧：椭圆毛玻璃背板 + 3 图标（垂直居中）
        MGlassBox(
            modifier = Modifier.align(Alignment.CenterStart),
            corner = 20.dp,
            tint = Color(0xFFBFBFBF)
        ) {
            Row(
                modifier = Modifier.height(40.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                config.left.forEach { icon ->
                    TitleBarIconButton(icon)
                }
            }
        }
        // 中间：居中标题（媒体库名）：宽度约束 + 省略（之前无宽度约束，长标题会把左右按钮顶出）
        config.centerTitle?.let { title ->
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A),
                modifier = Modifier.align(Alignment.Center)
                    .widthIn(max = 420.dp)
                    .padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 右侧：椭圆毛玻璃背板 + 最小化/最大化/关闭（垂直居中）
        MGlassBox(
            modifier = Modifier.align(Alignment.CenterEnd),
            corner = 20.dp,
            tint = Color(0xFFBFBFBF)
        ) {
            Row(
                modifier = Modifier.height(40.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ic = Color(0xFF1A1A1A)
                WindowButton(onClick = onMinimize, desc = "最小化") { s ->
                    drawLine(ic, Offset(s.width * 0.3f, s.height / 2f), Offset(s.width * 0.7f, s.height / 2f), strokeWidth = 2.5f)
                }
                WindowButton(onClick = onToggleMaximize, desc = if (isMaximized) "还原" else "最大化") { s ->
                    if (isMaximized) {
                        // 还原：两个叠方块
                        drawRect(ic, Offset(s.width * 0.36f, s.height * 0.28f), androidx.compose.ui.geometry.Size(s.width * 0.36f, s.height * 0.36f), style = Stroke(2.2f))
                        drawRect(Color(0xFFBFBFBF), Offset(s.width * 0.36f, s.height * 0.28f), androidx.compose.ui.geometry.Size(s.width * 0.36f, s.height * 0.36f), style = Stroke(4f))
                        drawRect(ic, Offset(s.width * 0.28f, s.height * 0.36f), androidx.compose.ui.geometry.Size(s.width * 0.36f, s.height * 0.36f), style = Stroke(2.2f))
                    } else {
                        drawRect(ic, Offset(s.width * 0.3f, s.height * 0.3f), androidx.compose.ui.geometry.Size(s.width * 0.4f, s.height * 0.4f), style = Stroke(2.2f))
                    }
                }
                WindowButton(onClick = onClose, desc = "关闭") { s ->
                    val m = 0.32f
                    drawLine(ic, Offset(s.width * m, s.height * m), Offset(s.width * (1 - m), s.height * (1 - m)), strokeWidth = 2.5f)
                    drawLine(ic, Offset(s.width * (1 - m), s.height * m), Offset(s.width * m, s.height * (1 - m)), strokeWidth = 2.5f)
                }
            }
        }
    }
}

@Composable
private fun TitleBarIconButton(icon: TitleLeftIcon) {
    val interaction = remember { MutableInteractionSource() }
    val mod = Modifier
        .size(40.dp)
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                when (icon) {
                    is TitleLeftIcon.App -> icon.onClick()
                    is TitleLeftIcon.Server -> icon.onClick()
                    is TitleLeftIcon.Favorites -> icon.onClick()
                    is TitleLeftIcon.Back -> icon.onClick()
                    is TitleLeftIcon.Sort -> icon.onClick()
                }
            }
        )
    Box(mod, contentAlignment = Alignment.Center) {
        when (icon) {
            is TitleLeftIcon.App -> AppGlyph()
            is TitleLeftIcon.Server -> ServerGlyph()
            // 收藏图标用 Canvas 手绘红心路径，不再用 ♥ 字符（emoji 与手绘图标混用）
            is TitleLeftIcon.Favorites -> Box(
                Modifier.size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                HeartGlyph()
            }
            is TitleLeftIcon.Back -> Box(
                Modifier.size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            }
            is TitleLeftIcon.Sort -> SortGlyph()
        }
    }
}

/** 手绘红心（收藏图标）：与 Screens.HeartIcon 同一路径，不用 ♥ 字符 */
@Composable
private fun HeartGlyph() {
    val path = remember {
        PathParser().parsePathString(
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        ).toPath()
    }
    Canvas(Modifier.size(18.dp)) {
        val sc = size.minDimension / 24f
        val dx = (size.width - 24f * sc) / 2f
        val dy = (size.height - 24f * sc) / 2f
        withTransform({ translate(dx, dy); scale(sc, sc) }) {
            drawPath(path, Color(0xFFF44336))
        }
    }
}

/** APP图标：对齐安卓——黑圆角方块+天蓝几何G/C环+薄荷播放三角 */
@Composable
private fun AppGlyph() {
    Canvas(
        Modifier.size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        // 天蓝几何 G/C 环
        val ringColor = Color(0xFF4FC3F7)  // 天蓝
        val ringRadius = w * 0.28f
        val strokeWidth = w * 0.09f
        // 画圆环（留缺口形成G形）
        drawArc(
            color = ringColor,
            startAngle = 40f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(cx - ringRadius, cy - ringRadius),
            size = androidx.compose.ui.geometry.Size(ringRadius * 2, ringRadius * 2),
            style = Stroke(width = strokeWidth)
        )
        // 薄荷播放三角
        val mintColor = Color(0xFF7FFFD4)  // 薄荷
        val triSize = w * 0.14f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx - triSize * 0.4f, cy - triSize * 0.7f)
            lineTo(cx + triSize * 0.8f, cy)
            lineTo(cx - triSize * 0.4f, cy + triSize * 0.7f)
            close()
        }
        drawPath(path, mintColor)
    }
}

/** 服务器图标：显示真实服务器图标（图标库自动匹配），加载失败时显示首字 */
@Composable
private fun ServerGlyph() {
    val serverName = Prefs.serverName
    val serverKey = try {
        Prefs.currentServerKey()
    } catch (_: Exception) { null }
    Box(
        Modifier.size(26.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        ServerIcon(
            serverName = serverName,
            serverKey = serverKey,
            modifier = Modifier.size(26.dp),
            contentScale = ContentScale.Crop,
            fallback = {
                Text(
                    serverName.take(1).uppercase().ifEmpty { "影" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GomeTheme.Accent
                )
            }
        )
    }
}

/** 返回箭头（Material arrow-back） */
@Composable
private fun BackGlyph() {
    val path = remember {
        PathParser().parsePathString("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z").toPath()
    }
    Canvas(modifier = Modifier.size(24.dp)) {
        val sc = size.minDimension / 24f
        val dx = (size.width - 24f * sc) / 2f
        val dy = (size.height - 24f * sc) / 2f
        withTransform({ translate(dx, dy); scale(sc, sc) }) {
            drawPath(path, Color(0xFF1A1A1A))
        }
    }
}

/** 排序图标：三横线 + 上下箭头 */
@Composable
private fun SortGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val c = Color(0xFF1A1A1A)
        val w = size.width
        // 三条横线（上长下短）
        drawLine(c, Offset(w * 0.2f, size.height * 0.3f), Offset(w * 0.62f, size.height * 0.3f), strokeWidth = 2.4f)
        drawLine(c, Offset(w * 0.2f, size.height * 0.5f), Offset(w * 0.5f, size.height * 0.5f), strokeWidth = 2.4f)
        drawLine(c, Offset(w * 0.2f, size.height * 0.7f), Offset(w * 0.38f, size.height * 0.7f), strokeWidth = 2.4f)
        // 右侧上下箭头
        val ax = w * 0.76f
        drawLine(c, Offset(ax, size.height * 0.24f), Offset(ax, size.height * 0.76f), strokeWidth = 2.4f)
        drawLine(c, Offset(ax, size.height * 0.24f), Offset(ax - 4f, size.height * 0.36f), strokeWidth = 2.4f)
        drawLine(c, Offset(ax, size.height * 0.24f), Offset(ax + 4f, size.height * 0.36f), strokeWidth = 2.4f)
        drawLine(c, Offset(ax, size.height * 0.76f), Offset(ax - 4f, size.height * 0.64f), strokeWidth = 2.4f)
        drawLine(c, Offset(ax, size.height * 0.76f), Offset(ax + 4f, size.height * 0.64f), strokeWidth = 2.4f)
    }
}

@Composable
private fun WindowButton(
    onClick: () -> Unit,
    desc: String,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Size) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) { draw(size) }
    }
}
