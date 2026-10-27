package com.fongmi.android.tv.ui.music.player

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Choreographer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.RichLyrics
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放器统一时钟（全场唯一帧循环）：Choreographer 单回调，引用计数启停；
 * 平滑播放位置只许在绘制阶段读（Canvas 绘制 lambda／graphicsLayer lambda），
 * 组合阶段只读 PlayerStateHolder.progress 的 250ms 低频量。回退 ≤350ms 忽略
 * （SMOOTH_REGRESSION_TOLERANCE_MS，gz-player-values.md §八）。
 */
object PlayerClock {

    val smoothPositionMs = mutableLongStateOf(0L)
    val frameTimeMs = mutableLongStateOf(0L)

    private var refCount = 0
    private var basePosMs = 0L
    private var baseUptimeMs = 0L
    private var playing = false
    private var posted = false
    private var framesUntilUptimeMs = 0L

    private val choreographer = Choreographer.getInstance()

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            val uptimeMs = frameTimeNanos / 1_000_000L
            frameTimeMs.longValue = uptimeMs
            if (playing) {
                val est = basePosMs + (uptimeMs - baseUptimeMs)
                val cur = smoothPositionMs.longValue
                if (est >= cur || cur - est > 350L) smoothPositionMs.longValue = est
            }
            if (refCount > 0 && (playing || uptimeMs < framesUntilUptimeMs)) post()
        }
    }

    private fun post() {
        if (posted) return
        posted = true
        choreographer.postFrameCallback(callback)
    }

    /** 进度低频量到达时同步基点（250ms 一次）：平滑位置由此续推，不跳变。 */
    fun syncBase(positionMs: Long, isPlaying: Boolean) {
        basePosMs = positionMs
        baseUptimeMs = SystemClock.uptimeMillis()
        playing = isPlaying
        if (!isPlaying) smoothPositionMs.longValue = positionMs
        if (refCount > 0 && isPlaying) post()
    }

    /** 暂停态也要帧的短时需求（歌词回锚/滚动收尾）：只续命帧循环，不改基点。 */
    fun requestFrames(durationMs: Long) {
        framesUntilUptimeMs = SystemClock.uptimeMillis() + durationMs
        if (refCount > 0) post()
    }

    @Synchronized
    fun acquire() {
        refCount++
        if (playing) post()
    }

    @Synchronized
    fun release() {
        if (refCount > 0) refCount--
    }
}

/** 时钟挂接（条与歌词舞台共用）：进出组合 acquire/release，进度变化 syncBase。 */
@Composable
fun rememberPlayerClock(progress: PlayerProgress, isPlaying: Boolean) {
    DisposableEffect(Unit) {
        PlayerClock.acquire()
        onDispose { PlayerClock.release() }
    }
    LaunchedEffect(progress.positionMs, progress.durationMs, isPlaying) {
        PlayerClock.syncBase(progress.positionMs, isPlaying)
    }
}

/** 歌词四色（§八色供给链由调用方按主题组好传入，引擎只消费）。 */
data class LyricPalette(
    val sung: Color,
    val activeUnsung: Color,
    val inactive: Color,
    val translation: Color
) {
    companion object {
        /** 手机三主题口径：已唱 SungSoft、当前未唱 白 .42、非当前 白 .30、翻译 白 .55。 */
        val ImmersiveDark = LyricPalette(
            sung = Color(0xFFEAEAEA),
            activeUnsung = Color.White.copy(alpha = 0.42f),
            inactive = Color.White.copy(alpha = 0.30f),
            translation = Color.White.copy(alpha = 0.55f)
        )
    }
}

// ================= 测量（后台线程按 (歌词表,宽,字号,字重) 缓存，量完再上屏） =================

private class LineMeasure(
    val mainLayout: StaticLayout,
    val transLayout: StaticLayout?,
    val heightPx: Float,
    val stepPx: Float,
    val wordSpans: List<WordSpan>
)

private class WordSpan(val charStart: Int, val charEnd: Int, val startMs: Long, val durMs: Long)

private class LyricMeasure(
    val lines: List<LineMeasure>,
    val tops: FloatArray,
    val totalHeightPx: Float,
    /** 布局构建时的同一份 Paint：StaticLayout.draw 用它上色，逐行颜色/alpha/模糊全改这一份。 */
    val mainPaint: TextPaint,
    val transPaint: TextPaint,
    val transGapPx: Float
)

private fun lyricTypeface(weightLevel: Int): Typeface {
    val w = when (weightLevel) {
        1 -> 800
        2 -> 900
        else -> 700
    }
    return if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.DEFAULT, w, false)
    } else {
        Typeface.DEFAULT_BOLD
    }
}

private fun buildMeasure(
    lines: List<RichLyrics.Line>,
    widthPx: Int,
    fontSizePx: Float,
    lineHeightFactor: Float,
    lineGapPx: Float,
    letterSpacingEm: Float,
    weightLevel: Int,
    alignMode: Int,
    showTranslation: Boolean,
    maxVisualLines: Int
): LyricMeasure {
    val alignment = when (alignMode) {
        1 -> Layout.Alignment.ALIGN_CENTER
        2 -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        typeface = lyricTypeface(weightLevel)
        letterSpacing = letterSpacingEm
    }
    val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx * 0.75f
        typeface = lyricTypeface(weightLevel)
        letterSpacing = letterSpacingEm
    }
    val transGapPx = fontSizePx * 0.12f
    val out = ArrayList<LineMeasure>(lines.size)
    val tops = FloatArray(lines.size)
    var top = 0f
    lines.forEachIndexed { i, line ->
        tops[i] = top
        val text = line.main?.text ?: ""
        val mainBuilder = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(alignment)
            .setLineSpacing(0f, lineHeightFactor)
            .setIncludePad(false)
        if (maxVisualLines > 0) mainBuilder.setMaxLines(maxVisualLines)
        val mainLayout = mainBuilder.build()
        var height = mainLayout.height.toFloat()
        var transLayout: StaticLayout? = null
        val transText = line.translation
        if (showTranslation && !transText.isNullOrEmpty()) {
            transLayout = StaticLayout.Builder.obtain(transText, 0, transText.length, transPaint, widthPx)
                .setAlignment(alignment)
                .setLineSpacing(0f, lineHeightFactor)
                .setIncludePad(false)
                .build()
            height += transGapPx + transLayout.height
        }
        val spans = ArrayList<WordSpan>()
        val words = line.main?.words
        if (words != null && words.isNotEmpty() && text.isNotEmpty()) {
            var charPos = 0
            for (w in words) {
                val wt = w.text ?: ""
                if (wt.isEmpty()) continue
                val start = charPos
                val end = min(charPos + wt.length, text.length)
                if (end > start) {
                    spans.add(
                        WordSpan(
                            start, end,
                            (line.main?.timeMs ?: 0L) + w.startOffsetMs,
                            w.durationMs
                        )
                    )
                }
                charPos += wt.length
                if (charPos >= text.length) break
            }
        }
        val step = height + lineGapPx
        out.add(LineMeasure(mainLayout, transLayout, height, step, spans))
        top += step
    }
    return LyricMeasure(out, tops, top, paint, transPaint, transGapPx)
}

// ================= 绘制期小函数（§八/§十五定谳公式） =================

/** 底部淡出分段表：停点 [0,.3,.55,.78,1]→[1,.85,.62,.48,floor]，段内 smoothstep。 */
private fun bottomFadeKeep(f: Float, floor: Float): Float {
    val xs = floatArrayOf(0f, 0.3f, 0.55f, 0.78f, 1f)
    val ys = floatArrayOf(1f, 0.85f, 0.62f, 0.48f, floor)
    val x = f.coerceIn(0f, 1f)
    for (i in 0 until xs.size - 1) {
        if (x <= xs[i + 1]) {
            val t = (x - xs[i]) / (xs[i + 1] - xs[i])
            val s = t * t * (3f - 2f * t)
            return ys[i] + (ys[i + 1] - ys[i]) * s
        }
    }
    return floor
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun easeOutCubic(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    val u = 1f - x
    return 1f - u * u * u
}

/**
 * 逐行模糊半径（绘制期参数、0.5px 量化，§十八）：B 组（全屏/Apple 列表）
 * 上下同式 min(|d|×2.5, 16)；A 组（封面窗/经典页内）上 min(2.0−d, 5)、下 min(d+1.0, 5)。
 * 最后乘档位系数（off 0／light 0.45／medium 1.0）。
 */
private fun blurRadiusPx(distance: Int, groupB: Boolean, coefficient: Float): Float {
    if (distance == 0 || coefficient <= 0f) return 0f
    val raw = if (groupB) {
        min(abs(distance) * 2.5f, 16f)
    } else if (distance < 0) {
        min(2.0f - distance, 5f)
    } else {
        min(distance + 1.0f, 5f)
    }
    val quantized = (raw * coefficient * 2f).roundToInt() / 2f
    return if (quantized < 0.75f) 0f else quantized
}

// ================= 舞台 =================

private class LyricScrollState {
    var shiftPx = 0f
    var velocityPx = 0f
    var browseDeltaPx = 0f
    var browseStartDelta = 0f
    var dragging = false
    var flingVelocityPx = 0f
    var gestureEndUptimeMs = 0L
    var seekSpringUntilMs = 0L
    var lastCurIndex = -2
    var lastFrameMs = -1L
    var viewportHPx = 0f
    var drawnTops: List<FloatArray> = emptyList()
}

/**
 * 歌词引擎（一件两装配，数值认 gz-player-values.md §八）：
 * 单个 Canvas 绘制面＋原生文本绘制；时钟只在绘制 lambda 内读；当前行/滚动/逐字/
 * 长音光晕/逐行模糊全是绘制期计算，组合阶段只收线表与配置。点行 seek、手滑浏览
 * （5000ms 回锚）都在本件内闭环。
 *
 * @param fullAnchor true=全屏像素锚（clamp(.22H,64dp,120dp) 封顶 .28H、对行中线）；
 * false=经典页内分数锚（anchorFraction×容器高、对行内 anchorFraction 点）。
 */
@Composable
fun LyricStage(
    lines: List<RichLyrics.Line>,
    untimed: Boolean,
    wordByWord: Boolean,
    fontSizeSp: Float,
    lineHeightFactor: Float,
    lineGapDp: Float,
    fullAnchor: Boolean,
    anchorFraction: Float,
    windowBefore: Int,
    windowAfter: Int,
    blurGroupB: Boolean,
    blurCoefficient: Float,
    palette: LyricPalette,
    showTranslation: Boolean,
    alignMode: Int,
    weightLevel: Int,
    lyricOffsetMs: Long,
    maxVisualLines: Int,
    topFadeDp: Float,
    letterSpacingEm: Float,
    onLineSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    val lineGapPx = with(density) { lineGapDp.dp.toPx() }
    val topFadePx = with(density) { topFadeDp.dp.toPx() }
    val anchorMinPx = with(density) { 64.dp.toPx() }
    val anchorMaxPx = with(density) { 120.dp.toPx() }
    val scroll = remember { LyricScrollState() }
    var browseVersion by remember { mutableIntStateOf(0) }

    val measure by produceState<LyricMeasure?>(
        null,
        lines, widthPx, fontSizePx, weightLevel, alignMode, showTranslation, maxVisualLines,
        lineHeightFactor, lineGapPx, letterSpacingEm
    ) {
        value = if (widthPx <= 0 || lines.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                buildMeasure(
                    lines, widthPx, fontSizePx, lineHeightFactor, lineGapPx,
                    letterSpacingEm, weightLevel, alignMode, showTranslation, maxVisualLines
                )
            }
        }
    }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .pointerInput(lines, untimed) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dragTotalY = 0f
                    var moved = false
                    var lastY = down.position.y
                    var lastMoveMs = down.uptimeMillis
                    var velPxPerMs = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!moved && change.uptimeMillis - down.uptimeMillis < 300) {
                                val y = change.position.y
                                val hit = scroll.drawnTops.firstOrNull { y >= it[1] && y <= it[2] }
                                if (hit != null) {
                                    val idx = hit[0].toInt()
                                    val line = lines.getOrNull(idx)
                                    if (line?.main != null && !untimed) {
                                        onLineSeek(line.main.timeMs)
                                        scroll.browseDeltaPx = 0f
                                        scroll.shiftPx = 0f
                                        scroll.velocityPx = 0f
                                        scroll.seekSpringUntilMs =
                                            SystemClock.uptimeMillis() + 400L
                                        browseVersion++
                                    }
                                }
                            } else if (moved) {
                                scroll.flingVelocityPx = -velPxPerMs * 1000f
                                scroll.gestureEndUptimeMs = change.uptimeMillis
                            }
                            scroll.dragging = false
                            break
                        }
                        val dy = change.position.y - lastY
                        val dtMs = (change.uptimeMillis - lastMoveMs).coerceAtLeast(1)
                        lastY = change.position.y
                        lastMoveMs = change.uptimeMillis
                        dragTotalY += dy
                        if (!moved && abs(dragTotalY) > viewConfiguration.touchSlop) {
                            moved = true
                            scroll.dragging = true
                            scroll.browseStartDelta = scroll.browseDeltaPx
                            scroll.flingVelocityPx = 0f
                        }
                        if (moved) {
                            scroll.browseDeltaPx =
                                (scroll.browseStartDelta - dragTotalY)
                                    .coerceIn(-size.height.toFloat(), 100000f)
                            velPxPerMs = 0.75f * velPxPerMs + 0.25f * (dy / dtMs)
                            browseVersion++
                        }
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val m = measure ?: return@Canvas
            if (m.lines.isEmpty()) return@Canvas
            // 绘制期读时钟（本 lambda 读快照状态只触发重绘、不触发重组）
            val frameMs = PlayerClock.frameTimeMs.longValue
            val nowMs = PlayerClock.smoothPositionMs.longValue + lyricOffsetMs
            val dtSec = if (scroll.lastFrameMs < 0L) {
                0f
            } else {
                ((frameMs - scroll.lastFrameMs) / 1000f).coerceIn(0f, 0.05f)
            }
            scroll.lastFrameMs = frameMs
            scroll.viewportHPx = size.height
            val h = size.height

            // 当前行（二分取 timeMs≤now 最后一行；无时间轴无当前行）
            var cur = -1
            if (!untimed) {
                var lo = 0
                var hi = lines.size - 1
                var ans = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val t = lines[mid].main?.timeMs ?: Long.MAX_VALUE
                    if (t <= nowMs) {
                        ans = mid
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                cur = ans
            }
            // 切行吸震：当前行变更时把 shift 反向补偿一步，屏幕位置不断裂，再由弹簧收回
            if (!untimed && cur >= 0 && scroll.lastCurIndex >= 0 && cur != scroll.lastCurIndex &&
                cur < m.tops.size && scroll.lastCurIndex < m.tops.size
            ) {
                scroll.shiftPx -= (m.tops[cur] - m.tops[scroll.lastCurIndex])
            }
            if (cur >= 0) scroll.lastCurIndex = cur

            // 锚点（§八定谳公式）
            val anchorPx: Float
            val anchorWithinPx: Float
            if (fullAnchor) {
                var v = (0.22f * h).coerceIn(anchorMinPx, anchorMaxPx)
                val cap = 0.28f * h
                if (v > cap) v = cap
                anchorPx = v
                anchorWithinPx = fontSizePx / 2f
            } else {
                anchorPx = anchorFraction * h
                anchorWithinPx = anchorFraction * fontSizePx * lineHeightFactor
            }

            // 惯性与回锚（松手 5000ms 回锚，APPLE_BROWSE_REANCHOR_DELAY）
            if (!scroll.dragging && abs(scroll.flingVelocityPx) > 1f && dtSec > 0f) {
                scroll.browseDeltaPx += scroll.flingVelocityPx * dtSec
                scroll.flingVelocityPx *= exp(-3.2f * dtSec)
                if (abs(scroll.flingVelocityPx) <= 1f) {
                    scroll.flingVelocityPx = 0f
                    scroll.gestureEndUptimeMs = SystemClock.uptimeMillis()
                }
            }
            val uptimeNow = SystemClock.uptimeMillis()
            val reanchoring = !scroll.dragging && scroll.gestureEndUptimeMs > 0L &&
                uptimeNow - scroll.gestureEndUptimeMs > 5000L &&
                (scroll.browseDeltaPx != 0f || abs(scroll.shiftPx) > 0.5f)
            if (reanchoring) scroll.browseDeltaPx = scroll.shiftPx
            val targetShift = if (reanchoring) 0f else scroll.browseDeltaPx

            // 滚动弹簧（settle＝spring(1.16, 237.3)；点行 seek 后 400ms 内＝spring(0.833, 100)）
            if (dtSec > 0f) {
                val seekSpring = uptimeNow < scroll.seekSpringUntilMs
                val damping = if (seekSpring) 0.833f else 1.16f
                val stiffness = if (seekSpring) 100f else 237.3f
                val accel = -stiffness * (scroll.shiftPx - targetShift) -
                    2f * damping * sqrt(stiffness) * scroll.velocityPx
                scroll.velocityPx += accel * dtSec
                scroll.shiftPx += scroll.velocityPx * dtSec
                if (abs(scroll.shiftPx - targetShift) < 0.4f && abs(scroll.velocityPx) < 4f) {
                    scroll.shiftPx = targetShift
                    scroll.velocityPx = 0f
                }
            }
            if (scroll.shiftPx != targetShift || abs(scroll.velocityPx) > 0.1f ||
                abs(scroll.browseDeltaPx) > 0.5f
            ) {
                PlayerClock.requestFrames(150)
            }

            // 可见行绘制（颜色/alpha/模糊都改测量时那份 Paint，StaticLayout.draw 才吃得到）
            val canvas = drawContext.canvas.nativeCanvas
            val paint = m.mainPaint
            val transPaint = m.transPaint
            val drawn = ArrayList<FloatArray>()
            val curTop = if (cur >= 0 && cur < m.tops.size) m.tops[cur] else 0f
            var i = 0
            while (i < m.lines.size) {
                val lm = m.lines[i]
                val screenTop = if (untimed) {
                    m.tops[i] - scroll.shiftPx
                } else if (cur >= 0) {
                    anchorPx - anchorWithinPx + (m.tops[i] - curTop) - scroll.shiftPx
                } else {
                    m.tops[i] - scroll.shiftPx
                }
                val screenBottom = screenTop + lm.heightPx
                i++
                if (screenBottom < -lm.stepPx || screenTop > h + lm.stepPx) continue
                val idx = i - 1
                drawn.add(floatArrayOf(idx.toFloat(), screenTop, screenBottom))

                val distance = if (untimed || cur < 0) Int.MAX_VALUE else idx - cur
                var alpha = 1f
                // 淡出跨度：显式 windowAfter 优先；≤0 时按锚点偏移÷行步长派生（经典页内口径）
                val spanLines = if (windowAfter > 0) {
                    windowAfter.toFloat()
                } else {
                    ((h - anchorPx) / (fontSizePx * lineHeightFactor + lineGapPx) + 2f)
                        .coerceAtLeast(1f)
                }
                if (!untimed && cur >= 0 && distance > 0) {
                    val span = spanLines
                    val fadeStart = 0.8f * span
                    if (distance > fadeStart) {
                        val prog = ((distance - fadeStart) / (span - fadeStart)).coerceIn(0f, 1f)
                        alpha *= bottomFadeKeep(prog, 0.45f)
                    }
                }
                if (topFadePx > 0f && screenBottom < topFadePx) {
                    alpha *= smoothstep(screenBottom / topFadePx)
                }
                if (alpha <= 0.01f) continue

                val baseColor = when {
                    untimed || cur < 0 -> palette.sung
                    idx < cur -> palette.sung
                    idx == cur -> palette.activeUnsung
                    else -> palette.inactive
                }
                val blur = if (untimed || cur < 0) {
                    0f
                } else {
                    blurRadiusPx(distance, blurGroupB, blurCoefficient)
                }
                paint.maskFilter = if (blur > 0f && Build.VERSION.SDK_INT >= 31) {
                    BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                } else {
                    null
                }

                canvas.save()
                canvas.translate(0f, screenTop)
                val line = lines[idx]
                val isCurrent = !untimed && idx == cur
                if (isCurrent && wordByWord && lm.wordSpans.isNotEmpty() &&
                    line.main != null
                ) {
                    drawWordLine(
                        canvas, paint, lm, line.main.text, nowMs,
                        baseColor = palette.activeUnsung,
                        sungColor = palette.sung,
                        alpha = alpha,
                        fontSizePx = fontSizePx
                    )
                } else {
                    val drawColor = if (isCurrent && !wordByWord) palette.sung else baseColor
                    paint.color = drawColor.toArgb()
                    paint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                    lm.mainLayout.draw(canvas)
                }
                paint.maskFilter = null
                val transLayout = lm.transLayout
                if (transLayout != null) {
                    transPaint.color = palette.translation.toArgb()
                    transPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
                    canvas.translate(0f, lm.mainLayout.height + m.transGapPx)
                    transLayout.draw(canvas)
                }
                canvas.restore()
            }
            scroll.drawnTops = drawn
            // browseVersion 在此读一次：手势改浏览量时触发重绘（绘制订阅）
            @Suppress("UNUSED_EXPRESSION")
            browseVersion
        }
    }
}

/**
 * 当前行逐字绘制：每字 [start,end] 线性提亮＋上浮 −0.09em（easeOut／max(1100ms,字长)）；
 * 长音（≥1000ms）BlurMaskFilter 光晕＋放大上限 1.2＋−0.05em 正弦浮动。
 * 词位由 StaticLayout 的字偏移量取（换行行同样成立）。
 */
private fun drawWordLine(
    canvas: android.graphics.Canvas,
    paint: TextPaint,
    lm: LineMeasure,
    text: String,
    nowMs: Long,
    baseColor: Color,
    sungColor: Color,
    alpha: Float,
    fontSizePx: Float
) {
    val layout = lm.mainLayout
    val alphaInt = (alpha * 255f).roundToInt().coerceIn(0, 255)
    for (span in lm.wordSpans) {
        val wordText = text.substring(span.charStart, span.charEnd)
        if (wordText.isEmpty()) continue
        val dur = span.durMs.coerceAtLeast(1L)
        val frac = ((nowMs - span.startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        val visualLine = layout.getLineForOffset(span.charStart)
        val x = layout.getPrimaryHorizontal(span.charStart)
        val baseline = layout.getLineBaseline(visualLine).toFloat()
        val liftT = easeOutCubic(
            (nowMs - span.startMs).toFloat() / maxOf(1100f, dur.toFloat())
        )
        var liftPx = 0.09f * fontSizePx * liftT
        val isLong = span.durMs >= 1000L
        if (isLong && frac > 0f && frac < 1f) {
            liftPx += 0.05f * fontSizePx * sin(frac * Math.PI.toFloat())
        }
        val wordWidth = paint.measureText(wordText)
        // 未唱底
        paint.maskFilter = null
        paint.color = baseColor.toArgb()
        paint.alpha = alphaInt
        canvas.drawText(wordText, x, baseline - liftPx, paint)
        if (frac <= 0f) continue
        // 光晕（长音、进行中）：模糊一份已唱色垫底
        if (isLong && frac < 1f) {
            paint.maskFilter = BlurMaskFilter(fontSizePx * 0.35f, BlurMaskFilter.Blur.NORMAL)
            paint.color = sungColor.toArgb()
            paint.alpha = (alphaInt * 0.6f).roundToInt()
            val scale = 1f + 0.2f * sin(frac * Math.PI.toFloat())
            canvas.save()
            canvas.translate(x + wordWidth / 2f, baseline - liftPx)
            canvas.scale(scale, scale)
            canvas.drawText(wordText, -wordWidth / 2f, 0f, paint)
            canvas.restore()
            paint.maskFilter = null
        }
        // 已唱部分：裁剪到 frac 宽度
        paint.color = sungColor.toArgb()
        paint.alpha = alphaInt
        canvas.save()
        canvas.clipRect(
            x,
            layout.getLineTop(visualLine).toFloat() - fontSizePx * 0.4f,
            x + wordWidth * frac,
            layout.getLineBottom(visualLine).toFloat() + fontSizePx * 0.4f
        )
        canvas.drawText(wordText, x, baseline - liftPx, paint)
        canvas.restore()
    }
}

