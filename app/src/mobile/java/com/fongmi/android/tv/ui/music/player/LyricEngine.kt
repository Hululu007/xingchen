package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.RichLyrics
import com.fongmi.android.tv.player.lyrics.LyricsWord
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 页内歌词引擎（经典页歌词区）：瓜子 AnchoredLyricEngine 的封面窗装配（CoverCurrentLyric），
 * 数值认 gz-player-values.md §八/§十六/§十八与 LyricsViewKt 常量亲验——
 * 窗口 前2/后6、当前行最多 2 视觉行、行距 20dp（COVER_LINE_GAP）、行高 ×4/3、字号读设置页内键（默认 22）。
 * 当前行二分定位＋平滑时钟帧驱动（回退 ≤350ms 忽略）；非当前行 A 组逐行模糊（§十八结案：
 * 封面窗/经典页内传 A 组，全屏链的 B 组是下一单的事）×设置模糊档系数，200ms/bs4.a 动画；
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

    // 平滑时钟：播放时帧驱动推进；服务位置回退 ≤350ms 忽略（瓜子 SMOOTH_REGRESSION_TOLERANCE_MS），
    // 大步回退＝seek 直接对齐；暂停时跟随服务位置。歌词偏移（＋＝提前）叠在平滑时上。
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
    val nowMs = (if (untimed) 0L else smoothPos) + offsetMs

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
                topY += blockHeightPx(wraps.getOrNull(i), i == cur, lineHeightPx, transLineHeightPx) +
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
                    distance = distance,
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

/** 一行块高：主行视觉行（当前行封顶 2）×行高＋翻译视觉行×翻译行高；换行未就绪按 1 主行估。 */
private fun blockHeightPx(
    wrap: LineWrap?,
    isCurrent: Boolean,
    lineHeightPx: Float,
    transLineHeightPx: Float
): Float {
    if (wrap == null) return lineHeightPx
    val mainRows = if (isCurrent) min(wrap.mainRows.size, CURRENT_MAX_VISUAL_LINES) else wrap.mainRows.size
    return mainRows * lineHeightPx + wrap.transRows.size * transLineHeightPx
}

@Composable
private fun LyricLineBlock(
    line: RichLyrics.Line,
    wrap: LineWrap?,
    isCurrent: Boolean,
    sung: Boolean,
    distance: Int,
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
    // 模糊目标＝A 组存档值×档位系数，半径减半（§十八）；200ms/bs4.a 动画（瓜子 lyricRowBlur）
    val blurTarget = if (untimed || blurCoefficient == 0f) {
        0f
    } else {
        coverBlurStored(distance) * blurCoefficient / 2f
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
            val shownRows = if (isCurrent) mainRows.take(CURRENT_MAX_VISUAL_LINES) else mainRows
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
