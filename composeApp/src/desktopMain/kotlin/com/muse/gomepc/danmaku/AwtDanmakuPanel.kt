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
// 上一帧是否有弹幕：用于弹幕清空时隐藏窗口
private var hadItems = false
// 连续无弹幕帧数：弹幕间隙短暂为空时不立即隐藏窗口（防时隐时现闪烁），
// 连续 15 帧（约 0.5s）无弹幕才隐藏。
private var emptyFrames = 0
// 诊断节流时间戳（面板 Timer 里 5 秒一记状态）。
private var lastDiagMs = 0L
// Timer 体异常日志节流。
private var lastTimerErrMs = 0L
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

/** 启动驱动 Timer（timer == null 时才建，避免重复）。removeNotify 停掉后若面板被重新挂上，addNotify 会调它重建，避免 Timer 停了就再也不动、弹幕永远不显示。 */
private fun startTimer() {
if (timer != null) return
com.muse.gomepc.player.DebugLog.d("Danmaku", "面板 Timer 已启动")
// 30fps 驱动；无弹幕/未启用时不重绘，避免透明 JWindow 空转拖慢 DWM 合成
val t = Timer(33) {
// P0-D4：enabled=false 时显式 wantVisible=false 并隐藏窗口。
// 原代码整个 Timer 体包在 if (engine.enabled) 里，关闭后 wantVisible stale、
// 窗口常驻。现每次都处理关闭分支。
val win = hostWindow()
// 诊断（5 秒一记，无条件）：之前诊断藏在 vw>0 分支里，尺寸为 0 / 未启用时
// 静默跳过，日志里什么都看不到，无法定位“发射没被尝试”。
if (engine.pool.isNotEmpty()) {
val diagNow = System.currentTimeMillis()
if (diagNow - lastDiagMs >= 5000) {
lastDiagMs = diagNow
val vwD = videoRectInParent?.invoke()?.width?: width
val vhD = videoRectInParent?.invoke()?.height?: height
com.muse.gomepc.player.DebugLog.d("Danmaku", "面板状态: 启用=${engine.enabled} 池=${engine.pool.size} 进度=${engine.currentTimeSec} 视图=${vwD}x${vhD} 在屏=${engine.items.size} wantVisible=$wantVisible 窗口可见=${win?.isVisible} 面板可见=$isVisible")
}
}
if (!engine.enabled) {
wantVisible = false
hadItems = false
emptyFrames = 0
if (win!= null && win.isVisible) win.isVisible = false
} else {
var vw = videoRectInParent?.invoke()?.width?: width
var vh = videoRectInParent?.invoke()?.height?: height
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
val fm = getFontMetrics(danmakuFont.deriveFont(fontSize.toFloat()))
try {
engine.step(0.033f) { text -> fm.stringWidth(text).toFloat()}
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
repaint()
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
// 文本渲染提示：灰度抗锯齿 + 高质量 + 分数度量 + 双三次插值。
// LCD 子像素抗锯齿在透明/视频背景上会镶边，不用。
g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
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
// 明确指定中文字体
val baseFont = try {
Font("Noto Sans CJK SC", Font.BOLD, fontSize)
} catch (_: Throwable) {
Font("SansSerif", Font.BOLD, fontSize)
}
val dx = vr?.x?: 0
val dy = vr?.y?: 0
// 字号/透明度/描边/设备像素比变了清缓存
val alphaInt = (255 * engine.alpha).toInt().coerceIn(26, 255)
if (fontSize!= cachedFontSize || alphaInt!= cachedAlpha || engine.strokePx!= cachedStrokePx || devScale != cachedScale) {
textCache.clear()
cachedFontSize = fontSize
cachedAlpha = alphaInt
cachedStrokePx = engine.strokePx
cachedScale = devScale
}
// ascent 每帧只算一次，别在循环里调 getFontMetrics
val ascent = getFontMetrics(baseFont).ascent
// P2-D7：绘制用快照，避免遍历实时列表时并发修改；延迟未到可见时间的跳过。
val now = System.currentTimeMillis()
val snapshot = engine.items.toList()
// P1-D3：strokePx 实际生效，0 则不描边；>0 按半径画描边（上限 4px 防性能爆炸）。
val strokeRadius = if (engine.strokePx <= 0f) 0
else kotlin.math.ceil(engine.strokePx.toDouble()).toInt().coerceIn(1, 4)
for (d in snapshot) {
if (now < d.visibleAt) continue
val baselineY = engine.rowBaseline(d.row, fontSize.toFloat())
val x = (d.x + dx).toInt()
val y = (baselineY + dy).toInt()
try {
// 缓存命中直接画，未命中才渲染（描边只做一次）
val img = textCache.getOrPut(d.text) {
val fm0 = getFontMetrics(baseFont)
val tw = fm0.stringWidth(d.text).coerceAtLeast(1)
val th = fm0.height.coerceAtLeast(1)
// 按设备像素建图 + ig.scale(devScale)：同一份绘制代码以设备分辨率光栅化。
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
ig.font = baseFont
val ifm = ig.fontMetrics
val bx = 4
val by = ifm.ascent + 4
if (strokeRadius > 0) {
// 描边：以半径内圆形偏移多遍绘制（半径 1 时即旧 8 方向 1px）
ig.color = Color(0, 0, 0, 230)
for (ox in -strokeRadius..strokeRadius) {
for (oy in -strokeRadius..strokeRadius) {
if (ox == 0 && oy == 0) continue
if (ox * ox + oy * oy > strokeRadius * strokeRadius + strokeRadius) continue
ig.drawString(d.text, bx + ox, by + oy)
}
}
}
// 白字
ig.color = Color(255, 255, 255, alphaInt)
ig.drawString(d.text, bx, by)
ig.dispose()
if (d.textWidth < 0) d.textWidth = tw.toFloat()
bi
}
g2.drawImage(img, x, y - ascent - 4, (img.width / devScale).toInt(), (img.height / devScale).toInt(), null)
} catch (_: Throwable) {}
}
}
}
