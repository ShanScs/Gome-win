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
    data object ResumeList : Screen
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
            // 集数徽章：右上角 28dp 圆形，13sp 粗体 #2F6FED
            if (item.episodeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFFDCE9FB)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        item.episodeCount.toString(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2F6FED)
                    )
                }
            }
            // 收藏红心：左上角 28dp
            if (item.isFavorite) {
                Text(
                    "♥",
                    fontSize = 20.sp,
                    color = Color(0xFFFF3B30),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(28.dp)
                )
            }
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
fun HomeScreen(
    onItemClick: (UiMediaItem) -> Unit,
    onResumeMore: () -> Unit = {}
) {
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
                // 顶栏：左服务器图标（可点切换）/ 中服务器名 / 右收藏按钮
                // 对齐 Android activity_main.xml
                Box(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 左：服务器图标 44dp 圆形
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(44.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.White)
                            .clickable { /* TODO: 服务器切换弹窗 */ },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            Prefs.serverName.take(1).uppercase().ifEmpty { "影" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2F6FED)
                        )
                    }
                    // 中：服务器名 20sp 粗体
                    Text(
                        Prefs.serverName.ifEmpty { "影音" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = GomeTheme.TextPrimary
                    )
                    // 右：收藏 33dp 毛玻璃圆 + 22dp 红心
                    MGlassBox(
                        modifier = Modifier.align(Alignment.CenterEnd).size(33.dp),
                        corner = 17.dp
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "♥",
                                fontSize = 16.sp,
                                color = Color(0xFFFF3B30)
                            )
                        }
                    }
                }
                if (Repo.demoMode) {
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
            // 顶部轮播（最新入库）
            if (!latest.isNullOrEmpty()) {
                item {
                    BannerCarousel(items = latest!!, onItemClick = onItemClick)
                }
            }
            // 媒体库横排（对齐 Android：tvLibTitle + rvLibs）
            if (libs!!.isNotEmpty()) {
                item {
                    Text(
                        "媒体库",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = GomeTheme.TextPrimary,
                        modifier = Modifier.padding(start = 16.dp, top = 18.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                        contentPadding = PaddingValues(end = 12.dp)
                    ) {
                        items(libs!!) { lib ->
                            LibraryCard(
                                lib = lib,
                                posters = (libItems[lib.id] ?: emptyList()).take(4),
                                onClick = { /* TODO: 跳媒体库详情 */ }
                            )
                        }
                    }
                }
            }
            if (resume!!.isNotEmpty()) {
                item {
                    SectionHeader("继续观看", "更多", onAction = onResumeMore)
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
private fun SectionHeader(title: String, action: String, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            action,
            fontSize = 14.sp,
            color = Color(0xFF2F6FED),
            modifier = Modifier.padding(8.dp)
                .clickable(enabled = onAction != null) { onAction?.invoke() }
        )
    }
}

/**
 * 继续观看完整列表（对齐 Android activity_resume_list.xml）
 */
@Composable
fun ResumeListScreen(
    onItemClick: (UiMediaItem) -> Unit,
    onBack: () -> Unit
) {
    var items by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        try {
            error = null; items = null
            items = Repo.resumeItems()
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                fontSize = 24.sp,
                color = GomeTheme.TextPrimary,
                modifier = Modifier.size(40.dp)
                    .clickable(onClick = onBack),
                textAlign = TextAlign.Center
            )
            Text(
                "继续观看",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GomeTheme.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(40.dp))
        }
        when {
            error != null -> ErrorBox(error!!, onRetry = { reloadKey++ }, Modifier.weight(1f))
            items == null -> LoadingBox(Modifier.weight(1f))
            items!!.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("暂无观看记录", fontSize = 14.sp, color = GomeTheme.TextSecondary)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp, bottom = 110.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items!!) { item ->
                    // 网格版继续观看卡片（复用 ResumeCard，宽度自适应）
                    ResumeCard(item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

/**
 * 顶部轮播（最新入库）：横向滚动大卡片。
 * 对齐 Android item_banner.xml：300x170dp，20dp圆角，无阴影；
 * 标题24sp粗体白字右下角，元信息11sp白字深色药丸，无渐变。
 */
@Composable
private fun BannerCarousel(
    items: List<UiMediaItem>,
    onItemClick: (UiMediaItem) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp)
    ) {
        items(items) { item ->
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(170.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE0E0E0))
                    .clickable { onItemClick(item) }
            ) {
                EmbyImage(
                    url = item.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // 右下角标题+元信息（无渐变，保持干净）
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        item.name,
                        fontSize = 24.sp,
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
                            fontSize = 11.sp,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x99000000))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
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
 * 资源库页：服务器卡片网格 + 短剧入口 + 添加。
 * 对齐 Android activity_resource.xml：28sp标题+锁/更多按钮+搜索框+双列卡片+右下角FAB。
 */
@Composable
private fun ServerCardsGrid(
    modifier: Modifier = Modifier,
    onServerSelected: () -> Unit
) {
    var servers by remember { mutableStateOf(Prefs.getServers()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun refresh() { servers = Prefs.getServers() }

    val filtered = if (searchQuery.isBlank()) servers
        else servers.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.host.contains(searchQuery, ignoreCase = true)
        }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：标题 + 右侧图标
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "资源库",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = GomeTheme.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "🔒",
                    fontSize = 20.sp,
                    modifier = Modifier.size(44.dp)
                        .clickable { /* TODO: 应用锁 */ },
                    textAlign = TextAlign.Center
                )
                Text(
                    "⋯",
                    fontSize = 20.sp,
                    color = Color(0xFF2E7CF6),
                    modifier = Modifier.size(44.dp)
                        .clickable { /* TODO: 更多菜单 */ },
                    textAlign = TextAlign.Center
                )
            }
            // 搜索框：48dp 高
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF2F4F8))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔍", fontSize = 20.sp, modifier = Modifier.size(20.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索", fontSize = 15.sp, color = Color(0xFFA0A0A0)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                if (searchQuery.isNotEmpty()) {
                    Text(
                        "✕",
                        fontSize = 16.sp,
                        color = Color(0xFFA0A0A0),
                        modifier = Modifier.clickable { searchQuery = "" }.padding(8.dp)
                    )
                }
            }
            // 卡片网格
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "点击右下角 ＋ 添加服务器",
                        fontSize = 14.sp,
                        color = Color(0xFFA0A0A0)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 8.dp, end = 8.dp, bottom = 96.dp
                    )
                ) {
                    // 第一个：短剧卡片
                    item {
                        ShortDramaCard(
                            onClick = { /* TODO: 短剧播放 */ },
                            onLongClick = { /* TODO: 短剧管理 */ }
                        )
                    }
                    // 服务器卡片
                    items(filtered, key = { server: ServerEntry -> server.key() }) { server ->
                        val stat = Prefs.getServerStats()[server.key()] ?: Prefs.ServerStat()
                        val isCurrent = Prefs.serverName == server.name
                        ServerCard(
                            server = server,
                            movies = stat.movies,
                            series = stat.series,
                            lastUsed = stat.lastUsed,
                            isCurrent = isCurrent,
                            onClick = {
                                Prefs.serverName = server.name
                                Prefs.touchServerLastUsed(server.key())
                                onServerSelected()
                            },
                            onLongClick = {
                                // TODO: 编辑服务器对话框
                            }
                        )
                    }
                }
            }
        }
        // 右下角悬浮添加按钮：60dp 圆，bottom|end，20dp/112dp 边距
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 112.dp)
                .size(60.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0xFF2F6FED))
                .clickable { showAddDialog = true },
            contentAlignment = Alignment.Center
        ) {
            Text("+", fontSize = 32.sp, color = Color.White)
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
    movies: Int,
    series: Int,
    lastUsed: Long,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 对齐 Android item_server_card.xml：24dp圆角，1dp阴影，7dp边距，10dp内边距
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(7.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp)
    ) {
        // 第一行：服务器名 + 状态点 + 圆形图标
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                server.name.ifEmpty { server.host },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = GomeTheme.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 状态点 8dp（简化：当前为绿，否则灰）
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (isCurrent) Color(0xFF34C759) else Color(0xFFCCCCCC)
                    )
            )
            // 服务器图标 32dp 圆形
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(32.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    server.name.take(1).uppercase().ifEmpty { "服" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2F6FED)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 第二行：电影数 + 剧集数
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🎬", fontSize = 14.sp, modifier = Modifier.size(14.dp))
            Text(
                if (movies >= 0) movies.toString() else "–",
                fontSize = 12.sp,
                color = Color(0xFF8A7B6C),
                modifier = Modifier.padding(start = 4.dp)
            )
            Text("📺", fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp).size(14.dp))
            Text(
                if (series >= 0) series.toString() else "–",
                fontSize = 12.sp,
                color = Color(0xFF8A7B6C),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        // 第三行：地址 + 上次使用
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text(
                server.host,
                fontSize = 11.sp,
                color = Color(0xFFA0A0A0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (lastUsed > 0) {
                Text(
                    (if (isCurrent) "当前 · " else "") + timeAgo(lastUsed),
                    fontSize = 11.sp,
                    color = Color(0xFFA0A0A0),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** 相对时间（如"3小时前"） */
private fun timeAgo(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min}分钟前"
        min < 1440 -> "${min / 60}小时前"
        else -> "${min / 1440}天前"
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShortDramaCard(
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 对齐 Android item_local_card.xml：24dp圆角，1dp阴影，7dp边距，#FFF6E5底
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(7.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFFFF6E5))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "短剧",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = GomeTheme.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            // 抖音图标 32dp 圆形
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(32.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", fontSize = 16.sp, color = Color.White)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "本地",
                fontSize = 11.sp,
                color = Color(0xFFA0A0A0),
                modifier = Modifier.weight(1f)
            )
            Text(
                "未观看",
                fontSize = 11.sp,
                color = Color(0xFFA0A0A0)
            )
        }
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
    // 搜索历史（持久化）
    var history by remember { mutableStateOf(Prefs.getSearchHistory()) }

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
            // 保存到历史
            Prefs.addSearchHistory(q)
            history = Prefs.getSearchHistory()
        } catch (_: Exception) {
            results = emptyList()
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // 服务器选择（聚合搜索）：药丸框
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "聚合搜索",
                fontSize = 15.sp,
                color = GomeTheme.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFF2F4F8))
                    .clickable { /* TODO: 服务器选择弹窗 */ }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
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
            results == null -> {
                // 搜索历史
                if (history.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "搜索历史",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GomeTheme.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "🗑",
                                fontSize = 16.sp,
                                modifier = Modifier.size(32.dp)
                                    .clickable {
                                        Prefs.clearSearchHistory()
                                        history = emptyList()
                                    },
                                textAlign = TextAlign.Center
                            )
                        }
                        // 历史标签流式布局
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            history.forEach { h ->
                                Text(
                                    h,
                                    fontSize = 13.sp,
                                    color = GomeTheme.TextPrimary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFFF2F4F8))
                                        .clickable {
                                            query = h
                                            searchKey++
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("输入关键词搜索", fontSize = 13.sp, color = GomeTheme.TextSecondary)
                    }
                }
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
    var cacheOn by remember { mutableStateOf(true) }
    var dockBlurOn by remember { mutableStateOf(true) }
    var thumbOn by remember { mutableStateOf(true) }
    var strictOn by remember { mutableStateOf(false) }
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
        // 播放设置组（对齐 Android）
        item { SettingGroupTitle("播放设置") }
        item {
            SettingCard {
                SettingRowIcon(
                    label = "默认解码方式",
                    iconBg = Color(0xFF2F7CF6),
                    iconText = "🎬",
                    value = "HW+",
                    showDivider = true,
                    onClick = { /* TODO: 解码方式选择 */ }
                )
                SettingRowIcon(
                    label = "缓存",
                    iconBg = Color(0xFF2F7CF6),
                    iconText = "💾",
                    checked = cacheOn,
                    onChange = { cacheOn = it },
                    showDivider = true
                )
                SettingRowIcon(
                    label = "缓存大小",
                    iconBg = Color(0xFF2F7CF6),
                    iconText = "📦",
                    value = "1G",
                    showDivider = false,
                    onClick = { /* TODO: 缓存大小选择 */ }
                )
            }
        }
        // 界面设置组
        item { SettingGroupTitle("界面设置") }
        item {
            SettingCard {
                SettingRowIcon(
                    label = "Dock 毛玻璃",
                    iconBg = Color(0xFF5856D6),
                    iconText = "✨",
                    checked = dockBlurOn,
                    onChange = { dockBlurOn = it },
                    showDivider = true
                )
            }
        }
        // 弹幕设置组
        item { SettingGroupTitle("弹幕设置") }
        item {
            SettingCard {
                SettingRowIcon(
                    label = "启用弹幕",
                    iconBg = Color(0xFF34C759),
                    iconText = "💬",
                    checked = danmakuOn,
                    onChange = { danmakuOn = it },
                    showDivider = true
                )
                SettingRowIcon(
                    label = "弹幕 API",
                    iconBg = Color(0xFF34C759),
                    iconText = "🌐",
                    value = "",
                    showDivider = false,
                    onClick = { /* TODO: 弹幕 API 设置 */ }
                )
            }
        }
        // 详情页组
        item { SettingGroupTitle("详情页") }
        item {
            SettingCard {
                SettingRowIcon(
                    label = "使用剧集缩略图",
                    iconBg = Color(0xFFFF9500),
                    iconText = "🖼️",
                    checked = thumbOn,
                    onChange = { thumbOn = it },
                    showDivider = true
                )
                SettingRowIcon(
                    label = "下滑严格模式",
                    iconBg = Color(0xFFFF9500),
                    iconText = "⬇",
                    checked = strictOn,
                    onChange = { strictOn = it },
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
    trailing: @Composable (() -> Unit)? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp)
                .clickable(enabled = onChange != null || onClick != null) {
                    if (onClick != null) onClick()
                    else onChange?.invoke(!(checked ?: false))
                }
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
                value != null -> {
                    Text(value, fontSize = 15.sp, color = Color(0xFF8A8A8A))
                    Spacer(Modifier.width(4.dp))
                }
            }
            if (onClick != null || (onChange == null && trailing == null && value == null)) {
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
/** 详情页功能按钮（对齐 Android：40dp 白色图标） */
@Composable
private fun DetailActionBtn(icon: String, desc: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp)
    ) {
        Text(icon, fontSize = 20.sp, color = Color.White)
        Text(desc, fontSize = 10.sp, color = Color.White)
    }
}

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
                                textAlign = TextAlign.Center,
                                style = androidx.compose.ui.text.TextStyle(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = Color(0x80000000),
                                        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                        blurRadius = 8f
                                    )
                                )
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
                            // 类型行
                            if (it.genres.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    it.genres.joinToString(" · "),
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                            // 简介：最多4行，白色，点击看全文
                            if (it.overview.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    it.overview,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { expanded = !expanded }
                                )
                            }
                            // 6 功能按钮：已看/收藏/合集/音频/评论/更多
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                DetailActionBtn("✓", "已看") { /* TODO */ }
                                DetailActionBtn("♥+", "收藏") { /* TODO */ }
                                DetailActionBtn("🎬", "合集") { /* TODO */ }
                                DetailActionBtn("🎧", "音频") { /* TODO */ }
                                DetailActionBtn("💬", "评论") { /* TODO */ }
                                DetailActionBtn("⋯", "更多") { /* TODO */ }
                            }
                        }
                    }
                }
                // 播放按钮：白色大胶囊 56dp
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color.White)
                            .clickable {
                                val first = eps.firstOrNull() ?: return@clickable
                                onPlay(it, first)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // 绿色已播进度（简化：无进度时不显示）
                        Text(
                            "▶ 播放",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = GomeTheme.TextPrimary
                        )
                    }
                }
                // 季选择
                item {
                    if (seasons.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
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
