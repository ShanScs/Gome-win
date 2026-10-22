package com.muse.gomepc.danmaku

import java.awt.*
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import javax.swing.Timer

/**
* AWT 版弹幕覆盖层（用于 JWindow 悬浮窗）。
* Compose 的 ComposePanel 在 JWindow 里文字光栅化异常，改用 Graphics2D 直接绘制。
* 与 DanmakuOverlay.kt 同样的 DanmakuEngine，共用逻辑。
*/
/**
* @param videoRectInParent 提供视频区在父容器中的矩形（glasspane 模式用；JWindow 模式传 null 则全画）
*/
class AwtDanmakuPanel(
private val engine: DanmakuEngine,
private val videoRectInParent: (() -> java.awt.Rectangle?)? = null,
private val onMouseActivity: () -> Unit
): JPanel() {

// 弹幕文本渲染缓存：同一文本只渲染一次，避免每帧9遍描边绘制卡顿
// P2-D7：LRU 缓存（accessOrder），超限只淘汰最久未用，不再全清。
private val textCache = object: LinkedHashMap<String, java.awt.image.BufferedImage>(128, 0.75f, true) {
override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.awt.image.BufferedImage>?): Boolean {
return size > 500
}
}
private var cachedFontSize = -1
private var cachedAlpha = -1
private var cachedStrokePx = -1f
private var cachedScale = -1f
// 后台光栅化：paint 只做缓存位图 blit，新文本的光栅化（描边多遍 drawString）移到专用线程，
// 避免在 EDT 上光栅化造成帧间隔尖峰（日志实测：在屏弹幕越多帧耗时越高，20+ 条时平均 45-84ms）。
private val rasterExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
Thread(r, "DanmakuRasterizer").apply { isDaemon = true }
}
private val rasterPending = java.util.Collections.synchronizedSet(HashSet<String>())
@Volatile private var rasterGeneration = 0
private val rasterTextWidths = java.util.concurrent.ConcurrentHashMap<String, Float>()
// 后台光栅线程自己的字体/度量（与 paint 侧同字体同字号，stringWidth 一致），只在光栅线程内访问。
private var rasterFontSize = -1
private var rasterFont: Font? = null
private var rasterFontMetrics: FontMetrics? = null
// 上一帧是否有弹幕：用于弹幕清空时隐藏窗口
private var hadItems = false
// 连续无弹幕帧数：弹幕间隙短暂为空时不立即隐藏窗口（防时隐时现闪烁），
// 连续 15 帧（约 0.5s）无弹幕才隐藏。
private var emptyFrames = 0
// 诊断节流时间戳（面板 Timer 里 5 秒一记状态）。
private var lastDiagMs = 0L
// Timer 体异常日志节流。
private var lastTimerErrMs = 0L
// 帧时间统计（Timer 每 tick 记一次帧间隔，5 秒随“面板状态”诊断一起记一行，记完清零）：
// 用 nanoTime 计时，避免 currentTimeMillis 在 Windows 下的粒度问题；只做几次浮点累加，轻量无每帧日志。
// 阈值：>50ms 计掉帧（约 1.5 倍 Timer 周期 33ms），>100ms 计严重卡顿（约 3 倍周期）。
// 注意：Timer 周期本身就是 33ms，旧阈值 33ms 会把几乎每帧都记成掉帧，是误报。
private var lastFrameNs = 0L
private var frameStatCount = 0L
private var frameStatTotalMs = 0.0
private var frameStatMaxMs = 0.0
private var frameStatDrop33 = 0L
private var frameStatSevere50 = 0L
// 字号缓存：Timer 每 tick / 每 paint 都 deriveFont + getFontMetrics 太浪费，字号不变时复用。
private var tickFontSize = -1
private var tickFont: Font? = null
private var tickFontMetrics: FontMetrics? = null
private var paintFontSize = -1
private var paintFont: Font? = null
private var paintFontMetrics: FontMetrics? = null
private var paintAscent = 0
// 脏区重绘：上一 tick 的弹幕矩形 + 算它们时用的字号（字号变了高度变化超出余量，强制全量重绘一次）。
private var prevDirtyRects: List<java.awt.Rectangle> = emptyList()
private var dirtyFontSize = -1
// 供外部查询：面板是否希望窗口可见（有弹幕且启用）
var wantVisible = false
private set

// P2-D4：Timer 持有引用，可 stop()/removeNotify() 停，避免面板移除后空转。
private var timer: Timer? = null

/** P2-D4：停止驱动 Timer（面板移除 / 窗口销毁时调用）。 */
fun stop() {
if (timer != null) com.muse.gomepc.player.DebugLog.d("Danmaku", "面板 Timer 已停止")
try { timer?.stop()} catch (_: Throwable) {}
timer = null
}

override fun removeNotify() {
stop()
super.removeNotify()
}

/** 面板被重新挂上时重建 Timer（removeNotify 停掉后若不重建，step 再也不跑、弹幕永远不显示）。 */
override fun addNotify() {
super.addNotify()
startTimer()
}

override fun contains(x: Int, y: Int): Boolean {
return super.contains(x, y)
}

/**
* Bug 7：宿主窗口查找兜底。getWindowAncestor 在面板尚未挂到窗口、
* 或窗口正在销毁时可能返回 null / 抛异常，兜底用 getRoot，仍为空则返回 null
* 由调用方按“无窗口”处理，绝不崩。
*/
private fun hostWindow(): java.awt.Window? {
return try {
javax.swing.SwingUtilities.getWindowAncestor(this@AwtDanmakuPanel)
?: (javax.swing.SwingUtilities.getRoot(this@AwtDanmakuPanel) as? java.awt.Window)
} catch (_: Throwable) {
null
}
}

init {
isOpaque = false

val mouseAdapter = object: java.awt.event.MouseAdapter() {
override fun mouseMoved(e: java.awt.event.MouseEvent?) {
// P2-D5：删 mouseMoved 日志（每帧刷屏的日志洪泛）。
onMouseActivity()
// 悬停也要透传，否则底层控件的 hover 状态失效
dispatchToUnderlying(e)
}

override fun mouseDragged(e: java.awt.event.MouseEvent?) {
onMouseActivity()
// 拖动也要透传，否则进度条 Slider 无法拖动
dispatchToUnderlying(e)
}

// P2-D5：补滚轮/进入/离开透传。
override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent?) {
onMouseActivity()
dispatchToUnderlying(e)
}

override fun mouseEntered(e: java.awt.event.MouseEvent?) {
dispatchToUnderlying(e)
}

override fun mouseExited(e: java.awt.event.MouseEvent?) {
dispatchToUnderlying(e)
}

private var pressTarget: java.awt.Component? = null

/** P2-D6：pressTarget 校验：已销毁/不在窗口里/就是本面板时视为失效，重新 hit-test。 */
private fun validPressTarget(t: java.awt.Component?): Boolean {
if (t == null) return false
if (t === this@AwtDanmakuPanel) return false
if (!t.isDisplayable) return false
return try {
javax.swing.SwingUtilities.getWindowAncestor(t)!= null
} catch (_: Throwable) {
false
}
}

/**
* P0-D3：标准 Swing GlassPane 透传。原代码只认 JFrame，JWindow 模式
* 下 getWindowAncestor 返回 JWindow，强转失败直接返回 null，透传全失效。
* 现按 java.awt.Window 处理：JWindow 模式透传给 owner 窗口的 contentPane，
* glasspane 模式透传给自身窗口的 contentPane。
*/
private fun findTargetUnder(e: java.awt.event.MouseEvent): java.awt.Component? {
val glassPane = this@AwtDanmakuPanel
val window = hostWindow()?: return null
// JWindow 悬浮窗本身不收事件，目标在它 owner 的 contentPane 里。
val host = (window as? javax.swing.JWindow)?.owner?: window
val contentPane = (host as? RootPaneContainer)?.contentPane as? java.awt.Container
?: return null
val contentPoint = javax.swing.SwingUtilities.convertPoint(glassPane, e.point, contentPane)
val target = javax.swing.SwingUtilities.getDeepestComponentAt(
contentPane, contentPoint.x, contentPoint.y
)
// 命中自己（JWindow 无 owner 时 contentPane 里就是本面板）不透传，避免自循环。
if (target === glassPane) return null
return target
}

private fun redispatch(e: java.awt.event.MouseEvent, target: java.awt.Component) {
val targetPoint = javax.swing.SwingUtilities.convertPoint(
this@AwtDanmakuPanel, e.point, target
)
if (e is java.awt.event.MouseWheelEvent) {
val newEvent = java.awt.event.MouseWheelEvent(
target,
e.id,
e.`when`,
e.modifiers,
targetPoint.x,
targetPoint.y,
e.xOnScreen,
e.yOnScreen,
e.clickCount,
e.isPopupTrigger,
e.scrollType,
e.scrollAmount,
e.wheelRotation
)
target.dispatchEvent(newEvent)
return
}
val newEvent = java.awt.event.MouseEvent(
target,
e.id,
e.`when`,
e.modifiers,
targetPoint.x,
targetPoint.y,
e.xOnScreen,
e.yOnScreen,
e.clickCount,
e.isPopupTrigger,
e.button
)
target.dispatchEvent(newEvent)
}

private fun dispatchToUnderlying(e: java.awt.event.MouseEvent?) {
if (e == null) return
try {
when (e.id) {
java.awt.event.MouseEvent.MOUSE_PRESSED -> {
val target = findTargetUnder(e)
pressTarget = target
if (target!= null) redispatch(e, target)
}
java.awt.event.MouseEvent.MOUSE_DRAGGED,
java.awt.event.MouseEvent.MOUSE_RELEASED -> {
// 拖动/释放必须发给按下时的同一个组件，否则 Slider 抓不住拖动手势
val target = if (validPressTarget(pressTarget)) pressTarget else findTargetUnder(e)
if (target!= null) redispatch(e, target)
if (e.id == java.awt.event.MouseEvent.MOUSE_RELEASED) pressTarget = null
}
else -> {
val target = findTargetUnder(e)
if (target!= null) redispatch(e, target)
}
}
} catch (_: Throwable) {}
}

override fun mousePressed(e: java.awt.event.MouseEvent?) {
onMouseActivity()
dispatchToUnderlying(e)
}

override fun mouseReleased(e: java.awt.event.MouseEvent?) {
dispatchToUnderlying(e)
}

override fun mouseClicked(e: java.awt.event.MouseEvent?) {
dispatchToUnderlying(e)
}
}
addMouseListener(mouseAdapter)
addMouseMotionListener(mouseAdapter)
addMouseWheelListener(mouseAdapter)
}

private val danmakuFont = try {
Font("Noto Sans CJK SC", Font.BOLD, 30)
} catch (_: Throwable) {
Font("SansSerif", Font.BOLD, 30)
}

/**
* 脏区重绘（替代整窗 repaint()）：透明 JWindow 每帧整窗重绘 + 全屏 fillRect + DWM 全窗合成
* 是帧间隔超出 33ms 目标的主要开销。弹幕只占顶部区域，逐个 repaint 上一 tick 与本 tick
* 的弹幕矩形（RepaintManager 会合并为并集），实际绘制面积缩到约 1/3。
* 矩形尺寸用与 paint() 相同的度量：宽 = textWidth + 8（位图含 4px 内衬），
* 高 = fm.height + 8，y = 基线 − ascent − 4；四周再留 8px PAD 覆盖描边与取整误差。
* 字号变了（高度变化可能超出 PAD）就全量重绘一次并清空脏区记录，避免残影。
*/
private fun repaintDirty(now: Long, fontSize: Int, fm: FontMetrics) {
if (fontSize != dirtyFontSize || fontSize <= 0) {
dirtyFontSize = fontSize
prevDirtyRects = emptyList()
repaint()
return
}
val pad = 8
val cur = ArrayList<java.awt.Rectangle>(engine.items.size)
for (d in engine.items) {
if (now < d.visibleAt) continue
if (d.row < 0) continue
val tw = if (d.textWidth >= 0) d.textWidth else DanmakuEmotes.width(d.text, fm)
val top = engine.rowBaseline(d.row, fontSize.toFloat()) - fm.ascent - 4f
cur.add(
java.awt.Rectangle(
(d.x - pad).toInt(),
(top - pad).toInt(),
(tw + 8 + pad * 2).toInt(),
fm.height + 8 + pad * 2
)
)
}
for (r in prevDirtyRects) repaint(r)
for (r in cur) repaint(r)
prevDirtyRects = cur
}

/** 当前描边半径（与 paint 侧同一公式）。 */
private fun currentStrokeRadius(): Int = if (engine.strokePx <= 0f) 0
else kotlin.math.ceil(engine.strokePx.toDouble()).toInt().coerceIn(1, 4)

/**
* 请求后台光栅化一条文本（去重）。参数为请求时刻的渲染参数；
* 参数代次变化（字号/透明度/描边/缩放变了）后完成的结果会被丢弃。
*/
/** 弹幕位图缓存键：同一文本不同颜色是不同的图，必须分开缓存。 */
private fun cacheKey(text: String, color: Int): String = color.toString() + "\u0001" + text

private fun enqueueRaster(key: String, text: String, color: Int, fontSize: Int, alphaInt: Int, strokeRadius: Int, devScale: Float) {
if (synchronized(textCache) { textCache.containsKey(key) }) return
if (!rasterPending.add(key)) return
if (rasterPending.size > 300) { rasterPending.remove(key); return }
val gen = rasterGeneration
rasterExecutor.execute {
try {
val img = rasterizeText(text, color, fontSize, alphaInt, strokeRadius, devScale)
if (img != null && gen == rasterGeneration) {
synchronized(textCache) { textCache.put(key, img) }
}
} catch (_: Throwable) {
} finally {
rasterPending.remove(key)
}
}
}

/**
* 光栅化一条弹幕文本到位图（与旧 paint 内联光栅化同一套绘制：描边多遍 + 白字）。
* 只在后台光栅线程调用。测宽用本线程的 FontMetrics（同字体同字号，与面板度量一致），
* 宽度经 rasterTextWidths 回传，paint 侧据此回填 item.textWidth。
*/
private fun rasterizeText(text: String, color: Int, fontSize: Int, alphaInt: Int, strokeRadius: Int, devScale: Float): java.awt.image.BufferedImage? {
if (rasterFontSize != fontSize || rasterFont == null) {
rasterFont = try {
Font("Noto Sans CJK SC", Font.BOLD, fontSize)
} catch (_: Throwable) {
Font("SansSerif", Font.BOLD, fontSize)
}
val scratch = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
val sg = scratch.createGraphics()
sg.font = rasterFont
rasterFontMetrics = sg.fontMetrics
sg.dispose()
rasterFontSize = fontSize
}
val baseFont = rasterFont ?: return null
val fm0 = rasterFontMetrics ?: return null
// 分段：命中的 B 站表情标记画成图片，其余按文字、取弹幕自带颜色。
val segs = DanmakuEmotes.split(text)
val segWidths = FloatArray(segs.size)
var totalW = 0f
for (i2 in segs.indices) {
val wpx = when (val s = segs[i2]) {
is DanmakuEmotes.Seg.Txt -> fm0.stringWidth(s.s).toFloat()
is DanmakuEmotes.Seg.Emo -> DanmakuEmotes.boxPx(fm0)
}
segWidths[i2] = wpx
totalW += wpx
}
val tw = totalW.toInt().coerceAtLeast(1)
val th = fm0.height.coerceAtLeast(1)
val bi = java.awt.image.BufferedImage(
kotlin.math.ceil((tw + 8) * devScale).toInt().coerceAtLeast(1),
kotlin.math.ceil((th + 8) * devScale).toInt().coerceAtLeast(1),
java.awt.image.BufferedImage.TYPE_INT_ARGB)
val ig = bi.createGraphics()
ig.scale(devScale.toDouble(), devScale.toDouble())
ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
ig.setRenderingHint(RenderingHints.KEY_RENDERING,
RenderingHints.VALUE_RENDER_QUALITY)
ig.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
RenderingHints.VALUE_FRACTIONALMETRICS_ON)
ig.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
RenderingHints.VALUE_INTERPOLATION_BILINEAR)
ig.font = baseFont
val ifm = ig.fontMetrics
val bx = 4
val by = ifm.ascent + 4
if (strokeRadius > 0) {
// 描边只描文字段（表情图自带轮廓，不描边）
ig.color = Color(0, 0, 0, 230)
var sx = bx.toFloat()
for (i2 in segs.indices) {
val s = segs[i2]
if (s is DanmakuEmotes.Seg.Txt) {
for (ox in -strokeRadius..strokeRadius) {
for (oy in -strokeRadius..strokeRadius) {
if (ox == 0 && oy == 0) continue
if (ox * ox + oy * oy > strokeRadius * strokeRadius + strokeRadius) continue
ig.drawString(s.s, sx + ox, (by + oy).toFloat())
}
}
}
sx += segWidths[i2]
}
}
// 正文：文字段用弹幕自带颜色（白色即与旧版完全一致），表情段贴图；取不到图时回退画原标记文字，不丢内容。
ig.color = Color((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, alphaInt)
var cx = bx.toFloat()
for (i2 in segs.indices) {
when (val s = segs[i2]) {
is DanmakuEmotes.Seg.Txt -> ig.drawString(s.s, cx, by.toFloat())
is DanmakuEmotes.Seg.Emo -> {
val img = DanmakuEmotes.imageFor(s.token)
if (img != null) {
val box = DanmakuEmotes.boxPx(fm0).toInt().coerceAtLeast(1)
ig.drawImage(img, cx.toInt(), by - ifm.ascent, box, box, null)
} else {
ig.drawString(s.token, cx, by.toFloat())
}
}
}
cx += segWidths[i2]
}
ig.dispose()
rasterTextWidths[cacheKey(text, color)] = tw.toFloat()
return bi
}

/** 启动驱动 Timer（timer == null 时才建，避免重复）。removeNotify 停掉后若面板被重新挂上，addNotify 会调它重建，避免 Timer 停了就再也不动、弹幕永远不显示。 */
private fun startTimer() {
if (timer != null) return
com.muse.gomepc.player.DebugLog.d("Danmaku", "面板 Timer 已启动")
// 帧统计清零：重建 Timer 后第一帧间隔无意义（可能跨越长时间停顿），不计入统计。
lastFrameNs = 0L
frameStatCount = 0L
frameStatTotalMs = 0.0
frameStatMaxMs = 0.0
frameStatDrop33 = 0L
frameStatSevere50 = 0L
// 30fps 驱动；无弹幕/未启用时不重绘，避免透明 JWindow 空转拖慢 DWM 合成
val t = Timer(33) {
// 帧时间统计：每 tick 记一次帧间隔（当前时间 - 上一帧时间，nanoTime 计时）。
// 放在 tick 最开头，统计的是帧与帧之间的真实间隔（含上一 tick 的执行耗时 + EDT 等待）。
// 本 tick 的真实间隔（秒）：tick 抖动时步进按真实间隔走，否则位移按固定 0.033 前进、
// tick 实际 40ms 一次时运动忽快忽慢 = 视觉抖动。首次 tick 用 0.033 默认值。
var tickDt = 0.033f
val frameNowNs = System.nanoTime()
if (lastFrameNs != 0L) {
val intervalMs = (frameNowNs - lastFrameNs) / 1_000_000.0
tickDt = (intervalMs / 1000.0).toFloat().coerceIn(0f, 0.1f)
frameStatCount++
frameStatTotalMs += intervalMs
if (intervalMs > frameStatMaxMs) frameStatMaxMs = intervalMs
if (intervalMs > 50.0) frameStatDrop33++
if (intervalMs > 100.0) frameStatSevere50++
}
lastFrameNs = frameNowNs
// P0-D4：enabled=false 时显式 wantVisible=false 并隐藏窗口。
// 原代码整个 Timer 体包在 if (engine.enabled) 里，关闭后 wantVisible stale、
// 窗口常驻。现每次都处理关闭分支。
val win = hostWindow()
// 每 tick 只取一次视频矩形，诊断与发射共用，避免重复 invoke。
val rect = videoRectInParent?.invoke()
// 诊断（5 秒一记，无条件）：之前诊断藏在 vw>0 分支里，尺寸为 0 / 未启用时
// 静默跳过，日志里什么都看不到，无法定位“发射没被尝试”。
if (engine.pool.isNotEmpty()) {
val diagNow = System.currentTimeMillis()
if (diagNow - lastDiagMs >= 5000) {
lastDiagMs = diagNow
val vwD = rect?.width?: width
val vhD = rect?.height?: height
com.muse.gomepc.player.DebugLog.d("Danmaku", "面板状态: 启用=${engine.enabled} 池=${engine.pool.size} 进度=${engine.currentTimeSec} 视图=${vwD}x${vhD} 在屏=${engine.items.size} wantVisible=$wantVisible 窗口可见=${win?.isVisible} 面板可见=$isVisible")
// 帧统计：随 5 秒诊断一起记一行（平均/最大帧间隔、>50ms 掉帧、>100ms 严重卡顿），记完清零，统计窗口为两次诊断之间。
val avgFrameMs = if (frameStatCount > 0) frameStatTotalMs / frameStatCount else 0.0
val avg1 = kotlin.math.round(avgFrameMs * 10) / 10.0
val max1 = kotlin.math.round(frameStatMaxMs * 10) / 10.0
com.muse.gomepc.player.DebugLog.d("Danmaku", "帧统计：平均 ${avg1}ms 最大 ${max1}ms 掉帧 ${frameStatDrop33} 次 严重 ${frameStatSevere50} 次")
frameStatCount = 0L
frameStatTotalMs = 0.0
frameStatMaxMs = 0.0
frameStatDrop33 = 0L
frameStatSevere50 = 0L
}
}
if (!engine.enabled) {
wantVisible = false
hadItems = false
emptyFrames = 0
if (win!= null && win.isVisible) win.isVisible = false
} else {
var vw = rect?.width?: width
var vh = rect?.height?: height
// 尺寸兜底：面板自身还没布局出尺寸时用宿主窗口尺寸（面板填满窗口），
// 否则 vw=0 会静默跳过整 tick，step 永远不跑。
if (vw <= 0 && win != null && win.width > 0) vw = win.width
if (vh <= 0 && win != null && win.height > 0) vh = win.height
if (vw > 0) {
engine.viewWidth = vw.toFloat()
engine.viewHeight = vh.toFloat()
val fontSize = (engine.fontSp * 1.5f).toInt().coerceAtLeast(12)
// P1-D1：尺寸/字号确定后重算行数。
engine.recalcRows(fontSize.toFloat())
// 字号不变时复用 Font/FontMetrics，不再每 tick deriveFont + 查表。
if (tickFontSize != fontSize || tickFont == null) {
tickFont = danmakuFont.deriveFont(fontSize.toFloat())
tickFontMetrics = getFontMetrics(tickFont)
tickFontSize = fontSize
}
val fm = tickFontMetrics!!
try {
engine.step(tickDt) { text -> DanmakuEmotes.width(text, fm) }
} catch (err: Throwable) {
// step 抛异常时本 tick 剩余工作全跳过（诊断在外层已记），节流记一笔，
// 否则异常静默、发射永远不跑，日志里看不到任何原因。
val errNow = System.currentTimeMillis()
if (errNow - lastTimerErrMs >= 5000) {
lastTimerErrMs = errNow
com.muse.gomepc.player.DebugLog.d("Danmaku", "面板 step 异常: ${err.message}")
}
}
// 有弹幕才显示窗口并重绘；无弹幕时隐藏窗口，DWM 零开销
// 延迟未到可见时间的弹幕不算“有弹幕”。
val now = System.currentTimeMillis()
val hasItems = engine.items.any { now >= it.visibleAt}
if (hasItems) {
wantVisible = true
emptyFrames = 0
if (win!= null &&!win.isVisible) win.isVisible = true
// 预取光栅化：本 tick 刚变可见的弹幕先入队后台渲染，paint 时多已就绪。
val pfAlpha = (255 * engine.alpha).toInt().coerceIn(26, 255)
val pfScale = try { (graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0).toFloat() } catch (_: Throwable) { 1f }
.let { if (it.isFinite() && it >= 1f) it.coerceAtMost(4f) else 1f }
val pfStroke = currentStrokeRadius()
for (pd in engine.items) {
if (now < pd.visibleAt) continue
if (pd.row < 0) continue
enqueueRaster(cacheKey(pd.text, pd.color), pd.text, pd.color, fontSize, pfAlpha, pfStroke, pfScale)
}
repaintDirty(now, fontSize, fm)
hadItems = true
} else if (hadItems) {
// 弹幕刚清空：宽限期内保持可见（弹幕间隙短暂为空是常态，立即隐藏会时隐时现），
// 连续 15 帧无弹幕才真正隐藏。
emptyFrames++
if (emptyFrames >= 15) {
wantVisible = false
if (win!= null && win.isVisible) win.isVisible = false
hadItems = false
emptyFrames = 0
} else {
wantVisible = true
}
} else {
wantVisible = false
}

}
}
}
timer = t
t.start()
}

init {
startTimer()
}

override fun paintComponent(g: Graphics) {
super.paintComponent(g)
val g2 = g as Graphics2D
// 文本渲染提示：灰度抗锯齿 + 高质量 + 分数度量 + 双线性插值（位图缩放）。
// LCD 子像素抗锯齿在透明/视频背景上会镶边，不用。
g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
// 弹幕位图缩放走双线性：双三次在缩放显示下每帧对约 20 张位图做缩放绘制，开销过大且移动文字看不出差别。
g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
// HiDPI 设备像素比：字形缓存按设备像素渲染，否则 1x 位图被放大到 125%/150% 缩放屏上会模糊。
val devScale = try { (graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0).toFloat() } catch (_: Throwable) { 1f }
.let { if (it.isFinite() && it >= 1f) it.coerceAtMost(4f) else 1f }
// P2-D8：先裁剪再垫底。原代码先全屏 fillRect 再 clip，垫底超出视频区。
val vr = videoRectInParent?.invoke()
if (vr!= null) {
g.clipRect(vr.x, vr.y, vr.width, vr.height)
}
// 💥 ：给 Windows 操作系统垫一块肉眼看不见的空气垫（Alpha=1）
g2.color = java.awt.Color(0, 0, 0, 1)
g2.fillRect(0, 0, width, height)

if (!engine.enabled) return
// 字号：与 Compose 版一致（fontSp sp → px，桌面 density≈1）
val fontSize = (engine.fontSp * 1.5f).toInt().coerceAtLeast(12)
// 字号不变时复用 Font/FontMetrics/ascent，不再每 paint 构造 Font + 查表。
if (paintFontSize != fontSize || paintFont == null) {
paintFont = try {
Font("Noto Sans CJK SC", Font.BOLD, fontSize)
} catch (_: Throwable) {
Font("SansSerif", Font.BOLD, fontSize)
}
paintFontMetrics = getFontMetrics(paintFont)
paintAscent = paintFontMetrics!!.ascent
paintFontSize = fontSize
}
val baseFont = paintFont!!
val dx = vr?.x?: 0
val dy = vr?.y?: 0
// 字号/透明度/描边/设备像素比变了清缓存
val alphaInt = (255 * engine.alpha).toInt().coerceIn(26, 255)
if (fontSize!= cachedFontSize || alphaInt!= cachedAlpha || engine.strokePx!= cachedStrokePx || devScale != cachedScale) {
synchronized(textCache) { textCache.clear() }
rasterPending.clear()
rasterTextWidths.clear()
rasterGeneration++
cachedFontSize = fontSize
cachedAlpha = alphaInt
cachedStrokePx = engine.strokePx
cachedScale = devScale
}
// ascent 每帧只算一次，别在循环里调 getFontMetrics（字号不变时复用缓存）。
val ascent = paintAscent
// P2-D7：绘制用快照，避免遍历实时列表时并发修改；延迟未到可见时间的跳过。
val now = System.currentTimeMillis()
val snapshot = engine.items.toList()
// P1-D3：strokePx 实际生效，0 则不描边；>0 按半径画描边（上限 4px 防性能爆炸）。
val strokeRadius = if (engine.strokePx <= 0f) 0
else kotlin.math.ceil(engine.strokePx.toDouble()).toInt().coerceIn(1, 4)
// 行距每帧只算一次，不在循环里重复 rowBaseline→rowPitchPx（纯算术，但逐项重算无意义）。
val rowPitch = engine.rowPitchPx(fontSize.toFloat())
for (d in snapshot) {
if (now < d.visibleAt) continue
val baselineY = (d.row + 0.8f) * rowPitch
val x = (d.x + dx).toInt()
val y = (baselineY + dy).toInt()
try {
// paint 只 blit 已光栅化的位图；未就绪的入队后台光栅化，本帧跳过（下一帧补上，不丢弹幕）。
val key = cacheKey(d.text, d.color)
val img = synchronized(textCache) { textCache[key] }
if (img == null) {
enqueueRaster(key, d.text, d.color, fontSize, alphaInt, strokeRadius, devScale)
continue
}
if (d.textWidth < 0) rasterTextWidths[key]?.let { d.textWidth = it }
// devScale=1 时目标尺寸就是原尺寸，走无缩放 blit 快路径，避免每项每帧进缩放绘制管线。
if (devScale == 1f) {
g2.drawImage(img, x, y - ascent - 4, null)
} else {
g2.drawImage(img, x, y - ascent - 4, (img.width / devScale).toInt(), (img.height / devScale).toInt(), null)
}
} catch (_: Throwable) {}
}
}
}
