package com.muse.gomepc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.Dp
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

/** 详情页选的播放选项：版本/音轨/字幕，随 onPlay 一起传给播放器 */
data class PlaybackSelection(
    /** 选的版本 MediaSourceId，空=默认版本 */
    val mediaSourceId: String = "",
    /** 选的音轨序号（同类音轨中的第 N 条），-1=未选择 */
    val audioIndex: Int = -1,
    /** 选的字幕序号（同类字幕中的第 N 条），-1=关闭字幕，-2=未选择 */
    val subtitleIndex: Int = -2
)

/** 首页媒体库排序版本号：Prefs.libOrder 不是 Compose 状态，排序保存后递增它触发重组 */
private var libOrderVersion by mutableStateOf(0)

/** 导航目标 */
sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Favorites : Screen
    data object Settings : Screen
    data class Detail(
        val itemId: String,
        /** 当前正在播的那集（从播放器返回时传入，详情页用它标"当前集"，而不是用 playTicks 猜） */
        val currentEpisodeId: String? = null
    ) : Screen
    data class Player(
        val itemId: String,
        val itemName: String,
        val episodeId: String,
        val episodeIndex: Int,
        /** 断点续播位置（秒），0=从头播 */
        val startPositionSec: Double = 0.0,
        /** 详情页选的版本 MediaSourceId，空=默认版本 */
        val mediaSourceId: String = "",
        /** 详情页选的音轨序号，-1=未选择 */
        val audioIndex: Int = -1,
        /** 详情页选的字幕序号，-1=关闭字幕，-2=未选择 */
        val subtitleIndex: Int = -2
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
                    color = GomeTheme.Accent,
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
                        color = GomeTheme.Accent
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
        libs == null -> LoadingBox()
        else -> {
            // 应用用户自定义的媒体库排序
            // libOrderVersion：Prefs.libOrder 不是 Compose 状态，排序保存后递增版本号触发重组
            val orderedLibs = remember(libs, libOrderVersion) {
                val order = Prefs.libOrder.split(",").filter { it.isNotBlank() }
                if (order.isEmpty()) libs!!
                else {
                    val libMap = libs!!.associateBy { it.id }
                    val sorted = order.mapNotNull { libMap[it] }
                    val remaining = libs!!.filter { it.id !in order.toSet() }
                    sorted + remaining
                }
            }
            LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.White),
            contentPadding = PaddingValues(bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            state = listState ?: androidx.compose.foundation.lazy.rememberLazyListState()
        ) {
            // 顶部轮播（最新入库）：latest 为空时用第一个媒体库的数据兜底
            // 轮播图置顶全宽，顶栏悬浮在轮播图上方
            val bannerItems = if (!latest.isNullOrEmpty()) latest!!
                else libs?.firstOrNull()?.let { libItems[it.id]?.take(8) } ?: emptyList()
            // 轮播图置顶全宽、直通顶边；顶栏已统一为透明悬浮标题栏（Main.GomeApp 叠加）
            if (bannerItems.isNotEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BannerCarousel(items = bannerItems, onItemClick = onItemClick)
                    }
                }
            }
            // 继续观看（放媒体库上面）
            if (!resume.isNullOrEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        SectionHeader("继续观看", "更多", onAction = onResumeMore)
                        ArrowScrollRow(itemCount = resume!!.size) { idx ->
                            ResumeCard(resume!![idx], onClick = { onItemClick(resume!![idx]) })
                        }
                    }
                }
            }
            // 媒体库横排（对齐 Android：tvLibTitle + rvLibs）
            if (orderedLibs.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            "媒体库",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = GomeTheme.TextPrimary,
                            modifier = Modifier.padding(top = 18.dp)
                        )
                        ArrowScrollRow(
                            itemCount = orderedLibs.size,
                            contentPadding = PaddingValues(end = 12.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) { idx ->
                            val lib = orderedLibs[idx]
                            LibraryCard(
                                lib = lib,
                                posters = (libItems[lib.id] ?: emptyList()).take(4),
                                onClick = { onLibraryClick(lib) }
                            )
                        }
                    }
                }
            }
            orderedLibs.forEach { lib ->
                val items = libItems[lib.id] ?: emptyList()
                if (items.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            SectionHeader(lib.name, "更多", onAction = { onLibraryClick(lib) })
                            ArrowScrollRow(itemCount = items.size) { idx ->
                                ItemCard(items[idx], onClick = { onItemClick(items[idx]) })
                            }
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
            color = GomeTheme.Accent,
            modifier = Modifier.padding(8.dp)
                .clickable(enabled = onAction != null) { onAction?.invoke() }
        )
    }
}

/**
 * 带左右箭头的横向滚动行（PC端无法触摸滑动，用箭头翻页）。
 * @param content 每一项的内容，参数为 item 索引
 * @param initialCenterIndex 初始时居中显示的项索引（-1=不滚动）
 */
@Composable
fun ArrowScrollRow(
    itemCount: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    initialCenterIndex: Int = -1,
    centerIndex: Int = -1,
    itemContent: @Composable (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 初始滚动到指定项居中
    LaunchedEffect(initialCenterIndex, itemCount) {
        if (initialCenterIndex in 0 until itemCount) {
            // 居中：目标项放在可见区域中间
            listState.scrollToItem(
                index = (initialCenterIndex - 2).coerceAtLeast(0)
            )
        }
    }
    // 当 centerIndex 变化时滚动到居中（用于数据异步加载后）
    LaunchedEffect(centerIndex, itemCount) {
        if (centerIndex in 0 until itemCount && centerIndex != initialCenterIndex) {
            listState.scrollToItem(
                index = (centerIndex - 2).coerceAtLeast(0)
            )
        }
    }
    // 是否可向左/右滚动
    val canScrollLeft by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val canScrollRight by remember { derivedStateOf { listState.canScrollForward } }
    Box(modifier = modifier) {
        LazyRow(
            state = listState,
            horizontalArrangement = horizontalArrangement,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(itemCount) { idx -> itemContent(idx) }
        }
        // 左箭头
        if (canScrollLeft) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFBFBFBF))
                    .clickable {
                        scope.launch {
                            val target = (listState.firstVisibleItemIndex - 3).coerceAtLeast(0)
                            listState.animateScrollToItem(target)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            }
        }
        // 右箭头
        if (canScrollRight) {
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFBFBFBF))
                    .clickable {
                        scope.launch {
                            val target = listState.firstVisibleItemIndex + 3
                            listState.animateScrollToItem(target)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("›", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            }
        }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // 顶栏（顶部留出统一透明标题栏高度）
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 76.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "←",
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
    val carouselScope = androidx.compose.runtime.rememberCoroutineScope()
    // 用户拖动时暂停自动轮播（否则自动滚动与手动滑动打架）
    var autoPaused by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> autoPaused = true
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> autoPaused = false
            }
        }
    }
    // 自动轮播：每 5 秒切换
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            if (!autoPaused) {
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
            .pointerInput(Unit) {
                // 悬停暂停自动轮播（之前只有拖动暂停，鼠标停在上面不动也会被自动切走）
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            androidx.compose.ui.input.pointer.PointerEventType.Enter -> autoPaused = true
                            androidx.compose.ui.input.pointer.PointerEventType.Exit -> autoPaused = false
                        }
                    }
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            // 按条目 id 做 key，列表变化时页面不串位
            key = { items[it].id },
            modifier = Modifier
                .fillMaxWidth()
                .height(640.dp)
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
                // 底部淡出到白色（对齐安卓：海报底部淡出衔接继续观看顶部）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color.White
                            )
                        )
                )
                // 大标题（白色，压在图片上、淡出区上方）
                Text(
                    item.name,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = 300.dp)
                )
                // 评分/年份/类型/简介（落在白色淡出区，深色字，对齐安卓）
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp, end = 20.dp, bottom = 30.dp)
                ) {
                    val meta = listOfNotNull(
                        item.rating?.let { "★ $it" },
                        item.year.takeIf { it.isNotBlank() },
                        item.genres.firstOrNull()
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            fontSize = 13.sp,
                            color = GomeTheme.TextPrimary,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (item.overview.isNotBlank()) {
                        Text(
                            item.overview,
                            fontSize = 13.sp,
                            color = GomeTheme.TextSecondary,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
        // 圆点指示器（白色淡出区上用灰色）：可点击跳转（之前不可点）
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
                            if (index == pagerState.currentPage) Color(0xFF666666)
                            else Color(0xFFCCCCCC)
                        )
                        .clickable {
                            carouselScope.launch { pagerState.scrollToPage(index) }
                        }
                )
            }
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
/** 排序选项：显示名 → (SortBy, SortOrder, 箭头)，对齐 Android LibraryFragment */
private val librarySortOptions = listOf(
    "更新日期" to Triple("DateLastContentAdded", "Descending", "↓"),
    // "最新上线"与"创建日期"曾是同一 (DateCreated, Descending) 重复项，去重只留一个
    "最新上线" to Triple("DateCreated", "Descending", "↓"),
    "公众评分" to Triple("CommunityRating", "Descending", "↓"),
    "标题" to Triple("SortName", "Ascending", "↑"),
    "首映日期" to Triple("PremiereDate", "Descending", "↓"),
    "官方分级" to Triple("OfficialRating", "Ascending", "↑"),
    "出品年份" to Triple("ProductionYear", "Descending", "↓"),
    "影评人评分" to Triple("CriticRating", "Descending", "↓"),
    "播放日期" to Triple("DatePlayed", "Descending", "↓"),
)

@Composable
fun LibraryScreen(
    libId: String,
    libName: String,
    onItemClick: (UiMediaItem) -> Unit,
    onBack: () -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState? = null,
    /** 标题栏排序图标点击时递增，触发排序弹窗（统一透明标题栏用） */
    sortRequestKey: Int = 0
) {
    var libItems by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var selectedSort by remember { mutableStateOf(4) }  // 默认：首映日期 ↓（对齐 Android；去重后索引 4）
    var showSortDialog by remember { mutableStateOf(false) }
    // 加载失败与空库区分：之前失败也置 emptyList，空库与失败都显示空白网格
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(libId, selectedSort, reloadKey) {
        libItems = null
        loadError = null
        try {
            val (_, triple) = librarySortOptions[selectedSort]
            // 增量分页：第一页到就显示首屏，不再等全量（默认 2000 条）取完；libName 透传，不多发 libraries 请求
            Repo.libraryItemsPaged(libId, triple.first, triple.second, libName = libName) { page ->
                libItems = page
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { loadError = e.message ?: "加载失败" }
    }
    // 统一透明标题栏的排序图标 → 打开排序弹窗
    LaunchedEffect(sortRequestKey) {
        if (sortRequestKey > 0) showSortDialog = true
    }

    // 顶栏已统一为透明悬浮标题栏（Main.GomeApp 叠加）：库名居中、排序/返回在标题栏
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (loadError != null) {
            ErrorBox(loadError!!, onRetry = { reloadKey++ }, Modifier.weight(1f))
        } else if (libItems == null) {
            LoadingBox(Modifier.weight(1f))
        } else if (libItems!!.isEmpty()) {
            // 空库：明确提示，不再显示空白网格
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("该媒体库为空", fontSize = 15.sp, color = Color(0xFFAAAAAA))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 76.dp, bottom = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState ?: androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            ) {
                items(libItems!!, key = { it.id }) { item ->
                    ItemCard(item, onClick = { onItemClick(item) })
                }
            }
        }
    }

    // 排序弹窗：3 列 chip 网格（对齐 Android dialog_sort_chips）
    if (showSortDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排序", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(librarySortOptions) { index, (name, triple) ->
                        val selected = index == selectedSort
                        Box(
                            modifier = Modifier.height(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) GomeTheme.Accent else Color(0x80FFFFFF))
                                .border(
                                    1.dp,
                                    if (selected) GomeTheme.Accent else MGlass.Stroke,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    selectedSort = index
                                    showSortDialog = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$name ${triple.third}",
                                fontSize = 13.sp,
                                color = if (selected) Color.White else Color(0xFF1A1A1A)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showSortDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

/**
 * 切换服务器：先快照旧配置，登录成功后再写回新配置并记入列表；
 * 登录失败时恢复旧配置、不导航、不清空旧登录态。
 * 旧代码先写 Prefs 再登录，失败后旧服务器配置已丢且照常跳首页。
 * 返回 null=成功，否则为失败原因。
 */
private suspend fun trySwitchServer(server: ServerEntry): String? {
    val p = Prefs
    val oldProtocol = p.protocol
    val oldHost = p.host
    val oldPort = p.port
    val oldPath = p.path
    val oldUsername = p.username
    val oldServerName = p.serverName
    val oldToken = p.token
    val oldUserId = p.userId
    return try {
        p.protocol = server.protocol
        p.host = server.host
        p.port = server.port
        p.path = server.path
        p.username = server.username
        p.clearLogin()
        YambyClient.login(server.username, server.password)
        try {
            val sn = YambyClient.getServerName()
            p.serverName = if (sn.isNotEmpty()) sn else server.name
        } catch (_: Exception) {
            p.serverName = server.name
        }
        p.rememberCurrentServer(server.password)
        p.touchServerLastUsed(server.key())
        null
    } catch (e: Exception) {
        // 失败回滚旧配置
        p.protocol = oldProtocol
        p.host = oldHost
        p.port = oldPort
        p.path = oldPath
        p.username = oldUsername
        p.serverName = oldServerName
        p.token = oldToken
        p.userId = oldUserId
        e.message ?: "登录失败"
    }
}

/**
 * 资源库页：服务器卡片网格 + 短剧入口 + 添加。
 * 对齐 Android activity_resource.xml：28sp标题+锁/更多按钮+搜索框+双列卡片+右下角FAB。
 * 注：已搬到设置页"服务器"tab，此函数保留供设置页嵌入使用。
 */
@Composable
private fun ServerCardsGrid(
    modifier: Modifier = Modifier,
    onServerSelected: () -> Unit,
    showTitle: Boolean = true,
    topPadding: Dp = 76.dp
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var switching by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var servers by remember { mutableStateOf(Prefs.getServers()) }
    var switchError by remember { mutableStateOf<String?>(null) }
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
            // 顶栏：标题（右上角锁/更多图标已删，用户要求；顶部留出统一透明标题栏高度）
            // 嵌入设置页时不显示标题（左侧tab已有"服务器"）
            if (showTitle) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 20.dp, top = topPadding, end = 12.dp, bottom = 8.dp),
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
            } else {
                Spacer(Modifier.height(topPadding))
            }
            // 应用锁 / 管理服务器入口：之前右上角锁/更多图标被删，三个弹窗（设 PIN/验 PIN/删服务器）
            // 永远打不开；这里补显式入口
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "应用锁",
                    fontSize = 13.sp,
                    color = GomeTheme.Accent,
                    modifier = Modifier.clickable {
                        if (Prefs.appLockPin.isEmpty()) showSetPin = true else showVerifyPin = true
                    }.padding(8.dp)
                )
                Text(
                    "管理服务器",
                    fontSize = 13.sp,
                    color = GomeTheme.Accent,
                    modifier = Modifier.clickable { showDeleteServer = true }.padding(8.dp)
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
            // 切换服务器失败提示（失败不跳首页，旧配置已回滚）
            if (switchError != null) {
                Text(
                    switchError!!,
                    fontSize = 13.sp,
                    color = Color(0xFFE53935),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
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
                        // “当前”按服务器 Key 判（地址+协议+端口+路径+用户名），不再按名字判重
                        // （两个服务器同名时旧逻辑会把两个都标成当前）
                        val isCurrent = server.key() == Prefs.currentServerKey()
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
                                    val errMsg = trySwitchServer(server)
                                    switching = false
                                    if (errMsg == null) {
                                        onServerSelected()
                                    } else {
                                        switchError = "切换失败：$errMsg"
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
                .background(GomeTheme.Accent)
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

/** 媒体库排序弹窗：上下移动调整首页媒体库顺序 */
@Composable
fun LibSortDialog(
    onDismiss: () -> Unit
) {
    var libs by remember { mutableStateOf(HomeDataCache.libs ?: emptyList()) }

    // 初始化时按当前排序显示
    LaunchedEffect(Unit) {
        val order = Prefs.libOrder.split(",").filter { it.isNotBlank() }
        if (order.isNotEmpty()) {
            val libMap = libs.associateBy { it.id }
            val sorted = order.mapNotNull { libMap[it] }
            val remaining = libs.filter { it.id !in order.toSet() }
            libs = sorted + remaining
        }
    }

    fun saveOrder() {
        Prefs.libOrder = libs.joinToString(",") { it.id }
        // 递增版本号，触发 HomeScreen 重组应用新排序
        libOrderVersion++
    }

    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(320.dp).heightIn(max = 480.dp)) {
            Text(
                "媒体库排序",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(libs.size) { index ->
                    val lib = libs[index]
                    Row(
                        Modifier.fillMaxWidth().height(48.dp)
                            .background(Color(0xFFF2F1F6), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            lib.name,
                            fontSize = 16.sp,
                            color = Color.Black,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        // 上移
                        if (index > 0) {
                            Text(
                                "↑",
                                fontSize = 20.sp,
                                color = GomeTheme.Accent,
                                modifier = Modifier.clickable {
                                    libs = libs.toMutableList().also {
                                        val tmp = it[index]
                                        it[index] = it[index - 1]
                                        it[index - 1] = tmp
                                    }
                                }.padding(8.dp)
                            )
                        }
                        // 下移
                        if (index < libs.size - 1) {
                            Text(
                                "↓",
                                fontSize = 20.sp,
                                color = GomeTheme.Accent,
                                modifier = Modifier.clickable {
                                    libs = libs.toMutableList().also {
                                        val tmp = it[index]
                                        it[index] = it[index + 1]
                                        it[index + 1] = tmp
                                    }
                                }.padding(8.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "取消",
                    fontSize = 16.sp,
                    color = Color(0xFF8E8E93),
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "保存",
                    fontSize = 16.sp,
                    color = GomeTheme.Accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        saveOrder()
                        onDismiss()
                    }.padding(8.dp)
                )
            }
        }
    }
}

/** 首页服务器图标弹窗：媒体库排序 + 切换服务器卡片 */
@Composable
fun ServerPopupDialog(
    onDismiss: () -> Unit,
    onServerSelected: () -> Unit,
    onSortClick: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var switching by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var servers by remember { mutableStateOf(Prefs.getServers()) }
    // 切换服务器失败提示
    var switchError by remember { mutableStateOf<String?>(null) }

    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(340.dp).heightIn(max = 500.dp)) {
            Text(
                "服务器",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            // 媒体库排序行
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .clickable { onSortClick() }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("媒体库排序", fontSize = 16.sp, color = Color.Black, modifier = Modifier.weight(1f))
                Text("›", fontSize = 20.sp, color = Color(0xFFC7C7CC))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Spacer(Modifier.height(8.dp))
            // 切换服务器失败提示（失败不跳首页，旧配置已回滚）
            if (switchError != null) {
                Text(
                    switchError!!,
                    fontSize = 13.sp,
                    color = Color(0xFFE53935),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
            // 服务器卡片列表
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers, key = { it.key() }) { server ->
                    val isCurrent = Prefs.serverName == server.name
                    ServerCard(
                        server = server,
                        movies = 0,
                        series = 0,
                        lastUsed = 0L,
                        isCurrent = isCurrent,
                        onClick = {
                            if (switching) return@ServerCard
                            val isSame = server.host == Prefs.host &&
                                server.port == Prefs.port &&
                                server.path == Prefs.path &&
                                server.username == Prefs.username
                            if (isSame && Prefs.isLoggedIn()) {
                                onServerSelected()
                                onDismiss()
                                return@ServerCard
                            }
                            switching = true
                            scope.launch {
                                val errMsg = trySwitchServer(server)
                                switching = false
                                if (errMsg == null) {
                                    onServerSelected()
                                    onDismiss()
                                } else {
                                    switchError = "切换失败：$errMsg"
                                }
                            }
                        },
                        onLongClick = {},
                        onAvatarClick = {}
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "取消",
                    fontSize = 16.sp,
                    color = GomeTheme.Accent,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp)
                )
            }
        }
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
                    // 无匹配时 resolveIcon 已回落到图标库默认 emby 图标；
                    // 图标库不可达时留白，不显示服务器名首字
                    fallback = {
                        Box(Modifier.size(32.dp))
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
    var serverName by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port ?: "443") }
    var path by remember { mutableStateOf(existing?.path ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var useHttps by remember { mutableStateOf((existing?.protocol ?: "https") == "https") }
    var protocolExpanded by remember { mutableStateOf(false) }
    // 编辑模式记录旧 key，用于 key 变化时删除旧条目
    val oldKey = existing?.key()

    // 备用线路暂存：子卡片点保存只暂存，主框点保存才真正写入（对齐安卓）
    var backupDraft by remember {
        mutableStateOf(
            if (existing != null) ServerEntry(
                name = existing.name, protocol = existing.protocol, host = existing.host,
                port = existing.port, path = existing.path, username = existing.username,
                password = existing.password,
                backupProtocol = existing.backupProtocol, backupHost = existing.backupHost,
                backupPort = existing.backupPort, backupPath = existing.backupPath,
                backupName = existing.backupName
            ) else null
        )
    }
    var showBackupCard by remember { mutableStateOf(false) }
    var showPasswordCard by remember { mutableStateOf(false) }
    // 表单校验错误（旧代码校验失败时静默无反馈，用户以为按钮坏了）
    var formError by remember { mutableStateOf<String?>(null) }
    // 删除二次确认
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 协议切换时自动跟随默认端口（对齐安卓），但只在当前端口是默认值/空白时跟随；
    // 用户手改过的端口（如 8096）不能被协议切换悄悄覆盖
    fun onProtocolChange(https: Boolean) {
        useHttps = https
        if (port.isBlank() || port == "80" || port == "443") {
            port = if (https) "443" else "80"
        }
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
                                    // 无匹配时 resolveIcon 已回落到图标库默认 emby 图标；
                                    // 图标库不可达时留灰底空白，不显示服务器名首字
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(Color(0xFFF2F2F2)),
                                        contentAlignment = Alignment.Center
                                    ) {}
                                }
                            )
                        }
                        Text(
                            "更换图标",
                            fontSize = 14.sp,
                            color = GomeTheme.Accent,
                            modifier = Modifier
                                .clickable { showIconPicker = true }
                                .padding(12.dp, 8.dp, 12.dp, 8.dp)
                        )
                        Text(
                            "备用线路",
                            fontSize = 14.sp,
                            color = GomeTheme.Accent,
                            modifier = Modifier
                                .clickable { showBackupCard = true }
                                .padding(12.dp, 8.dp, 12.dp, 8.dp)
                        )
                        Text(
                            "更换密码",
                            fontSize = 14.sp,
                            color = GomeTheme.Accent,
                            modifier = Modifier
                                .clickable { showPasswordCard = true }
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
                    // 备用线路子卡片（对齐安卓 showBackupCard）
                    if (showBackupCard) {
                        BackupLineDialog(
                            initial = backupDraft,
                            onDismiss = { showBackupCard = false },
                            onSaved = { draft ->
                                backupDraft = draft
                                showBackupCard = false
                            }
                        )
                    }
                    // 更换密码子卡片（对齐安卓 showPasswordCard）：填入主框密码栏
                    if (showPasswordCard) {
                        ChangePasswordDialog(
                            onDismiss = { showPasswordCard = false },
                            onConfirmed = { newPass ->
                                password = newPass
                                showPasswordCard = false
                            }
                        )
                    }
                }
                // 服务器名称（可手动改名，对齐安卓 custom_name）
                ServerField("服务器名称", serverName) { serverName = it }
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
                // 校验错误提示
                if (formError != null) {
                    Text(
                        formError!!,
                        fontSize = 13.sp,
                        color = Color(0xFFE53935),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    // 校验：逐项给反馈，不再静默失败
                    val portNum = port.trim().toIntOrNull()
                    formError = when {
                        host.isBlank() -> "请填写服务器地址"
                        host.any { it.isWhitespace() } -> "服务器地址不能含空格"
                        username.isBlank() -> "请填写用户名"
                        portNum == null || portNum !in 1..65535 -> "端口必须是 1~65535 的数字"
                        else -> null
                    }
                    if (formError == null) {
                        val bd = backupDraft
                        // 地址清洗：host/path 一律 trim；host 里粘贴完整 URL（带 scheme）时拆出
                        // 协议/端口/路径（之前直接存坏：host 里带 http://，baseUrl 拼出非法地址）
                        var effProtocol = if (useHttps) "https" else "http"
                        var effHost = host.trim().trimEnd('/')
                        var effPort = port.trim()
                        var effPath = path.trim().trim('/')
                        if (effHost.contains("://")) {
                            val withoutScheme = effHost.substringAfter("://")
                            if (effHost.startsWith("https://", true)) effProtocol = "https"
                            else if (effHost.startsWith("http://", true)) effProtocol = "http"
                            val slash = withoutScheme.indexOf('/')
                            val hostPort = if (slash >= 0) withoutScheme.substring(0, slash) else withoutScheme
                            val pathPart = if (slash >= 0) withoutScheme.substring(slash + 1) else ""
                            val colon = hostPort.lastIndexOf(':')
                            effHost = if (colon >= 0) hostPort.substring(0, colon) else hostPort
                            if (colon >= 0) effPort = hostPort.substring(colon + 1)
                            if (pathPart.isNotBlank()) effPath = pathPart.trim().trim('/')
                        }
                        val newEntry = ServerEntry(
                            name = serverName.ifBlank { existing?.name?.ifEmpty { effHost } ?: effHost },
                            protocol = effProtocol,
                            host = effHost, port = effPort, path = effPath,
                            username = username.trim(), password = password,
                            backupProtocol = bd?.backupProtocol ?: "",
                            backupHost = bd?.backupHost ?: "",
                            backupPort = bd?.backupPort ?: "",
                            backupPath = bd?.backupPath ?: "",
                            backupName = bd?.backupName ?: ""
                        )
                        val newKey = newEntry.key()
                        // 编辑模式且 key 变化：迁移统计和自定义名，再删旧条目（对齐安卓）
                        if (isEdit && oldKey != null && oldKey != newKey) {
                            Prefs.transferServerStat(oldKey, newKey)
                            Prefs.transferCustomServerName(oldKey, newKey)
                            Prefs.removeServer(oldKey)
                        }
                        Prefs.upsertServer(newEntry)
                        // 手改的名称：记为自定义名，服务器上报名不再覆盖它；留空则清除自定义
                        Prefs.setCustomServerName(newKey, serverName.trim())
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
                // 删除按钮（仅编辑模式）：红色文字，点后二次确认（旧代码一点即删，无确认）
                if (isEdit) {
                    androidx.compose.material3.TextButton(
                        onClick = { showDeleteConfirm = true }
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

    // 删除二次确认
    if (showDeleteConfirm && existing != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除服务器") },
            text = { Text("删除「${existing.name.ifEmpty { existing.host }}」？该操作不可撤销。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        Prefs.removeServer(existing.key())
                        showDeleteConfirm = false
                        onAdded()
                    }
                ) { Text("删除", color = Color(0xFFFF3B30)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 备用线路子卡片（对齐安卓 showBackupCard）。点保存只暂存，主框点保存才真正写入。 */
@Composable
private fun BackupLineDialog(
    initial: ServerEntry?,
    onDismiss: () -> Unit,
    onSaved: (ServerEntry) -> Unit
) {
    var bName by remember { mutableStateOf(initial?.backupName ?: "") }
    var bHost by remember { mutableStateOf(initial?.backupHost ?: "") }
    var bProtocol by remember {
        mutableStateOf(
            (initial?.backupProtocol ?: "").let { if (it == "http") "http" else "https" }
        )
    }
    var bPort by remember {
        mutableStateOf(
            (initial?.backupPort ?: "").ifEmpty { if (bProtocol == "https") "443" else "80" }
        )
    }
    var bPath by remember { mutableStateOf(initial?.backupPath ?: "") }
    var protoExpanded by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备用线路") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField("线路名称", bName) { bName = it }
                ServerField("备用服务器地址", bHost) { bHost = it }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = if (bProtocol == "https") "HTTPS" else "HTTP",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("协议") },
                            singleLine = true,
                            trailingIcon = {
                                Text(
                                    "▼",
                                    modifier = Modifier.clickable { protoExpanded = !protoExpanded }.padding(8.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable { protoExpanded = true }
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = protoExpanded,
                            onDismissRequest = { protoExpanded = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("HTTPS") },
                                onClick = {
                                    bProtocol = "https"
                                    if (bPort.isEmpty() || bPort == "80") bPort = "443"
                                    protoExpanded = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("HTTP") },
                                onClick = {
                                    bProtocol = "http"
                                    if (bPort.isEmpty() || bPort == "443") bPort = "80"
                                    protoExpanded = false
                                }
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        ServerField("端口", bPort) { bPort = it }
                    }
                }
                ServerField("备用路径(可选,无则留空)", bPath) { bPath = it }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val bh = bHost.trim()
                val bp = bPort.trim().ifEmpty { if (bProtocol == "https") "443" else "80" }
                val base = initial ?: ServerEntry(
                    name = "", protocol = "https", host = "", port = "",
                    path = "", username = "", password = ""
                )
                onSaved(
                    base.copy(
                        backupProtocol = bProtocol,
                        backupHost = bh,
                        backupPort = if (bh.isEmpty()) "" else bp,
                        backupPath = bPath.trim(),
                        backupName = bName.trim()
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 更换密码子卡片（对齐安卓 showPasswordCard）。确认后把新密码填入主框密码栏。 */
@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirmed: (String) -> Unit
) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更换密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField("新密码", p1, isPassword = true) { p1 = it }
                ServerField("确认新密码", p2, isPassword = true) { p2 = it }
                if (error != null) {
                    Text(error!!, color = Color(0xFFFF3B30), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (p1.isEmpty()) {
                    error = "请输入新密码"
                    return@TextButton
                }
                if (p1 != p2) {
                    error = "两次输入不一致"
                    return@TextButton
                }
                onConfirmed(p1)
            }) { Text("确定") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
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
                // 当前服务器选项（之前叫“聚合搜索”，实际只搜当前服务器，是假分支，已改名）
                ServerOptionRow(
                    name = "当前服务器",
                    isSelected = currentKey.isEmpty(),
                    onClick = { onSelected("当前服务器", "") }
                )
                androidx.compose.material3.Divider(color = Color(0xFFEFEFF4))
                // 各服务器
                servers.forEach { server ->
                    // key 用 ServerEntry.key()（与 Prefs.currentServerKey 同格式），旧的 protocol://host:port+path
                    // 永远对不上当前服务器，选中"当前服务器"也路由不到
                    val key = server.key()
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
            Text("✓", fontSize = 18.sp, color = GomeTheme.Accent)
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
    // 密码框可见切换：之前密码只能密文，输错看不见
    var passwordVisible by remember { mutableStateOf(false) }
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword && !passwordVisible) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                Text(
                    if (passwordVisible) "隐藏" else "显示",
                    fontSize = 13.sp,
                    color = GomeTheme.Accent,
                    modifier = Modifier.clickable { passwordVisible = !passwordVisible }.padding(8.dp)
                )
            }
        } else null,
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
    // 搜索失败与“0 个结果”区分：失败时显示错误 + 重试，不再谎报“找到 0 个结果”
    var searchError by remember { mutableStateOf<String?>(null) }
    // 搜索历史（持久化）
    var history by remember { mutableStateOf(Prefs.getSearchHistory()) }
    // 服务器选择弹窗状态
    var showServerSelector by remember { mutableStateOf(false) }
    var serverName by remember { mutableStateOf(Prefs.searchServerName) }

    LaunchedEffect(searchKey) {
        if (searchKey == 0) return@LaunchedEffect
        val q = query
        searchError = null
        if (q.isBlank()) {
            // 空查询回到历史，不再显示“找到 0 个结果”
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        try {
            results = Repo.search(q)
            // 保存到历史
            Prefs.addSearchHistory(q)
            history = Prefs.getSearchHistory()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            results = null
            searchError = e.message ?: "搜索失败"
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // 服务器选择（当前服务器）：药丸框（对齐 Android bg_pill：#F0F0F0，20dp 圆角）
        // 顶部留出统一透明标题栏高度
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 76.dp, bottom = 4.dp),
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
            // 清除按钮：40dp（对齐 Android btnClear），有文字时显示；
            // 清除时连结果与错误一起清，回到历史（之前只清输入，旧结果残留）
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .clickable { query = ""; results = null; searchError = null },
                    contentAlignment = Alignment.Center
                ) {
                    CloseIcon(iconSize = 24.dp, tint = Color(0xFF8A8A8A))
                }
            }
        }
        // 可见的“搜索”按钮：之前只靠 IME/回车触发，无可见按钮
        androidx.compose.material3.Button(
            onClick = { searchKey++ },
            enabled = query.isNotBlank() && !searching,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = GomeTheme.Accent
            )
        ) {
            Text(if (searching) "搜索中…" else "搜索", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        when {
            searching -> LoadingBox(Modifier.weight(1f))
            searchError != null -> ErrorBox(searchError!!, onRetry = { searchKey++ }, Modifier.weight(1f))
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
                                // 历史标签 + 单条删除 ×（之前只能清空全部）
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFFF2F4F8))
                                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                ) {
                                    Text(
                                        h,
                                        fontSize = 13.sp,
                                        color = GomeTheme.TextPrimary,
                                        modifier = Modifier.clickable {
                                            query = h
                                            searchKey++
                                        }
                                    )
                                    Text(
                                        "×",
                                        fontSize = 14.sp,
                                        color = Color(0xFF8A8A8A),
                                        modifier = Modifier.clickable {
                                            Prefs.removeSearchHistoryItem(h)
                                            history = Prefs.getSearchHistory()
                                        }.padding(start = 6.dp, end = 4.dp)
                                    )
                                }
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
                    items(results!!, key = { it.id }) { item ->
                        ItemCard(item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

/** 收藏页：左右两张等大卡片，左边收藏（5列），右边搜索（3列） */
@Composable
fun FavoritesScreen(onItemClick: (UiMediaItem) -> Unit) {
    var items by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var favError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        items = null
        favError = null
        try {
            items = Repo.favorites()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            favError = e.message ?: "加载失败"
        }
    }

    Row(
        Modifier.fillMaxSize().background(Color.White)
            .padding(start = 16.dp, end = 16.dp, top = 76.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 左卡片：收藏（5列，更宽）
        Card(
            modifier = Modifier.weight(1.6f).fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                // 标题 + 计数 + 刷新：之前无标题/计数/刷新入口
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        "收藏" + if (items != null) "（${items!!.size}）" else "",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = GomeTheme.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "刷新",
                        fontSize = 13.sp,
                        color = GomeTheme.Accent,
                        modifier = Modifier.clickable { reloadKey++ }.padding(8.dp)
                    )
                }
                val list = items
                when {
                    favError != null -> ErrorBox(favError!!, onRetry = { reloadKey++ }, Modifier.weight(1f))
                    list == null -> LoadingBox(Modifier.weight(1f))
                    list.isEmpty() -> {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("暂无收藏", fontSize = 15.sp, color = Color(0xFFAAAAAA))
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            // 自适应列宽：之前 Fixed(5) 在窄卡片里溢出
                            columns = GridCells.Adaptive(130.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(list, key = { it.id }) { item ->
                                ItemCard(item, onClick = { onItemClick(item) })
                            }
                        }
                    }
                }
            }
        }
        // 右卡片：搜索（3列）
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            CompactSearchPanel(onItemClick = onItemClick)
        }
    }
}

/** 紧凑搜索面板（用于收藏页右卡片）：搜索框 + 3列结果 */
@Composable
fun CompactSearchPanel(onItemClick: (UiMediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchKey by remember { mutableStateOf(0) }
    var searchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchKey) {
        if (searchKey == 0) return@LaunchedEffect
        val q = query
        searchError = null
        if (q.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        try {
            results = Repo.search(q)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            results = null
            searchError = e.message ?: "搜索失败"
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 搜索框
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFF2F4F8))
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavIcon(NavKind.SEARCH, tint = Color(0xFF1A1A1A).copy(alpha = 0.5f), iconSize = 20.dp)
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
                    fontSize = 14.sp,
                    color = Color(0xFF1A1A1A)
                ),
                modifier = Modifier.weight(1f).height(40.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("搜索", fontSize = 14.sp, color = Color(0xFFAAAAAA))
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(32.dp)
                        .clickable { query = ""; results = null; searchError = null },
                    contentAlignment = Alignment.Center
                ) {
                    CloseIcon(iconSize = 20.dp, tint = Color(0xFF8A8A8A))
                }
            }
        }
        // 可见的“搜索”按钮（与搜索页一致，之前只靠 IME/回车）
        androidx.compose.material3.Button(
            onClick = { searchKey++ },
            enabled = query.isNotBlank() && !searching,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = GomeTheme.Accent
            )
        ) {
            Text(if (searching) "搜索中…" else "搜索", color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        when {
            searching -> LoadingBox(Modifier.weight(1f))
            searchError != null -> ErrorBox(searchError!!, onRetry = { searchKey++ }, Modifier.weight(1f))
            results == null -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("输入关键词搜索", fontSize = 14.sp, color = Color(0xFFAAAAAA))
                }
            }
            results!!.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("无结果", fontSize = 14.sp, color = Color(0xFFAAAAAA))
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results!!, key = { it.id }) { item ->
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
    onToggleDemo: (Boolean) -> Unit = {},
    onServerSelected: () -> Unit = {},
    demoMode: Boolean = false
) {
    val prefs = com.muse.gomepc.emby.Prefs
    // 弹窗状态
    var showDecodeDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var showDanmakuDialog by remember { mutableStateOf(false) }
    var showAnimationDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    // 刷新触发
    var refreshTick by remember { mutableStateOf(0) }
    // 清缓存 / 检查更新的操作反馈
    var settingsMsg by remember { mutableStateOf<String?>(null) }

    fun decodeLabel(): String = when (prefs.decodeMode) {
        "mediacodec-copy" -> "HW+"
        "mediacodec" -> "HW"
        "no" -> "SW"
        else -> "Auto"
    }
    fun cacheLabel(): String = if (prefs.cacheEnabled) "${prefs.cacheSizeGb}G" else "关"
    fun danmakuLabel(): String {
        val srcs = prefs.danmakuSources
        return when {
            !prefs.danmakuEnabled -> "关"
            srcs.isEmpty() -> "未设置"
            else -> "${srcs.count { it.enabled }} / ${srcs.size} 源"
        }
    }
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
    var selectedTab by remember { mutableStateOf(0) }
    Row(
        Modifier.fillMaxSize().background(Color(0xFFF2F1F6))
            .padding(top = 76.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp)
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight()
        ) {
            Text(
                "设置",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 10.dp, start = 10.dp)
            )
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                listOf("服务器", "播放", "界面", "字幕", "网络", "关于").forEachIndexed { i, s ->
                    val sel = i == selectedTab
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .background(
                                if (sel) Color(0xFFF2F1F6) else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { selectedTab = i }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            s,
                            fontSize = 19.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) Color(0xFF172029) else Color(0xFF52606A)
                        )
                    }
                }
            }
        }
        if (selectedTab == 0) {
            // ===== 服务器：从资源库搬过来，嵌入设置页第一排 =====
            ServerCardsGrid(
                modifier = Modifier.weight(4f).fillMaxHeight(),
                onServerSelected = onServerSelected,
                showTitle = false,
                topPadding = 0.dp
            )
        } else {
        LazyColumn(
            Modifier.weight(4f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            when (selectedTab) {
                1 -> {
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
                }
                2 -> {
        // ===== 界面卡片 =====
        item { SettingGroupTitle("界面") }
        item {
            SettingCard {
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
                }
                3 -> {
                    item { SettingGroupTitle("字幕") }
                    item {
                        SettingCard {
                            // 字幕 tab 不再是占位文案：播放器字幕说明 + 弹幕样式入口（真实可改的设置）
                            SettingRowVector(
                                label = "播放器字幕",
                                iconBg = GomeTheme.Accent,
                                paths = null,
                                iconText = "CC",
                                value = "简中优先",
                                showDivider = true,
                                onClick = null
                            )
                            SettingRowVector(
                                label = "弹幕样式",
                                iconBg = Color(0xFF34C759),
                                paths = SETTING_ICON_DANMAKU,
                                value = danmakuLabel(),
                                showDivider = false,
                                onClick = { showDanmakuDialog = true }
                            )
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                "播放器字幕由 mpv 自动选择简体中文；详情页点“字幕”可手动切换或关闭。",
                                fontSize = 13.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }
                    }
                }
                4 -> {
                    item { SettingGroupTitle("网络") }
                    item {
                        SettingCard {
                            // 网络 tab 不再是占位文案：缓存设置 + 当前服务器（真实可改/可看）
                            SettingRowVector(
                                label = "缓存",
                                iconBg = Color(0xFFFF9F0A),
                                paths = SETTING_ICON_CACHE,
                                value = cacheLabel(),
                                showDivider = true,
                                onClick = { showCacheDialog = true }
                            )
                            SettingRowVector(
                                label = "当前服务器",
                                iconBg = GomeTheme.Accent,
                                paths = null,
                                iconText = "S",
                                value = Prefs.host.ifEmpty { "未连接" },
                                showDivider = true,
                                onClick = null
                            )
                            SettingRowVector(
                                label = "服务器地址",
                                iconBg = Color(0xFF8E8E93),
                                paths = null,
                                iconText = "U",
                                value = Prefs.baseUrl().ifEmpty { "未配置" },
                                showDivider = false,
                                onClick = null
                            )
                        }
                    }
                }
                5 -> {
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
                    showDivider = true,
                    onClick = { showAboutDialog = true }
                )
                // 检查更新：打开 GitHub Releases（之前无检查更新入口）
                SettingRowVector(
                    label = "检查更新",
                    iconBg = GomeTheme.Accent,
                    paths = null,
                    iconText = "↻",
                    value = "查看最新版本",
                    showDivider = true,
                    onClick = {
                        try {
                            java.awt.Desktop.getDesktop().browse(
                                java.net.URI("https://github.com/ShanScs/Gome-win/releases")
                            )
                            settingsMsg = "已打开更新页面"
                        } catch (e: Exception) {
                            settingsMsg = "无法打开浏览器，当前版本 ${AppVersion.DISPLAY}"
                        }
                        refreshTick++
                    }
                )
                // 清除缓存：清 Repo 内存缓存 + 磁盘首页缓存 + 图片缓存（之前无清缓存入口）
                SettingRowVector(
                    label = "清除缓存",
                    iconBg = Color(0xFFFF9F0A),
                    paths = null,
                    iconText = "⌫",
                    value = settingsMsg ?: "点击清除",
                    showDivider = true,
                    onClick = {
                        Repo.clearAllCaches()
                        HomeDiskCache.clear(Prefs.currentServerKey())
                        EmbyImageLoader.clear()
                        settingsMsg = "已清除"
                        refreshTick++
                    }
                )
                // 演示模式开关：之前 onToggleDemo 是死参，无入口
                SettingRowVector(
                    label = "演示模式",
                    iconBg = Color(0xFF34C759),
                    paths = null,
                    iconText = "▶",
                    value = if (demoMode) "开" else "关",
                    showDivider = true,
                    onClick = { onToggleDemo(!demoMode) }
                )
                // 退出登录：之前 onLogout 是死参，无入口
                SettingRowVector(
                    label = "退出登录",
                    iconBg = Color(0xFFFF3B30),
                    paths = null,
                    iconText = "↩",
                    value = null,
                    showDivider = false,
                    onClick = { onLogout() }
                )
            }
        }
                }
            }
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
            Modifier.fillMaxWidth().height(68.dp)
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                if (paths != null) {
                    SettingVectorIcon(paths = paths, tint = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(iconText ?: "", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                fontSize = 20.sp,
                color = Color.Black,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (value != null) {
                Text(value, fontSize = 19.sp, color = Color(0xFF8E8E93))
                Spacer(Modifier.width(6.dp))
            }
            Text("›", fontSize = 28.sp, color = Color(0xFFC7C7CC))
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth()
                    .padding(start = 70.dp)
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
                .border(1.dp, MGlass.Stroke, RoundedCornerShape(16.dp))
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
                Text("取消", fontSize = 16.sp, color = GomeTheme.Accent,
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
                Text("确定", fontSize = 16.sp, color = GomeTheme.Accent,
                    modifier = Modifier.clickable {
                        prefs.cacheEnabled = enabled
                        prefs.cacheSizeGb = size
                        onConfirm()
                    }.padding(8.dp))
            }
        }
    }
}

/**
 * 弹幕源管理弹窗（1:1 Android showDanmakuSourcesDialog）。
 * 顶部"启用弹幕"总开关；中间源列表（每条可开关/删除）；底部"＋ 添加弹幕源"。
 */
@Composable
private fun SettingDanmakuDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val prefs = com.muse.gomepc.emby.Prefs
    var enabled by remember { mutableStateOf(prefs.danmakuEnabled) }
    var sources by remember { mutableStateOf(prefs.danmakuSources) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }

    fun persistSources(list: List<com.muse.gomepc.emby.Prefs.DanmakuSource>) {
        sources = list
        prefs.danmakuSources = list
    }

    if (showAddDialog) {
        MaoDiDialog(onDismiss = { showAddDialog = false; addError = null }) {
            Column(Modifier.width(300.dp)) {
                Text("添加弹幕源", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text("dandanplay 兼容的 API 地址（如自建 danmu_api）",
                    fontSize = 13.sp, color = Color(0xFF8E8E93),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it; addError = null },
                    label = { Text("https://你的弹幕API地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (addError != null) {
                    Text(addError!!, fontSize = 13.sp, color = Color(0xFFFF3B30),
                        modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("取消", fontSize = 16.sp, color = Color(0xFF8E8E93),
                        modifier = Modifier.clickable {
                            showAddDialog = false; addError = null
                        }.padding(8.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加", fontSize = 16.sp, color = GomeTheme.Accent,
                        modifier = Modifier.clickable {
                            val ok = prefs.addDanmakuSource(addText)
                            if (ok) {
                                sources = prefs.danmakuSources
                                addText = ""
                                showAddDialog = false
                            } else {
                                addError = "地址为空或已存在"
                            }
                        }.padding(8.dp))
                }
            }
        }
    }

    MaoDiDialog(onDismiss = onDismiss) {
        Column(Modifier.width(340.dp)) {
            Text("弹幕源（dandanplay 兼容）", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用弹幕", fontSize = 16.sp, color = Color.Black, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.danmakuEnabled = it
                        if (it) prefs.danmakuDisabled = false
                    }
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            // 源列表
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 280.dp)
            ) {
                items(sources.size) { i ->
                    val s = sources[i]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = s.enabled,
                            onCheckedChange = { on ->
                                persistSources(sources.map {
                                    if (it.url == s.url) it.copy(enabled = on) else it
                                })
                            }
                        )
                        Text(
                            s.url,
                            fontSize = 14.sp,
                            color = Color.Black,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            maxLines = 2
                        )
                        Text("删除", fontSize = 14.sp, color = Color(0xFFFF3B30),
                            modifier = Modifier.clickable {
                                persistSources(sources.filter { it.url != s.url })
                            }.padding(8.dp))
                    }
                    if (i < sources.size - 1) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
                    }
                }
            }
            if (sources.isEmpty()) {
                Text("暂无弹幕源，点击下方添加",
                    fontSize = 14.sp, color = Color(0xFF8E8E93),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Text("＋ 添加弹幕源", fontSize = 16.sp, color = GomeTheme.Accent,
                modifier = Modifier.fillMaxWidth().clickable { showAddDialog = true }
                    .padding(vertical = 14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEFEFF4)))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("关闭", fontSize = 16.sp, color = GomeTheme.Accent,
                    modifier = Modifier.clickable { onConfirm() }.padding(8.dp))
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
                Text("确定", fontSize = 16.sp, color = GomeTheme.Accent,
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
            Text("确定", fontSize = 16.sp, color = GomeTheme.Accent,
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
        }
    }
}

@Composable
private fun SettingGroupTitle(text: String) {
    Text(
        text,
        fontSize = 17.sp,
        color = Color(0xFF8E8E93),
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 10.dp)
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
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
            Modifier.fillMaxWidth().height(68.dp)
                .clickable(enabled = onChange != null || onClick != null) {
                    if (onClick != null) onClick()
                    else onChange?.invoke(!(checked ?: false))
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Text(iconText, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                fontSize = 20.sp,
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

/** 详情页填充 vector 图标（多 path 版，用于 5 功能按钮的 Material 填充图标） */
@Composable
private fun DetailFillIcons(paths: List<String>, tint: Color, modifier: Modifier = Modifier) {
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

// 5 功能图标 pathData（白环+毛玻璃风格，2026-10-13 定稿）
// 已看/收藏：填充图标；版本/音频：描边图标；字幕：CC
private val DETAIL_ICON_CHECK = listOf("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
private val DETAIL_ICON_HEART_PLUS = listOf(
    "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
)
private val DETAIL_ICON_FILM = listOf(
    "M3.5,5.5h17v13h-17z",
    "M7.5,5.5v13M16.5,5.5v13M3.5,10h4M3.5,14h4M16.5,10h4M16.5,14h4"
)
private val DETAIL_ICON_HEADPHONES = listOf(
    "M4.5,15v-2.5a7.5,7.5 0 0,1 15,0V15",
    "M3.5,14h4v6.5h-4zM16.5,14h4v6.5h-4z"
)
private val DETAIL_ICON_COMMENTS = listOf("M4,4h16c1.1,0 2,0.9 2,2v10c0,1.1 -0.9,2 -2,2H8l-4,4V6c0,-1.1 0.9,-2 2,-2z")
// 字幕 CC 图标（描边圆角矩形 + CC 文字用 path 绘制）
private val DETAIL_ICON_SUBTITLE_CC = listOf(
    "M3.5,5.5h17v13h-17z",
    "M9.2,10.2c-0.5,-0.4 -1.2,-0.6 -1.9,-0.6c-1.7,0 -3,1.2 -3,2.9s1.3,2.9 3,2.9c0.7,0 1.4,-0.2 1.9,-0.6M15.2,10.2c-0.5,-0.4 -1.2,-0.6 -1.9,-0.6c-1.7,0 -3,1.2 -3,2.9s1.3,2.9 3,2.9c0.7,0 1.4,-0.2 1.9,-0.6"
)
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

/** 海报上的白色圆环按钮（白环+环内毛玻璃+文字图标，2026-10-13 定稿） */
@Composable
private fun PosterCircleBtn(
    paths: List<String>,
    desc: String,
    isStroke: Boolean = false,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    // 文字图标映射（Canvas SVG 在真机上不渲染，改用文字保证显示）
    val iconText = when (desc) {
        "已看" -> "✓"
        "收藏" -> "♥"
        "版本" -> "🎬"
        "音频" -> "🎧"
        "字幕" -> "CC"
        else -> "•"
    }
    val iconSize = when (desc) {
        "字幕" -> 18.sp
        else -> 26.sp
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(62.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0x26FFFFFF)) // 环内毛玻璃（半透明白）
                .border(2.5.dp, Color.White, CircleShape), // 白色圆环
            contentAlignment = Alignment.Center
        ) {
            Text(iconText, fontSize = iconSize, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(desc, fontSize = 12.sp, color = Color.White)
    }
}

/** 详情页毛底单选弹窗（1:1 安卓 setSingleChoiceItems + 毛底样式） */
/**
 * Emby 语言码 → 中文名（详情页版本/音频弹窗用，与播放器 AwtToolbarPanel.langDisplayName 同表）。
 * 详情页之前直接用原始 DisplayTitle / Language 码当标签：
 * DisplayTitle 可能是韩文/日文原文（如“한국어”），弹窗字体无对应字形会显示成“□□□”；
 * Language 是 ISO 639-2 码（如“chi”），用户看不懂。语言码能映射时一律用中文名。
 */
private fun detailLangDisplayName(lang: String): String? {
    return when (lang.lowercase()) {
        "zh-hans", "zh-cn", "zh-sg", "chi", "zho", "cmn" -> "中文（简体）"
        "zh-hant", "zh-tw", "zh-hk", "zh-mo", "cht" -> "中文（繁體）"
        "zh", "zh-unknown" -> "中文"
        "yue" -> "粤语"
        "en", "eng" -> "英语"
        "ko", "kor" -> "韩语"
        "ja", "jpn" -> "日语"
        "fr", "fra", "fre" -> "法语"
        "de", "deu", "ger" -> "德语"
        "es", "spa" -> "西班牙语"
        "it", "ita" -> "意大利语"
        "ru", "rus" -> "俄语"
        "pt", "por" -> "葡萄牙语"
        "th", "tha" -> "泰语"
        "vi", "vie" -> "越南语"
        "ar", "ara" -> "阿拉伯语"
        "hi", "hin" -> "印地语"
        "id", "ind" -> "印度尼西亚语"
        "ms", "msa" -> "马来语"
        "nl", "nld", "dut" -> "荷兰语"
        "pl", "pol" -> "波兰语"
        "tr", "tur" -> "土耳其语"
        "uk", "ukr" -> "乌克兰语"
        "sv", "swe" -> "瑞典语"
        "da", "dan" -> "丹麦语"
        "fi", "fin" -> "芬兰语"
        "nb", "nob", "nn", "nno", "nor" -> "挪威语"
        "cs", "ces", "cze" -> "捷克语"
        "hu", "hun" -> "匈牙利语"
        "el", "ell", "gre" -> "希腊语"
        "he", "heb" -> "希伯来语"
        "la", "lat" -> "拉丁语"
        else -> null
    }
}

/**
 * 弹窗标签去重：同一标签出现多次时追加“ (2)”“ (3)”区分，不丢条目
 * （版本/音轨的不同条目可能同名，如两个“1080p”版本、两条“中文 AAC”音轨，
 * 直接丢掉会让用户选不到第二条）。
 */
private fun dedupePopupLabel(label: String, seen: MutableMap<String, Int>): String {
    val count = (seen[label] ?: 0) + 1
    seen[label] = count
    return if (count == 1) label else "$label ($count)"
}

@Composable
private fun DetailSingleChoiceDialog(
    title: String,
    names: List<String>,
    checkedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // checkedIndex=-1 表示"未选择"，不预勾任何一项（旧代码 coerceIn(0..) 会把未选择显示成选中第一项）
    var selected by remember { mutableStateOf(checkedIndex) }
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
                // 列表可滚 + 限高：之前无滚动无上限，条目多（如十几条字幕）时弹窗会撑出屏幕
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                ) {
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
                            Text("✓", fontSize = 18.sp, color = GomeTheme.Accent, fontWeight = FontWeight.Bold)
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
private fun EpisodeThumbCard(ep: UiEpisode, isCurrent: Boolean, isSelected: Boolean = false, onClick: () -> Unit) {
    Column(modifier = Modifier.width(180.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.width(180.dp).height(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE0E0E0))
                .then(
                    if (isSelected) Modifier.border(3.dp, GomeTheme.Accent, RoundedCornerShape(12.dp))
                    else Modifier
                )
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
                        .background(GomeTheme.Accent)
                )
            }
            // 播放进度条（有进度且未看完时显示）
            val prog = if (ep.runTicks > 0 && ep.playTicks > 0 && !ep.played) {
                (ep.playTicks.toFloat() / ep.runTicks.toFloat()).coerceIn(0f, 1f)
            } else 0f
            if (prog > 0) {
                Box(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth(prog).height(4.dp)
                        .background(GomeTheme.Accent)
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
fun DetailScreen(
    itemId: String,
    onBack: () -> Unit,
    onPlay: (UiMediaItem, UiEpisode, PlaybackSelection) -> Unit,
    /** 当前正在播的那集（从播放器返回时传入），用于标"当前集" */
    currentEpisodeId: String? = null
) {
    var item by remember(itemId) { mutableStateOf<UiMediaItem?>(null) }
    var epData by remember(itemId) { mutableStateOf<EpisodeData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var showFullOverview by remember { mutableStateOf(false) }
    var seasonIdx by remember { mutableStateOf(0) }
    var seasonEps by remember { mutableStateOf<List<UiEpisode>?>(null) }
    var seasonLoading by remember { mutableStateOf(false) }
    // 季切换失败提示：失败时保留旧季数据，不静默置空
    var seasonError by remember { mutableStateOf<String?>(null) }
    var showSeasonMenu by remember { mutableStateOf(false) }
    // 5 功能按钮状态（1:1 安卓 DetailActivity）
    var isWatched by remember(itemId) { mutableStateOf(false) }
    var isFav by remember(itemId) { mutableStateOf(false) }
    var selectedMediaSourceId by remember(itemId) { mutableStateOf("") }
    var selectedAudioIndex by remember(itemId) { mutableStateOf(-1) }
    // 字幕 -2=未选择（走 mpv 默认），-1=用户选了"关闭字幕"，>=0=同类字幕中的序号
    var selectedSubtitleIndex by remember(itemId) { mutableStateOf(-2) }
    var versionDialog by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) }
    var audioDialog by remember { mutableStateOf<Pair<List<String>, List<Int>>?>(null) }
    var subtitleDialog by remember { mutableStateOf<Pair<List<String>, List<Int>>?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    // 版本/音轨/字幕弹窗加载态：点按钮后拉取 PlaybackInfo 期间给“加载中…”反馈，之前点完无反馈
    var trackLoading by remember { mutableStateOf<String?>(null) }
    val detailScope = rememberCoroutineScope()
    // 选中的剧集索引（点击选集只选中加框，不进播放器；点播放按钮才进）
    var selectedEpIndex by remember(itemId) { mutableStateOf(0) }
    // 自动选中只做一次：首次进详情页时选中带进度的那集；切季/刷新时不再自动重选，避免覆盖用户选择
    var autoSelected by remember(itemId) { mutableStateOf(false) }

    LaunchedEffect(itemId, reloadKey) {
        try {
            error = null; item = null; epData = null
            // 并行取详情、选集，比串行快
            val dDeferred = async { Repo.itemDetailCached(itemId) }
            val epDeferred = async { Repo.episodes(itemId) }
            val d = dDeferred.await()
            item = d
            // 先显示基本信息
            isWatched = d.played
            isFav = d.isFavorite
            val epResult = epDeferred.await()
            val eps = epResult.episodes
            // episodes 接口已带 playTicks，无需额外 getResumableEpisodes
            epData = epResult
            // 1:1 安卓：重置版本/音轨/字幕选择
            selectedMediaSourceId = ""
            selectedAudioIndex = -1
            selectedSubtitleIndex = -2
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
            // 进入详情页时，自动选中当前有进度的那集（没有则第一集）；只做一次
            LaunchedEffect(eps) {
                if (!autoSelected && eps.isNotEmpty()) {
                    autoSelected = true
                    val idx = eps.indexOfFirst { it.playTicks > 0 }.takeIf { it >= 0 } ?: 0
                    selectedEpIndex = idx.coerceIn(0, eps.size - 1)
                }
            }
            LaunchedEffect(seasonIdx) {
                val data = epData ?: return@LaunchedEffect
                if (seasons.isEmpty()) return@LaunchedEffect
                if (seasonEps == null && seasonIdx == 0) return@LaunchedEffect
                val s = seasons.getOrNull(seasonIdx) ?: return@LaunchedEffect
                seasonLoading = true
                seasonError = null
                try {
                    seasonEps = Repo.seasonEpisodes(itemId, s.id)
                    // 切季重置选集索引：新季从第 1 集开始选，不沿用旧季的选中位置
                    selectedEpIndex = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 失败保留旧季数据 + 给提示，不再静默置空（旧季列表被清空、用户以为该季无集）
                    seasonError = "切换季失败：${e.message?.take(60) ?: "未知错误"}"
                }
                seasonLoading = false
            }

            Box(Modifier.fillMaxSize().background(Color.White)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 110.dp)
                ) {
                    // 海报头 640dp（1:1 安卓：标题/5圆按钮/播放/简介叠在海报上）
                    item {
                        Box(Modifier.fillMaxWidth().height(640.dp)) {
                            if (it.imageUrl != null) {
                                EmbyImage(
                                    url = YambyClient.imageUrl(it.id, "Backdrop", 1280),
                                    contentDescription = it.name,
                                    modifier = Modifier.fillMaxSize(),
                                    // 1:1 安卓：Backdrop 没有时用 Primary 海报兜底，不直接掉渐变
                                    fallbackUrl = YambyClient.imageUrl(it.id, "Primary", 1280),
                                    fallback = {
                                        val (c1, c2) = posterColors(it.hue)
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, c2))))
                                    }
                                )
                            } else {
                                val (c1, c2) = posterColors(it.hue)
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c1, c2))))
                            }
                            // 底部深色渐变（保证白色文字可读，对齐安卓）
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0.35f to Color.Transparent,
                                        0.75f to Color(0xD9000000),
                                        1f to Color(0xD9000000)
                                    )
                                )
                            )
                            // 底部淡出：海报最底部渐变为背景色（在深色渐变之上，只占最底边）
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0.85f to Color.Transparent,
                                        1f to Color(0xFFF5F6F8)
                                    )
                                )
                            )
                            // 叠加内容：标题 + 5白圆按钮 + 播放/简介行
                            Column(
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                            ) {
                                Text(
                                    it.name,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(12.dp))
                                // 5个白色圆按钮（已看/收藏/合集/音频/评论）
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    // 已看：电影管自己，剧集管未看的第一集
                                    PosterCircleBtn(
                                        DETAIL_ICON_CHECK, "已看",
                                        tint = Color.White
                                    ) {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            val prevWatched = isWatched
                                            val prevEpData = epData
                                            isWatched = !isWatched
                                            // 乐观更新本地选集数据（列表里的已看勾同步变）
                                            epData = prevEpData?.copy(
                                                episodes = prevEpData.episodes.map { ep ->
                                                    if (ep.id == tid) ep.copy(played = isWatched) else ep
                                                }
                                            )
                                            try {
                                                // Repo.setPlayed 返回 Boolean（true=服务器 2xx 确认成功），失败直接抛走回滚；
                                                // 成功时 Repo 已失效对应缓存。Emby 组已把 setPlayed 改为返回 Boolean，不再需要读回校验。
                                                if (!Repo.setPlayed(tid, isWatched)) throw Exception("服务器未生效")
                                                toastMsg = if (isWatched) "已标记为看过" else "已取消标记"
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                // 失败回滚：按钮状态 + 选集数据一起还原
                                                isWatched = prevWatched
                                                epData = prevEpData
                                                toastMsg = "标记失败，已回滚"
                                            }
                                        }
                                    }
                                    // 收藏
                                    PosterCircleBtn(
                                        DETAIL_ICON_HEART_PLUS, "收藏",
                                        tint = Color.White
                                    ) {
                                        detailScope.launch {
                                            val prevFav = isFav
                                            isFav = !isFav
                                            try {
                                                // Repo.setFavorite 返回 Boolean（true=服务器 2xx 确认成功），失败直接抛走回滚；
                                                // 成功时 Repo 已失效详情缓存。Emby 组已把 setFavorite 改为返回 Boolean，不再需要读回校验。
                                                if (!Repo.setFavorite(it.id, isFav)) throw Exception("服务器未生效")
                                                toastMsg = if (isFav) "已收藏" else "已取消收藏"
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                isFav = prevFav
                                                toastMsg = "收藏失败，已回滚"
                                            }
                                        }
                                    }
                                    // 合集→版本选择
                                    PosterCircleBtn(DETAIL_ICON_FILM, "版本", isStroke = true) {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            trackLoading = "正在加载版本…"
                                            try {
                                                val json = YambyClient.getPlaybackInfoJson(tid)
                                                val sources = json.optJSONArray("MediaSources")
                                                if (sources == null || sources.length() == 0) {
                                                    toastMsg = "无版本信息"; return@launch
                                                }
                                                val names = mutableListOf<String>()
                                                val ids = mutableListOf<String>()
                                                val seenLabels = mutableMapOf<String, Int>()
                                                for (idx in 0 until sources.length()) {
                                                    val s = sources.getJSONObject(idx)
                                                    val name = s.optString("Name", "").trim()
                                                    var desc = name
                                                    val streams = s.optJSONArray("MediaStreams")
                                                    if (streams != null) {
                                                        for (j in 0 until streams.length()) {
                                                            val st = streams.getJSONObject(j)
                                                            if (st.optString("Type") == "Video") {
                                                                val w = st.optInt("Width", 0)
                                                                val h = st.optInt("Height", 0)
                                                                // Name 为空时不拼前导空格（之前 "$name ${w}x$h" 会得到 " 1920x1080"）
                                                                if (w > 0 && h > 0) desc = if (name.isEmpty()) "${w}x$h" else "$name ${w}x$h"
                                                                break
                                                            }
                                                        }
                                                    }
                                                    if (desc.isEmpty()) desc = "版本 ${idx + 1}"
                                                    // 同名版本去重区分（Emby 常有多个同名同分辨率版本，之前会显示两条完全一样的选项）
                                                    desc = dedupePopupLabel(desc, seenLabels)
                                                    names.add(desc)
                                                    ids.add(s.optString("Id", ""))
                                                }
                                                versionDialog = names to ids
                                                trackLoading = null
                                            } catch (e: Exception) {
                                                trackLoading = null
                                                toastMsg = "获取版本失败"
                                            }
                                        }
                                    }
                                    // 音频
                                    PosterCircleBtn(DETAIL_ICON_HEADPHONES, "音频", isStroke = true) {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            trackLoading = "正在加载音轨…"
                                            try {
                                                val json = YambyClient.getPlaybackInfoJson(tid)
                                                val sources = json.optJSONArray("MediaSources")
                                                if (sources == null || sources.length() == 0) {
                                                    trackLoading = null
                                                    toastMsg = "无音轨信息"
                                                    return@launch
                                                }
                                                var srcIdx = 0
                                                if (selectedMediaSourceId.isNotEmpty()) {
                                                    for (i in 0 until sources.length()) {
                                                        if (sources.getJSONObject(i).optString("Id") == selectedMediaSourceId) {
                                                            srcIdx = i; break
                                                        }
                                                    }
                                                }
                                                val streams = sources.getJSONObject(srcIdx).optJSONArray("MediaStreams")
                                                    ?: run { trackLoading = null; toastMsg = "无音轨信息"; return@launch }
                                                val names = mutableListOf<String>()
                                                val idxs = mutableListOf<Int>()
                                                val seenLabels = mutableMapOf<String, Int>()
                                                for (i in 0 until streams.length()) {
                                                    val st = streams.getJSONObject(i)
                                                    if (st.optString("Type") == "Audio") {
                                                        val ai = st.optInt("Index", -1)
                                                        // 标签不用原始 DisplayTitle：它可能是韩文/日文原文（如“한국어”），
                                                        // 弹窗字体无对应字形会显示成“□□□”。语言码能映射时一律用中文名。
                                                        val lang = st.optString("Language", "")
                                                        val codec = st.optString("Codec", "")
                                                        val mapped = detailLangDisplayName(lang)
                                                        var label: String
                                                        if (mapped != null) {
                                                            label = mapped
                                                            if (codec.isNotEmpty()) label += " $codec"
                                                            if (st.optBoolean("IsDefault", false)) label += " [默认]"
                                                            if (st.optBoolean("IsForced", false)) label += " [Forced]"
                                                        } else {
                                                            val title = st.optString("DisplayTitle", "")
                                                            // DisplayTitle 只有全 ASCII/中文/常用标点时才直接用，否则会出方框
                                                            val clean = title.isNotEmpty() && title.all { ch ->
                                                                ch.code < 128 || ch in '\u4E00'..'\u9FFF' ||
                                                                    ch in '　'..'〿' || ch in '！'..'｟'
                                                            }
                                                            label = if (clean) title
                                                                else listOf(lang, codec).filter { s -> s.isNotEmpty() }.joinToString(" ")
                                                        }
                                                        if (label.isEmpty()) label = "音轨 ${names.size + 1}"
                                                        // 同名音轨去重区分（两条“中文 AAC”之前会显示两条完全一样的选项）
                                                        label = dedupePopupLabel(label, seenLabels)
                                                        names.add(label); idxs.add(ai)
                                                    }
                                                }
                                                if (names.isEmpty()) {
                                                    trackLoading = null
                                                    toastMsg = "无音轨信息"
                                                    return@launch
                                                }
                                                audioDialog = names to idxs
                                                trackLoading = null
                                            } catch (e: Exception) {
                                                trackLoading = null
                                                toastMsg = "获取音轨失败"
                                            }
                                        }
                                    }
                                    // 评论→字幕选择
                                    PosterCircleBtn(DETAIL_ICON_SUBTITLE_CC, "字幕", isStroke = true) {
                                        detailScope.launch {
                                            val tid = if (it.type == "Movie") it.id
                                            else eps.firstOrNull { e -> !e.played }?.id ?: eps.firstOrNull()?.id ?: it.id
                                            trackLoading = "正在加载字幕…"
                                            try {
                                                val json = YambyClient.getPlaybackInfoJson(tid)
                                                val sources = json.optJSONArray("MediaSources")
                                                if (sources == null || sources.length() == 0) {
                                                    trackLoading = null
                                                    toastMsg = "无字幕信息"
                                                    return@launch
                                                }
                                                var srcIdx = 0
                                                if (selectedMediaSourceId.isNotEmpty()) {
                                                    for (i in 0 until sources.length()) {
                                                        if (sources.getJSONObject(i).optString("Id") == selectedMediaSourceId) {
                                                            srcIdx = i; break
                                                        }
                                                    }
                                                }
                                                val streams = sources.getJSONObject(srcIdx).optJSONArray("MediaStreams")
                                                    ?: run { trackLoading = null; toastMsg = "无字幕信息"; return@launch }
                                                val names = mutableListOf("关闭字幕")
                                                val idxs = mutableListOf(-1)
                                                val seenLabels = mutableMapOf<String, Int>()
                                                for (i in 0 until streams.length()) {
                                                    val st = streams.getJSONObject(i)
                                                    if (st.optString("Type") == "Subtitle") {
                                                        val si = st.optInt("Index", -1)
                                                        // 标签不用原始 DisplayTitle：它可能是韩文/日文原文（如“한국어”），
                                                        // 弹窗字体无对应字形会显示成“□□□”。语言码能映射时一律用中文名。
                                                        val lang = st.optString("Language", "")
                                                        val mapped = detailLangDisplayName(lang)
                                                        var label: String
                                                        if (mapped != null) {
                                                            label = mapped
                                                            if (st.optBoolean("IsDefault", false)) label += " [默认]"
                                                            if (st.optBoolean("IsForced", false)) label += " [Forced]"
                                                        } else {
                                                            val title = st.optString("DisplayTitle", "")
                                                            // DisplayTitle 只有全 ASCII/中文/常用标点时才直接用，否则会出方框
                                                            val clean = title.isNotEmpty() && title.all { ch ->
                                                                ch.code < 128 || ch in '\u4E00'..'\u9FFF' ||
                                                                    ch in '　'..'〿' || ch in '！'..'｟'
                                                            }
                                                            label = if (clean) title
                                                                else listOf(lang).filter { s -> s.isNotEmpty() }.joinToString(" ")
                                                        }
                                                        if (label.isEmpty()) label = "字幕 ${names.size}"
                                                        // 同名字幕去重区分（两条“中文（繁體）”之前会显示两条完全一样的选项）
                                                        label = dedupePopupLabel(label, seenLabels)
                                                        names.add(label); idxs.add(si)
                                                    }
                                                }
                                                if (names.size <= 1) {
                                                    trackLoading = null
                                                    toastMsg = "无字幕信息"
                                                    return@launch
                                                }
                                                subtitleDialog = names to idxs
                                                trackLoading = null
                                            } catch (e: Exception) {
                                                trackLoading = null
                                                toastMsg = "获取字幕失败"
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                // 播放按钮 + 类型/简介
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier.width(200.dp).height(52.dp)
                                                .shadow(4.dp, RoundedCornerShape(26.dp))
                                                .clip(RoundedCornerShape(26.dp))
                                                .background(Color.White)
                                                .clickable {
                                                    // 播选中的那集，带上版本/音轨/字幕选择
                                                    val target = eps.getOrNull(selectedEpIndex)
                                                        ?: eps.firstOrNull() ?: return@clickable
                                                    val curItem = item ?: return@clickable
                                                    onPlay(
                                                        curItem,
                                                        target,
                                                        PlaybackSelection(
                                                            mediaSourceId = selectedMediaSourceId,
                                                            audioIndex = selectedAudioIndex,
                                                            subtitleIndex = selectedSubtitleIndex
                                                        )
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            // 用选中的那集来显示按钮进度
                                            val progEp = eps.getOrNull(selectedEpIndex)
                                            val p = if (progEp != null && progEp.runTicks > 0 && progEp.playTicks > 0) {
                                                (progEp.playTicks.toFloat() / progEp.runTicks.toFloat()).coerceIn(0f, 1f)
                                            } else it.progress
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
                                        // 播放按钮下方显示将要播放的是第几集
                                        val selEp = eps.getOrNull(selectedEpIndex)
                                        if (selEp != null && eps.size > 1) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "第${selEp.index}集",
                                                fontSize = 12.sp,
                                                color = Color(0xE6FFFFFF)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            listOfNotNull(
                                                it.genres.joinToString(" · ").takeIf { s -> s.isNotEmpty() },
                                                it.year.takeIf { s -> s.isNotEmpty() },
                                                it.rating?.let { r -> "★$r" }
                                            ).joinToString(" · "),
                                            fontSize = 13.sp,
                                            color = Color(0xE6FFFFFF),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (it.overview.isNotEmpty()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                it.overview,
                                                fontSize = 13.sp,
                                                color = Color(0xB3FFFFFF),
                                                lineHeight = 18.sp,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.clickable { showFullOverview = true }
                                            )
                                        }
                                    }
                                }
                            }
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
                        if (seasonError != null) {
                            Text(
                                seasonError!!,
                                fontSize = 13.sp,
                                color = Color(0xFFE53935),
                                modifier = Modifier.padding(start = 20.dp, top = 8.dp)
                            )
                        }
                        if (seasonLoading) {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = GomeTheme.Accent)
                            }
                        } else if (eps.isNotEmpty()) {
                            ArrowScrollRow(
                                itemCount = eps.size,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                initialCenterIndex = selectedEpIndex,
                                centerIndex = selectedEpIndex
                            ) { idx ->
                                val ep = eps[idx]
                                EpisodeThumbCard(
                                    ep = ep,
                                    // "当前集"用外部传入的 currentEpisodeId 判断，不要用 playTicks>0 猜
                                    // （有进度 ≠ 正在播，之前几乎所有有进度的集都显示"当前"蓝条）
                                    isCurrent = currentEpisodeId != null && ep.id == currentEpisodeId,
                                    isSelected = idx == selectedEpIndex,
                                    onClick = {
                                        // 点击选集只选中加框，不进播放器
                                        selectedEpIndex = idx
                                    }
                                )
                            }
                        } else {
                            // 选集为空：之前无任何提示、无 else 分支
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无剧集", fontSize = 14.sp, color = Color(0xFF8A8A8A))
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
                                items(it.people, key = { person -> person.id }) { person ->
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
                                        // 演职人员显示 role（之前只显示名字，角色信息丢了）
                                        if (person.role.isNotBlank()) {
                                            Text(
                                                person.role,
                                                fontSize = 10.sp,
                                                color = GomeTheme.TextSecondary,
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
                }
                // 顶部按钮已统一为透明悬浮标题栏（Main.GomeApp 叠加）：返回走标题栏返回箭头
                // 简介全文弹窗
                if (showFullOverview) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showFullOverview = false }) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(20.dp)
                        ) {
                            // 全文弹窗可滚动：之前长简介超出屏幕看不到，也滚不动
                            Box(
                                Modifier.heightIn(max = 480.dp)
                                    .verticalScroll(rememberScrollState())
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
                }
                // 版本选择弹窗（1:1 安卓 showVersionDialog）
                versionDialog?.let { (names, ids) ->
                    // 未选择时 checked=-1（不预勾第一项）；旧代码 coerceAtLeast(0) 会把未选择显示成选中第一项
                    val checked = ids.indexOf(selectedMediaSourceId)
                    DetailSingleChoiceDialog(
                        title = "选择版本", names = names, checkedIndex = checked,
                        onSelect = { which ->
                            selectedMediaSourceId = ids[which]
                            selectedAudioIndex = -1
                            selectedSubtitleIndex = -2
                            toastMsg = "已选: ${names[which]}"
                            versionDialog = null
                        },
                        onDismiss = { versionDialog = null }
                    )
                }
                // 音轨选择弹窗（1:1 安卓 showAudioDialog）
                audioDialog?.let { (names, _) ->
                    // 播放器按"同类音轨中的序号"选轨，这里传序号（which），不传 Emby 流 Index
                    // （流 Index 是全文件流编号，与 mpv track-list 里的同类序号对不上）
                    val checked = selectedAudioIndex
                    DetailSingleChoiceDialog(
                        title = "选择音轨", names = names, checkedIndex = checked,
                        onSelect = { which ->
                            selectedAudioIndex = which
                            toastMsg = "已选: ${names[which]}"
                            audioDialog = null
                        },
                        onDismiss = { audioDialog = null }
                    )
                }
                // 字幕选择弹窗（1:1 安卓 showSubtitleDialog）
                subtitleDialog?.let { (names, _) ->
                    // names[0]="关闭字幕"；其余按同类字幕序号映射：which-1（-1=关闭，-2=未选）
                    val checked = when (selectedSubtitleIndex) {
                        -2 -> -1
                        -1 -> 0
                        else -> selectedSubtitleIndex + 1
                    }
                    DetailSingleChoiceDialog(
                        title = "选择字幕", names = names, checkedIndex = checked,
                        onSelect = { which ->
                            selectedSubtitleIndex = if (which == 0) -1 else which - 1
                            toastMsg = "已选: ${names[which]}"
                            subtitleDialog = null
                        },
                        onDismiss = { subtitleDialog = null }
                    )
                }
                // 轨道加载提示（版本/音轨/字幕拉取中）
                if (trackLoading != null) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(trackLoading!!, fontSize = 14.sp, color = Color.White)
                    }
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
