package com.muse.gomepc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.gomepc.emby.Prefs
import com.muse.gomepc.emby.ServerEntry

/** 导航目标 */
sealed interface Screen {
    data object Home : Screen
    data object Grid : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Detail(val itemId: String) : Screen
    data class Player(
        val itemId: String,
        val itemName: String,
        val episodeId: String,
        val episodeIndex: Int
    ) : Screen
}

/** App 主题色 */
object GomeTheme {
    val Bg = Color(0xFFF5F6F8)
    val CardBg = Color.White
    val TextPrimary = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF8A8A8A)
    val Accent = Color(0xFF2F6FED)
    val SidebarBg = Color(0xFFECEDEF)
}

/** 加载中占位 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = GomeTheme.Accent)
    }
}

/** 错误提示（带重试） */
@Composable
fun ErrorBox(msg: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                msg.take(200),
                fontSize = 13.sp,
                color = GomeTheme.TextSecondary,
                modifier = Modifier.padding(horizontal = 32.dp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("重试", color = GomeTheme.Accent, fontSize = 14.sp)
            }
        }
    }
}


/** 海报：真实模式用 EmbyImage，演示模式用渐变占位 */
@Composable
fun PosterImage(
    item: UiMediaItem,
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp = 14.dp
) {
    if (item.imageUrl != null) {
        EmbyImage(
            url = item.imageUrl,
            contentDescription = item.name,
            modifier = modifier.clip(RoundedCornerShape(corner)),
            fallback = { PosterGradient(item = item, corner = corner, modifier = Modifier.fillMaxSize()) }
        )
    } else {
        PosterGradient(item = item, corner = corner, modifier = modifier)
    }
}

@Composable
private fun PosterGradient(
    item: UiMediaItem,
    corner: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val (c1, c2) = posterColors(item.hue)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.verticalGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            item.name.take(1),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f)
        )
        if (item.rating != null) {
            Text(
                item.rating,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** 继续观看卡片（对齐 Android item_resume.xml：200×112dp，圆角 14dp，徽章+进度条） */
@Composable
fun ResumeCard(
    item: UiMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .width(200.dp)
                .height(112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE0E0E0))
        ) {
            if (item.imageUrl != null) {
                EmbyImage(
                    url = item.imageUrl,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PosterGradient(item = item, corner = 14.dp, modifier = Modifier.fillMaxSize())
            }
            // 剩余时间徽章：左下角
            if (item.badge != null) {
                Text(
                    item.badge,
                    fontSize = 11.sp,
                    color = Color(0xFF2F6FED),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFDCE9FB))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            // 播放进度条：底部 3dp
            if (item.progress != null && item.progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth(item.progress)
                        .height(3.dp)
                        .background(GomeTheme.Accent)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            fontSize = 14.sp,
            color = GomeTheme.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(200.dp)
        )
        if (item.subtitle.isNotEmpty()) {
            Text(
                item.subtitle,
                fontSize = 12.sp,
                color = GomeTheme.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(200.dp)
            )
        }
    }
}

/** 首页宫格卡片（对齐 Android item_poster_h.xml：120×170dp，圆角 14dp） */
@Composable
fun ItemCard(
    item: UiMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .width(120.dp)
                .height(170.dp)
                .clip(RoundedCornerShape(14.dp))
        ) {
            PosterImage(
                item,
                corner = 14.dp,
                modifier = Modifier.fillMaxSize()
            )
            // 继续观看进度条
            if (item.progress != null && item.progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth(item.progress)
                        .height(3.dp)
                        .background(GomeTheme.Accent)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            fontSize = 13.sp,
            color = GomeTheme.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(120.dp)
        )
        Text(
            item.year,
            fontSize = 11.sp,
            color = GomeTheme.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(120.dp)
        )
    }
}

/** 首页：继续观看 + 各媒体库横排（参考 MainFragment 横屏版） */
@Composable
fun HomeScreen(onItemClick: (UiMediaItem) -> Unit) {
    var libs by remember { mutableStateOf<List<UiLibrary>?>(null) }
    var libItems by remember { mutableStateOf<Map<String, List<UiMediaItem>>>(emptyMap()) }
    var resume by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var latest by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        try {
            error = null
            libs = null; resume = null; libItems = emptyMap(); latest = null
            val l = Repo.libraries()
            libs = l
            resume = Repo.resumeItems()
            latest = try { Repo.latestItems(8) } catch (_: Exception) { emptyList() }
            val map = mutableMapOf<String, List<UiMediaItem>>()
            for (lib in l) {
                try {
                    map[lib.id] = Repo.items(lib.id, 12)
                } catch (_: Exception) { }
            }
            libItems = map
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    when {
        error != null -> ErrorBox(error!!, onRetry = { reloadKey++ })
        libs == null || resume == null -> LoadingBox()
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().background(GomeTheme.Bg),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("首页", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
                    if (Repo.demoMode) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "演示模式",
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF9E9E9E))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            // 顶部轮播（最新入库）
            if (!latest.isNullOrEmpty()) {
                item {
                    BannerCarousel(items = latest!!, onItemClick = onItemClick)
                }
            }
            if (resume!!.isNotEmpty()) {
                item {
                    SectionHeader("继续观看", "更多")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(resume!!) { item ->
                            ResumeCard(item, onClick = { onItemClick(item) })
                        }
                    }
                }
            }
            libs!!.forEach { lib ->
                val items = libItems[lib.id] ?: emptyList()
                if (items.isNotEmpty()) {
                    item {
                        SectionHeader(lib.name, "更多")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items) { item ->
                                ItemCard(item, onClick = { onItemClick(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(action, fontSize = 13.sp, color = GomeTheme.TextSecondary)
    }
}

/**
 * 顶部轮播（最新入库）：横向滚动大卡片，背景图+标题+类型|年份。
 * 对齐 Android item_banner.xml。
 */
@Composable
private fun BannerCarousel(
    items: List<UiMediaItem>,
    onItemClick: (UiMediaItem) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items) { item ->
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onItemClick(item) }
            ) {
                // 背景图（用海报，裁剪填充）
                EmbyImage(
                    url = item.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // 底部渐变
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xB3000000)),
                                startY = 0.5f
                            )
                        )
                )
                // 标题+元信息
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    Text(
                        item.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    val meta = listOfNotNull(
                        item.libName.takeIf { it.isNotBlank() },
                        item.year.takeIf { it.isNotBlank() }
                    ).joinToString(" | ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            fontSize = 12.sp,
                            color = Color(0xFFDDDDDD),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** 媒体库卡片（对齐 Android item_library_card.xml：190×105dp，14dp 圆角，2×2 拼图） */
@Composable
fun LibraryCard(
    lib: UiLibrary,
    posters: List<UiMediaItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .width(190.dp)
                .height(105.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFCFD8DC))
        ) {
            // 2×2 拼图
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LibraryCollageCell(posters.getOrNull(0), Modifier.weight(1f).fillMaxSize())
                    LibraryCollageCell(posters.getOrNull(1), Modifier.weight(1f).fillMaxSize())
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LibraryCollageCell(posters.getOrNull(2), Modifier.weight(1f).fillMaxSize())
                    LibraryCollageCell(posters.getOrNull(3), Modifier.weight(1f).fillMaxSize())
                }
            }
            // 库名压在拼图上：白色 20sp bold，左侧
            Text(
                lib.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LibraryCollageCell(item: UiMediaItem?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFFB0BEC5))) {
        if (item?.imageUrl != null) {
            EmbyImage(url = item.imageUrl, contentDescription = item.name, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 宫格：媒体库卡片，点击进入该库 */
@Composable
fun GridScreen(onItemClick: (UiMediaItem) -> Unit) {
    var libs by remember { mutableStateOf<List<UiLibrary>?>(null) }
    var collages by remember { mutableStateOf<Map<String, List<UiMediaItem>>>(emptyMap()) }
    var selectedLib by remember { mutableStateOf<UiLibrary?>(null) }
    var libItems by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        try {
            error = null; libs = null
            val l = Repo.libraries()
            libs = l
            val map = mutableMapOf<String, List<UiMediaItem>>()
            for (lib in l) {
                try { map[lib.id] = Repo.items(lib.id, 4) } catch (_: Exception) { }
            }
            collages = map
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    LaunchedEffect(selectedLib) {
        val lib = selectedLib ?: return@LaunchedEffect
        libItems = null
        try { libItems = Repo.items(lib.id, 60) }
        catch (_: Exception) { libItems = emptyList() }
    }

    val sel = selectedLib
    when {
        error != null -> ErrorBox(error!!, onRetry = { reloadKey++ })
        libs == null -> LoadingBox()
        sel != null -> {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "‹ 返回",
                        fontSize = 15.sp,
                        color = GomeTheme.TextPrimary,
                        modifier = Modifier.clickable { selectedLib = null; libItems = null }
                            .padding(12.dp)
                    )
                    Text(
                        sel.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = GomeTheme.TextPrimary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (libItems == null) {
                    LoadingBox(Modifier.weight(1f))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(libItems!!) { item ->
                            ItemCard(item, onClick = { onItemClick(item) })
                        }
                    }
                }
            }
        }
        else -> ServerCardsGrid(
            modifier = Modifier.fillMaxSize().background(Color.White),
            onServerSelected = {
                // 切换服务器后回到首页
                reloadKey++
            }
        )
    }
}

/**
 * 资源库页：服务器卡片（对齐 Android）。
 * 双列卡片，宽高比 1.84:1，24dp 圆角，无描边，1dp 柔和阴影；
 * 背景为以右上图标为中心的霜化径向渐变（取图标色50%强度）→白色；
 * 所有服务器头像为圆形；另有短剧卡片；"+" 可添加服务器。
 */
@Composable
private fun ServerCardsGrid(
    modifier: Modifier = Modifier,
    onServerSelected: () -> Unit
) {
    var servers by remember { mutableStateOf(Prefs.getServers()) }
    var showAddDialog by remember { mutableStateOf(false) }

    // 刷新服务器列表
    fun refresh() { servers = Prefs.getServers() }

    Column(modifier) {
        Text(
            "资源库",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = GomeTheme.TextPrimary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(servers, key = { server: ServerEntry -> server.key() }) { server ->
                ServerCard(
                    server = server,
                    onClick = {
                        // 切换为当前服务器
                        Prefs.serverName = server.name
                        // TODO: 实际切换 Repo 的 server 上下文
                        onServerSelected()
                    },
                    onLongClick = {
                        // 长按删除（简化版，Android 有图标选择器）
                    }
                )
            }
            // 短剧卡片
            item {
                ShortDramaCard(onClick = { /* TODO: 本地短剧 */ })
            }
            // 添加卡片
            item {
                AddServerCard(onClick = { showAddDialog = true })
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdded = { refresh(); showAddDialog = false }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: ServerEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 卡片宽高比 1.84:1，24dp 圆角
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.84f)
            .clip(RoundedCornerShape(24.dp))
            .background(
                // 霜化径向渐变：右上图标色50% → 白色（简化版用浅蓝灰）
                Brush.radialGradient(
                    colors = listOf(Color(0xFFE8EEF5), Color.White),
                    center = androidx.compose.ui.geometry.Offset(0.85f, 0.15f)
                )
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp)
    ) {
        // 右上圆形图标
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0xFF5B8DEF)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                server.name.take(1).uppercase(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        // 左下服务器名
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                server.name.ifBlank { server.host },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = GomeTheme.TextPrimary
            )
            Text(
                "${server.protocol}://${server.host}:${server.port}",
                fontSize = 12.sp,
                color = Color(0xFF8A8F9E)
            )
        }
    }
}

@Composable
private fun ShortDramaCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.84f)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFF5E8E8), Color.White),
                    center = androidx.compose.ui.geometry.Offset(0.85f, 0.15f)
                )
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("短剧", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
    }
}

@Composable
private fun AddServerCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.84f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFF2F4F8))
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("+", fontSize = 32.sp, color = Color(0xFF8A8F9E))
    }
}

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdded: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8096") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useHttps by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField("名称", name) { name = it }
                ServerField("地址", host) { host = it }
                ServerField("端口", port) { port = it }
                ServerField("用户名", username) { username = it }
                ServerField("密码", password) { password = it }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (host.isNotBlank() && username.isNotBlank()) {
                    Prefs.upsertServer(
                        ServerEntry(
                            name = name.ifBlank { host },
                            protocol = if (useHttps) "https" else "http",
                            host = host, port = port, path = "",
                            username = username, password = password
                        )
                    )
                    onAdded()
                }
            }) { Text("保存") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ServerField(label: String, value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 搜索（对齐 Android activity_search.xml：白底，药丸+圆角搜索框） */
@Composable
fun SearchScreen(onItemClick: (UiMediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchKey by remember { mutableStateOf(0) }

    LaunchedEffect(searchKey) {
        if (searchKey == 0) return@LaunchedEffect
        val q = query
        if (q.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        try {
            results = Repo.search(q)
        } catch (_: Exception) {
            results = emptyList()
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // 搜索框：#F2F4F8 底，28dp 圆角，48dp 高
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFFF2F4F8))
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavIcon(NavKind.SEARCH, tint = Color(0xFF1A1A1A).copy(alpha = 0.5f), iconSize = 24.dp)
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { searchKey++ }
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = Color(0xFF1A1A1A)
                ),
                modifier = Modifier.weight(1f).height(48.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("搜索电影、剧集、演员", fontSize = 16.sp, color = Color(0xFFAAAAAA))
                        }
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            searching -> LoadingBox(Modifier.weight(1f))
            results == null -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("输入关键词搜索", fontSize = 13.sp, color = GomeTheme.TextSecondary)
            }
            else -> {
                Text(
                    "找到 ${results!!.size} 个结果",
                    fontSize = 13.sp,
                    color = GomeTheme.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results!!) { item ->
                        ItemCard(item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

/** 设置（对齐 Android activity_settings.xml：iOS 分组卡片风格） */
@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {},
    onToggleDemo: (Boolean) -> Unit = {}
) {
    var danmakuOn by remember { mutableStateOf(true) }
    var hwdecOn by remember { mutableStateOf(true) }
    LazyColumn(
        Modifier.fillMaxSize().background(Color(0xFFF2F1F6)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp)
    ) {
        item {
            Text(
                "设置",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        // 播放设置组
        item { SettingGroupTitle("播放设置") }
        item {
            SettingCard {
                SettingRowIcon(
                    label = "弹幕",
                    iconBg = Color(0xFF2F7CF6),
                    iconText = "幕",
                    checked = danmakuOn,
                    onChange = { danmakuOn = it },
                    showDivider = true
                )
                SettingRowIcon(
                    label = "硬件解码",
                    iconBg = Color(0xFF5856D6),
                    iconText = "硬",
                    checked = hwdecOn,
                    onChange = { hwdecOn = it },
                    showDivider = false
                )
            }
        }
        // 服务器组
        item { SettingGroupTitle("服务器") }
        item {
            SettingCard {
                val serverText = if (Repo.demoMode) "演示模式（Mock 数据）"
                else com.muse.gomepc.emby.Prefs.serverName.ifBlank { com.muse.gomepc.emby.Prefs.baseUrl() }
                SettingRowIcon(
                    label = serverText,
                    iconBg = Color(0xFF34C759),
                    iconText = "服",
                    showDivider = true,
                    trailing = {}
                )
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .clickable {
                            if (Repo.demoMode) onToggleDemo(false)
                            else { com.muse.gomepc.emby.Prefs.clearLogin(); onLogout() }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (Repo.demoMode) "退出演示模式" else "退出登录",
                        fontSize = 16.sp,
                        color = Color(0xFFE53935),
                        modifier = Modifier.padding(start = 41.dp)
                    )
                }
            }
        }
        // 关于组
        item { SettingGroupTitle("关于") }
        item {
            SettingCard {
                SettingRowIcon(
                    label = "Gome PC 1.0.0（杜比视界兼容版）",
                    iconBg = Color(0xFF8E8E93),
                    iconText = "ⓘ",
                    showDivider = true,
                    trailing = {}
                )
                SettingRowIcon(
                    label = "播放核心：libmpv + libplacebo",
                    iconBg = Color(0xFF8E8E93),
                    iconText = "▶",
                    showDivider = false,
                    trailing = {}
                )
            }
        }
    }
}

@Composable
private fun SettingGroupTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = Color(0xFF8E8E93),
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp, top = 8.dp)
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
        content = { content() }
    )
}

/** 设置行：29dp 彩色图标 + 16sp 文字 + 右侧控件/值 + › */
@Composable
private fun SettingRowIcon(
    label: String,
    iconBg: Color,
    iconText: String,
    checked: Boolean? = null,
    onChange: ((Boolean) -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp)
                .clickable(enabled = onChange != null) { onChange?.invoke(!(checked ?: false)) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(29.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Text(iconText, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                fontSize = 16.sp,
                color = Color.Black,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when {
                trailing != null -> trailing()
                checked != null -> {
                    androidx.compose.material3.Switch(
                        checked = checked,
                        onCheckedChange = { onChange?.invoke(it) }
                    )
                }
            }
            if (onChange == null && trailing == null) {
                Text("›", fontSize = 22.sp, color = Color(0xFFC7C7CC))
            }
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth()
                    .padding(start = 53.dp)
                    .height(1.dp)
                    .background(Color(0xFFEFEFF4))
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = GomeTheme.TextPrimary)
        Spacer(Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 详情页（参考 DetailActivity 横屏版：海报头 + 标题 + 简介 + 选集） */
@Composable
fun DetailScreen(itemId: String, onBack: () -> Unit, onPlay: (UiMediaItem, UiEpisode) -> Unit) {
    var item by remember(itemId) { mutableStateOf<UiMediaItem?>(null) }
    var epData by remember(itemId) { mutableStateOf<EpisodeData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var seasonIdx by remember { mutableStateOf(0) }
    var seasonEps by remember { mutableStateOf<List<UiEpisode>?>(null) }
    var seasonLoading by remember { mutableStateOf(false) }

    LaunchedEffect(itemId, reloadKey) {
        try {
            error = null; item = null; epData = null
            val d = Repo.itemDetail(itemId)
            item = d
            epData = Repo.episodes(itemId)
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    // 季切换由下面的 LaunchedEffect(seasonIdx) 处理

    when {
        error != null -> ErrorBox(error!!, onRetry = { reloadKey++ })
        item == null || epData == null -> LoadingBox()
        else -> {
            val it = item!!
            val eps = seasonEps ?: epData!!.episodes
            val seasons = epData!!.seasons
            // 季切换的协程
            LaunchedEffect(seasonIdx) {
                val data = epData ?: return@LaunchedEffect
                if (seasons.isEmpty()) return@LaunchedEffect
                // 首次（seasonEps==null 且 seasonIdx==0）用已加载的数据
                if (seasonEps == null && seasonIdx == 0) return@LaunchedEffect
                val s = seasons.getOrNull(seasonIdx) ?: return@LaunchedEffect
                seasonLoading = true
                try {
                    seasonEps = Repo.seasonEpisodes(itemId, s.id)
                } catch (_: Exception) {
                    seasonEps = emptyList()
                }
                seasonLoading = false
            }

            LazyColumn(
                Modifier.fillMaxSize().background(GomeTheme.Bg),
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                // 海报头
                item {
                    Box(Modifier.fillMaxWidth().height(480.dp)) {
                        if (it.imageUrl != null) {
                            EmbyImage(
                                url = com.muse.gomepc.emby.YambyClient.imageUrl(it.id, "Backdrop", 1280),
                                contentDescription = it.name,
                                modifier = Modifier.fillMaxSize(),
                                fallback = {
                                    val (c1, c2) = posterColors(it.hue)
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, c2))))
                                }
                            )
                            // 底部渐隐，保证标题可读
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color(0x99000000)),
                                        startY = 0.4f
                                    )
                                )
                            )
                        } else {
                            val (c1, c2) = posterColors(it.hue)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, c2))))
                        }
                        androidx.compose.material3.TextButton(
                            onClick = onBack,
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        ) { Text("‹ 返回", color = Color.White, fontSize = 15.sp) }
                        Column(
                            Modifier.align(Alignment.BottomCenter)
                                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                it.name,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOfNotNull(
                                    it.year.takeIf { s -> s.isNotEmpty() },
                                    it.libName.takeIf { s -> s.isNotEmpty() },
                                    it.rating?.let { r -> "★$r" }
                                ).joinToString(" · "),
                                fontSize = 13.sp,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                // 简介 + 播放
                item {
                    Column(Modifier.padding(20.dp)) {
                        if (it.overview.isNotEmpty()) {
                            Text(
                                it.overview,
                                fontSize = 14.sp,
                                color = GomeTheme.TextSecondary,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { expanded = !expanded }
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        // 季选择
                        if (seasons.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(seasons.size) { idx ->
                                    val sel = idx == seasonIdx
                                    Text(
                                        seasons[idx].name,
                                        fontSize = 13.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) Color.White else GomeTheme.TextPrimary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (sel) GomeTheme.Accent else Color(0xFFE0E0E0))
                                            .clickable { seasonIdx = idx }
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        androidx.compose.material3.Button(
                            onClick = {
                                val first = eps.firstOrNull() ?: return@Button
                                onPlay(it, first)
                            },
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("▶ 播放", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                }
                // 选集
                item {
                    Text(
                        "选集（${eps.size}）",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = GomeTheme.TextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                item {
                    if (seasonLoading) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GomeTheme.Accent)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(110.dp),
                            modifier = Modifier.padding(horizontal = 20.dp).height(320.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(eps) { ep ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White)
                                        .clickable { onPlay(it, ep) }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (ep.index > 0) "第${ep.index}集" else ep.name,
                                        fontSize = 13.sp,
                                        color = GomeTheme.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
