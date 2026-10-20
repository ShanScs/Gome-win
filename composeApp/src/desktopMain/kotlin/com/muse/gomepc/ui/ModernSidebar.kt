package com.muse.gomepc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 现代主题侧边导航（仅 GomeTheme.modern 时由 Main 挂载；原版主题不经过此文件）。
 * 只读主题令牌与当前 Screen，通过回调请求 Main 切换页面，自身不持有导航状态。
 */
@Composable
fun ModernSidebar(
    current: Screen,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
    onServer: () -> Unit
) {
    Row(
        Modifier.width(204.dp).fillMaxHeight().background(GomeTheme.SidebarBg)
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 顶部留白避开悬浮标题栏（标题栏高 64dp，内容延伸到窗口边框）
            Spacer(Modifier.height(76.dp))
            Text(
                "Gome",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = GomeTheme.TextPrimary,
                modifier = Modifier.padding(start = 26.dp, bottom = 20.dp)
            )
            ModernNavItem("首页", selected = current is Screen.Home, onClick = onHome)
            ModernNavItem("搜索", selected = current is Screen.Search, onClick = onSearch)
            ModernNavItem("收藏", selected = current is Screen.Favorites, onClick = onFavorites)
            ModernNavItem("设置", selected = current is Screen.Settings, onClick = onSettings)
            Spacer(Modifier.weight(1f))
            // 底部「服务器」：与首页标题栏 Server 图标同行为（弹服务器窗）
            ModernNavItem("服务器", selected = false, onClick = onServer)
            Spacer(Modifier.height(16.dp))
        }
        // 右侧 1dp 分隔线（白色 8%）
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
    }
}

@Composable
private fun ModernNavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) GomeTheme.Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else GomeTheme.TextPrimary
        )
    }
}
