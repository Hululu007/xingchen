package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.RichLyrics
import com.fongmi.android.tv.player.lyrics.LyricsWord
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 歌词引擎（AnchoredLyricEngine 两套装配同居一份）：封面窗装配（CoverCurrentLyric，经典页歌词区）
 * 与全屏链装配（AppleLyricsList，全屏歌词页）共用同一批纯函数、平滑时钟与行块渲染，按场景装配——
 * 数值认 gz-player-values.md §八/§十六/§十八与 LyricsViewKt 常量亲验——
 * 窗口 前2/后6、当前行最多 2 视觉行、行距 20dp（COVER_LINE_GAP）、行高 ×4/3、字号读设置页内键（默认 22）。
 * 当前行二分定位＋平滑时钟帧驱动（回退 ≤350ms 忽略）；非当前行 A 组逐行模糊（§十八结案：
 * 封面窗/经典页内传 A 组、全屏链传 B 组）×设置模糊档系数，200ms/bs4.a 动画；
 * 行 alpha 再乘底部淡出（起始 0.8、地板 0.45、bottomFadeKeep 分段 smoothstep 表）。
 * 逐字：每词 [start,end] 线性提亮、上浮 −0.09em（easeOutExpo、时长 max(1100ms, 词长)）、
 * 长音（≥1000ms）光晕＋放大＋−0.05em 正弦浮动、行末 +400ms 拖尾。
 * 色（手机主题无 palette、immersiveDark）：已唱 #FFEAEAEA、当前行未唱 白 0.42、非当前 白 0.30、翻译 白 0.55。
 * 点行＝seek 到该行行首（定案）；纯文本无时间轴＝无当前行、静态显示、不滚动。
 * 字形斜度 −0.08 是他亲定的星辰自有口径（瓜子无此项），只施于歌词块。
 */

// ===== 瓜子常量（LyricsViewKt 亲验） =====
private const val WINDOW_BEFORE = 2
private const val WINDOW_AFTER = 6
private const val CURRENT_MAX_VISUAL_LINES = 2
private const val LINE_GAP_DP = 20f
private const val FADE_START = 0.8f
private const val FADE_FLOOR = 0.45f
private const val WORD_LIFT_EM = -0.09f
private const val WORD_LIFT_MIN_MS = 1100L
private const val EMPH_MIN_MS = 1000L
private const val EMPH_TAIL_MS = 400L
private const val EMPH_FLOAT_EM = -0.05f
private const val EMPH_SCALE_PEAK = 1.2f
private const val SMOOTH_REGRESSION_MS = 350L
private const val BLUR_ANIM_MS = 200

// ===== 全屏链常量（§八 AppleLyricsList 口径） =====
private const val FULL_WINDOW_BEFORE = 3
private const val FULL_WINDOW_AFTER = 15
private const val FULL_LINE_GAP_DP = 6f
private const val FULL_LINE_HEIGHT_RATIO = 1.3f
private val FULL_LETTER_SPACING_SP = (-0.2f).sp
private const val TOP_FADE_HEIGHT_DP = 40f
private const val ANCHOR_RATIO = 0.22f
private const val ANCHOR_MIN_DP = 64f
private const val ANCHOR_MAX_DP = 120f
private const val ANCHOR_CAP_RATIO = 0.28f
private const val REANCHOR_DELAY_MS = 5000L
private const val SNAP_VIEWPORT_FACTOR = 1.5f
private const val SETTLE_SPRING_DAMPING = 1.16f
private const val SETTLE_SPRING_STIFFNESS = 237.3f
private const val SEEK_SPRING_DAMPING = 0.833f
private const val SEEK_SPRING_STIFFNESS = 100f
private const val ALIGN_TWEEN_MS = 350

/** 手滑惯性门槛（§八「|速度|>60」；速度单位未定名，按 px/s 装配）。 */
private const val FLING_VELOCITY_MIN = 60f

/** 翻译行字号比：值表/jadx 未抠到页内翻译字号，暂按主行 ×0.75（交卷清单单列、待真机对）。 */
private const val TRANSLATION_SIZE_RATIO = 0.75f

/** 字形斜度（他亲定「带一点斜」；瓜子无此数值，星辰自有口径）。 */
private const val LYRIC_SKEW_X = -0.08f

private val COLOR_SUNG = Color(0xFFEAEAEA)
private val COLOR_UNSUNG = Color.White.copy(alpha = 0.42f)
private val COLOR_INACTIVE = Color.White.copy(alpha = 0.30f)
private val COLOR_TRANSLATION = Color.White.copy(alpha = 0.55f)

private const val WRAP_PUNCTUATION = ",.;:!?，。；：！？、）】》」』’”)]}>~…\""
private const val WRAP_PUNCT_REWARD = 0.6
private const val WRAP_SPACE_REWARD = 0.4
private const val WRAP_CJK_PENALTY = 0.15
private const val WRAP_NORMAL_PENALTY = 0.5
private const val WRAP_OVERFLOW_PENALTY = 1000.0

// ================= 纯函数（瓜子公式逐项移植） =================

/** bottomFadeKeep（LyricsViewKt 亲读移植）：停点 [0,.3,.55,.78,1]→[1,.85,.62,.48,floor]，段内 smoothstep 插值。 */
internal fun bottomFadeKeep(progress: Float, floor: Float): Float {
    val xs = floatArrayOf(0f, 0.3f, 0.55f, 0.78f, 1f)
    val ys = floatArrayOf(1f, 0.85f, 0.62f, 0.48f, floor)
    val v = progress.coerceIn(0f, 1f)
    for (i in 0 until 4) {
        if (v <= xs[i + 1]) {
            val t = (v - xs[i]) / (xs[i + 1] - xs[i])
            val s = (3f - 2f * t) * t * t
            return (ys[i + 1] - ys[i]) * s + ys[i]
        }
    }
    return ys[4]
}

/** amllEaseOutExpo（LyricsViewKt 亲读移植）。 */
internal fun easeOutExpo(f: Float): Float {
    if (f >= 1f) return 1f
    return 1f - 2f.pow(-10f * f)
}

/** isCJK（LyricsViewKt 亲读移植，码点区间原样）。 */
internal fun isCjkChar(c: Char): Boolean {
    val v = c.code
    if (v in 12352 until 12544) return true
    if (v in 19968 until 40960) return true
    if (v in 13312 until 19904) return true
    if (v < 63744 || v >= 64256) return v in 12288 until 12352
    return true
}

/** 当前行下标：二分取 timeMs ≤ now 的最后一行；now 在首行之前回 -1（瓜子/通知卡同口径）。 */
internal fun findCurrentLine(lines: List<RichLyrics.Line>, nowMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].main.timeMs <= nowMs) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/**
 * A 组逐行模糊（§十八结案，单位 dp，d＝行序−当前行序，带符号）：
 * 上方 min(2.0−d, 5)、下方 min(d+1.0, 5)，当前行 0。返回值是引擎存档值，
 * 实际半径按 §十八「×2 存档、半径＝量化值/2」在调用处减半。
 */
internal fun coverBlurStored(distance: Int): Float {
    return when {
        distance == 0 -> 0f
        distance < 0 -> min(2.0f - distance, 5f)
        else -> min(distance + 1.0f, 5f)
    }
}

/**
 * B 组逐行模糊（§十八结案，全屏链装配，单位 dp）：min(|d|×2.5, 16)，上下同式
 * （全屏调用点 z6=true 亲验）。返回值同 A 组为引擎存档值，调用处减半作半径。
 */
internal fun fullBlurStored(distance: Int): Float {
    return min(abs(distance) * 2.5f, 16f)
}

/** smoothstep（§八顶部淡入与 bottomFadeKeep 段内插值同形）：t 钳 [0,1] 后 t²(3−2t)。 */
internal fun smoothstep01(t: Float): Float {
    val v = t.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/**
 * 全屏链锚点（§八定谳，px）：v＝clamp(0.22×容器高, 64dp, 120dp)，再封顶 0.28×容器高，
 * 减字号一半对到当前行中线（dig 亲验为 E0(24dp)/2、24＝该调用点字号），返回值＝当前行行顶 y。
 */
internal fun fullAnchorTopPx(
    containerHeightPx: Float,
    minAnchorPx: Float,
    maxAnchorPx: Float,
    fontSizePx: Float
): Float {
    var v = ANCHOR_RATIO * containerHeightPx
    v = v.coerceIn(minAnchorPx, maxAnchorPx)
    v = min(v, ANCHOR_CAP_RATIO * containerHeightPx)
    return v - fontSizePx / 2f
}

/** 换行分段（瓜子 WrapSegment 的本侧等价物）：word 非空＝逐字词段，空＝纯文本段。 */
internal class WrapSeg(val text: String, val word: LyricsWord?, val widthPx: Float) {
    val isSpace: Boolean = text.isBlank()
}

/**
 * calcBalancedBreaks 移植（LyricsViewKt §十六）：总宽 ≤W 不断行；
 * DP 自尾向前，一行代价＝剩余空白²，单段独占超宽＝超宽²×1000；
 * 断点附加按断点前一段判定（优先级 smali 定谳）：末字标点 −(0.6W)² → 空格段/末尾空格 −(0.4W)²
 * → 含 CJK +(0.15W)² → 其余 +(0.5W)²。返回每条视觉行的起始段下标（含 0）。
 */
internal fun calcBalancedBreaks(segs: List<WrapSeg>, maxWidthPx: Float): IntArray {
    val n = segs.size
    if (n == 0 || maxWidthPx <= 0f) return intArrayOf(0)
    val prefix = FloatArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + segs[i].widthPx
    if (prefix[n] <= maxWidthPx) return intArrayOf(0)
    val w = maxWidthPx.toDouble()
    val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
    val next = IntArray(n + 1) { -1 }
    cost[n] = 0.0
    for (i in n - 1 downTo 0) {
        var j = i + 1
        while (j <= n) {
            val lineWidth = (prefix[j] - prefix[i]).toDouble()
            if (lineWidth > w && j > i + 1) break
            val lineCost = if (lineWidth <= w) {
                val slack = w - lineWidth
                slack * slack
            } else {
                val over = lineWidth - w
                over * over * WRAP_OVERFLOW_PENALTY
            }
            val adjust = if (j < n) breakAdjust(segs[j - 1], w) else 0.0
            val total = lineCost + adjust + cost[j]
            if (total < cost[i]) {
                cost[i] = total
                next[i] = j
            }
            j++
        }
    }
    val starts = ArrayList<Int>()
    var k = 0
    starts.add(0)
    while (k in 0 until n) {
        val nx = next[k]
        if (nx <= k || nx >= n) break
        starts.add(nx)
        k = nx
    }
    return starts.toIntArray()
}

private fun breakAdjust(seg: WrapSeg, w: Double): Double {
    val text = seg.text
    if (text.isNotEmpty() && WRAP_PUNCTUATION.indexOf(text[text.length - 1]) >= 0) {
        val r = WRAP_PUNCT_REWARD * w
        return -(r * r)
    }
    if (seg.isSpace || text.endsWith(' ')) {
        val r = WRAP_SPACE_REWARD * w
        return -(r * r)
    }
    if (text.any { isCjkChar(it) }) {
        val p = WRAP_CJK_PENALTY * w
        return p * p
    }
    val p = WRAP_NORMAL_PENALTY * w
    return p * p
}

/** 纯文本分段：java.text.BreakIterator（瓜子同用此件切段）。 */
internal fun breakSegments(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getWordInstance(Locale.ROOT)
    iterator.setText(text)
    val out = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        out.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return out
}

/** 一行的视觉行切分（主行＋翻译行各自按同一 DP 换行）。 */
private class LineWrap(
    val mainRows: List<List<WrapSeg>>,
    val transRows: List<List<WrapSeg>>
)

private fun wrapText(
    measurer: TextMeasurer,
    text: String,
    words: List<LyricsWord>,
    style: TextStyle,
    maxWidthPx: Float
): List<List<WrapSeg>> {
    if (text.isEmpty() || maxWidthPx <= 0f) return emptyList()
    val segs: List<WrapSeg> = if (words.isNotEmpty()) {
        words.map { w -> WrapSeg(w.text, w, measurer.measure(w.text, style).size.width.toFloat()) }
    } else {
        breakSegments(text).map { s -> WrapSeg(s, null, measurer.measure(s, style).size.width.toFloat()) }
    }
    if (segs.isEmpty()) return emptyList()
    val starts = calcBalancedBreaks(segs, maxWidthPx)
    val rows = ArrayList<List<WrapSeg>>(starts.size)
    for (r in starts.indices) {
        val from = starts[r]
        val to = if (r + 1 < starts.size) starts[r + 1] else segs.size
        if (from < to) rows.add(segs.subList(from, to))
    }
    return rows
}

// ================= 共用平滑时钟 =================

/**
 * 平滑时钟（封面窗与全屏链共用一份，不许两处各写一套）：
 * 播放时帧驱动推进；服务位置回退 ≤350ms 忽略（SMOOTH_REGRESSION_TOLERANCE_MS），
 * 大步回退＝seek 直接对齐；暂停时跟随服务位置。歌词偏移（＋＝提前）叠在平滑时上。
 * 纯文本无时间轴恒回偏移量（调用方按 untimed 分流、不用此时钟定位当前行）。
 */
@Composable
private fun rememberLyricNowMs(
    lines: List<RichLyrics.Line>,
    untimed: Boolean,
    progress: PlayerProgress,
    isPlaying: Boolean,
    offsetMs: Long
): Long {
    val servicePos = rememberUpdatedState(progress.positionMs)
    val playingNow = rememberUpdatedState(isPlaying)
    var smoothPos by remember(lines) { mutableLongStateOf(progress.positionMs) }
    LaunchedEffect(lines, untimed) {
        if (untimed) return@LaunchedEffect
        var prevFrame = -1L
        while (true) {
            withFrameNanos { frame ->
                if (prevFrame >= 0L && playingNow.value) {
                    val dtMs = (frame - prevFrame) / 1_000_000L
                    if (dtMs in 1..100) smoothPos += dtMs
                }
                prevFrame = frame
                val svc = servicePos.value
                when {
                    svc > smoothPos -> smoothPos = svc
                    smoothPos - svc > SMOOTH_REGRESSION_MS -> smoothPos = svc
                }
            }
        }
    }
    LaunchedEffect(progress.positionMs, isPlaying, untimed) {
        if (!isPlaying && !untimed) smoothPos = progress.positionMs
    }
    return (if (untimed) 0L else smoothPos) + offsetMs
}

// ================= 窗口组件 =================

@Composable
fun ClassicLyricWindow(
    lines: List<RichLyrics.Line>,
    untimed: Boolean,
    progress: PlayerProgress,
    isPlaying: Boolean,
    wordMode: Boolean,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 读设置（现行存档键，引擎只读不写）：页内字号/字重/对齐/模糊档/翻译开关/歌词偏移
    val fontSizeSp = MusicPlayerSettings.getCoverLyricFontSize()
    val fontWeight = when (MusicPlayerSettings.getLyricWeight()) {
        1 -> FontWeight.W800
        2 -> FontWeight.W900
        else -> FontWeight.W700
    }
    val align = MusicPlayerSettings.getLyricAlign()
    val blurCoefficient = MusicPlayerSettings.blurCoefficient()
    val showTranslation = MusicPlayerSettings.isShowTranslation()
    val offsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong()

    val mainStyle = TextStyle(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 4f / 3f).sp,
        fontWeight = fontWeight
    )
    val transFontSizeSp = fontSizeSp * TRANSLATION_SIZE_RATIO
    val transStyle = TextStyle(
        fontSize = transFontSizeSp.sp,
        lineHeight = (transFontSizeSp * 4f / 3f).sp,
        fontWeight = fontWeight
    )
    val lineHeightPx = with(density) { mainStyle.lineHeight.toPx() }
    val transLineHeightPx = with(density) { transStyle.lineHeight.toPx() }
    val gapPx = with(density) { LINE_GAP_DP.dp.toPx() }
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }

    var boxWidthPx by remember { mutableStateOf(0) }
    var boxHeightPx by remember { mutableStateOf(0) }

    // 平滑时钟（与全屏链共用同一实现，见 rememberLyricNowMs）
    val nowMs = rememberLyricNowMs(lines, untimed, progress, isPlaying, offsetMs)

    val cur = if (untimed) -1 else findCurrentLine(lines, nowMs)
    val anchor = if (cur >= 0) cur else 0
    val from = max(0, anchor - WINDOW_BEFORE)
    val to = min(lines.size, anchor + WINDOW_AFTER + 1)

    // 窗口内各行换行（宽度就绪后算；宽度未就绪先按整行单行占位，下一帧即真值）
    val wraps: List<LineWrap?> = remember(lines, boxWidthPx, fontSizeSp, fontWeight, showTranslation) {
        if (boxWidthPx <= 0) {
            List(lines.size) { null }
        } else {
            lines.map { line ->
                val mainRows = wrapText(
                    measurer, line.main.text,
                    if (line.main.hasWords()) line.main.words else emptyList(),
                    mainStyle, boxWidthPx.toFloat()
                )
                val transText = if (showTranslation) line.translation else null
                val transRows = if (!transText.isNullOrEmpty()) {
                    wrapText(measurer, transText, emptyList(), transStyle, boxWidthPx.toFloat())
                } else {
                    emptyList()
                }
                LineWrap(mainRows, transRows)
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                boxWidthPx = it.width
                boxHeightPx = it.height
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 锚点行顶（淡出 span 的原点）：窗口内当前行块的顶边 y
            var topY = 0f
            var anchorTopY = 0f
            val tops = HashMap<Int, Float>()
            for (i in from until to) {
                tops[i] = topY
                if (i == anchor) anchorTopY = topY
                topY += blockHeightPx(wraps.getOrNull(i), i == cur, lineHeightPx, transLineHeightPx, CURRENT_MAX_VISUAL_LINES) +
                    if (i < to - 1) gapPx else 0f
            }
            val span = boxHeightPx - anchorTopY
            for (i in from until to) {
                val wrap = wraps.getOrNull(i)
                val distance = i - cur
                val keep = when {
                    untimed -> 1f
                    span <= 0f -> 1f
                    else -> {
                        val rowPos = (tops[i] ?: 0f) - anchorTopY
                        val fadeStart = FADE_START * span
                        if (rowPos <= fadeStart || span <= fadeStart) {
                            1f
                        } else {
                            bottomFadeKeep((rowPos - fadeStart) / (span - fadeStart), FADE_FLOOR)
                        }
                    }
                }
                LyricLineBlock(
                    line = lines[i],
                    wrap = wrap,
                    isCurrent = i == cur,
                    sung = cur >= 0 && i < cur,
                    blurStored = coverBlurStored(distance),
                    capMainRows = CURRENT_MAX_VISUAL_LINES,
                    untimed = untimed,
                    keep = keep,
                    blurCoefficient = blurCoefficient,
                    useWords = wordMode && i == cur,
                    nowMs = nowMs,
                    mainStyle = mainStyle,
                    transStyle = transStyle,
                    align = align,
                    fontSizePx = fontSizePx,
                    onClick = if (untimed) null else ({ onLineClick(i) })
                )
                if (i < to - 1) Spacer(Modifier.height(LINE_GAP_DP.dp))
            }
        }
    }
}

/** 一行块高：主行视觉行（当前行封顶 capMainRows，全屏链传 Int.MAX_VALUE 即不封顶）×行高＋翻译视觉行×翻译行高；换行未就绪按 1 主行估。 */
private fun blockHeightPx(
    wrap: LineWrap?,
    isCurrent: Boolean,
    lineHeightPx: Float,
    transLineHeightPx: Float,
    capMainRows: Int
): Float {
    if (wrap == null) return lineHeightPx
    val mainRows = if (isCurrent) min(wrap.mainRows.size, capMainRows) else wrap.mainRows.size
    return mainRows * lineHeightPx + wrap.transRows.size * transLineHeightPx
}

@Composable
private fun LyricLineBlock(
    line: RichLyrics.Line,
    wrap: LineWrap?,
    isCurrent: Boolean,
    sung: Boolean,
    blurStored: Float,
    capMainRows: Int,
    untimed: Boolean,
    keep: Float,
    blurCoefficient: Float,
    useWords: Boolean,
    nowMs: Long,
    mainStyle: TextStyle,
    transStyle: TextStyle,
    align: Int,
    fontSizePx: Float,
    onClick: (() -> Unit)?
) {
    // 模糊目标＝装配传入的存档值（封面窗 A 组／全屏 B 组）×档位系数，半径减半（§十八）；200ms/bs4.a 动画（瓜子 lyricRowBlur）
    val blurTarget = if (untimed || blurCoefficient == 0f) {
        0f
    } else {
        blurStored * blurCoefficient / 2f
    }
    val blurAnim by animateFloatAsState(
        targetValue = blurTarget,
        animationSpec = tween(BLUR_ANIM_MS, easing = EaseBs4A),
        label = "lyricRowBlur"
    )
    var mod = Modifier.fillMaxWidth()
    if (blurAnim > 0.05f) mod = mod.blur(blurAnim.dp)
    if (keep < 0.999f) mod = mod.alpha(keep)
    if (onClick != null) {
        mod = mod.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    }
    val arrangement = when (align) {
        1 -> Arrangement.Center
        2 -> Arrangement.End
        else -> Arrangement.Start
    }
    // 标准模式当前行整行上色、已唱行同色（瓜子色链）；逐字模式走 WordCell 不用此色
    val rowColor = if (isCurrent || sung) COLOR_SUNG else COLOR_INACTIVE
    Column(mod) {
        Column(Modifier.lyricSkew()) {
            val mainRows = wrap?.mainRows ?: listOf(
                listOf(WrapSeg(line.main.text, null, 0f))
            )
            val shownRows = if (isCurrent) mainRows.take(capMainRows) else mainRows
            for (row in shownRows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                    if (useWords) {
                        for (seg in row) {
                            val word = seg.word
                            if (word != null) {
                                WordCell(
                                    word = word,
                                    lineStartMs = line.main.timeMs,
                                    nowMs = nowMs,
                                    style = mainStyle,
                                    fontSizePx = fontSizePx
                                )
                            } else {
                                // 当前行无逐字数据：回落标准模式整行上色
                                Text(
                                    text = seg.text,
                                    style = mainStyle,
                                    color = COLOR_SUNG,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }
                    } else {
                        Text(
                            text = row.joinToString("") { it.text },
                            style = mainStyle,
                            color = rowColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
            val transRows = wrap?.transRows ?: emptyList()
            for (row in transRows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                    Text(
                        text = row.joinToString("") { it.text },
                        style = transStyle,
                        color = COLOR_TRANSLATION,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}

/**
 * 逐字格：底＝当前行未唱色，提亮层按词内进度横向裁出（每词自己的 [start,end] 线性）；
 * 上浮 −0.09em×easeOutExpo（时长 max(1100ms, 词长)）；长音（≥1000ms）进行中：
 * 放大至多约 1.2、−0.05em 正弦浮动、身后一层同字模糊副本作光晕（瓜子为 BlurMaskFilter
 * 边缘光晕，Compose 无等价件，以模糊副本近似）；行末 +400ms 拖尾计入强调窗。
 */
@Composable
private fun WordCell(
    word: LyricsWord,
    lineStartMs: Long,
    nowMs: Long,
    style: TextStyle,
    fontSizePx: Float
) {
    val startAbs = lineStartMs + word.startOffsetMs
    val duration = word.durationMs
    val fill = if (duration > 0L) {
        ((nowMs - startAbs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        if (nowMs >= startAbs) 1f else 0f
    }
    val liftWindow = max(WORD_LIFT_MIN_MS, duration)
    val liftP = easeOutExpo(((nowMs - startAbs).toFloat() / liftWindow.toFloat()).coerceIn(0f, 1f))
    var emphasis = 0f
    if (duration >= EMPH_MIN_MS && nowMs >= startAbs && nowMs < startAbs + duration + EMPH_TAIL_MS) {
        val p = ((nowMs - startAbs).toFloat() / (duration + EMPH_TAIL_MS).toFloat()).coerceIn(0f, 1f)
        emphasis = sin(p * PI.toFloat())
    }
    val scale = 1f + (EMPH_SCALE_PEAK - 1f) * emphasis
    val translateY = WORD_LIFT_EM * fontSizePx * liftP + EMPH_FLOAT_EM * fontSizePx * emphasis
    Box(
        Modifier.graphicsLayer {
            translationY = translateY
            scaleX = scale
            scaleY = scale
        }
    ) {
        if (emphasis > 0.03f) {
            Text(
                text = word.text,
                style = style,
                color = COLOR_SUNG.copy(alpha = 0.55f * emphasis),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.blur((5f * emphasis).dp)
            )
        }
        Text(
            text = word.text,
            style = style,
            color = COLOR_UNSUNG,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        if (fill > 0f) {
            Text(
                text = word.text,
                style = style,
                color = COLOR_SUNG,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.drawWithContent {
                    val contentScope = this
                    clipRect(0f, 0f, size.width * fill, size.height) {
                        with(contentScope) { drawContent() }
                    }
                }
            )
        }
    }
}

/** 字形微斜（他亲定口径，仅歌词块）：画布 skew −0.08。 */
private fun Modifier.lyricSkew(): Modifier = drawWithContent {
    drawContext.canvas.save()
    drawContext.canvas.skew(LYRIC_SKEW_X, 0f)
    drawContent()
    drawContext.canvas.restore()
}

// ================= 全屏链装配（AppleLyricsList 口径，§八） =================

/**
 * 全屏歌词列表（全屏歌词页歌词区）：与封面窗共用同一引擎纯函数族与行块渲染，装配换全屏链——
 * 字号走全屏键（默认 24）、行高 ×1.3、字距 −0.2、行距 6dp、窗口 前3/后15、当前行不封顶；
 * 锚点＝fullAnchorTopPx（像素偏移定谳）；模糊 B 组 min(|d|×2.5,16)×档位系数；
 * 压暗同源 bottomFadeKeep（锚下起始 0.8、地板 0.45），顶部另走 40dp 渐隐带 smoothstep 淡入。
 * 手滑浏览：偏移钳在首尾行界内、|速度|>60 走惯性衰减、松手 5000ms 回锚
 * （|偏移|>视口×1.5 直接 snap、否则 lyricSettleSpring 1.16/237.3）；
 * 当前行变更行对齐 tween(350, bs4.a)；点行 seek 立即回锚＋lyricSeekPosSpring(0.833, 100)。
 * 纯文本无时间轴：无当前行、从顶静态排、排满容器为止、不滚动（§八）。
 */
@Composable
fun FullLyricList(
    lines: List<RichLyrics.Line>,
    untimed: Boolean,
    progress: PlayerProgress,
    isPlaying: Boolean,
    wordMode: Boolean,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    // 读设置（现行存档键，引擎只读不写）：全屏字号/字重/对齐/模糊档/翻译开关/歌词偏移
    val fontSizeSp = MusicPlayerSettings.getFullLyricFontSize()
    val fontWeight = when (MusicPlayerSettings.getLyricWeight()) {
        1 -> FontWeight.W800
        2 -> FontWeight.W900
        else -> FontWeight.W700
    }
    val align = MusicPlayerSettings.getLyricAlign()
    val blurCoefficient = MusicPlayerSettings.blurCoefficient()
    val showTranslation = MusicPlayerSettings.isShowTranslation()
    val offsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong()

    val mainStyle = TextStyle(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * FULL_LINE_HEIGHT_RATIO).sp,
        fontWeight = fontWeight,
        letterSpacing = FULL_LETTER_SPACING_SP
    )
    val transFontSizeSp = fontSizeSp * TRANSLATION_SIZE_RATIO
    val transStyle = TextStyle(
        fontSize = transFontSizeSp.sp,
        lineHeight = (transFontSizeSp * FULL_LINE_HEIGHT_RATIO).sp,
        fontWeight = fontWeight,
        letterSpacing = FULL_LETTER_SPACING_SP
    )
    val lineHeightPx = with(density) { mainStyle.lineHeight.toPx() }
    val transLineHeightPx = with(density) { transStyle.lineHeight.toPx() }
    val gapPx = with(density) { FULL_LINE_GAP_DP.dp.toPx() }
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    val topFadePx = with(density) { TOP_FADE_HEIGHT_DP.dp.toPx() }
    val minAnchorPx = with(density) { ANCHOR_MIN_DP.dp.toPx() }
    val maxAnchorPx = with(density) { ANCHOR_MAX_DP.dp.toPx() }

    var boxWidthPx by remember { mutableStateOf(0) }
    var boxHeightPx by remember { mutableStateOf(0) }

    // 平滑时钟（与封面窗共用同一实现）
    val nowMs = rememberLyricNowMs(lines, untimed, progress, isPlaying, offsetMs)
    val cur = if (untimed) -1 else findCurrentLine(lines, nowMs)

    // 全行换行（宽度就绪后算；未就绪先按整行单行占位，下一帧即真值）——块高须与当前行无关，锚点才稳
    val wraps: List<LineWrap?> = remember(lines, boxWidthPx, fontSizeSp, fontWeight, showTranslation) {
        if (boxWidthPx <= 0) {
            List(lines.size) { null }
        } else {
            lines.map { line ->
                val mainRows = wrapText(
                    measurer, line.main.text,
                    if (line.main.hasWords()) line.main.words else emptyList(),
                    mainStyle, boxWidthPx.toFloat()
                )
                val transText = if (showTranslation) line.translation else null
                val transRows = if (!transText.isNullOrEmpty()) {
                    wrapText(measurer, transText, emptyList(), transStyle, boxWidthPx.toFloat())
                } else {
                    emptyList()
                }
                LineWrap(mainRows, transRows)
            }
        }
    }

    // 内容系行顶坐标与总高（行间 6dp；当前行不封顶，块高不随 cur 变）
    val tops = remember(wraps, gapPx, lineHeightPx, transLineHeightPx) {
        val arr = FloatArray(lines.size)
        var acc = 0f
        for (i in lines.indices) {
            arr[i] = acc
            acc += blockHeightPx(wraps.getOrNull(i), false, lineHeightPx, transLineHeightPx, Int.MAX_VALUE) +
                if (i < lines.size - 1) gapPx else 0f
        }
        arr
    }
    val totalHeightPx = if (lines.isEmpty()) 0f else {
        (tops.getOrElse(lines.size - 1) { 0f }) +
            blockHeightPx(wraps.getOrNull(lines.size - 1), false, lineHeightPx, transLineHeightPx, Int.MAX_VALUE)
    }

    val anchorIdx = if (cur >= 0) cur else 0
    val curTopPx = tops.getOrElse(anchorIdx) { 0f }
    val anchorTopPx = if (boxHeightPx > 0) {
        fullAnchorTopPx(boxHeightPx.toFloat(), minAnchorPx, maxAnchorPx, fontSizePx)
    } else {
        0f
    }
    // 手滑偏移界：下拖不让首行顶过锚点、上拖不让尾行底跌破当前行块底
    val curBlockHPx = blockHeightPx(wraps.getOrNull(anchorIdx), false, lineHeightPx, transLineHeightPx, Int.MAX_VALUE)
    val browseMax = curTopPx
    val browseMin = -(totalHeightPx - curTopPx - curBlockHPx).coerceAtLeast(0f)
    val browseMinState = rememberUpdatedState(browseMin)
    val browseMaxState = rememberUpdatedState(browseMax)

    val alignAnim = remember(lines) { Animatable(0f) }
    val browseAnim = remember(lines) { Animatable(0f) }
    var aligned by remember(lines) { mutableStateOf(false) }
    var seekAnimNext by remember(lines) { mutableStateOf(false) }
    var reanchorJob by remember { mutableStateOf<Job?>(null) }
    var browseJob by remember { mutableStateOf<Job?>(null) }
    val curTopState = rememberUpdatedState(curTopPx)

    // 行对齐：首帧 snap 落位；其后当前行变更 tween(350, bs4.a)；点行 seek 走 lyricSeekPosSpring
    LaunchedEffect(anchorIdx, boxWidthPx, boxHeightPx, lines) {
        if (untimed || boxWidthPx <= 0 || boxHeightPx <= 0) return@LaunchedEffect
        val target = curTopState.value
        when {
            !aligned -> {
                alignAnim.snapTo(target)
                aligned = true
            }
            seekAnimNext -> {
                seekAnimNext = false
                alignAnim.animateTo(
                    target,
                    spring(dampingRatio = SEEK_SPRING_DAMPING, stiffness = SEEK_SPRING_STIFFNESS)
                )
            }
            else -> alignAnim.animateTo(target, tween(ALIGN_TWEEN_MS, easing = EaseBs4A))
        }
    }

    fun scheduleReanchor() {
        reanchorJob?.cancel()
        reanchorJob = scope.launch {
            delay(REANCHOR_DELAY_MS)
            val off = browseAnim.value
            if (off != 0f) {
                if (abs(off) > SNAP_VIEWPORT_FACTOR * boxHeightPx) {
                    browseAnim.snapTo(0f)
                } else {
                    browseAnim.animateTo(
                        0f,
                        spring(dampingRatio = SETTLE_SPRING_DAMPING, stiffness = SETTLE_SPRING_STIFFNESS)
                    )
                }
            }
        }
    }

    val gestureMod = if (untimed) {
        Modifier
    } else {
        Modifier.pointerInput(lines) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 新手势作废待回锚与惯性（Job.cancel 非挂起，手势受限作用域内可直调；
                // Animatable 的挂起调用一律走中枢协程，本作用域内不直调）
                reanchorJob?.cancel()
                browseJob?.cancel()
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var lastY = down.position.y
                var dragValue = browseAnim.value
                var moved = false
                var released = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    val dy = change.position.y - lastY
                    lastY = change.position.y
                    if (dy != 0f) {
                        moved = true
                        dragValue = (dragValue + dy).coerceIn(browseMinState.value, browseMaxState.value)
                        val target = dragValue
                        scope.launch { browseAnim.snapTo(target) }
                        change.consume()
                    }
                }
                if (moved && released) {
                    val velocityY = tracker.calculateVelocity().y
                    browseJob = scope.launch {
                        if (abs(velocityY) > FLING_VELOCITY_MIN) {
                            browseAnim.updateBounds(browseMinState.value, browseMaxState.value)
                            browseAnim.animateDecay(velocityY, exponentialDecay())
                        }
                        scheduleReanchor()
                    }
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                boxWidthPx = it.width
                boxHeightPx = it.height
            }
            .then(gestureMod)
    ) {
        if (untimed) {
            // 纯文本静态：从顶排起、排满容器为止，无当前行、不滚动、不接点 seek
            Column(Modifier.fillMaxWidth()) {
                var usedPx = 0f
                for (i in lines.indices) {
                    val h = blockHeightPx(wraps.getOrNull(i), false, lineHeightPx, transLineHeightPx, Int.MAX_VALUE)
                    if (i > 0 && boxHeightPx > 0 && usedPx + h > boxHeightPx) break
                    LyricLineBlock(
                        line = lines[i],
                        wrap = wraps.getOrNull(i),
                        isCurrent = false,
                        sung = false,
                        blurStored = 0f,
                        capMainRows = Int.MAX_VALUE,
                        untimed = true,
                        keep = 1f,
                        blurCoefficient = blurCoefficient,
                        useWords = false,
                        nowMs = nowMs,
                        mainStyle = mainStyle,
                        transStyle = transStyle,
                        align = align,
                        fontSizePx = fontSizePx,
                        onClick = null
                    )
                    usedPx += h
                    if (i < lines.size - 1) {
                        Spacer(Modifier.height(FULL_LINE_GAP_DP.dp))
                        usedPx += gapPx
                    }
                }
            }
        } else if (aligned) {
            val from = max(0, anchorIdx - FULL_WINDOW_BEFORE)
            val to = min(lines.size, anchorIdx + FULL_WINDOW_AFTER + 1)
            val baseOffset = anchorTopPx - alignAnim.value + browseAnim.value -
                (tops.getOrElse(from) { 0f })
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, baseOffset.roundToInt()) }
            ) {
                val span = boxHeightPx - anchorTopPx
                for (i in from until to) {
                    val screenTop = (tops.getOrElse(i) { 0f }) + baseOffset
                    val bottomKeep = run {
                        val rowPos = screenTop - anchorTopPx
                        if (span <= 0f || rowPos <= FADE_START * span || span <= FADE_START * span) {
                            1f
                        } else {
                            bottomFadeKeep((rowPos - FADE_START * span) / (span - FADE_START * span), FADE_FLOOR)
                        }
                    }
                    val topFactor = if (screenTop >= topFadePx) {
                        1f
                    } else {
                        smoothstep01(screenTop / topFadePx)
                    }
                    LyricLineBlock(
                        line = lines[i],
                        wrap = wraps.getOrNull(i),
                        isCurrent = i == cur,
                        sung = cur >= 0 && i < cur,
                        blurStored = fullBlurStored(i - cur),
                        capMainRows = Int.MAX_VALUE,
                        untimed = false,
                        keep = bottomKeep * topFactor,
                        blurCoefficient = blurCoefficient,
                        useWords = wordMode && i == cur,
                        nowMs = nowMs,
                        mainStyle = mainStyle,
                        transStyle = transStyle,
                        align = align,
                        fontSizePx = fontSizePx,
                        onClick = {
                            // 点行 seek 立即回锚（瓜子口径）：浏览偏移即清、对齐走 seek 弹簧
                            reanchorJob?.cancel()
                            seekAnimNext = true
                            scope.launch { browseAnim.snapTo(0f) }
                            onLineClick(i)
                        }
                    )
                    if (i < to - 1) Spacer(Modifier.height(FULL_LINE_GAP_DP.dp))
                }
            }
        }
    }
}
