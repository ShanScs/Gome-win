package com.muse.gomepc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部 Dock 栏（对齐 Android activity_host.xml 毛玻璃版）。
 *
 * 参数：
 * - M玻璃：MGlassBox，圆角 30dp（Android 90px@3x），blur 24
 * - 内边距：左右 12dp，上下 6dp
 * - 选中指示器：#DEDEDE 灰色椭圆 68×56dp，圆角 24dp，位于选中 tab 下方
 * - Tab：72×60dp，图标 30dp，文字 11sp；顺序：资源库 / 主页 / 搜索 / 设置
 * - 图标文字：选中 #000000，未选中 #8A8F9E
 */
private val DockTabWidth: Dp = 72.dp
private val DockTabHeight: Dp = 60.dp
private val DockTabGap: Dp = 4.dp
private val DockIndicatorW: Dp = 68.dp
private val DockIndicatorH: Dp = 56.dp

private data class DockTab(val label: String, val kind: NavKind, val screen: Screen)

private fun dockTabs(): List<DockTab> = listOf(
    DockTab("资源库", NavKind.GRID, Screen.Grid),
    DockTab("主页", NavKind.HOME, Screen.Home),
    DockTab("搜索", NavKind.SEARCH, Screen.Search),
    DockTab("设置", NavKind.SETTINGS, Screen.Settings),
)

@Composable
fun DockBar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = dockTabs()
    // 选中 tab 索引（Detail/Player 等非常驻页保持上次选中的 tab 高亮）
    val selectedIdx = tabs.indexOfFirst { it.screen::class == current::class }.let {
        if (it >= 0) it else 1 // 默认主页
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter
    ) {
        MGlassBox(
            modifier = Modifier.padding(bottom = 12.dp),
            corner = 30.dp
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                // 选中指示器（灰色 pill，位于 tab 下层）
                Box(
                    Modifier
                        .offset(
                            x = (DockTabWidth + DockTabGap) * selectedIdx + (DockTabWidth - DockIndicatorW) / 2,
                            y = (DockTabHeight - DockIndicatorH) / 2
                        )
                        .size(DockIndicatorW, DockIndicatorH)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFDEDEDE))
                )
                // Tabs
                Row {
                    tabs.forEachIndexed { idx, tab ->
                        val selected = idx == selectedIdx
                        if (idx > 0) Spacer(Modifier.width(DockTabGap))
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .size(DockTabWidth, DockTabHeight)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(tab.screen) }
                                .padding(vertical = 6.dp)
                        ) {
                            NavIcon(
                                tab.kind,
                                tint = if (selected) Color(0xFF000000) else Color(0xFF8A8F9E),
                                iconSize = 30.dp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                tab.label,
                                fontSize = 11.sp,
                                color = if (selected) Color(0xFF000000) else Color(0xFF8A8F9E)
                            )
                        }
                    }
                }
            }
        }
    }
}
