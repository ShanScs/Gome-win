package com.muse.gomepc.danmaku

import com.muse.gomepc.emby.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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

/** 池填充时刻（wall clock ms）/ 填充时播放进度（秒）/ 上次定时发射成功时刻（0=自填充以来从未成功），用于诊断与发射回落。 */
@Volatile var poolFilledAtMs: Long = 0L
private set
@Volatile var poolFilledAtSec: Float = 0f
private set
@Volatile var lastTimedEmitAtMs: Long = 0L
private set
private var fallbackLogged = false
private var lastFallbackEmitMs: Long = 0L

/** 池填充/清空后记一笔：池大小 + 时间范围 + 当前进度。时间范围异常（如全 -1、全 0、毫秒级）时下一条日志能直接定位。 */
private fun onPoolFilled() {
poolFilledAtMs = System.currentTimeMillis()
poolFilledAtSec = currentTimeSec
lastTimedEmitAtMs = 0L
fallbackLogged = false
lastFallbackEmitMs = 0L
if (pool.isEmpty()) {
com.muse.gomepc.player.DebugLog.d("Danmaku", "池已清空，当前进度: $currentTimeSec 秒")
return
}
val times = pool.mapNotNull { if (it.timeSec >= 0f) it.timeSec else null }
val min = times.minOrNull()
val max = times.maxOrNull()
com.muse.gomepc.player.DebugLog.d("Danmaku", "池已填充: ${pool.size} 条，带时间 ${times.size} 条，时间范围: ${min}~${max} 秒，当前进度: $currentTimeSec 秒，前5条时间: ${times.take(5)}")
}

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
// 读超时 60s（原 10s）：danmu_api 弹幕要实时去上游抓取，1.6 万条弹幕的集
// 首次抓取超 10s 很常见；用户日志里 18s 的一次加载就是倒在弹幕接口超时上。
.readTimeout(60, TimeUnit.SECONDS)
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
pool.addAll(evenSampleByTime(list, 20000))
emittedIndices.clear()
items.clear()
onPoolFilled()
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
// 密集场景每帧最多发 3 条（旧版每帧只发 1 条，窗口滑过后未发出的直接丢失）。
var emittedThisFrame = 0
while (emittedThisFrame < 3) {
val lo = currentTimeSec - 1f
val hi = currentTimeSec + 0.5f
val candidateIdx = poolSnapshot.indices.firstOrNull { idx ->
idx!in emittedIndices &&
poolSnapshot[idx].timeSec >= lo && poolSnapshot[idx].timeSec <= hi
}?: break
val item = poolSnapshot[candidateIdx]
emittedIndices.add(candidateIdx)
if (!emit(item.text, w, measureText, now)) break
emittedThisFrame++
}
if (emittedThisFrame > 0) lastTimedEmitAtMs = now
// 回落：池已填 10 秒、播放已推进 10 秒，但定时发射一次都没成功 ——
// 说明时间对齐有问题（池时间与播放进度对不上），再等下去屏幕永远没弹幕。
// 回落到随机发射（原版行为），至少让弹幕可见；只记一次日志，不刷屏。
// 一旦某次定时发射成功，回落自动停止（lastTimedEmitAtMs != 0）。
if (emittedThisFrame == 0 && lastTimedEmitAtMs == 0L && poolFilledAtMs > 0 &&
now - poolFilledAtMs > 10_000 && currentTimeSec - poolFilledAtSec > 10f &&
now - lastFallbackEmitMs >= 1000) {
lastFallbackEmitMs = now
if (!fallbackLogged) {
fallbackLogged = true
com.muse.gomepc.player.DebugLog.d("Danmaku", "定时发射 10 秒无产出，回落随机发射：池 ${pool.size} 条，当前进度 $currentTimeSec 秒，池填充时进度 $poolFilledAtSec 秒")
}
emit(poolSnapshot.random().text, w, measureText, now)
}
} else {
// 无时间信息池子：保留旧随机发射行为。
val emitProb = when (position) { 0 -> 0.5f; 1 -> 0.4f; else -> 0.25f}
if (Random.nextFloat() < emitProb) {
emit(poolSnapshot.random().text, w, measureText, now)
}
}
}

/** 发射一条弹幕：做行重叠检查，delaySec 用 visibleAt 时间戳（出生点不再右移）。
* 返回是否成功发射。旧版随机抽一行、该行拥挤就直接丢弃（已标记已发射，永久丢失），
* 密集时大量弹幕因此丢失；现按随机顺序试全部行，只在所有行都拥挤时才丢弃。 */
private fun emit(text: String, w: Float, measureText: (String) -> Float, now: Long): Boolean {
val rowOrder = (0 until max(1, rows)).shuffled()
for (row in rowOrder) {
val rowItems = items.filter { it.row == row }
// 检查该行最右弹幕位置，避免重叠（未到可见时间的弹幕也占行，避免延迟期内堆叠）。
val rightmost = rowItems.maxOfOrNull {
if (it.textWidth < 0) it.textWidth = measureText(it.text)
it.x + it.textWidth
}?: -1f
if (rightmost < w - 100) {
val tw = measureText(text)
// 横穿时长固定约 15 秒：速度 = (窗口宽 + 文本宽) / 时长，与窗口宽度无关；
// ±12% 随机避免齐步走。speedFactor 在 step() 里再乘（50%→30 秒，200%→7.5 秒）。
val duration = 15f * (0.88f + Random.nextFloat() * 0.24f)
var speed = (w + tw) / duration
// 同行防追尾：每条弹幕各抽一次随机时长时，后发的可能比先发的快
// （最快约 13.2 秒 vs 最慢约 16.8 秒，快约 27%），飞行中会追上先发的
// 同行弹幕造成重叠；出生检查只保发射瞬间 100px 间距，防不住追尾。
// 现新弹幕速度不超过该行已有的最慢速度，同行速度一致、间距只增不减，
// 永不追尾；空行用新鲜随机速度，各行速度仍有差异。
val rowSlowest = rowItems.minOfOrNull { it.speed }
if (rowSlowest != null && speed > rowSlowest) speed = rowSlowest
// P1-D10：delaySec 只推迟可见时间，不右移出生点、不占 maxItems 名额。
val visibleAt = now + (delaySec * 1000f).toLong()
items.add(Danmaku(text, w, row, speed, now, tw, visibleAt))
return true
}
}
return false
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
Thread {
val list = doLoadFromApi(url.trim().trimEnd('/'), title, episodeIndex, gen) { postError(it)}
if (list != null && list.isNotEmpty() && loadGeneration.get() == gen) {
pool.clear()
pool.addAll(list)
emittedIndices.clear()
items.clear()
onPoolFilled()
}
}.start()
}

/**
* 多源弹幕：并行尝试各 API，全部结果合并去重后填入池子；全部失败才回调一次错误。
* 旧版同代首个成功者抢写入权、其余结果丢弃——用户配 3 个源只用 1 个源的量，弹幕不全。
* 现等全部源返回后合并（文本+时间去重，按时间排序），覆盖更全。
*/
fun loadFromApiMulti(urls: List<String>, title: String = "", episodeIndex: Int = 0) {
val list = urls.map { it.trim().trimEnd('/')}.filter { it.isNotBlank()}
if (list.isEmpty()) return
if (list.size == 1) {
loadFromApi(list[0], title, episodeIndex)
return
}
val gen = loadGeneration.incrementAndGet()
val lastErr = java.util.concurrent.atomic.AtomicReference("")
val latch = java.util.concurrent.CountDownLatch(list.size)
val allResults = Collections.synchronizedList(mutableListOf<List<DanmakuItem>>())
for (base in list) {
Thread {
try {
if (loadGeneration.get() == gen) {
val r = doLoadFromApi(base, title, episodeIndex, gen) { lastErr.set(it)}
if (r != null && r.isNotEmpty()) allResults.add(r)
}
} catch (_: Exception) {} finally {
latch.countDown()
}
}.start()
}
Thread {
// 分段等全部源返回：单源全程（搜索重试 + bangumi + 弹幕抓取 60s 读超时 + 退避重试）
// 可超过 45 秒（实测约 47 秒），1.0.348 的单次 await(45s) 会在好源还在抓弹幕时
// 误判“全部失败”并退出，导致之后完成的源结果无人合并、池永不填充。
// 故每 15 秒一轮、总上限 180 秒；超时时用已完成源的结果合并，不跳过。
var waitedMs = 0L
while (latch.count > 0 && waitedMs < 180_000) {
latch.await(15, TimeUnit.SECONDS)
waitedMs += 15_000
if (loadGeneration.get() != gen) return@Thread
if (latch.count > 0 && allResults.isEmpty() && waitedMs >= 45_000) {
com.muse.gomepc.player.DebugLog.d("Danmaku", "合并等待中：已等 ${waitedMs / 1000} 秒，仍有 ${latch.count} 个源未返回，继续等待")
}
}
if (loadGeneration.get() != gen) return@Thread
if (allResults.isEmpty()) {
if (latch.count > 0) {
// 总上限到了仍有源在飞：没有已完成的结果可合并，只记超时，不报“库里无记录”
// （lastErr 可能来自早失败的源，会误导成整部剧无弹幕）。
com.muse.gomepc.player.DebugLog.d("Danmaku", "合并等待超时：180 秒已到，仍有 ${latch.count} 个源未返回，无已完成结果可合并")
return@Thread
}
postError(if (lastErr.get().isNotEmpty()) lastErr.get() else "所有弹幕源均无结果")
return@Thread
}
val merged = evenSampleByTime(
allResults.flatten()
.distinctBy { it.text to it.timeSec }
.sortedBy { if (it.timeSec >= 0f) it.timeSec else Float.MAX_VALUE },
30000
)
pool.clear()
pool.addAll(merged)
emittedIndices.clear()
items.clear()
onPoolFilled()
com.muse.gomepc.player.DebugLog.d("Danmaku", "多源合并：${allResults.size} 个源有结果，合并去重后 ${merged.size} 条")
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
* @return 成功时返回解析排序后的弹幕列表（调用方负责填入 pool），失败返回 null。
* 不直接写 pool：多源合并时由调用方统一合并去重，避免首个成功者丢弃其余源。
*/
private fun doLoadFromApi(
base: String,
title: String,
episodeIndex: Int = 0,
gen: Int = loadGeneration.get(),
onErr: (String) -> Unit
): List<DanmakuItem>? {
fun isStale() = loadGeneration.get()!= gen
try {
val cleanTitle = cleanTitleForSearch(title)
com.muse.gomepc.player.DebugLog.d("Danmaku", "弹幕API base: $base, 清洗后: $cleanTitle")

// ---- 搜索：多试标题变体，取全部候选 ID，按标题相似度排序（Bug 1 + P1-D6 + Bug 11） ----
var candidates: List<AnimeCandidate> = emptyList()
var searchCode = 0
// 搜索返回过网页（非 JSON）：说明源不是 DanDanPlay 兼容接口或路径不对，需单独报错。
var htmlResponse = false
val variants = searchTitleVariants(title, cleanTitle)
// 注意：不要去掉 base 的子路径。danmu_api（LogVar）的 TOKEN 就是 URL 路径段，
// 如 https://dm.211.li/nonay 的 /nonay 就是 TOKEN，去掉后请求变成无 TOKEN，
// 服务端会按未鉴权处理。用户填的 base 本身就是 API 根（含 TOKEN）。
// 搜不到时只把整组变体重试一次（上游抓取偶发空结果），不再试“去路径的 origin”。
for (searchAttempt in 0..1) {
if (candidates.isNotEmpty()) break
if (searchAttempt > 0) {
try { Thread.sleep(2000) } catch (_: InterruptedException) { return null }
if (isStale()) return null
}
for (q in variants) {
if (isStale()) return null
val searchUrl = "$base/api/v2/search/anime?keyword=${URLEncoder.encode(q, "UTF-8")}"
val (code, body) = httpGet(searchUrl)
searchCode = code
if (body == null) continue
// 诊断：记完整 URL + 原始响应（LogVar 等自定义源会回网页而非 JSON，
// 仅记 query 看不出路径对错）。响应是 HTML 时直接标明，非 DanDanPlay 格式/路径错误。
com.muse.gomepc.player.DebugLog.d("Danmaku", "搜索请求: $searchUrl HTTP $code 响应前1000字: ${body.take(1000)}")
val trimmed = body.trimStart()
if (trimmed.startsWith("<") || trimmed.startsWith("<!DOCTYPE", ignoreCase = true)) {
com.muse.gomepc.player.DebugLog.d("Danmaku", "搜索返回了网页而非 JSON：该源可能不是 DanDanPlay 兼容接口，或 API 路径不对（当前请求：$searchUrl）")
htmlResponse = true
continue
}
candidates = extractAnimeIds(body, q)
if (candidates.isNotEmpty()) break
// 诊断：HTTP 200 但解析出 0 候选——记一小段原始响应，区分“库里真没有”与“解析漏了”。
com.muse.gomepc.player.DebugLog.d("Danmaku", "搜索无候选: q=$q HTTP $code 响应前300字: ${body.take(300)}")
}
}
if (candidates.isEmpty()) {
if (htmlResponse) {
// 源返回了网页而非 JSON：不是“库里没有这部剧”，是接口格式/路径不对。
val msg = "弹幕源返回了网页而非 JSON：$base 可能不是 DanDanPlay 兼容接口，或 API 路径不对（已试 $base/api/v2/search/anime）。请检查该源的实际接口格式。"
Log.w("Danmaku", msg)
onErr(msg)
} else if (searchCode == 200) {
// API 通且正常返回，只是库里没有这部剧——明确告知，不要让用户以为软件坏了。
val msg = "该剧在弹幕库中无记录：$title（已试 ${variants.size} 种标题，API 正常返回但无匹配番剧）"
Log.w("Danmaku", msg)
onErr(msg)
} else {
Log.w("Danmaku", "未搜到番剧: $title")
onErr("未搜到番剧: $title (HTTP $searchCode)")
}
return null
}

// ---- Bug 1：逐个试候选 animeId，404/空继续下一个，全部失败才算源失败 ----
var lastDetail = ""
for (cand in candidates) {
if (isStale()) return null
// ---- Bug 3：bangumi 失败延迟重试一次 ----
var bangumiBody: String? = null
var bangumiCode = 0
for (attempt in 0..1) {
if (isStale()) return null
if (attempt > 0) {
try { Thread.sleep(800)} catch (_: InterruptedException) { return null}
if (isStale()) return null
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

// 弹幕请求是重活：danmu_api 要实时去上游（优酷等）抓弹幕，1.6 万条弹幕的集
// 首次抓取可能很慢；429（限流）/5xx 时退避重试。format=json 按 danmu_api
// 文档显式要求 JSON（查询参数优先级高于服务端 DANMU_OUTPUT_FORMAT 环境变量，
// 防止服务端配了 xml 时 PC 拿到 XML 却按 JSON 解析出 0 条）。
var commentBody: String? = null
var commentCode = 0
for (commentAttempt in 0..2) {
if (isStale()) return null
if (commentAttempt > 0) {
try { Thread.sleep(if (commentAttempt == 1) 2000 else 6000) } catch (_: InterruptedException) { return null }
if (isStale()) return null
}
val (code, body) = httpGet("$base/api/v2/comment/$episodeId?format=json&withRelated=true")
commentCode = code
if (body != null) { commentBody = body; break }
// 超时(-1)已耗尽 60s 读超时，重试无意义；只对 429/5xx 这类服务端侧失败重试。
if (code != 429 && !(code in 500..599)) break
}
if (commentBody == null) {
lastDetail = "弹幕接口失败(HTTP $commentCode)"
continue
}
val list = parseDandanComments(commentBody)
com.muse.gomepc.player.DebugLog.d("Danmaku", "解析到 ${list.size} 条弹幕")
if (list.isEmpty()) {
lastDetail = "弹幕为空"
continue
}
// ---- Bug 5：代次隔离，列表由调用方统一填入（多源合并时不再首个成功者独占） ----
if (isStale()) return null
return list.sortedBy { if (it.timeSec >= 0f) it.timeSec else Float.MAX_VALUE}
}
onErr("弹幕源无结果: $title ($lastDetail)")
return null
} catch (e: Exception) {
Log.w("Danmaku", "API失败: ${e.message}")
onErr("API失败: ${e.message}")
return null
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

/** 搜索标题变体：清洗后标题、原标题、去年份版（Bug 11）、分隔符前第一段、去常见后缀词。
* 注意：分隔符只切全角中文符号与括号，不切 ASCII ':'/'-'，避免 "Re:从零开始" 切成 "Re"、"Spider-Man" 切成 "Spider"。 */
private fun searchTitleVariants(title: String, cleanTitle: String): List<String> {
val base = listOf(cleanTitle, title, removeYear(title), removeYear(cleanTitle))
val extra = mutableListOf<String>()
for (t in base) {
// 分隔符前第一段（如“无可替代：特别篇”→“无可替代”）
val firstSeg = t.split('：', '|', '（', '(', '【', '[').firstOrNull()?.trim() ?: ""
if (firstSeg.isNotBlank()) extra.add(firstSeg)
// 去常见后缀词（国产剧 Emby 标题里常见）
var s = t
for (suffix in listOf("电视剧", "完整版", "高清", "未删减", "全集", "抢先版", "正片")) {
s = s.replace(suffix, "")
}
s = s.replace(Regex("\\s+"), " ").trim()
if (s.isNotBlank()) extra.add(s)
}
return (base + extra)
.map { it.trim() }
.distinct()
.filter { it.isNotBlank() }
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
val idRe = Regex("\"(anime_?[Ii][Dd]|bangumi_?[Ii][Dd])\"\\s*:\\s*\"?(\\d+)\"?")
for (m in idRe.findAll(json)) {
val id = m.groupValues[2]
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
val idRe = Regex("\"episodeId\"\\s*:\\s*\"?(\\d+)\"?")
for (m in idRe.findAll(json)) {
val id = m.groupValues[1]
if (out.any { it.episodeId == id}) continue
val start = max(0, m.range.first - 200)
val end = minOf(json.length, m.range.last + 201)
val window = json.substring(start, end)
val numM = Regex("\"(episodeNumber|episode|number)\"\\s*:\\s*\"?(\\d+)\"?").find(window)
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

/** 从 p 字段取颜色：按段数区分格式。DanDanPlay 4 段 time,mode,color,uid，颜色在 [2]；
* danmu_api 5 段 time,type,size,color,timestamp 与 B 站 XML 8 段 time,mode,fontsize,color,…，颜色在 [3]。
* 旧代码固定取 [2]，对 danmu_api 会取到字号（25）而非颜色。 */
private fun colorFromP(parts: List<String>): Int {
return if (parts.size >= 5) parts.getOrNull(3)?.toIntOrNull() ?: 0xFFFFFF
else parts.getOrNull(2)?.toIntOrNull() ?: 0xFFFFFF
}

/** 等间隔采样封顶：列表超长时按时间均匀取样，保证保留的弹幕覆盖全集时间范围。
 * 禁止“取前 N 条”：服务端返回全集弹幕（时间升序）时 take(N) 只保留最早 N 条，
 * 池只覆盖前几分钟，断点续播到后半集时发射窗口内永远无弹幕。
 * 未超长时原样返回，不改变任何行为。 */
private fun evenSampleByTime(list: List<DanmakuItem>, max: Int): List<DanmakuItem> {
if (list.size <= max) return list
val sorted = list.sortedBy { if (it.timeSec >= 0f) it.timeSec else Float.MAX_VALUE }
val step = sorted.size.toDouble() / max
val out = ArrayList<DanmakuItem>(max)
for (i in 0 until max) out.add(sorted[(i * step).toInt().coerceIn(0, sorted.size - 1)])
com.muse.gomepc.player.DebugLog.d("Danmaku", "弹幕等间隔采样：原始 ${list.size} 条 → 保留 ${out.size} 条（覆盖全集时间范围）")
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
val color = colorFromP(parts)
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
return evenSampleByTime(out, 20000)
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
val color = colorFromP(parts)
list.add(DanmakuItem(s, timeSec, mode, color))
}
}
} else {
list.addAll(parseDanmakuJson(t))
}
} catch (_: Exception) {}
val result = evenSampleByTime(list, 20000)
if (result.isNotEmpty()) {
loadGeneration.incrementAndGet()
pool.clear()
pool.addAll(result)
emittedIndices.clear()
items.clear()
onPoolFilled()
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
return evenSampleByTime(out, 20000)
}
}
