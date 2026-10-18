package com.muse.gomepc.danmaku

import com.muse.gomepc.emby.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
* 弹幕引擎：纯 Kotlin，与 UI 无关。
* 逻辑从 Android DanmakuView 移植（数据结构/行数/滚动/发射/样式/API/导入）。
* 绘制由 Compose DanmakuOverlay / AWT AwtDanmakuPanel 负责，通过 measureText 回调做文本宽度测量。
*/
class DanmakuEngine {

/** 弹幕池条目：timeSec < 0 表示无时间信息（旧 String 池 / 纯文本导入）。 */
data class DanmakuItem(
val text: String,
val timeSec: Float = -1f,
val mode: Int = 1,
val color: Int = 0xFFFFFF
)

data class Danmaku(
val text: String,
var x: Float,
val row: Int,
val speed: Float,
val bornAt: Long,
/** 文本宽度缓存（px），-1 表示未测量 */
var textWidth: Float = -1f,
/** 可见时间戳（ms，delaySec 延迟后才可见）；0 表示立即见 */
var visibleAt: Long = 0L
)

private data class AnimeCandidate(val animeId: String, val title: String)
private data class EpisodeInfo(val episodeId: String, val number: Int?)

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
/** P1-D5：线程安全，读写可跨线程（加载线程写 / 绘制线程读）。 */
val items = CopyOnWriteArrayList<Danmaku>()
/** 弹幕池：带时间/模式/颜色；默认为空，仅 API/导入/搜索填充 */
val pool = CopyOnWriteArrayList<DanmakuItem>()

/** 当前播放进度（秒），UI 层在 onTimePos 回调里调 setCurrentTime 传入。 */
@Volatile
var currentTimeSec: Float = 0f
private set

/** 已按时间窗口发射过的 pool 下标，避免同一条在窗口内重复发射。 */
private val emittedIndices: MutableSet<Int> =
Collections.synchronizedSet(mutableSetOf())

/** P0-D?/Bug 5：加载代次，只接受最新一次加载的结果，旧集慢请求直接丢弃。 */
private val loadGeneration = AtomicInteger(0)
private var lastFontSizePx: Float = 0f

/** 视图尺寸（px），由 UI 层通过 onSizeChanged 设置；变化时重算行数。 */
var viewWidth: Float = 0f
set(v) {
field = v
if (v > 0 && lastFontSizePx > 0) recalcRows(lastFontSizePx)
}
var viewHeight: Float = 0f
set(v) {
field = v
if (v > 0 && lastFontSizePx > 0) recalcRows(lastFontSizePx)
}

/** 透明度（0~1），由 opacityPct 换算 */
val alpha: Float get() = (opacityPct / 100f).coerceIn(0.1f, 1f)

/** 错误回调（UI 线程） */
var onError: ((String) -> Unit)? = null

companion object {
/** P1-D7：共享单例 OkHttpClient，不设 proxy，走 OkHttp 默认系统代理。 */
private val sharedClient: OkHttpClient by lazy {
OkHttpClient.Builder()
.connectTimeout(10, TimeUnit.SECONDS)
.readTimeout(10, TimeUnit.SECONDS)
.build()
}
}

// ============ API（与 Android 版保持一致） ============

/** 应用全部弹幕样式设置 */
fun applyStyle(fontSp: Int, strokePx: Float, opacityPct: Int) {
this.fontSp = fontSp
this.strokePx = strokePx
this.opacityPct = opacityPct.coerceIn(10, 100)
invalidateWidthCache()
// P1-D1：样式变化后重算行数（字号变了行高也变）。
recalcRows(fontSp * 1.5f)
}

/** 直接设置弹幕池（桌面测试/外部数据源用，无时间信息）。 */
fun setDanmakuList(list: List<String>) {
setDanmakuItems(list.map { DanmakuItem(it, -1f)})
}

/** 直接设置带时间的弹幕池。 */
fun setDanmakuItems(list: List<DanmakuItem>) {
// 新池子使在途旧加载失效，避免旧结果覆盖手动设置的池子。
loadGeneration.incrementAndGet()
pool.clear()
pool.addAll(list.take(2000))
emittedIndices.clear()
items.clear()
}

fun setSpeedFactor(f: Float) { speedFactor = f.coerceIn(0.5f, 2f)}
fun setDelaySec(s: Float) { delaySec = s.coerceIn(0f, 10f)}

/** 设置弹幕区域比例（0.05~1.0），立即重算行数并清空重排 */
fun setAreaRatio(ratio: Float) {
areaRatio = ratio.coerceIn(0.05f, 1f)
recalcRows(lastFontSizePx)
items.clear()
}

fun setPosition(p: Int) {
position = p.coerceIn(0, 2)
recalcRows(lastFontSizePx)
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

/** P0-D1：UI 组在 onTimePos 回调里调用，传入 mpv timePos（秒）。 */
fun setCurrentTime(sec: Float) {
if (!sec.isFinite() || sec < 0f) return
// 往回跳超过 1s：视为 Seek/切集，已发射集合失效，旧弹幕不再补发。
if (sec < currentTimeSec - 1f) emittedIndices.clear()
currentTimeSec = sec
}

/** P0-D1：Seek 时清屏重算：清空在屏弹幕并重置已发射集合。 */
fun onSeek() {
items.clear()
emittedIndices.clear()
}

/** Seek 时清屏并同步进度。 */
fun onSeek(sec: Float) {
currentTimeSec = sec.coerceAtLeast(0f)
onSeek()
}

/** 字号变化后失效宽度缓存（UI 层调完 applyStyle 后调） */
fun invalidateWidthCache() {
for (d in items) d.textWidth = -1f
}

// ============ 行数/位置计算（与 Android 一致） ============

/**
* P1-D2：areaRatio 实际生效。默认 areaRatio=0.35 时与旧默认值一致
* （顶部 0.2 / 半屏 0.5 / 全屏 1.0），用户调小 areaRatio 则按比例缩小有效区。
*/
private fun effectiveAreaRatio(): Float {
return when (position) {
0 -> (areaRatio / 0.35f * 0.2f).coerceIn(0.05f, 0.2f)
1 -> (areaRatio / 0.35f * 0.5f).coerceIn(0.05f, 0.5f)
else -> (areaRatio / 0.35f).coerceIn(0.05f, 1f)
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
if (fontSizePx > 0) lastFontSizePx = fontSizePx
val size = if (fontSizePx > 0) fontSizePx else lastFontSizePx
val fixed = fixedRows()
if (size <= 0 || viewHeight <= 0) {
if (fixed > 0) rows = fixed
return
}
rows = if (fixed > 0) {
// 固定排数按 areaRatio 比例缩放，默认值下与旧排数一致。
val base = if (position == 0) 0.2f else 0.5f
val scale = (effectiveAreaRatio() / base).coerceIn(0.2f, 2f)
max(1, (fixed * scale).roundToInt())
} else {
val rowPx = size * 1.6f // 每行高度 = 字号*1.6
max(1, ((viewHeight * effectiveAreaRatio()) / rowPx).toInt())
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
// P1-D11：dt 钳制到 [0, 0.1]。
val dtC = dt.coerceIn(0f, 0.1f)
val w = viewWidth
if (w <= 0) return
val now = System.currentTimeMillis()
// 移动现有弹幕（延迟未到可见时间的弹幕不移动；CopyOnWrite 迭代器不支持 remove，用快照 + removeIf）。
val snapshot = items.toList()
for (d in snapshot) {
if (d.textWidth < 0) d.textWidth = measureText(d.text)
if (now < d.visibleAt) continue
d.x -= d.speed * speedFactor * dtC
}
items.removeIf { it.textWidth >= 0 && it.x + it.textWidth < 0}
// 发射新弹幕（无数据源时 pool 为空，不显示任何弹幕）
// P1-D10：延迟期间的弹幕不计入 maxItems（只数已可见的）。
val visibleCount = items.count { now >= it.visibleAt}
val maxItems = when (position) { 0 -> 40; 1 -> 60; else -> 80}
if (!enabled || pool.isEmpty() || visibleCount >= maxItems) return
val poolSnapshot = pool.toList()
val hasTimed = poolSnapshot.any { it.timeSec >= 0f}
if (hasTimed) {
// P0-D1：只发射时间窗口内的弹幕（当前进度 -1s ~ +0.5s，容忍帧抖动）。
val lo = currentTimeSec - 1f
val hi = currentTimeSec + 0.5f
val candidateIdx = poolSnapshot.indices.firstOrNull { idx ->
idx!in emittedIndices &&
poolSnapshot[idx].timeSec >= lo && poolSnapshot[idx].timeSec <= hi
}?: return
val item = poolSnapshot[candidateIdx]
emittedIndices.add(candidateIdx)
emit(item.text, w, measureText, now)
} else {
// 无时间信息池子：保留旧随机发射行为。
val emitProb = when (position) { 0 -> 0.5f; 1 -> 0.4f; else -> 0.25f}
if (Random.nextFloat() < emitProb) {
emit(poolSnapshot.random().text, w, measureText, now)
}
}
}

/** 发射一条弹幕：做行重叠检查，delaySec 用 visibleAt 时间戳（出生点不再右移）。 */
private fun emit(text: String, w: Float, measureText: (String) -> Float, now: Long) {
val row = Random.nextInt(max(1, rows))
// 检查该行最右弹幕位置，避免重叠（未到可见时间的弹幕也占行，避免延迟期内堆叠）。
val rightmost = items.filter { it.row == row}.maxOfOrNull {
if (it.textWidth < 0) it.textWidth = measureText(it.text)
it.x + it.textWidth
}?: -1f
if (rightmost < w - 100) {
val speed = 180f + Random.nextFloat() * 120f
val tw = measureText(text)
// P1-D10：delaySec 只推迟可见时间，不右移出生点、不占 maxItems 名额。
val visibleAt = now + (delaySec * 1000f).toLong()
items.add(Danmaku(text, w, row, speed, now, tw, visibleAt))
}
}

// ============ 数据源（从 Android 移植） ============

/**
* 从 API 拉取弹幕（DanDanPlay 兼容）。
* @param url API base，如 https://dm.nnn.xx.kg
* @param title 视频标题
* @param episodeIndex 当前集编号（0 起），按它从 bangumi episodes[] 选 episodeId
*/
fun loadFromApi(url: String, title: String = "", episodeIndex: Int = 0) {
if (url.isBlank()) return
val gen = loadGeneration.incrementAndGet()
val winner = AtomicBoolean(false)
Thread {
doLoadFromApi(url.trim().trimEnd('/'), title, episodeIndex, gen, winner) { postError(it)}
}.start()
}

/**
* 多源弹幕：并行尝试各 API，任一成功即停；全部失败才回调一次错误。
* 同一代次内首个成功者用 compareAndSet 抢写入权（P1-D4），其余结果丢弃。
*/
fun loadFromApiMulti(urls: List<String>, title: String = "", episodeIndex: Int = 0) {
val list = urls.map { it.trim().trimEnd('/')}.filter { it.isNotBlank()}
if (list.isEmpty()) return
if (list.size == 1) {
loadFromApi(list[0], title, episodeIndex)
return
}
val gen = loadGeneration.incrementAndGet()
val winner = AtomicBoolean(false)
val lastErr = java.util.concurrent.atomic.AtomicReference("")
val latch = java.util.concurrent.CountDownLatch(list.size)
for (base in list) {
Thread {
try {
if (loadGeneration.get() == gen) {
doLoadFromApi(base, title, episodeIndex, gen, winner) { lastErr.set(it)}
}
} catch (_: Exception) {} finally {
latch.countDown()
}
}.start()
}
Thread {
latch.await()
if (!winner.get() && loadGeneration.get() == gen) {
postError(if (lastErr.get().isNotEmpty()) lastErr.get() else "所有弹幕源均无结果")
}
}.start()
}

/** 单次 GET：返回 (HTTP 码, 成功时 body / 失败时 null)。响应用 use{} 关闭（P1-D7）。 */
private fun httpGet(url: String): Pair<Int, String?> {
return try {
sharedClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
val body = try { resp.body?.string()} catch (_: Exception) { null}
Pair(resp.code, if (resp.isSuccessful) body else null)
}
} catch (_: Exception) {
Pair(-1, null)
}
}

/**
* 单源拉取核心逻辑（供 loadFromApi / loadFromApiMulti 共用）。
* @return true=成功取到弹幕并填入 pool（且抢到写入权）
*/
private fun doLoadFromApi(
base: String,
title: String,
episodeIndex: Int = 0,
gen: Int = loadGeneration.get(),
winner: AtomicBoolean = AtomicBoolean(false),
onErr: (String) -> Unit
): Boolean {
fun isStale() = loadGeneration.get()!= gen
try {
val cleanTitle = cleanTitleForSearch(title)
com.muse.gomepc.player.DebugLog.d("Danmaku", "弹幕API base: $base, 清洗后: $cleanTitle")

// ---- 搜索：多试标题变体，取全部候选 ID，按标题相似度排序（Bug 1 + P1-D6 + Bug 11） ----
var candidates: List<AnimeCandidate> = emptyList()
var searchCode = 0
for (q in searchTitleVariants(title, cleanTitle)) {
if (isStale()) return false
val searchUrl = "$base/api/v2/search/anime?keyword=${URLEncoder.encode(q, "UTF-8")}"
val (code, body) = httpGet(searchUrl)
searchCode = code
if (body == null) continue
candidates = extractAnimeIds(body, q)
if (candidates.isNotEmpty()) break
}
if (candidates.isEmpty()) {
Log.w("Danmaku", "未搜到番剧: $title")
onErr("未搜到番剧: $title (HTTP $searchCode)")
return false
}

// ---- Bug 1：逐个试候选 animeId，404/空继续下一个，全部失败才算源失败 ----
var lastDetail = ""
for (cand in candidates) {
if (isStale()) return false
// ---- Bug 3：bangumi 失败延迟重试一次 ----
var bangumiBody: String? = null
var bangumiCode = 0
for (attempt in 0..1) {
if (isStale()) return false
if (attempt > 0) {
try { Thread.sleep(800)} catch (_: InterruptedException) { return false}
if (isStale()) return false
}
val (code, body) = httpGet("$base/api/v2/bangumi/${cand.animeId}")
bangumiCode = code
if (body!= null) { bangumiBody = body; break}
}
if (bangumiBody == null) {
lastDetail = "bangumi HTTP $bangumiCode"
continue
}
// ---- P0-D2：按 episodeIndex 从 episodes[] 匹配 episodeId，找不到回落第一个 ----
val episodes = extractEpisodes(bangumiBody)
if (episodes.isEmpty()) {
lastDetail = "未取到分集(HTTP $bangumiCode)"
continue
}
val episodeId = episodes.firstOrNull { it.number == episodeIndex + 1}?.episodeId
?: episodes.getOrNull(episodeIndex)?.episodeId
?: episodes.first().episodeId

val (_, commentBody) = httpGet("$base/api/v2/comment/$episodeId?withRelated=true")
if (commentBody == null) {
lastDetail = "弹幕接口失败"
continue
}
val list = parseDandanComments(commentBody)
com.muse.gomepc.player.DebugLog.d("Danmaku", "解析到 ${list.size} 条弹幕")
if (list.isEmpty()) {
lastDetail = "弹幕为空"
continue
}
// ---- Bug 5：代次隔离 + 同代首个成功胜出 ----
if (isStale()) return false
if (!winner.compareAndSet(false, true)) return false
if (isStale()) return false
val sorted = list.sortedBy { if (it.timeSec >= 0f) it.timeSec else Float.MAX_VALUE}
pool.clear()
pool.addAll(sorted)
emittedIndices.clear()
items.clear()
return true
}
onErr("弹幕源无结果: $title ($lastDetail)")
return false
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

/** 搜索标题变体：清洗后标题、原标题、去年份版（Bug 11）。 */
private fun searchTitleVariants(title: String, cleanTitle: String): List<String> {
return listOf(cleanTitle, title, removeYear(title), removeYear(cleanTitle))
.map { it.trim()}
.distinct()
.filter { it.isNotBlank()}
}

private fun removeYear(t: String): String {
return t.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
.replace(Regex("\\d{4}年"), "")
.replace(Regex("\\s+"), " ").trim()
}

/**
* Bug 10：清洗标题用于搜索：先对称删括号内容（括号+内容一起删），
* 再去 S/E/季/集标记。括号不配对时只删残留括号字符，不删正文。
*/
private fun cleanTitleForSearch(title: String): String {
var t = title
// 对称删括号内容：重复处理嵌套括号；每种括号单独匹配，避免 "（a]b）" 错配。
val bracketPairs = listOf(
Regex("\\([^()]*\\)"),
Regex("\\[[^\\[\\]]*\\]"),
Regex("（[^（）]*）"),
Regex("【[^】]*】")
)
var prev: String
do {
prev = t
for (re in bracketPairs) t = t.replace(re, "")
} while (t!= prev)
// 残留的单个括号字符删掉（不删正文）。
t = t.replace(Regex("[\\(\\)\\[\\]（）]"), "")
t = t.replace(Regex("(?i)\\bS\\d+\\b"), "")
t = t.replace(Regex("(?i)\\bE\\d+\\b"), "")
t = t.replace(Regex("(?i)\\bSeason\\s*\\d+\\b"), "")
t = t.replace(Regex("(?i)\\bEpisode\\s*\\d+\\b"), "")
t = t.replace(Regex("第[一二三四五六七八九十\\d]+季"), "")
t = t.replace(Regex("第[\\d一二三四五六七八九十]+集"), "")
t = t.replace(Regex("第[\\d一二三四五六七八九十]+话"), "")
t = t.replace(Regex("\\s+"), " ").trim()
return t
}

/**
* Bug 1 + P1-D6：返回全部 animeId（去重，上限 8 个），按与查询标题的相似度排序，
* 标题完全匹配优先。旧单 ID 入口保留为兼容包装。
*/
private fun extractAnimeIds(json: String, queryTitle: String = ""): List<AnimeCandidate> {
val out = LinkedHashMap<String, AnimeCandidate>()
try {
val idRe = Regex("\"animeId\"\\s*:\\s*(\\d+)")
for (m in idRe.findAll(json)) {
val id = m.groupValues[1]
if (id in out) continue
// 在 ID 附近窗口内找标题字段。
val start = max(0, m.range.first - 300)
val end = minOf(json.length, m.range.last + 301)
val window = json.substring(start, end)
val titleM = Regex("\"(animeTitle|title|name)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(window)
val rawTitle = titleM?.groupValues?.get(2)?: ""
out[id] = AnimeCandidate(id, unescapeJsonString(rawTitle))
if (out.size >= 8) break
}
} catch (_: Exception) {}
val q = queryTitle.trim()
return out.values.sortedWith(
compareByDescending<AnimeCandidate> { titleSimilarity(it.title, q)}
.thenBy { it.animeId}
)
}

private fun extractAnimeId(json: String): String {
return extractAnimeIds(json).firstOrNull()?.animeId?: ""
}

/** 标题相似度：完全匹配 1.0，包含 0.9，否则 1 - 归一化编辑距离。 */
private fun titleSimilarity(a: String, b: String): Float {
if (b.isBlank()) return 0f
val na = normalizeTitle(a)
val nb = normalizeTitle(b)
if (na.isEmpty() || nb.isEmpty()) return 0f
if (na == nb) return 1f
if (na.contains(nb) || nb.contains(na)) return 0.9f
val dist = levenshtein(na, nb)
val maxLen = max(na.length, nb.length)
return (1f - dist.toFloat() / maxLen).coerceIn(0f, 1f)
}

private fun normalizeTitle(t: String): String {
return t.lowercase()
.replace(Regex("[\\s\\-_.:：·!！?？,，、。]"), "")
}

private fun levenshtein(a: String, b: String): Int {
if (a == b) return 0
if (a.isEmpty()) return b.length
if (b.isEmpty()) return a.length
var prev = IntArray(b.length + 1) { it}
var cur = IntArray(b.length + 1)
for (i in 1..a.length) {
cur[0] = i
for (j in 1..b.length) {
val cost = if (a[i - 1] == b[j - 1]) 0 else 1
cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
}
val tmp = prev; prev = cur; cur = tmp
}
return prev[b.length]
}

/** 从 bangumi JSON 按顺序取出全部 episodeId（含 episodeNumber，如有）。 */
private fun extractEpisodes(json: String): List<EpisodeInfo> {
val out = ArrayList<EpisodeInfo>()
try {
val idRe = Regex("\"episodeId\"\\s*:\\s*(\\d+)")
for (m in idRe.findAll(json)) {
val id = m.groupValues[1]
if (out.any { it.episodeId == id}) continue
val start = max(0, m.range.first - 200)
val end = minOf(json.length, m.range.last + 201)
val window = json.substring(start, end)
val numM = Regex("\"(episodeNumber|episode|number)\"\\s*:\\s*(\\d+)").find(window)
out.add(EpisodeInfo(id, numM?.groupValues?.get(2)?.toIntOrNull()))
}
} catch (_: Exception) {}
return out
}

private fun extractEpisodeId(json: String): String {
return extractEpisodes(json).firstOrNull()?.episodeId?: ""
}

/** P1-D9：正确单遍 unescape，处理 \uXXXX 与全部常用转义。 */
private fun unescapeJsonString(s: String): String {
if (!s.contains('\\')) return s
val sb = StringBuilder(s.length)
var i = 0
while (i < s.length) {
val c = s[i]
if (c!= '\\' || i + 1 >= s.length) {
sb.append(c)
i++
continue
}
val n = s[i + 1]
when (n) {
'"' -> { sb.append('"'); i += 2}
'\\' -> { sb.append('\\'); i += 2}
'/' -> { sb.append('/'); i += 2}
'b' -> { sb.append('\b'); i += 2}
'f' -> { sb.append('\u000C'); i += 2}
'n' -> { sb.append('\n'); i += 2}
'r' -> { sb.append('\r'); i += 2}
't' -> { sb.append('\t'); i += 2}
'u' -> {
if (i + 5 < s.length) {
val hex = s.substring(i + 2, i + 6)
val code = hex.toIntOrNull(16)
if (code!= null) {
sb.append(code.toChar())
i += 6
continue
}
}
sb.append(n)
i += 2
}
else -> { sb.append(n); i += 2}
}
}
return sb.toString()
}

/** P1-D8：XML 实体解码（数字实体 + 命名实体，&amp; 最后解避免二次解码）。 */
private fun decodeXmlEntities(s: String): String {
var out = s
out = Regex("&#(\\d+);").replace(out) { m ->
val code = m.groupValues[1].toIntOrNull()?: return@replace m.value
if (code in 0..0x10FFFF) code.toChar().toString() else m.value
}
out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
val code = m.groupValues[1].toIntOrNull(16)?: return@replace m.value
if (code in 0..0x10FFFF) code.toChar().toString() else m.value
}
out = out.replace("&lt;", "<").replace("&gt;", ">")
.replace("&quot;", "\"").replace("&apos;", "'")
.replace("&amp;", "&")
return out
}

/** 解析 DanDanPlay 格式弹幕：p 字段第一个值为秒（P0-D1），m 为文本。 */
private fun parseDandanComments(json: String): List<DanmakuItem> {
val out = mutableListOf<DanmakuItem>()
try {
val blockRe = Regex("\\{[^{}]*\\}")
for (block in blockRe.findAll(json)) {
val b = block.value
val m = Regex("\"m\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(b)?: continue
val text = unescapeJsonString(m.groupValues[1])
if (text.isBlank()) continue
val p = Regex("\"p\"\\s*:\\s*\"([^\"]*)\"").find(b)?.groupValues?.get(1)?: ""
val parts = p.split(",")
val timeSec = parts.getOrNull(0)?.toFloatOrNull()?: -1f
val mode = parts.getOrNull(1)?.toIntOrNull()?: 1
val color = parts.getOrNull(2)?.toIntOrNull()?: 0xFFFFFF
out.add(DanmakuItem(text, timeSec, mode, color))
}
if (out.isEmpty()) {
// 兜底：对象嵌套导致块解析失败时，只按 m 取文本（无时间）。
val re = Regex("\"m\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
re.findAll(json).forEach { m ->
val s = unescapeJsonString(m.groupValues[1])
if (s.isNotBlank()) out.add(DanmakuItem(s, -1f))
}
}
} catch (_: Exception) {}
return out.take(1000)
}

/** 从本地文件内容导入弹幕：支持 B站 XML 或 JSON 数组；返回导入条数 */
fun importFromText(content: String): Int {
val list = mutableListOf<DanmakuItem>()
val t = content.trim()
try {
if (t.startsWith("<")) {
val re = Regex("<d\\s*([^>]*)>([^<]+)</d>")
re.findAll(t).forEach { m ->
val attrs = m.groupValues[1]
val s = decodeXmlEntities(m.groupValues[2].trim())
if (s.isNotEmpty()) {
val p = Regex("p\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?: ""
val parts = p.split(",")
val timeSec = parts.getOrNull(0)?.toFloatOrNull()?: -1f
val mode = parts.getOrNull(1)?.toIntOrNull()?: 1
val color = parts.getOrNull(2)?.toIntOrNull()?: 0xFFFFFF
list.add(DanmakuItem(s, timeSec, mode, color))
}
}
} else {
list.addAll(parseDanmakuJson(t))
}
} catch (_: Exception) {}
val result = list.take(2000)
if (result.isNotEmpty()) {
loadGeneration.incrementAndGet()
pool.clear()
pool.addAll(result)
emittedIndices.clear()
items.clear()
}
return result.size
}

fun danmakuCount(): Int = pool.size

/**
* P1-D8：只取对象里的 text/m/content 字段；已删除旧的“裸字符串全吞入”
* 回退分支（它会把 key 名等误当弹幕）。
*/
private fun parseDanmakuJson(json: String): List<DanmakuItem> {
val out = mutableListOf<DanmakuItem>()
try {
val blockRe = Regex("\\{[^{}]*\\}")
for (block in blockRe.findAll(json)) {
val b = block.value
val textM = Regex("\"(text|m|content)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(b)
?: continue
val text = unescapeJsonString(textM.groupValues[2])
if (text.isBlank()) continue
val timeM = Regex("\"(time|timeSec|p)\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?").find(b)
val timeSec = timeM?.groupValues?.get(2)?.toFloatOrNull()?: -1f
out.add(DanmakuItem(text, timeSec))
}
} catch (_: Exception) {}
return out.take(500)
}
}
