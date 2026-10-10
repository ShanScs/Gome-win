package com.muse.gomepc.danmaku

import com.muse.gomepc.emby.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.random.Random

/**
* 弹幕引擎：纯 Kotlin，与 UI 无关。
* 逻辑从 Android DanmakuView 移植（数据结构/行数/滚动/发射/样式/API/导入）。
* 绘制由 Compose DanmakuOverlay 负责，通过 measureText 回调做文本宽度测量。
*/
class DanmakuEngine {

data class Danmaku(
val text: String,
var x: Float,
val row: Int,
val speed: Float,
val bornAt: Long,
/** 文本宽度缓存（px），-1 表示未测量 */
var textWidth: Float = -1f
)

// ---- 样式设置（与 Android API 一致） ----
var fontSp: Int = 20
private set
var strokePx: Float = 0f
private set
var opacityPct: Int = 100
private set

// ---- 行为设置 ----
var speedFactor: Float = 1f
private set
var delaySec: Float = 0f
private set
var areaRatio: Float = 0.35f
private set
/** 弹幕位置：0=顶部（贴顶细条），1=半屏（上半屏），2=全屏 */
var position: Int = 0
private set
var enabled: Boolean = true
private set

// ---- 运行状态 ----
var rows: Int = 5
private set
val items = mutableListOf<Danmaku>()
/** 弹幕池：默认为空，仅 API/导入/搜索填充 */
val pool = mutableListOf<String>()

/** 视图尺寸（px），由 UI 层通过 onSizeChanged 设置 */
var viewWidth: Float = 0f
var viewHeight: Float = 0f

/** 透明度（0~1），由 opacityPct 换算 */
val alpha: Float get() = (opacityPct / 100f).coerceIn(0.1f, 1f)

/** 错误回调（UI 线程） */
var onError: ((String) -> Unit)? = null

// ============ API（与 Android 版保持一致） ============

/** 应用全部弹幕样式设置 */
fun applyStyle(fontSp: Int, strokePx: Float, opacityPct: Int) {
this.fontSp = fontSp
this.strokePx = strokePx
this.opacityPct = opacityPct.coerceIn(10, 100)
invalidateWidthCache()
// fontSizePx 需要 density，调用方在 UI 就绪后调 recalcRowsPx
}

/** 直接设置弹幕池（桌面测试/外部数据源用） */
fun setDanmakuList(list: List<String>) {
pool.clear()
pool.addAll(list.take(2000))
items.clear()
}

fun setSpeedFactor(f: Float) { speedFactor = f.coerceIn(0.5f, 2f)}
fun setDelaySec(s: Float) { delaySec = s.coerceIn(0f, 10f)}

/** 设置弹幕区域比例（0.05~1.0），立即重算行数并清空重排 */
fun setAreaRatio(ratio: Float) {
areaRatio = ratio.coerceIn(0.05f, 1f)
items.clear()
}

fun setPosition(p: Int) {
position = p.coerceIn(0, 2)
items.clear()
invalidateWidthCache()
}

fun setEnabled(on: Boolean) {
enabled = on
if (!on) {
items.clear()
}
}

fun isDanmakuOn() = enabled

fun clear() {
items.clear()
}

/** 字号变化后失效宽度缓存（UI 层调完 applyStyle 后调） */
fun invalidateWidthCache() {
for (d in items) d.textWidth = -1f
}

// ============ 行数/位置计算（与 Android 一致） ============

/** 有效区域比例：半屏 50%，全屏 100%；顶部用固定3排 */
private fun effectiveAreaRatio(): Float {
return when (position) {
1 -> 0.5f
2 -> 1.0f
else -> 0.2f
}
}

/** 固定排数：顶部3排，半屏5排，全屏按高度算（-1 表示按高度计算） */
private fun fixedRows(): Int {
return when (position) {
0 -> 3
1 -> 5
else -> -1
}
}

/** UI 层在尺寸或字号变化后调用 */
fun recalcRows(fontSizePx: Float) {
val fixed = fixedRows()
rows = if (fixed > 0) {
fixed
} else if (viewHeight > 0 && fontSizePx > 0) {
val rowPx = fontSizePx * 1.6f // 每行高度 = 字号*1.6
max(1, ((viewHeight * effectiveAreaRatio()) / rowPx).toInt())
} else {
rows
}
}

/**
* 某行的文本基线 Y（px），与 Android rowHeight() 同公式。
* Compose 绘制时需换算：top = baseline - firstBaseline。
*/
fun rowBaseline(row: Int, fontSizePx: Float): Float {
val rowPx = fontSizePx * 1.6f
// 半屏：5排均匀分布在上半屏
if (position == 1 && rows == 5 && viewHeight > 0) {
val halfH = viewHeight * 0.5f
val spacing = halfH / 5f
return (row + 0.8f) * spacing.coerceAtLeast(rowPx)
}
return (row + 0.8f) * rowPx
}

// ============ 动画步进（与 Android step() 一致） ============

/**
* 推进一帧。
* @param dt 秒（建议钳制到 0.1 以内，避免切后台后跳变）
* @param measureText 文本宽度测量（px），由 UI 层提供（需与绘制同字号）
*/
fun step(dt: Float, measureText: (String) -> Float) {
val w = viewWidth
if (w <= 0) return
// 移动现有弹幕
val iter = items.iterator()
while (iter.hasNext()) {
val d = iter.next()
if (d.textWidth < 0) d.textWidth = measureText(d.text)
d.x -= d.speed * speedFactor * dt
if (d.x + d.textWidth < 0) iter.remove()
}
// 发射新弹幕（无数据源时 pool 为空，不显示任何弹幕）
val maxItems = when (position) { 0 -> 40; 1 -> 60; else -> 80}
val emitProb = when (position) { 0 -> 0.5f; 1 -> 0.4f; else -> 0.25f}
if (enabled && pool.isNotEmpty() && items.size < maxItems && Random.nextFloat() < emitProb) {
val row = Random.nextInt(max(1, rows))
// 检查该行最右弹幕位置，避免重叠
val rightmost = items.filter { it.row == row}.maxOfOrNull {
if (it.textWidth < 0) it.textWidth = measureText(it.text)
it.x + it.textWidth
}?: -1f
if (rightmost < w - 100) {
val text = pool.random()
val speed = 180f + Random.nextFloat() * 120f
val tw = measureText(text)
items.add(Danmaku(text, w + delaySec * speed, row, speed, System.currentTimeMillis(), tw))
}
}
}

// ============ 数据源（从 Android 移植） ============

/**
* 从 API 拉取弹幕（DanDanPlay 兼容）。
* @param url API base，如 https://dm.nnn.xx.kg
* @param title 视频标题
*/
fun loadFromApi(url: String, title: String = "") {
if (url.isBlank()) return
Thread {
doLoadFromApi(url.trim().trimEnd('/'), title) { postError(it) }
}.start()
}

/** 多源弹幕：按顺序尝试各 API，任一成功即停；全部失败才回调一次错误（1:1 Android） */
fun loadFromApiMulti(urls: List<String>, title: String = "") {
val list = urls.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }
if (list.isEmpty()) return
if (list.size == 1) {
loadFromApi(list[0], title)
return
}
Thread {
var lastErr = ""
for (base in list) {
if (doLoadFromApi(base, title) { lastErr = it }) return@Thread
}
postError(if (lastErr.isNotEmpty()) lastErr else "所有弹幕源均无结果")
}.start()
}

/**
* 单源拉取核心逻辑（供 loadFromApi / loadFromApiMulti 共用）。
* @return true=成功取到弹幕并填入 pool
*/
private fun doLoadFromApi(base: String, title: String, onErr: (String) -> Unit): Boolean {
try {
val cleanTitle = cleanTitleForSearch(title)
Log.d("Danmaku", "弹幕API base: $base, 清洗后: $cleanTitle")
val client = OkHttpClient.Builder()
.proxy(Proxy.NO_PROXY)
.connectTimeout(30, TimeUnit.SECONDS)
.readTimeout(30, TimeUnit.SECONDS)
.build()

var animeId = ""
for (q in listOf(cleanTitle, title).distinct().filter { it.isNotBlank()}) {
val searchUrl = "$base/api/v2/search/anime?keyword=${URLEncoder.encode(q, "UTF-8")}"
val searchResp = client.newCall(Request.Builder().url(searchUrl).build()).execute()
val searchBody = searchResp.body?.string()?: ""
searchResp.close()
animeId = extractAnimeId(searchBody)
if (animeId.isNotEmpty()) break
}
if (animeId.isEmpty()) {
Log.w("Danmaku", "未搜到番剧: $title")
onErr("未搜到番剧: $title")
return false
}

val bangumiUrl = "$base/api/v2/bangumi/$animeId"
val bangumiResp = client.newCall(Request.Builder().url(bangumiUrl).build()).execute()
val bangumiBody = bangumiResp.body?.string()?: ""
bangumiResp.close()
val episodeId = extractEpisodeId(bangumiBody)
if (episodeId.isEmpty()) {
onErr("未取到分集")
return false
}

val commentUrl = "$base/api/v2/comment/$episodeId?withRelated=true"
val commentResp = client.newCall(Request.Builder().url(commentUrl).build()).execute()
val commentBody = commentResp.body?.string()?: ""
commentResp.close()
val list = parseDandanComments(commentBody)
Log.d("Danmaku", "解析到 ${list.size} 条弹幕")
if (list.isNotEmpty()) {
pool.clear()
pool.addAll(list)
return true
} else {
onErr("弹幕为空")
return false
}
} catch (e: Exception) {
Log.w("Danmaku", "API失败: ${e.message}")
onErr("API失败: ${e.message}")
return false
}
}

private fun postError(msg: String) {
// Android 用 View.post 切主线程；桌面用 Swing EDT
try {
SwingUtilities.invokeLater { onError?.invoke(msg)}
} catch (_: Exception) {
onError?.invoke(msg)
}
}

/** 清洗标题用于搜索：去掉年份、季、集数、特殊符号 */
private fun cleanTitleForSearch(title: String): String {
var t = title
t = t.replace(Regex("[\\(\\[）]"), "")
t = t.replace(Regex("(?i)\\s*S\\d+"), "")
t = t.replace(Regex("第[一二三四五六七八九十\\d]+季"), "")
t = t.replace(Regex("第[\\d一二三四五六七八九十]+集"), "")
t = t.replace(Regex("(?i)\\s*E\\d+"), "")
t = t.replace(Regex("\\s+"), " ").trim()
return t
}

private fun extractAnimeId(json: String): String {
return try {
Regex("\"animeId\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?: ""
} catch (_: Exception) { ""}
}

private fun extractEpisodeId(json: String): String {
return try {
Regex("\"episodeId\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?: ""
} catch (_: Exception) { ""}
}

/** 解析 DanDanPlay 格式弹幕 */
private fun parseDandanComments(json: String): List<String> {
val out = mutableListOf<String>()
try {
val re = Regex("\"m\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
re.findAll(json).forEach { m ->
val s = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
if (s.isNotEmpty()) out.add(s)
}
} catch (_: Exception) {}
return out.take(1000)
}

/** 从本地文件内容导入弹幕：支持 B站 XML 或 JSON 数组；返回导入条数 */
fun importFromText(content: String): Int {
val list = mutableListOf<String>()
val t = content.trim()
try {
if (t.startsWith("<")) {
val re = Regex("<d[^>]*>([^<]+)</d>")
re.findAll(t).forEach { m ->
val s = m.groupValues[1].trim()
if (s.isNotEmpty()) list.add(s)
}
} else {
list.addAll(parseDanmakuJson(t))
}
} catch (_: Exception) {}
val result = list.take(2000)
if (result.isNotEmpty()) {
pool.clear()
pool.addAll(result)
items.clear()
}
return result.size
}

fun danmakuCount(): Int = pool.size

private fun parseDanmakuJson(json: String): List<String> {
val out = mutableListOf<String>()
try {
val t = json.trim()
if (!t.startsWith("[")) return out
val objRe = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
objRe.findAll(t).forEach { out.add(it.groupValues[1].replace("\\\"", "\""))}
if (out.isEmpty()) {
val strRe = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
strRe.findAll(t).forEach { out.add(it.groupValues[1].replace("\\\"", "\""))}
}
} catch (_: Exception) {}
return out.take(500)
}
}
