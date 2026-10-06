package com.muse.gomepc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** 左侧边栏（代替手机底部 dock，M玻璃材质） */
@Composable
fun Sidebar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        Triple("首页", NavKind.HOME, Screen.Home as Screen),
        Triple("宫格", NavKind.GRID, Screen.Grid as Screen),
        Triple("搜索", NavKind.SEARCH, Screen.Search as Screen),
        Triple("设置", NavKind.SETTINGS, Screen.Settings as Screen),
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(96.dp)
            .padding(12.dp)
    ) {
        MGlassBox(modifier = Modifier.fillMaxSize(), corner = 20.dp) {
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Gome 字标
                Text(
                    "Gome",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = GomeTheme.TextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                tabs.forEach { (label, kind, screen) ->
                    val selected = current::class == screen::class
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color(0x33000000) else Color.Transparent)
                            .clickable { onSelect(screen) }
                            .padding(vertical = 10.dp, horizontal = 16.dp)
                    ) {
                        NavIcon(
                            kind,
                            tint = if (selected) GomeTheme.Accent else Color(0xFF555555)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (selected) GomeTheme.Accent else Color(0xFF555555),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
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

/** 首页宫格卡片（参考 item_grid_poster：海报 + 剧名 + 年份） */
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
            .padding(6.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            PosterImage(
                item,
                modifier = Modifier.fillMaxSize()
            )
            // 继续观看进度条
            if (item.progress != null && item.progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth(item.progress)
                        .height(4.dp)
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
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            item.year,
            fontSize = 11.sp,
            color = GomeTheme.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 首页：继续观看 + 各媒体库横排（参考 MainFragment 横屏版） */
@Composable
fun HomeScreen(onItemClick: (UiMediaItem) -> Unit) {
    var libs by remember { mutableStateOf<List<UiLibrary>?>(null) }
    var libItems by remember { mutableStateOf<Map<String, List<UiMediaItem>>>(emptyMap()) }
    var resume by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        try {
            error = null
            libs = null; resume = null; libItems = emptyMap()
            val l = Repo.libraries()
            libs = l
            resume = Repo.resumeItems()
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
            contentPadding = PaddingValues(20.dp),
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
            if (resume!!.isNotEmpty()) {
                item {
                    SectionHeader("继续观看", "更多")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(resume!!) { item ->
                            ItemCard(item, onClick = { onItemClick(item) }, modifier = Modifier.width(130.dp))
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
                                ItemCard(item, onClick = { onItemClick(item) }, modifier = Modifier.width(140.dp))
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

/** 宫格：全部条目网格 */
@Composable
fun GridScreen(onItemClick: (UiMediaItem) -> Unit) {
    var all by remember { mutableStateOf<List<UiMediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        try {
            error = null; all = null
            val libs = Repo.libraries()
            all = libs.flatMap {
                try { Repo.items(it.id, 20) } catch (_: Exception) { emptyList() }
            }
        } catch (e: Exception) {
            error = e.message ?: "未知错误"
        }
    }

    when {
        error != null -> ErrorBox(error!!, onRetry = { reloadKey++ })
        all == null -> LoadingBox()
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().background(GomeTheme.Bg),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(all!!) { item ->
                ItemCard(item, onClick = { onItemClick(item) })
            }
        }
    }
}

/** 搜索 */
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

    Column(Modifier.fillMaxSize().background(GomeTheme.Bg).padding(20.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索剧名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(
            onClick = { searchKey++ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("搜索")
        }
        Spacer(Modifier.height(8.dp))
        when {
            searching -> LoadingBox(Modifier.weight(1f))
            results == null -> Text("输入关键词搜索", fontSize = 13.sp, color = GomeTheme.TextSecondary)
            else -> {
                Text("找到 ${results!!.size} 个结果", fontSize = 13.sp, color = GomeTheme.TextSecondary)
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
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

/** 设置 */
@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {},
    onToggleDemo: (Boolean) -> Unit = {}
) {
    var danmakuOn by remember { mutableStateOf(true) }
    var hwdecOn by remember { mutableStateOf(true) }
    LazyColumn(
        Modifier.fillMaxSize().background(GomeTheme.Bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary) }
        item {
            MGlassBox(Modifier.fillMaxWidth(), corner = 16.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("播放", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
                    SettingRow("弹幕", danmakuOn) { danmakuOn = it }
                    SettingRow("硬件解码", hwdecOn) { hwdecOn = it }
                }
            }
        }
        item {
            MGlassBox(Modifier.fillMaxWidth(), corner = 16.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务器", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
                    if (Repo.demoMode) {
                        Text("当前：演示模式（Mock 数据）", fontSize = 13.sp, color = GomeTheme.TextSecondary)
                    } else {
                        Text(
                            "当前：${com.muse.gomepc.emby.Prefs.serverName.ifBlank { com.muse.gomepc.emby.Prefs.baseUrl() }}",
                            fontSize = 13.sp,
                            color = GomeTheme.TextSecondary
                        )
                        Text(
                            "用户：${com.muse.gomepc.emby.Prefs.username}",
                            fontSize = 13.sp,
                            color = GomeTheme.TextSecondary
                        )
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            if (Repo.demoMode) {
                                onToggleDemo(false)
                            } else {
                                com.muse.gomepc.emby.Prefs.clearLogin()
                                onLogout()
                            }
                        }
                    ) {
                        Text(
                            if (Repo.demoMode) "退出演示模式" else "退出登录",
                            color = Color(0xFFE53935),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        item {
            MGlassBox(Modifier.fillMaxWidth(), corner = 16.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("关于", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GomeTheme.TextPrimary)
                    Text("Gome PC 1.0.0（杜比视界兼容版）", fontSize = 13.sp, color = GomeTheme.TextSecondary)
                    Text("播放核心：libmpv + libplacebo", fontSize = 13.sp, color = GomeTheme.TextSecondary)
                }
            }
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

            LazyColumn(Modifier.fillMaxSize().background(GomeTheme.Bg)) {
                // 海报头
                item {
                    Box(Modifier.fillMaxWidth().height(300.dp)) {
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
                            Modifier.align(Alignment.BottomStart).padding(20.dp)
                        ) {
                            Text(it.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    it.year.takeIf { s -> s.isNotEmpty() },
                                    it.libName.takeIf { s -> s.isNotEmpty() },
                                    it.rating?.let { r -> "★$r" }
                                ).joinToString(" · "),
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.85f)
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
