package com.muse.gomepc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.gomepc.emby.Prefs
import com.muse.gomepc.emby.YambyClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberCoroutineScope
import com.muse.gomepc.emby.ServerEntry
import androidx.compose.foundation.border
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch

/** 导航目标 */
sealed interface Screen {
    data object Home : Screen
    data object Grid : Screen
    data object Search : Screen
    data object Favorites : Screen
    data object Settings : Screen
    data class Detail(val itemId: String) : Screen
    data class Player(
        val itemId: String,
        val itemName: String,
        val episodeId: String,
        val episodeIndex: Int
    ) : Screen
    data object ResumeList : Screen
    data class Library(val libId: String, val libName: String) : Screen
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

/** 红心 vector 图标（对齐 Android ic_heart_red，#F44336） */
@Composable
fun HeartIcon(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFF44336)
) {
    val path = remember {
        PathParser().parsePathString(
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        ).toPath()
    }
    Canvas(modifier = modifier) {
        val sc = size.minDimension / 24f
        val dx = (size.width - 24f * sc) / 2f
        val dy = (size.height - 24f * sc) / 2f
        withTransform({ translate(dx, dy); scale(sc, sc) }) {
            drawPath(path, color)
        }
    }
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
            // 播放进度条：底部 3dp，轨道 #40FFFFFF，进度 #FFFFFF（对齐 bg_progress）
            if (item.progress != null && item.progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x40FFFFFF))
                ) {
                    Box(
                        Modifier.fillMaxWidth(item.progress)
                            .fillMaxHeight()
                            .background(Color.White)
                    )
                }
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
            // 收藏红心：左上角 28dp（对齐 Android ivFavHeart）
            if (item.isFavorite) {
                HeartIcon(
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
    onResumeMore: () -> Unit = {},
    onServerIconClick: () -> Unit = {},
    onLibraryClick: (UiLibrary) -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState? = null
) {
    // 主页数据只在第一次启动时后台加载一次，之后切回直接用缓存
    LaunchedEffect(Unit) { HomeDataCache.ensureLoaded() }
    val libs = HomeDataCache.libs
    val libItems = HomeDataCache.libItems
    val resume = HomeDataCache.resume
    val latest = HomeDataCache.latest
    val error = HomeDataCache.error

    when {
        error != null -> ErrorBox(error!!, onRetry = { HomeDataCache.retry() })
        libs == null || resume == null -> LoadingBox()
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.White),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            state = listState ?: androidx.compose.foundation.lazy.rememberLazyListState()
        ) {
            // 顶部轮播（最新入库）：latest 为空时用第一个媒体库的数据兜底
            // 轮播图置顶全宽，顶栏悬浮在轮播图上方
            val bannerItems = if (!latest.isNullOrEmpty()) latest!!
                else libs?.firstOrNull()?.let { libItems[it.id]?.take(8) } ?: emptyList()
            if (bannerItems.isNotEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BannerCarousel(items = bannerItems, onItemClick = onItemClick)
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier.align(Alignment.CenterStart).size(44.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color(0x80FFFFFF)).clickable { onServerIconClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(Prefs.serverName.take(1).uppercase().ifEmpty { "影" },
                                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2F6FED))
                            }
                            Text(Prefs.serverName.ifEmpty { "影音" },
                                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            MGlassBox(modifier = Modifier.align(Alignment.CenterEnd).size(33.dp), corner = 17.dp) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    HeartIcon(modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier.align(Alignment.CenterStart).size(44.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.White).clickable { onServerIconClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(Prefs.serverName.take(1).uppercase().ifEmpty { "影" },
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2F6FED))
                        }
                        Text(Prefs.serverName.ifEmpty { "影音" },
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
                        MGlassBox(modifier = Modifier.align(Alignment.CenterEnd).size(33.dp), corner = 17.dp) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                HeartIcon(modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            // 继续观看（放媒体库上面）
            if (resume!!.isNotEmpty()) {
                item {
                    SectionHeader("继续观看", "更多", onAction = onResumeMore)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(resume!!) { item ->
                            ResumeCard(item, onClick = { onItemClick(item) })
                        }
                    }
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp),
                        contentPadding = PaddingValues(end = 12.dp)
                    ) {
                        items(libs!!) { lib ->
                            LibraryCard(
                                lib = lib,
                                posters = (libItems[lib.id] ?: emptyList()).take(4),
                                onClick = { onLibraryClick(lib) }
                            )
                        }
                    }
                }
            }
            libs!!.forEach { lib ->
                val items = libItems[lib.id] ?: emptyList()
                if (items.isNotEmpty()) {
                    item {
                        SectionHeader(lib.name, "更多", onAction = { onLibraryClick(lib) })
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
 * 顶部轮播大图（全宽横幅，对齐用户截图样式）。
 * 全宽 backdrop 大图，底部渐变叠加标题/评分/年份/类型/简介，圆点指示器，自动轮播。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BannerCarousel(
    items: List<UiMediaItem>,
    onItemClick: (UiMediaItem) -> Unit
) {
    if (items.isEmpty()) return
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { items.size })
    // 自动轮播：每 5 秒切换
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
        ) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onItemClick(item) }
            ) {
                // 背景大图（优先 backdrop，无则用海报）
                EmbyImage(
                    url = item.backdropUrl ?: item.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // 底部渐变
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0x99000000)
                                ),
                                startY = 200f
                            )
                        )
                )
                // 标题/元信息/简介（左下角）
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp, end = 20.dp, bottom = 36.dp)
                ) {
                    Text(
                        item.name,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    val meta = listOfNotNull(
                        item.rating?.let { "★ $it" },
                        item.year.takeIf { it.isNotBlank() },
                        item.genres.firstOrNull()
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            fontSize = 13.sp,
                            color = Color(0xE6FFFFFF),
                            maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (item.overview.isNotBlank()) {
                        Text(
                            item.overview,
                            fontSize = 13.sp,
                            color = Color(0xB3FFFFFF),
                            maxLines = 2,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
        // 圆点指示器
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) Color.White
                            else Color(0x80FFFFFF)
                        )
                )
            }
        }
    }
}

/** 媒体库卡片（对齐用户截图样式：左侧彩色块+库名，右侧海报拼贴） */
@Composable
fun LibraryCard(
    lib: UiLibrary,
    posters: List<UiMediaItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 根据库名选择背景色（对齐截图：国产剧青、港台剧浅绿、日韩剧金、欧美剧橄榄绿、短剧亮绿等）
    val bgColor = when {
        lib.name.contains("国产") -> Color(0xFF2E9AA6)
        lib.name.contains("港台") -> Color(0xFF7BC8A4)
        lib.name.contains("日韩") -> Color(0xFFD4A017)
        lib.name.contains("欧美") -> Color(0xFF9AA653)
        lib.name.contains("短剧") -> Color(0xFF2ECC71)
        lib.name.contains("电影") -> Color(0xFF1A6B7A)
        lib.name.contains("综艺") -> Color(0xFFE67E22)
        lib.name.contains("动漫") -> Color(0xFF9B59B6)
        lib.name.contains("纪录") -> Color(0xFF5D6D7E)
        else -> {
            val hues = listOf(0xFF2E9AA6, 0xFF7BC8A4, 0xFFD4A017, 0xFF9AA653, 0xFF2ECC71, 0xFF1A6B7A)
            Color(hues[Math.abs(lib.name.hashCode()) % hues.size])
        }
    }
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
                .background(bgColor)
        ) {
            // 左侧：库名
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp),
            ) {
                Text(
                    lib.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "TV",
                    fontSize = 10.sp,
                    color = Color(0xB3FFFFFF),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // 右侧：海报拼贴（3张错落叠放）
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy((-16).dp)
            ) {
                posters.take(3).forEachIndexed { index, poster ->
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x40000000))
                            .graphicsLayer {
                                rotationZ = (index - 1) * 8f
                                translationY = (index - 1) * 4f
                            }
                    ) {
                        if (poster.imageUrl != null) {
                            EmbyImage(
                                url = poster.imageUrl,
                                contentDescription = poster.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
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

/** 媒体库详情页：点击媒体库卡片或"更多"进入（对齐安卓 LibraryFragment） */
@Composable
fun LibraryScreen(
    libId: String,
    libName: String,
    onItemClick: (UiMediaItem) -> Unit,
    onBack: () -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState? = null
) {
    var libItems by remember { mutableStateOf<List<UiMediaItem>?>(null) }

    LaunchedEffect(libId) {
        libItems = null
        try { libItems = Repo.items(libId, 60) }
        catch (_: Exception) { libItems = emptyList() }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ 返回",
                fontSize = 15.sp,
                color = GomeTheme.TextPrimary,
                modifier = Modifier.clickable { onBack() }
                    .padding(12.dp)
            )
            Text(
                libName,
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState ?: androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            ) {
                items(libItems!!) { item ->
                    ItemCard(item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

/** 宫格：媒体库卡片，点击进入该库 */
@Composable
fun GridScreen(
    onItemClick: (UiMediaItem) -> Unit,
    onServerSelected: () -> Unit = {}
) {
    // 资源库页就是服务器选择器：本地数据，无网络刷新，点击服务器进主页
    ServerCardsGrid(
        modifier = Modifier.fillMaxSize().background(Color.White),
        onServerSelected = onServerSelected
    )
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var switching by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var servers by remember { mutableStateOf(Prefs.getServers()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // 应用锁弹窗状态
    var showSetPin by remember { mutableStateOf(false) }
    var showVerifyPin by remember { mutableStateOf(false) }
    var showLockManage by remember { mutableStateOf(false) }
    // 更多菜单（删除服务器）弹窗状态
    var showDeleteServer by remember { mutableStateOf(false) }
    var serverToDelete by remember { mutableStateOf<ServerEntry?>(null) }
    // 编辑服务器状态
    var serverToEdit by remember { mutableStateOf<ServerEntry?>(null) }

    fun refresh() { servers = Prefs.getServers() }

    val filtered = if (searchQuery.isBlank()) servers
        else servers.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.host.contains(searchQuery, ignoreCase = true)
        }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：标题（右上角锁/更多图标已删，用户要求）
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
            }
            // 搜索框：48dp 高，bg_search_box 圆角 28dp（对齐安卓）
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFF2F4F8))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 放大镜 20dp，ic_search（对齐安卓）
                SearchIcon(tint = Color(0xFF5F6368), iconSize = 20.dp)
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索", fontSize = 15.sp, color = Color(0xFFA0A0A0)) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        color = Color(0xFF1A1A1A)
                    ),
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
                    // 清除：32dp，ic_close（对齐安卓 btnClearSearch）
                    Box(
                        modifier = Modifier.size(32.dp)
                            .clickable { searchQuery = "" },
                        contentAlignment = Alignment.Center
                    ) {
                        CloseIcon(tint = Color(0xFF8A8A8A), iconSize = 20.dp)
                    }
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
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 8.dp, end = 8.dp, bottom = 96.dp
                    )
                ) {
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
                                if (switching) return@ServerCard
                                // 同一服务器：直接返回首页
                                val isSame = server.host == Prefs.host &&
                                    server.port == Prefs.port &&
                                    server.path == Prefs.path &&
                                    server.username == Prefs.username
                                if (isSame && Prefs.isLoggedIn()) {
                                    onServerSelected()
                                    return@ServerCard
                                }
                                switching = true
                                scope.launch {
                                    try {
                                        // 对齐安卓 switchTo：更新连接信息、清登录态、重新登录
                                        Prefs.protocol = server.protocol
                                        Prefs.host = server.host
                                        Prefs.port = server.port
                                        Prefs.path = server.path
                                        Prefs.username = server.username
                                        Prefs.clearLogin()
                                        YambyClient.login(server.username, server.password)
                                        Prefs.rememberCurrentServer(server.password)
                                        try {
                                            val sn = YambyClient.getServerName()
                                            if (sn.isNotEmpty()) {
                                                Prefs.serverName = sn
                                            } else {
                                                Prefs.serverName = server.name
                                            }
                                        } catch (_: Exception) {
                                            Prefs.serverName = server.name
                                        }
                                        Prefs.touchServerLastUsed(server.key())
                                    } catch (_: Exception) {
                                        Prefs.serverName = server.name
                                    } finally {
                                        switching = false
                                        onServerSelected()
                                    }
                                }
                            },
                            onLongClick = {
                                serverToEdit = server
                            },
                            onAvatarClick = {
                                serverToEdit = server
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

    // 编辑服务器对话框（预填数据）
    val editTarget = serverToEdit
    if (editTarget != null) {
        AddServerDialog(
            existing = editTarget,
            onDismiss = { serverToEdit = null },
            onAdded = { refresh(); serverToEdit = null }
        )
    }

    // 设置 PIN 弹窗
    if (showSetPin) {
        SetPinDialog(
            onDismiss = { showSetPin = false },
            onSet = { pin ->
                Prefs.appLockPin = pin
                showSetPin = false
            }
        )
    }

    // 验证 PIN 弹窗
    if (showVerifyPin) {
        VerifyPinDialog(
            title = "输入 PIN 以管理应用锁",
            onDismiss = { showVerifyPin = false },
            onVerified = {
                showVerifyPin = false
                showLockManage = true
            }
        )
    }

    // 应用锁管理弹窗（验证通过后）
    if (showLockManage) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLockManage = false },
            title = { Text("应用锁已开启") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    Prefs.appLockPin = ""
                    showLockManage = false
                }) { Text("关闭应用锁") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLockManage = false }) { Text("取消") }
            }
        )
    }

    // 删除服务器弹窗
    if (showDeleteServer) {
        DeleteServerDialog(
            servers = servers,
            onDismiss = { showDeleteServer = false },
            onDelete = { entry ->
                serverToDelete = entry
                showDeleteServer = false
            }
        )
    }

    // 二次确认删除
    val delTarget = serverToDelete
    if (delTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text("删除服务器") },
            text = { Text("删除「${delTarget.name.ifEmpty { delTarget.host }}」？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    Prefs.removeServer(delTarget.key())
                    refresh()
                    serverToDelete = null
                }) { Text("删除", color = Color(0xFFFF3B30)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { serverToDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 服务器卡片背景色：对齐安卓 CardTintHelper。
 * 按名称哈希取一个鲜艳色，再与白色混合成柔和浅色（82.5%白）。
 */
private fun serverTint(name: String): Color {
    val hash = name.hashCode()
    // 用哈希取色相（0-360），饱和度 0.7，明度 0.55，保证鲜艳
    val hue = ((hash % 360) + 360) % 360 / 360f
    val c = java.awt.Color.getHSBColor(hue, 0.7f, 0.65f)
    val r = c.red; val g = c.green; val b = c.blue
    // 与白色混合（82.5%白），对齐安卓的柔和浅色
    val m = 0.825f
    return Color(
        red = (r * (1 - m) + 255 * m).toInt().coerceIn(0, 255),
        green = (g * (1 - m) + 255 * m).toInt().coerceIn(0, 255),
        blue = (b * (1 - m) + 255 * m).toInt().coerceIn(0, 255)
    )
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
    onLongClick: () -> Unit,
    onAvatarClick: () -> Unit
) {
    // 卡片背景：对齐安卓 CardTintHelper——图标色（柔和浅色）为中心向四周的径向渐变→白色
    // PC 图标为字母，按服务器名哈希取色，再与白色混合（82.5%白）
    val tint = remember(server.name) { serverTint(server.name.ifEmpty { server.host }) }
    // 对齐 Android item_server_card.xml：24dp圆角，1dp阴影，7dp边距，10dp内边距
    // 宽高比 1.84:1（对齐安卓 ASPECT_W_H）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.84f)
            .padding(7.dp)
            .shadow(1.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(tint, Color.White),
                    center = androidx.compose.ui.geometry.Offset(0.85f, 0.15f),
                    radius = 1200f
                )
            )
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
            // 服务器图标 32dp 圆形：点击进编辑（用 pointerInput 避免跟卡片 combinedClickable 冲突）
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(32.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.White)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onAvatarClick() })
                    },
                contentAlignment = Alignment.Center
            ) {
                ServerIcon(
                    serverName = server.name.ifEmpty { server.host },
                    serverKey = server.key(),
                    modifier = Modifier.size(32.dp),
                    fallback = {
                        Text(
                            server.name.take(1).uppercase().ifEmpty { "服" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2F6FED)
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 第二行：电影数 + 剧集数（对齐安卓：14dp ic_film/ic_tv + 12sp #8A7B6C）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilmIcon(iconSize = 14.dp)
            Text(
                if (movies >= 0) movies.toString() else "–",
                fontSize = 12.sp,
                color = Color(0xFF8A7B6C),
                modifier = Modifier.padding(start = 4.dp)
            )
            TvIcon(iconSize = 14.dp, modifier = Modifier.padding(start = 12.dp))
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

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
    existing: ServerEntry? = null
) {
    val isEdit = existing != null
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port ?: "443") }
    var path by remember { mutableStateOf(existing?.path ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var useHttps by remember { mutableStateOf((existing?.protocol ?: "https") == "https") }
    var protocolExpanded by remember { mutableStateOf(false) }
    // 编辑模式记录旧 key，用于 key 变化时删除旧条目
    val oldKey = existing?.key()

    // 协议切换时自动跟随默认端口（对齐安卓）
    fun onProtocolChange(https: Boolean) {
        useHttps = https
        port = if (https) "443" else "80"
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑服务器" else "添加服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 更换图标（仅编辑模式，对齐安卓）
                if (isEdit && existing != null) {
                    val serverKey = existing.key()
                    var showIconPicker by remember { mutableStateOf(false) }
                    var iconVersion by remember { mutableStateOf(0) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        // 当前图标预览
                        key(iconVersion) {
                            ServerIcon(
                                serverName = existing.name.ifEmpty { existing.host },
                                serverKey = serverKey,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable { showIconPicker = true },
                                fallback = {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(Color(0xFFF2F2F2)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            existing.name.take(1).uppercase().ifEmpty { "服" },
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2F6FED)
                                        )
                                    }
                                }
                            )
                        }
                        Text(
                            "更换图标",
                            fontSize = 14.sp,
                            color = Color(0xFF2F6FED),
                            modifier = Modifier
                                .clickable { showIconPicker = true }
                                .padding(12.dp, 8.dp, 12.dp, 8.dp)
                        )
                    }
                    if (showIconPicker) {
                        IconPickerDialog(
                            serverKey = serverKey,
                            onDismiss = { showIconPicker = false },
                            onPicked = {
                                iconVersion++
                                showIconPicker = false
                            }
                        )
                    }
                }
                // 服务器地址（对齐安卓）
                ServerField("服务器地址", host) { host = it }
                // 协议 + 端口同行（对齐安卓）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 协议下拉
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = if (useHttps) "HTTPS" else "HTTP",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("协议") },
                            singleLine = true,
                            trailingIcon = {
                                Text(
                                    "▼",
                                    modifier = Modifier.clickable { protocolExpanded = !protocolExpanded }.padding(8.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable { protocolExpanded = true }
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = protocolExpanded,
                            onDismissRequest = { protocolExpanded = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("HTTPS") },
                                onClick = { onProtocolChange(true); protocolExpanded = false }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("HTTP") },
                                onClick = { onProtocolChange(false); protocolExpanded = false }
                            )
                        }
                    }
                    // 端口
                    Box(modifier = Modifier.weight(1f)) {
                        ServerField("端口", port) { port = it }
                    }
                }
                // 路径（对齐安卓）
                ServerField("路径(可选,无则留空)", path) { path = it }
                ServerField("用户名", username) { username = it }
                ServerField("密码", password, isPassword = true) { password = it }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    if (host.isNotBlank() && username.isNotBlank()) {
                        val newEntry = ServerEntry(
                            name = existing?.name?.ifEmpty { host } ?: host,
                            protocol = if (useHttps) "https" else "http",
                            host = host, port = port, path = path,
                            username = username, password = password
                        )
                        // 编辑模式且 key 变化：先删旧条目（对齐安卓 showEdit）
                        if (isEdit && oldKey != null && oldKey != newEntry.key()) {
                            Prefs.removeServer(oldKey)
                        }
                        Prefs.upsertServer(newEntry)
                        onAdded()
                    }
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2F7CF6)
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            ) { Text(if (isEdit) "保存" else "连接", color = Color.White) }
        },
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 删除按钮（仅编辑模式）：红色文字
                if (isEdit) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            Prefs.removeServer(existing.key())
                            onAdded()
                        }
                    ) {
                        Text("删除", color = Color(0xFFFF3B30))
                    }
                } else {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(1.dp))
                }
                Row {
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    // 保存/连接按钮由 confirmButton 提供，这里占位
                }
            }
        }
    )
}

/** 设置应用锁 PIN 弹窗（对齐安卓 showSetPinDialog） */
@Composable
private fun SetPinDialog(
    onDismiss: () -> Unit,
    onSet: (String) -> Unit
) {
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置应用锁") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField("设置 PIN（4-8 位数字）", pin1) { pin1 = it.filter { c -> c.isDigit() }.take(8) }
                PinField("再次输入确认", pin2) { pin2 = it.filter { c -> c.isDigit() }.take(8) }
                if (error != null) {
                    Text(error!!, color = Color(0xFFFF3B30), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (pin1.length < 4 || pin1.length > 8) {
                    error = "PIN 需为 4-8 位数字"
                    return@TextButton
                }
                if (pin1 != pin2) {
                    error = "两次输入不一致"
                    return@TextButton
                }
                onSet(pin1)
            }) { Text("确定") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 验证 PIN 弹窗（对齐安卓 showVerifyPinDialog） */
@Composable
private fun VerifyPinDialog(
    title: String,
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField("4-8 位数字", pin) { pin = it.filter { c -> c.isDigit() }.take(8) }
                if (error != null) {
                    Text(error!!, color = Color(0xFFFF3B30), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (pin == Prefs.appLockPin) {
                    onVerified()
                } else {
                    error = "PIN 不正确"
                }
            }) { Text("确定") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** PIN 输入框 */
@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(label, color = Color(0xFFAAAAAA)) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 服务器选择弹窗（对齐安卓 SearchFragment.showServerSelector） */
@Composable
private fun ServerSelectorDialog(
    onDismiss: () -> Unit,
    onSelected: (name: String, key: String) -> Unit
) {
    val servers = Prefs.getServers()
    val currentKey = Prefs.searchServerKey

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择服务器") },
        text = {
            Column {
                // 聚合搜索选项
                ServerOptionRow(
                    name = "聚合搜索",
                    isSelected = currentKey.isEmpty(),
                    onClick = { onSelected("聚合搜索", "") }
                )
                androidx.compose.material3.Divider(color = Color(0xFFEFEFF4))
                // 各服务器
                servers.forEach { server ->
                    val key = "${server.protocol}://${server.host}:${server.port}${server.path}"
                    val displayName = server.name.ifEmpty { server.host }
                    ServerOptionRow(
                        name = displayName,
                        isSelected = key == currentKey,
                        onClick = { onSelected(displayName, key) }
                    )
                    if (server != servers.last()) {
                        androidx.compose.material3.Divider(color = Color(0xFFEFEFF4))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 服务器选项行（带选中对勾） */
@Composable
private fun ServerOptionRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 16.sp,
            color = Color(0xFF1A1A1A),
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Text("✓", fontSize = 18.sp, color = Color(0xFF2F6FED))
        }
    }
}

/** 删除服务器选择弹窗（对齐安卓 showMoreMenu） */
@Composable
private fun DeleteServerDialog(
    servers: List<ServerEntry>,
    onDismiss: () -> Unit,
    onDelete: (ServerEntry) -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除服务器") },
        text = {
            if (servers.isEmpty()) {
                Text("还没有保存的服务器", color = Color(0xFF8E8E93))
            } else {
                Column {
                    servers.forEach { server ->
                        val displayName = server.name.ifEmpty { server.host }
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onDelete(server) }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(displayName, fontSize = 16.sp, color = Color(0xFF1A1A1A))
                        }
                        if (server != servers.last()) {
                            androidx.compose.material3.Divider(color = Color(0xFFEFEFF4))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ServerField(label: String, value: String, isPassword: Boolean = false, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
    // 服务器选择弹窗状态
    var showServerSelector by remember { mutableStateOf(false) }
    var serverName by remember { mutableStateOf(Prefs.searchServerName) }

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
        // 服务器选择（聚合搜索）：药丸框（对齐 Android bg_pill：#F0F0F0，20dp 圆角）
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                serverName,
                fontSize = 15.sp,
                color = GomeTheme.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFF0F0F0))
                    .clickable { showServerSelector = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 服务器选择弹窗
        if (showServerSelector) {
            ServerSelectorDialog(
                onDismiss = { showServerSelector = false },
                onSelected = { name, key ->
                    Prefs.searchServerName = name
                    Prefs.searchServerKey = key
                    serverName = name
                    showServerSelector = false
                    // 有搜索词时自动重新搜索
                    if (query.isNotBlank()) searchKey++
                }
            )
        }
        // 搜索框（对齐 Android bg_search_box：#F2F4F8，28dp 圆角，48dp 高）
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
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
            // 清除按钮：40dp（对齐 Android btnClear），有文字时显示
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .clickable { query = "" },
                    contentAlignment = Alignment.Center
                ) {
                    CloseIcon(iconSize = 24.dp, tint = Color(0xFF8A8A8A))
                }
            }
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
                            // 垃圾桶：32dp vector（对齐 Android ic_trash_gray）
                            Box(
                                modifier = Modifier.size(32.dp)
                                    .clickable {
                                        Prefs.clearSearchHistory()
                                        history = emptyList()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                TrashIcon(iconSize = 24.dp, tint = Color(0xFF8A8A8A))
                            }
                        }
                        // 历史标签流式布局
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    // 空态（对齐 Android："输入关键词开始搜索" 15sp #AAAAAA 居中）
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("输入关键词开始搜索", fontSize = 15.sp, color = Color(0xFFAAAAAA))
                    }
                }
            }
            else -> {
                // 结果数：13sp #8A8A8A（对齐 Android：marginStart 20dp）
                Text(
                    "找到 ${results!!.size} 个结果",
                    fontSize = 13.sp,
                    color = Color(0xFF8A8A8A),
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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

/** 收藏页（对齐 Android activity_favorites.xml）：顶栏"收藏"+计数，3列网格，空态"暂无收藏" */
@Composable
fun FavoritesScreen(onItemClick: (UiMediaItem) -> Unit) {
    var items by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        items = null
        try {
            items = Repo.favorites()
        } catch (_: Exception) {
            items = emptyList()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // 顶栏（对齐 Android：padding 16dp，标题"收藏" 20sp 加粗，右侧计数 13sp #8A8A8A）
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "收藏",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
            )
            Spacer(Modifier.weight(1f))
            val list = items
            if (list != null && list.isNotEmpty()) {
                Text(
                    "${list.size} 项",
                    fontSize = 13.sp,
                    color = Color(0xFF8A8A8A)
                )
            }
        }
        val list = items
        when {
            list == null -> LoadingBox(Modifier.weight(1f))
            list.isEmpty() -> {
                // 空态（对齐 Android："暂无收藏" 15sp #AAAAAA 居中）
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("暂无收藏", fontSize = 15.sp, color = Color(0xFFAAAAAA))
                }
            }
            else -> {
                // 3列网格（对齐 Android GridLayoutManager 3列，paddingBottom 100dp）
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(list) { item ->
                        ItemCard(item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

/** 设置（1:1 Android 1.22.75：播放 / 界面 / 关于 三卡片） */
@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {},
    onToggleDemo: (Boolean) -> Unit = {}
) {
    val prefs = com.muse.gomepc.emby.Prefs
    // 弹窗状态
    var showDecodeDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var showDanmakuDialog by remember { mutableStateOf(false) }
    var showDockDialog by remember { mutableStateOf(false) }
    var showAnimationDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    // 刷新触发
    var refreshTick by remember { mutableStateOf(0) }

    fun decodeLabel(): String = when (prefs.decodeMode) {
        "mediacodec-copy" -> "HW+"
        "mediacodec" -> "HW"
        "no" -> "SW"
        else -> "Auto"
    }
    fun cacheLabel(): String = if (prefs.cacheEnabled) "${prefs.cacheSizeGb}G" else "关"
    fun danmakuLabel(): String = when {
        !prefs.danmakuEnabled -> "关"
        prefs.danmakuApiUrl.isBlank() -> "未设置"
        else -> "已设置"
    }
    fun dockLabel(): String = if (prefs.dockStyle == 0) "M玻璃" else "原玻璃"
    fun animLabel(): String = when (prefs.flybackMode) {
        0 -> "严格"
        2 -> "宽松"
        else -> "标准"
    }

    // 弹窗
    if (showDecodeDialog) {
        SettingSingleChoiceDialog(
            title = "解码",
            options = listOf("HW+（默认）", "HW", "SW", "Auto"),
            selected = when (prefs.decodeMode) {
                "mediacodec-copy" -> 0; "mediacodec" -> 1; "no" -> 2; else -> 3
            },
            onSelect = {
                prefs.decodeMode = when (it) {
                    0 -> "mediacodec-copy"; 1 -> "mediacodec"; 2 -> "no"; else -> "auto"
                }
                refreshTick++
                showDecodeDialog = false
            },
            onDismiss = { showDecodeDialog = false }
        )
    }
    if (showCacheDialog) {
        SettingCacheDialog(
            onDismiss = { showCacheDialog = false },
            onConfirm = { refreshTick++ ; showCacheDialog = false }
        )
    }
    if (showDanmakuDialog) {
        SettingDanmakuDialog(
            onDismiss = { showDanmakuDialog = false },
            onConfirm = { refreshTick++ ; showDanmakuDialog = false }
        )
    }
    if (showDockDialog) {
        SettingSingleChoiceDialog(
            title = "dock",
            options = listOf("M玻璃", "原玻璃"),
            selected = prefs.dockStyle,
            onSelect = {
                prefs.dockStyle = it
                refreshTick++
                showDockDialog = false
            },
            onDismiss = { showDockDialog = false }
        )
    }
    if (showAnimationDialog) {
        SettingAnimationDialog(
            onDismiss = { showAnimationDialog = false },
            onConfirm = { refreshTick++ ; showAnimationDialog = false }
        )
    }
    if (showAboutDialog) {
        SettingAboutDialog(onDismiss = { showAboutDialog = false })
    }

    // 用 refreshTick 触发重组刷新显示值
    refreshTick.let { }
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
        // ===== 播放卡片 =====
        item { SettingGroupTitle("播放") }
        item {
            SettingCard {
                SettingRowVector(
                    label = "解码",
                    iconBg = Color(0xFF2F7CF6),
                    paths = SETTING_ICON_DECODE,
                    value = decodeLabel(),
                    showDivider = true,
                    onClick = { showDecodeDialog = true }
                )
                SettingRowVector(
                    label = "缓存",
                    iconBg = Color(0xFFFF9F0A),
                    paths = SETTING_ICON_CACHE,
                    value = cacheLabel(),
                    showDivider = true,
                    onClick = { showCacheDialog = true }
                )
                SettingRowVector(
                    label = "弹幕",
                    iconBg = Color(0xFF34C759),
                    paths = SETTING_ICON_DANMAKU,
                    value = danmakuLabel(),
                    showDivider = false,
                    onClick = { showDanmakuDialog = true }
                )
            }
        }
        // ===== 界面卡片 =====
        item { SettingGroupTitle("界面") }
        item {
            SettingCard {
                SettingRowVector(
                    label = "dock",
                    iconBg = Color(0xFF5AC8FA),
                    paths = SETTING_ICON_DOCK,
                    value = dockLabel(),
                    showDivider = true,
                    onClick = { showDockDialog = true }
                )
                SettingRowVector(
                    label = "动画",
                    iconBg = Color(0xFFAF52DE),
                    paths = SETTING_ICON_ANIM,
                    value = animLabel(),
                    showDivider = false,
                    onClick = { showAnimationDialog = true }
                )
            }
        }
        // ===== 关于卡片 =====
        item { SettingGroupTitle("关于") }
        item {
            SettingCard {
                SettingRowVector(
                    label = "关于",
                    iconBg = Color(0xFF8E8E93),
                    paths = null,
                    iconText = "i",
                    value = AppVersion.DISPLAY,
                    showDivider = false,
                    onClick = { showAboutDialog = true }
                )
            }
        }
    }
}

// ===== 设置页 vector 图标 pathData（1:1 Android drawable，白 glyph）=====
private val SETTING_ICON_DECODE = listOf(
    "M9,7h6c0.6,0 1,0.4 1,1v8c0,0.6 -0.4,1 -1,1H9c-0.6,0 -1,-0.4 -1,-1V8c0,-0.6 0.4,-1 1,-1zM10.5,10.5v3l2.5,-1.5z",
    "M6,9H4v6h2zM20,9h-2v6h2zM9,4v2h6V4zM9,18v2h6v-2z"
)
private val SETTING_ICON_CACHE = listOf(
    "M12,4c4.4,0 8,1.1 8,2.5S16.4,9 12,9S4,7.9 4,6.5S7.6,4 12,4zM4,8.5c0,1.4 3.6,2.5 8,2.5s8,-1.1 8,-2.5v6c0,1.4 -3.6,2.5 -8,2.5s-8,-1.1 -8,-2.5v-6z"
)
private val SETTING_ICON_DANMAKU = listOf(
    "M4,4h16c1.1,0 2,0.9 2,2v10c0,1.1 -0.9,2 -2,2H8l-4,4V6c0,-1.1 0.9,-2 2,-2z"
)
private val SETTING_ICON_DOCK = listOf(
    "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
)
private val SETTING_ICON_ANIM = listOf(
    "M12,3c5.5,0 10,4.5 10,10c0,1.5 -1.2,2.7 -2.7,2.7h-2.1c-0.8,0 -1.5,0.7 -1.5,1.5c0,0.8 -0.7,1.5 -1.5,1.5H12c-5.5,0 -10,-4.5 -10,-10S6.5,3 12,3zM7.5,8.5c-0.8,0 -1.5,0.7 -1.5,1.5s0.7,1.5 1.5,1.5s1.5,-0.7 1.5,-1.5s-0.7,-1.5 -1.5,-1.5zM10,6c-0.8,0 -1.5,0.7 -1.5,1.5S9.2,9 10,9s1.5,-0.7 1.5,-1.5S10.8,6 10,6zM14,6c-0.8,0 -1.5,0.7 -1.5,1.5S13.2,9 14,9s1.5,-0.7 1.5,-1.5S14.8,6 14,6zM16.5,8.5c-0.8,0 -1.5,0.7 -1.5,1.5s0.7,1.5 1.5,1.5s1.5,-0.7 1.5,-1.5s-0.7,-1.5 -1.5,-1.5z"
)

/** 设置行：29dp 彩色圆角图标（白 vector glyph）+ 16sp 标签 + 右侧值 + › */
@Composable
private fun SettingRowVector(
    label: String,
    iconBg: Color,
    paths: List<String>?,
    iconText: String? = null,
    value: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp)
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(29.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                if (paths != null) {
                    SettingVectorIcon(paths = paths, tint = Color.White, modifier = Modifier.size(18.dp))
                } else {
                    Text(iconText ?: "", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
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
            if (value != null) {
                Text(value, fontSize = 15.sp, color = Color(0xFF8E8E93))
                Spacer(Modifier.width(4.dp))
            }
            Text("›", fontSize = 22.sp, color = Color(0xFFC7C7CC))
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

/** 设置页多路径填充 vector 图标（白 glyph） */
@Composable
private fun SettingVectorIcon(paths: List<String>, tint: Color, modifier: Modifier = Modifier) {
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier = modifier) {
        val sc = size.minDimension / 24f
        val dx = (size.width - 24f * sc) / 2f
        val dy = (size.height - 24f * sc) / 2f
        withTransform({ translate(dx, dy); scale(sc, sc) }) {
            for (p in parsed) drawPath(p, tint)
        }
    }
}

// ===== 毛底弹窗（1:1 Android bg_dialog_rounded：#F2FFFFFF + 16dp圆角 + 白描边）=====
@Composable
private fun MaoDiDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF2FFFFFF))
                .border(1.dp, Color(0xAAFFFFFF), RoundedCornerShape(16.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** 单选弹窗：解码 / dock */
@Composable
private fun SettingSingleChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(280.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            options.forEachIndexed { i, opt ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = i == selected,
                        onClick = { onSelect(i) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(opt, fontSize = 16.sp, color = Color.Black)
                }
                if (i < options.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 16.sp, color = Color(0xFF2F6FED),
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
            }
        }
    }
}

/** 缓存弹窗：开关 + 1G/2G/3G */
@Composable
private fun SettingCacheDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val prefs = com.muse.gomepc.emby.Prefs
    var enabled by remember { mutableStateOf(prefs.cacheEnabled) }
    var size by remember { mutableStateOf(prefs.cacheSizeGb) }
    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(300.dp)) {
            Text("缓存", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用缓存", fontSize = 16.sp, color = Color.Black, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Text("缓存大小", fontSize = 14.sp, color = Color(0xFF8E8E93),
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(1, 2, 3).forEach { s ->
                    Row(
                        Modifier.clickable { size = s }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = size == s, onClick = { size = s })
                        Text("${s}G", fontSize = 16.sp, color = Color.Black)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("切换后重新加载生效", fontSize = 12.sp, color = Color(0xFF8E8E93),
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 16.sp, color = Color(0xFF8E8E93),
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("确定", fontSize = 16.sp, color = Color(0xFF2F6FED),
                    modifier = Modifier.clickable {
                        prefs.cacheEnabled = enabled
                        prefs.cacheSizeGb = size
                        onConfirm()
                    }.padding(8.dp))
            }
        }
    }
}

/** 弹幕弹窗：开关 + API 输入 */
@Composable
private fun SettingDanmakuDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val prefs = com.muse.gomepc.emby.Prefs
    var enabled by remember { mutableStateOf(prefs.danmakuEnabled) }
    var api by remember { mutableStateOf(prefs.danmakuApiUrl) }
    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(300.dp)) {
            Text("弹幕", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用弹幕", fontSize = 16.sp, color = Color.Black, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = api,
                onValueChange = { api = it },
                label = { Text("弹幕 API 地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("清除API", fontSize = 16.sp, color = Color(0xFF8E8E93),
                    modifier = Modifier.clickable { api = "" }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("取消", fontSize = 16.sp, color = Color(0xFF8E8E93),
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("保存", fontSize = 16.sp, color = Color(0xFF2F6FED),
                    modifier = Modifier.clickable {
                        prefs.danmakuEnabled = enabled
                        prefs.danmakuApiUrl = api.trim()
                        onConfirm()
                    }.padding(8.dp))
            }
        }
    }
}

/** 动画弹窗：飞回动画（严格/标准/宽松）+ 缩放动画（1倍/2倍/3倍），横排 */
@Composable
private fun SettingAnimationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val prefs = com.muse.gomepc.emby.Prefs
    var flyback by remember { mutableStateOf(prefs.flybackMode) }
    var zoom by remember { mutableStateOf(prefs.zoomSpeed) }
    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(320.dp)) {
            // 标题居中
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("动画", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Spacer(Modifier.height(12.dp))
            Text("飞回动画", fontSize = 14.sp, color = Color(0xFF8E8E93))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(0 to "严格", 1 to "标准", 2 to "宽松").forEach { (v, label) ->
                    Row(
                        Modifier.weight(1f).height(48.dp)
                            .clickable { flyback = v },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.RadioButton(selected = flyback == v, onClick = { flyback = v })
                        Text(label, fontSize = 16.sp, color = Color.Black)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Spacer(Modifier.height(12.dp))
            Text("缩放动画", fontSize = 14.sp, color = Color(0xFF8E8E93))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(1 to "1倍", 2 to "2倍", 3 to "3倍").forEach { (v, label) ->
                    Row(
                        Modifier.weight(1f).height(48.dp)
                            .clickable { zoom = v },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.RadioButton(selected = zoom == v, onClick = { zoom = v })
                        Text(label, fontSize = 16.sp, color = Color.Black)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 16.sp, color = Color(0xFF8E8E93),
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("确定", fontSize = 16.sp, color = Color(0xFF2F6FED),
                    modifier = Modifier.clickable {
                        prefs.flybackMode = flyback
                        prefs.zoomSpeed = zoom
                        onConfirm()
                    }.padding(8.dp))
            }
        }
    }
}

/** 关于弹窗 */
@Composable
private fun SettingAboutDialog(onDismiss: () -> Unit) {
    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("关于", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                modifier = Modifier.padding(bottom = 12.dp))
            Text(
                "Gome ${AppVersion.DISPLAY}\nYamby 风格 Emby 客户端（MPV 内核）",
                fontSize = 14.sp, color = Color(0xFF8E8E93),
            )
            Spacer(Modifier.height(16.dp))
            Text("确定", fontSize = 16.sp, color = Color(0xFF2F6FED),
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
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

/** 详情页描边 vector 图标（1:1 Android drawable pathData，白 tint） */
@Composable
private fun DetailStrokeIcon(paths: List<String>, tint: Color, modifier: Modifier = Modifier) {
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier = modifier) {
        val sc = size.minDimension / 24f
        val dx = (size.width - 24f * sc) / 2f
        val dy = (size.height - 24f * sc) / 2f
        withTransform({ translate(dx, dy); scale(sc, sc) }) {
            for (p in parsed) drawPath(p, tint, style = Stroke(width = 1.8f))
        }
    }
}

/** 详情页填充 vector 图标 */
@Composable
private fun DetailFillIcon(pathData: String, tint: Color, modifier: Modifier = Modifier) {
    val path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    Canvas(modifier = modifier) {
        val sc = size.minDimension / 24f
        val dx = (size.width - 24f * sc) / 2f
        val dy = (size.height - 24f * sc) / 2f
        withTransform({ translate(dx, dy); scale(sc, sc) }) {
            drawPath(path, tint)
        }
    }
}

// 5 功能图标 pathData（1:1 Android drawable）
private val DETAIL_ICON_CHECK = listOf("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
private val DETAIL_ICON_HEART_PLUS = listOf(
    "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z",
    "M19,12h-4M17,10v4"
)
private val DETAIL_ICON_FILM = listOf(
    "M4,5h16c1.1,0 2,0.9 2,2v10c0,1.1 -0.9,2 -2,2H4c-1.1,0 -2,-0.9 -2,-2V7c0,-1.1 0.9,-2 2,-2z",
    "M7,5v14M17,5v14M2,9h5M2,15h5M17,9h5M17,15h5"
)
private val DETAIL_ICON_HEADPHONES = listOf(
    "M4,14v-2c0,-4.42 3.58,-8 8,-8s8,3.58 8,8v2",
    "M4,14c-1.1,0 -2,0.9 -2,2v3c0,1.1 0.9,2 2,2h1v-7H4z",
    "M20,14c1.1,0 2,0.9 2,2v3c0,1.1 -0.9,2 -2,2h-1v-7h1z"
)
private val DETAIL_ICON_COMMENTS = listOf("M4,4h16c1.1,0 2,0.9 2,2v10c0,1.1 -0.9,2 -2,2H8l-4,4V6c0,-1.1 0.9,-2 2,-2z")
private const val DETAIL_ICON_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
private const val DETAIL_ICON_SEARCH = "M15.5,14h-0.79l-0.28,-0.27c1.2,-1.4 1.82,-3.31 1.48,-5.34c-0.47,-2.78 -2.79,-5 -5.59,-5.34c-4.23,-0.52 -7.79,3.04 -7.27,7.27c0.34,2.8 2.56,5.12 5.34,5.59c2.03,0.34 3.94,-0.28 5.34,-1.48l0.27,0.28v0.79l4.25,4.25c0.41,0.41 1.08,0.41 1.49,0c0.41,-0.41 0.41,-1.08 0,-1.49L15.5,14zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5S14,7.01 14,9.5S11.99,14 9.5,14z"
private const val DETAIL_ICON_MORE = "M12,8c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,5c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,11c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
private const val DETAIL_ICON_CHEVRON_DOWN = "M7,10l5,5 5,-5"

/** 详情页功能按钮：5 个白图标，各占 1/5 宽，高 40dp（1:1 Android） */
@Composable
private fun DetailActionBtn(paths: List<String>, desc: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(0.2f).height(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        DetailStrokeIcon(paths = paths, tint = tint, modifier = Modifier.size(26.dp))
    }
}

/** 详情页毛底单选弹窗（1:1 安卓 setSingleChoiceItems + 毛底样式） */
@Composable
private fun DetailSingleChoiceDialog(
    title: String,
    names: List<String>,
    checkedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(checkedIndex.coerceIn(0, names.size - 1)) }
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF2FFFFFF))
                .clickable(enabled = false, onClick = {})
                .padding(vertical = 8.dp)
        ) {
            Column {
                Text(
                    title,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
                names.forEachIndexed { idx, name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = idx; onSelect(idx) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name, fontSize = 15.sp, color = Color(0xFF1A1A1A),
                            modifier = Modifier.weight(1f)
                        )
                        if (idx == selected) {
                            Text("✓", fontSize = 18.sp, color = Color(0xFF2F6FED), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (idx < names.size - 1) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Color(0xFFEFEFF4)))
                    }
                }
            }
        }
    }
}

/** 详情页顶部 40dp 白圆按钮（1:1 Android） */
@Composable
private fun DetailTopCircleBtn(pathData: String, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        DetailFillIcon(pathData = pathData, tint = Color(0xFF1A1A1A), modifier = Modifier.fillMaxSize())
    }
}

private fun formatEpDuration(ticks: Long): String {
    if (ticks <= 0) return ""
    val sec = ticks / 10_000_000L
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 集数缩略图卡（1:1 Android item_episode_card：180×100dp 圆角12dp） */
@Composable
private fun EpisodeThumbCard(ep: UiEpisode, isCurrent: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.width(180.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.width(180.dp).height(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE0E0E0))
        ) {
            EmbyImage(
                url = YambyClient.imageUrl(ep.id, "Primary", 360),
                contentDescription = ep.name,
                modifier = Modifier.fillMaxSize()
            )
            if (ep.played) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .size(24.dp).clip(CircleShape).background(Color(0xFF34C759)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("√", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            val dur = formatEpDuration(ep.runTicks)
            if (dur.isNotEmpty()) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(dur, color = Color.White, fontSize = 11.sp)
                }
            }
            if (isCurrent) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth().height(4.dp)
                        .background(Color(0xFF2F6FED))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            ep.name.ifBlank { "第${ep.index}集" },
            fontSize = 13.sp,
            color = GomeTheme.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 详情页（1:1 Android activity_detail） */
@Composable
fun DetailScreen(itemId: String, onBack: () -> Unit, onPlay: (UiMediaItem, UiEpisode) -> Unit) {
    var item by remember(itemId) { mutableStateOf<UiMediaItem?>(null) }
    var epData by remember(itemId) { mutableStateOf<EpisodeData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var showFullOverview by remember { mutableStateOf(false) }
    var seasonIdx by remember { mutableStateOf(0) }
    var seasonEps by remember { mutableStateOf<List<UiEpisode>?>(null) }
    var seasonLoading by remember { mutableStateOf(false) }
    var showSeasonMenu by remember { mutableStateOf(false) }
    // 5 功能按钮状态（1:1 安卓 DetailActivity）
    var isWatched by remember(itemId) { mutableStateOf(false) }
    var isFav by remember(itemId) { mutableStateOf(false) }
    var selectedMediaSourceId by remember(itemId) { mutableStateOf("") }
    var selectedAudioIndex by remember(itemId) { mutableStateOf(-1) }
    var selectedSubtitleIndex by remember(itemId) { mutableStateOf(-1) }
    var versionDialog by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) }
    var audioDialog by remember { mutableStateOf<Pair<List<String>, List<Int>>?>(null) }
    var subtitleDialog by remember { mutableStateOf<Pair<List<String>, List<Int>>?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    val detailScope = rememberCoroutineScope()

    LaunchedEffect(itemId, reloadKey) {
        try {
            error = null; item = null; epData = null
            val d = Repo.itemDetail(itemId)
            item = d
            epData = Repo.episodes(itemId)
            // 1:1 安卓：初始化已看/收藏状态，重置版本/音轨/字幕选择
            isWatched = d.played
            isFav = d.isFavorite
            selectedMediaSourceId = ""
            selectedAudioIndex = -1
            selectedSubtitleIndex = -1
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    when {
        error != null -> ErrorBox(error!!, onRetry = { reloadKey++ })
        item == null || epData == null -> LoadingBox()
        else -> {
            val it = item!!
            val eps = seasonEps ?: epData!!.episodes
            val seasons = epData!!.seasons
            LaunchedEffect(seasonIdx) {
                val data = epData ?: return@LaunchedEffect
                if (seasons.isEmpty()) return@LaunchedEffect
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

            Box(Modifier.fillMaxSize().background(Color.White)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 110.dp)
                ) {
                    // 海报头 480dp
                    item {
                        Box(Modifier.fillMaxWidth().height(480.dp)) {
                            if (it.imageUrl != null) {
                                EmbyImage(
                                    url = YambyClient.imageUrl(it.id, "Backdrop", 1280),
                                    contentDescription = it.name,
                                    modifier = Modifier.fillMaxSize(),
                                    fallback = {
                                        val (c1, c2) = posterColors(it.hue)
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, c2))))
                                    }
                                )
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
                                    textAlign = TextAlign.Center,
                                    style = androidx.compose.ui.text.TextStyle(
                                        shadow = androidx.compose.ui.graphics.Shadow(
                                            color = Color(0x80000000),
                                            offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                            blurRadius = 4f
                                        )
                                    )
                                )
                                if (it.genres.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        it.genres.joinToString(" · "),
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        style = androidx.compose.ui.text.TextStyle(
                                            shadow = androidx.compose.ui.graphics.Shadow(
                                                color = Color(0x80000000),
                                                offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                                blurRadius = 4f
                                            )
                                        )
                                    )
                                }
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
                                        modifier = Modifier.clickable { showFullOverview = true }
                                    )
                                }
                                // 5 功能图标：已看/收藏/合集/音频/评论（1:1 安卓 DetailActivity）
                                Spacer(Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    // 已看：电影管自己，剧集管未看的第一集
                                    DetailActionBtn(
                                        DETAIL_ICON_CHECK, "已看",
                                        tint = if (isWatched) Color(0xFF34C759) else Color.White
                                    ) {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            isWatched = !isWatched
                                            try { YambyClient.setPlayed(tid, isWatched) } catch (_: Exception) { }
                                            toastMsg = if (isWatched) "已标记为看过" else "已取消标记"
                                        }
                                    }
                                    // 收藏
                                    DetailActionBtn(
                                        DETAIL_ICON_HEART_PLUS, "收藏",
                                        tint = if (isFav) Color(0xFFFF3B30) else Color.White
                                    ) {
                                        detailScope.launch {
                                            isFav = !isFav
                                            try { YambyClient.setFavorite(it.id, isFav) } catch (_: Exception) { }
                                            toastMsg = if (isFav) "已收藏" else "已取消收藏"
                                        }
                                    }
                                    // 合集→版本选择
                                    DetailActionBtn(DETAIL_ICON_FILM, "合集") {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            try {
                                                val json = YambyClient.getPlaybackInfoJson(tid)
                                                val sources = json.optJSONArray("MediaSources")
                                                if (sources == null || sources.length() == 0) {
                                                    toastMsg = "无版本信息"; return@launch
                                                }
                                                val names = mutableListOf<String>()
                                                val ids = mutableListOf<String>()
                                                for (idx in 0 until sources.length()) {
                                                    val s = sources.getJSONObject(idx)
                                                    val name = s.optString("Name", "")
                                                    var desc = name
                                                    val streams = s.optJSONArray("MediaStreams")
                                                    if (streams != null) {
                                                        for (j in 0 until streams.length()) {
                                                            val st = streams.getJSONObject(j)
                                                            if (st.optString("Type") == "Video") {
                                                                val w = st.optInt("Width", 0)
                                                                val h = st.optInt("Height", 0)
                                                                if (w > 0 && h > 0) desc = "$name ${w}x$h"
                                                                break
                                                            }
                                                        }
                                                    }
                                                    if (desc.isEmpty()) desc = "版本 ${idx + 1}"
                                                    names.add(desc)
                                                    ids.add(s.optString("Id", ""))
                                                }
                                                versionDialog = names to ids
                                            } catch (e: Exception) {
                                                toastMsg = "获取版本失败"
                                            }
                                        }
                                    }
                                    // 音频
                                    DetailActionBtn(DETAIL_ICON_HEADPHONES, "音频") {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            try {
                                                val json = YambyClient.getPlaybackInfoJson(tid)
                                                val sources = json.optJSONArray("MediaSources")
                                                if (sources == null || sources.length() == 0) return@launch
                                                var srcIdx = 0
                                                if (selectedMediaSourceId.isNotEmpty()) {
                                                    for (i in 0 until sources.length()) {
                                                        if (sources.getJSONObject(i).optString("Id") == selectedMediaSourceId) {
                                                            srcIdx = i; break
                                                        }
                                                    }
                                                }
                                                val streams = sources.getJSONObject(srcIdx).optJSONArray("MediaStreams")
                                                    ?: return@launch
                                                val names = mutableListOf<String>()
                                                val idxs = mutableListOf<Int>()
                                                for (i in 0 until streams.length()) {
                                                    val st = streams.getJSONObject(i)
                                                    if (st.optString("Type") == "Audio") {
                                                        val ai = st.optInt("Index", -1)
                                                        var label = st.optString("DisplayTitle", "")
                                                        if (label.isEmpty()) {
                                                            val lang = st.optString("Language", "")
                                                            val codec = st.optString("Codec", "")
                                                            label = listOf(lang, codec).filter { s -> s.isNotEmpty() }.joinToString(" ")
                                                        }
                                                        if (label.isEmpty()) label = "音轨 ${names.size + 1}"
                                                        names.add(label); idxs.add(ai)
                                                    }
                                                }
                                                if (names.isEmpty()) { toastMsg = "无音轨信息"; return@launch }
                                                audioDialog = names to idxs
                                            } catch (e: Exception) {
                                                toastMsg = "获取音轨失败"
                                            }
                                        }
                                    }
                                    // 评论→字幕选择
                                    DetailActionBtn(DETAIL_ICON_COMMENTS, "评论") {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            try {
                                                val json = YambyClient.getPlaybackInfoJson(tid)
                                                val sources = json.optJSONArray("MediaSources")
                                                if (sources == null || sources.length() == 0) return@launch
                                                var srcIdx = 0
                                                if (selectedMediaSourceId.isNotEmpty()) {
                                                    for (i in 0 until sources.length()) {
                                                        if (sources.getJSONObject(i).optString("Id") == selectedMediaSourceId) {
                                                            srcIdx = i; break
                                                        }
                                                    }
                                                }
                                                val streams = sources.getJSONObject(srcIdx).optJSONArray("MediaStreams")
                                                    ?: return@launch
                                                val names = mutableListOf("关闭字幕")
                                                val idxs = mutableListOf(-1)
                                                for (i in 0 until streams.length()) {
                                                    val st = streams.getJSONObject(i)
                                                    if (st.optString("Type") == "Subtitle") {
                                                        val si = st.optInt("Index", -1)
                                                        var label = st.optString("DisplayTitle", "")
                                                        if (label.isEmpty()) {
                                                            val lang = st.optString("Language", "")
                                                            label = lang.ifEmpty { "字幕 ${names.size}" }
                                                        }
                                                        names.add(label); idxs.add(si)
                                                    }
                                                }
                                                if (names.size <= 1) { toastMsg = "无字幕信息"; return@launch }
                                                subtitleDialog = names to idxs
                                            } catch (e: Exception) {
                                                toastMsg = "获取字幕失败"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 播放按钮：56dp 白大胶囊 + 绿进度叠层
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 10.dp)
                                .height(56.dp)
                                .shadow(4.dp, RoundedCornerShape(28.dp))
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color.White)
                                .clickable {
                                    val first = eps.firstOrNull() ?: return@clickable
                                    onPlay(it, first)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val p = it.progress
                            if (p != null && p > 0f) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(p.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .align(Alignment.CenterStart)
                                        .background(Color(0xFF34C759).copy(alpha = 0.25f))
                                )
                            }
                            Text(
                                if (p != null && p > 0f) "继续播放" else "▶ 播放",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = GomeTheme.TextPrimary
                            )
                        }
                    }
                    // 季选择行
                    item {
                        if (seasons.size > 1) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(start = 20.dp, end = 12.dp, top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        seasons.getOrNull(seasonIdx)?.name ?: "",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GomeTheme.TextPrimary,
                                        modifier = Modifier.clickable { showSeasonMenu = true }
                                    )
                                    DetailStrokeIcon(
                                        paths = listOf(DETAIL_ICON_CHEVRON_DOWN),
                                        tint = GomeTheme.TextPrimary,
                                        modifier = Modifier.size(20.dp)
                                            .padding(start = 4.dp)
                                            .clickable { showSeasonMenu = true }
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text("←", fontSize = 16.sp, color = Color(0xFF8A8A8A),
                                        modifier = Modifier.clickable { if (seasonIdx > 0) seasonIdx-- }.padding(8.dp))
                                    Text("→", fontSize = 16.sp, color = Color(0xFF8A8A8A),
                                        modifier = Modifier.clickable { if (seasonIdx < seasons.size - 1) seasonIdx++ }.padding(8.dp))
                                    Text("更多", fontSize = 14.sp, color = Color(0xFF8A8A8A),
                                        modifier = Modifier.clickable { showSeasonMenu = true }.padding(8.dp))
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showSeasonMenu,
                                    onDismissRequest = { showSeasonMenu = false }
                                ) {
                                    seasons.forEachIndexed { idx, s ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(s.name) },
                                            onClick = { seasonIdx = idx; showSeasonMenu = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 集数横滑缩略图
                    item {
                        if (seasonLoading) {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = GomeTheme.Accent)
                            }
                        } else if (eps.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(eps.size) { idx ->
                                    val ep = eps[idx]
                                    EpisodeThumbCard(ep = ep, isCurrent = idx == 0, onClick = { onPlay(it, ep) })
                                }
                            }
                        }
                    }
                    // 演职人员
                    item {
                        if (it.people.isNotEmpty()) {
                            Text(
                                "演职人员",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = GomeTheme.TextPrimary,
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(it.people) { person ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(64.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier.size(60.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE0E0E0))
                                        ) {
                                            EmbyImage(
                                                url = YambyClient.imageUrl(person.id, "Primary", 200),
                                                contentDescription = person.name,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            person.name,
                                            fontSize = 11.sp,
                                            color = GomeTheme.TextPrimary,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 顶部固定按钮：不跟随滚动
                DetailTopCircleBtn(
                    pathData = DETAIL_ICON_BACK, desc = "返回", onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                )
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)) {
                    DetailTopCircleBtn(pathData = DETAIL_ICON_SEARCH, desc = "搜索", onClick = { })
                    Spacer(Modifier.width(10.dp))
                    DetailTopCircleBtn(pathData = DETAIL_ICON_MORE, desc = "更多", onClick = { })
                }
                // 简介全文弹窗
                if (showFullOverview) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showFullOverview = false }) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(20.dp)
                        ) {
                            Text(
                                it.overview,
                                fontSize = 14.sp,
                                color = GomeTheme.TextPrimary,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                // 版本选择弹窗（1:1 安卓 showVersionDialog）
                versionDialog?.let { (names, ids) ->
                    val checked = ids.indexOf(selectedMediaSourceId).coerceAtLeast(0)
                    DetailSingleChoiceDialog(
                        title = "选择版本", names = names, checkedIndex = checked,
                        onSelect = { which ->
                            selectedMediaSourceId = ids[which]
                            selectedAudioIndex = -1
                            selectedSubtitleIndex = -1
                            toastMsg = "已选: ${names[which]}"
                            versionDialog = null
                        },
                        onDismiss = { versionDialog = null }
                    )
                }
                // 音轨选择弹窗（1:1 安卓 showAudioDialog）
                audioDialog?.let { (names, idxs) ->
                    val checked = idxs.indexOf(selectedAudioIndex).coerceAtLeast(0)
                    DetailSingleChoiceDialog(
                        title = "选择音轨", names = names, checkedIndex = checked,
                        onSelect = { which ->
                            selectedAudioIndex = idxs[which]
                            toastMsg = "已选: ${names[which]}"
                            audioDialog = null
                        },
                        onDismiss = { audioDialog = null }
                    )
                }
                // 字幕选择弹窗（1:1 安卓 showSubtitleDialog）
                subtitleDialog?.let { (names, idxs) ->
                    val checked = idxs.indexOf(selectedSubtitleIndex).coerceAtLeast(0)
                    DetailSingleChoiceDialog(
                        title = "选择字幕", names = names, checkedIndex = checked,
                        onSelect = { which ->
                            selectedSubtitleIndex = idxs[which]
                            toastMsg = "已选: ${names[which]}"
                            subtitleDialog = null
                        },
                        onDismiss = { subtitleDialog = null }
                    )
                }
                // 轻提示（替代安卓 Toast）
                toastMsg?.let { msg ->
                    LaunchedEffect(msg) {
                        kotlinx.coroutines.delay(1500)
                        toastMsg = null
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 130.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(msg, fontSize = 14.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
