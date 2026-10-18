package com.muse.gomepc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.muse.gomepc.emby.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图标选择器（桌面版，对齐安卓 IconPickerDialog）。
 * 从图标库搜索选择图标，手动指定给服务器。选空（"清除"）则恢复自动匹配。
 */
@Composable
fun IconPickerDialog(
    serverKey: String,
    onDismiss: () -> Unit,
    onPicked: (url: String) -> Unit
) {
    var allIcons by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { ServerIconHelper.getIconList() }
        allIcons = list
        loading = false
    }

    val filtered = remember(allIcons, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allIcons
        else allIcons.filter { it.first.lowercase().contains(q) }
    }

    Dialog(onDismissRequest = onDismiss) {
        MGlassBox(
            modifier = Modifier.size(width = 480.dp, height = 560.dp),
            corner = 16.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "选择服务器图标",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = GomeTheme.TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索图标名称…", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (loading) "加载中…" else "共 ${filtered.size} 个图标",
                    fontSize = 12.sp,
                    color = GomeTheme.TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.second }) { (name, url) ->
                        IconCell(
                            name = name,
                            url = url,
                            onClick = {
                                try {
                                    Prefs.setCustomIconUrl(serverKey, url)
                                } catch (_: Exception) {}
                                onPicked(url)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "清除自定义",
                    fontSize = 14.sp,
                    color = androidx.compose.ui.graphics.Color(0xFFFF3B30),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable {
                            try {
                                Prefs.setCustomIconUrl(serverKey, "")
                            } catch (_: Exception) {}
                            onPicked("")
                        }
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun IconCell(
    name: String,
    url: String,
    onClick: () -> Unit
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            ServerIconHelper.loadUrl(url, url)
        }
    }
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = name,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Text(
            name,
            fontSize = 10.sp,
            color = GomeTheme.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
