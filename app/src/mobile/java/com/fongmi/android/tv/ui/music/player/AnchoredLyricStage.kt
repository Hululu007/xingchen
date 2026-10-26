package com.fongmi.android.tv.ui.music.player

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 歌词引擎一件（照瓜子 AnchoredLyricEngine，数值见 gz-player-values.md 第八/十五/十六/十八节）：
 * 页内歌词与后续全屏歌词面、各主题歌词面共用，喂参不同，不许再起第二套。
 *
 * 本单落经典页内口径：行距 20dp（COVER_LINE_GAP）、行高 ×4/3、当前行锚点 45% 高（视觉定案）、
 * 锚下跨度 80% 起淡出至地板 0.45（pos39/pos40 定谳，bottomFadeKeep 分段 smoothstep）、
 * 顶部 40dp 渐隐带、逐行模糊 B 组 min(|距离|×2.5dp, 16dp)×档位系数（API≥31、DECAL）、
 * 均衡断行 calcBalancedBreaks、平滑时间（帧驱动、回退 ≤350ms 忽略）、
 * 滚动 settle spring(1.16, ≈237.3)、点行 seek 回锚 spring(0.833, 100)、
 * 手滑偏移叠加（|偏移|>视口×1.5 直接 snap、|速度|>60dp/s 惯性、松手 5000ms 回锚）、
 * 逐字推进（[start,end] 线性插值、单调不回退、上浮 −0.09em、长音 ≥1000ms 光晕＋放大 ≤1.2＋−0.05em 浮动）、
 * 字斜 −0.12（他定的产品项，旧实现 setTextSkewX 同值）。
 * 色（手机主题沉浸深色、无 palette 供给）：已唱 #FFEAEAEA、当前未唱 白 .42、非当前 白 .30、翻译 白 .55。
 * 字号/粗细/模糊档/译音显隐/偏移读 MusicPlayerSettings 既有存档（页内键）。
 */

// 引擎常量（出处见类头）
private const val ANCHOR_FRACTION = 0.45f
private const val FADE_START = 0.8f
private const val FADE_FLOOR = 0.45f
private const val TOP_FADE_DP = 40f
private const val LINE_GAP_DP = 20f
private const val SKEW_X = -0.12f
private const val WORD_LIFT_EM = -0.09f
private const val WORD_LIFT_MIN_MS = 1100L
private const val EMPH_MIN_MS = 1000L
private const val EMPH_RELEASE_TAIL_MS = 400L
private const val REANCHOR_DELAY_MS = 5000L
private const val SMOOTH_TOLERANCE_MS = 350L
private const val BLUR_MAX_DP = 16f
private const val BLUR_PER_LINE_DP = 2.5f

private val ColorSungSoft = Color(0xFFEAEAEA)
private val ColorCurrentUnsung = Color.White.copy(alpha = 0.42f)
private val ColorInactive = Color.White.copy(alpha = 0.30f)
private val ColorTranslation = Color.White.copy(alpha = 0.55f)

/**
 * 歌词舞台装配口径（瓜子同一台引擎、多处喂参不同；默认＝经典页内口径，本单前行为不变）：
 * 全屏歌词面喂另一组（案卷第八节）：全屏字号键（默认 24、钳 16–40）、行高 ×1.3、
 * 字距 −0.2、行距 6dp、像素锚点（见 [fullScreenAnchorY]）；wordByWord 关时当前行
 * 整行上色、不走逐字（全屏底部 Std 档）。
 */
data class LyricStageSpec(
    /** null＝读页内字号键；全屏面由调用方喂全屏字号键的值。 */
    val fontSizeSp: Float? = null,
    val lineHeightFactor: Float = 4f / 3f,
    val lineGapDp: Float = LINE_GAP_DP,
    val letterSpacingSp: Float = 0f,
    /** true＝全屏像素锚点；false＝页内 45% 锚点。 */
    val pixelAnchor: Boolean = false,
    val wordByWord: Boolean = true,
)

/**
 * 全屏锚点（瓜子 AppleLyricsList 调用方公式，jadx＋smali 亲验，本轮定谳）：
 * 偏移＝clamp(0.22×容器高, 64dp, 120dp)，再封顶 0.28×容器高，减 24dp/2，
 * 结果即当前行中线在本舞台内的 Y。另一「居中态」分支公式已抠到
 * （((高−Ag高)/2 − 24dp/2) − Ag高 − 行距、下限 0），其触发旗语义未定死，未落。
 */
private fun fullScreenAnchorY(heightPx: Float, density: Float): Float {
    val lo = 64f * density
    val hi = 120f * density
    var v = (0.22f * heightPx).coerceIn(lo, hi)
    val cap = 0.28f * heightPx
    if (v > cap) v = cap
    return v - (24f * density) / 2f
}

/** 一行的量测结果（视觉行划分＋总高），与容器宽/字号绑定，量一次全场用。 */
private class LineMeasure(
    /** 词行：每视觉行含的词下标；无词行为空表（整行自然折行） */
    val wordRows: List<List<Int>>,
    /** 断后文本（词行＝按断点插 \n 的整行，非当前行整行渲染用；无词行＝原文） */
    val plainText: String,
    val mainHeightPx: Float,
    /** 副行（翻译/音译）文本与高度：null＝无副行 */
    val subText: String?,
    val subHeightPx: Float,
    val heightPx: Float,
)

@Composable
fun AnchoredLyricStage(
    lines: List<LyricUiLine>,
    timed: Boolean,
    positionMs: Long,
    isPlaying: Boolean,
    onSeekLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
    spec: LyricStageSpec = LyricStageSpec(),
) {
    if (!timed) {
        StaticLyricText(lines = lines, spec = spec, modifier = modifier)
        return
    }
    TimedLyricStage(
        lines = lines,
        positionMs = positionMs,
        isPlaying = isPlaying,
        onSeekLine = onSeekLine,
        spec = spec,
        modifier = modifier,
    )
}

/** 纯文本：无当前行、静态显示、不滚动不均分（瓜子同口径）。 */
@Composable
private fun StaticLyricText(
    lines: List<LyricUiLine>,
    spec: LyricStageSpec,
    modifier: Modifier = Modifier,
) {
    val coverFontSizeSp = remember { MusicPlayerSettings.getCoverLyricFontSize() }
    val fontSizeSp = spec.fontSizeSp ?: coverFontSizeSp
    val weight = remember { lyricFontWeight() }
    Column(modifier = modifier.fillMaxSize().clipToBounds().lyricSkew()) {
        for (line in lines) {
            Text(
                text = line.text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = fontSizeSp.sp,
                fontWeight = weight,
                lineHeight = (fontSizeSp * spec.lineHeightFactor).sp,
                letterSpacing = spec.letterSpacingSp.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 歌词粗细存档→字重（瓜子三档：标准 700／加粗 800／特粗 900，MusicStore 注释亲验）。 */
private fun lyricFontWeight(): FontWeight = when (MusicPlayerSettings.getLyricWeight()) {
    1 -> FontWeight.ExtraBold
    2 -> FontWeight.Black
    else -> FontWeight.Bold
}

/** 字斜 −0.12：以块底边为轴 skew（旧实现画笔 setTextSkewX 的 Compose 等价面）。 */
private fun Modifier.lyricSkew(): Modifier = this.drawWithContent {
    with(drawContext.canvas.nativeCanvas) {
        val save = save()
        translate(0f, size.height)
        skew(SKEW_X, 0f)
        translate(0f, -size.height)
        drawContent()
        restoreToCount(save)
    }
}

@Composable
private fun TimedLyricStage(
    lines: List<LyricUiLine>,
    positionMs: Long,
    isPlaying: Boolean,
    onSeekLine: (Long) -> Unit,
    spec: LyricStageSpec,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 设置快照（进页读一次，与旧视图同口径）
    val coverFontSizeSp = remember { MusicPlayerSettings.getCoverLyricFontSize() }
    val fontSizeSp = spec.fontSizeSp ?: coverFontSizeSp
    val weight = remember { lyricFontWeight() }
    val blurCoef = remember { MusicPlayerSettings.blurCoefficient() }
    val showTrans = remember { MusicPlayerSettings.isShowTranslation() }
    val showRoma = remember { MusicPlayerSettings.isShowRomanization() }
    val advancedFx = remember { MusicPlayerSettings.isAdvancedFx() }
    val offsetMs = remember { MusicPlayerSettings.getLyricOffsetMs().toLong() }

    val smoothMs = rememberSmoothTime(positionMs, isPlaying)
    val effectiveMs = smoothMs + offsetMs
    val currentIndex = remember(lines, effectiveMs) { currentLineIndex(lines, effectiveMs) }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val anchorY = if (spec.pixelAnchor) {
            fullScreenAnchorY(heightPx, density.density)
        } else {
            heightPx * ANCHOR_FRACTION
        }
        val gapPx = with(density) { spec.lineGapDp.dp.toPx() }
        val lineHeightPx = with(density) { (fontSizeSp * spec.lineHeightFactor).sp.toPx() }
        val stepPx = lineHeightPx + gapPx

        // ---- 量测（宽/字号/行列变更才重算）----
        val measurer = rememberTextMeasurer()
        val measures = remember(
            lines, widthPx, fontSizeSp, weight, showTrans, showRoma,
            spec.lineHeightFactor, spec.letterSpacingSp,
        ) {
            measureLines(
                lines = lines,
                measurer = measurer,
                maxWidthPx = widthPx.roundToInt(),
                fontSizeSp = fontSizeSp,
                weight = weight,
                density = density.density,
                showTrans = showTrans,
                showRoma = showRoma,
                lineHeightFactor = spec.lineHeightFactor,
                letterSpacingSp = spec.letterSpacingSp,
            )
        }
        val cumTop = remember(measures, gapPx) {
            FloatArray(measures.size + 1).also { arr ->
                for (i in measures.indices) arr[i + 1] = arr[i] + measures[i].heightPx + gapPx
            }
        }
        val totalHeight = if (measures.isEmpty()) 0f else cumTop[measures.size - 1] + measures.last().heightPx
        fun centerOf(i: Int): Float = cumTop[i] + measures[i].heightPx / 2f

        // ---- 滚动：锚点 Animatable（内容坐标，当前行中线）＋手滑偏移 ----
        val anchorAnim = remember { Animatable(0f) }
        var browseOffset by remember { mutableFloatStateOf(0f) }
        var reanchorJob by remember { mutableStateOf<Job?>(null) }
        var seekTarget by remember { mutableStateOf(-1) }

        // 首次/换歌：直接落位不走动画
        LaunchedEffect(lines) {
            val idx = if (currentIndex >= 0) currentIndex else 0
            if (measures.isNotEmpty()) anchorAnim.snapTo(centerOf(idx.coerceIn(0, measures.size - 1)))
        }
        // 当前行推进：settle 弹簧跟随（点行 seek 走更软的 seek 弹簧，一次）
        LaunchedEffect(currentIndex) {
            if (currentIndex < 0 || measures.isEmpty()) return@LaunchedEffect
            val target = centerOf(currentIndex)
            if (seekTarget == currentIndex) {
                seekTarget = -1
                anchorAnim.animateTo(target, spring(dampingRatio = 0.833f, stiffness = 100f))
            } else if (abs(target - anchorAnim.value) > 0.5f) {
                anchorAnim.animateTo(target, spring(dampingRatio = 1.16f, stiffness = 237.3f))
            }
        }

        val anchorShown = anchorAnim.value

        // 手势协程里读实时值（不走组合捕获，免得拿到旧帧的锚点/偏移）
        fun lineIndexAtY(y: Float): Int {
            val contentY = y - anchorY + anchorAnim.value - browseOffset
            for (i in measures.indices) {
                if (contentY >= cumTop[i] && contentY < cumTop[i] + measures[i].heightPx) return i
            }
            return -1
        }

        fun scheduleReanchor() {
            reanchorJob?.cancel()
            reanchorJob = scope.launch {
                delay(REANCHOR_DELAY_MS)
                val anim = Animatable(browseOffset)
                anim.animateTo(0f, spring(dampingRatio = 1.16f, stiffness = 237.3f)) {
                    browseOffset = value
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(lines, measures, heightPx, totalHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        reanchorJob?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        var totalDy = 0f
                        var totalDx = 0f
                        var up = false
                        while (!up) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) {
                                // 抬起或取消都收手（1.9 无 changedToUpOrCanceled，pressed 兜底同义）
                                up = true
                            } else {
                                val dxy = change.positionChange()
                                totalDx += dxy.x
                                totalDy += dxy.y
                                if (!dragging && abs(totalDy) > viewConfiguration.touchSlop) {
                                    dragging = true
                                }
                                if (dragging) {
                                    val limit = maxOf(totalHeight, heightPx)
                                    browseOffset = (browseOffset + dxy.y).coerceIn(-limit, heightPx)
                                    change.consume()
                                }
                            }
                        }
                        // 横向移动过 slop＝同树 pager 的横滑（进/回全屏歌词面），不许误判成点行
                        val swipedSideways = abs(totalDx) > viewConfiguration.touchSlop
                        if (!dragging && !swipedSideways) {
                            // 点行快进：seek 到那行＋立即回锚（seek 弹簧）
                            val idx = lineIndexAtY(down.position.y)
                            if (idx >= 0 && idx < lines.size) {
                                seekTarget = idx
                                onSeekLine(lines[idx].timeMs)
                                scope.launch {
                                    val anim = Animatable(browseOffset)
                                    anim.animateTo(0f, tween(200, easing = Bs4A)) { browseOffset = value }
                                }
                            }
                        } else if (abs(browseOffset) > heightPx * 1.5f) {
                            // 偏移过大直接 snap：锚点跳到此刻离锚位最近的行、偏移清零
                            val target = anchorAnim.value - browseOffset
                            var best = 0
                            var bestDist = Float.MAX_VALUE
                            for (i in measures.indices) {
                                val d = abs(centerOf(i) - target)
                                if (d < bestDist) {
                                    bestDist = d
                                    best = i
                                }
                            }
                            val snapTargetY = centerOf(best)
                            scope.launch {
                                anchorAnim.snapTo(snapTargetY)
                                browseOffset = 0f
                            }
                        } else {
                            val velocityY = tracker.calculateVelocity().y
                            val flingThreshold = with(density) { 60.dp.toPx() }
                            if (abs(velocityY) > flingThreshold) {
                                scope.launch {
                                    val anim = Animatable(browseOffset)
                                    anim.animateDecay(velocityY, exponentialDecay()) {
                                        // 钳制后衰减自然耗尽（值不再动），不另行取消
                                        val limit = maxOf(totalHeight, heightPx)
                                        browseOffset = value.coerceIn(-limit, heightPx)
                                    }
                                    scheduleReanchor()
                                }
                            } else {
                                scheduleReanchor()
                            }
                        }
                    }
                },
        ) {
            if (measures.isNotEmpty()) {
                val fadeSpanBelow = heightPx - anchorY
                val fadeStartY = anchorY + FADE_START * fadeSpanBelow
                val topFadePx = with(density) { TOP_FADE_DP.dp.toPx() }
                for (i in lines.indices) {
                    val top = cumTop[i] - anchorShown + anchorY + browseOffset
                    val h = measures[i].heightPx
                    if (top + h < -stepPx || top > heightPx + stepPx) continue
                    // 顶部渐隐（40dp 带，smoothstep，按行底边进度——整行滑出才灭净）
                    // ＋锚下淡出（80% 起、地板 .45）
                    var fade = 1f
                    if (top < topFadePx) {
                        fade *= smoothstep01((top + h) / topFadePx)
                    }
                    if (top > fadeStartY && fadeSpanBelow > 0f) {
                        val frac = ((top - fadeStartY) / (fadeSpanBelow * (1f - FADE_START))).coerceIn(0f, 1f)
                        fade *= bottomFadeKeep(frac, FADE_FLOOR)
                    }
                    // 逐行模糊 B 组（距离以行步长为单位的分数距离，半径 0.5px 量化——瓜子同口径）
                    val distLines = abs(cumTop[i] + h / 2f - anchorShown) / stepPx
                    val blurDp = minOf(distLines * BLUR_PER_LINE_DP, BLUR_MAX_DP) * blurCoef
                    val blurPx = (blurDp * density.density * 2f).roundToInt() / 2f
                    LyricLineBlock(
                        line = lines[i],
                        measure = measures[i],
                        isCurrent = i == currentIndex,
                        isSungLine = currentIndex >= 0 && i < currentIndex,
                        effectiveMs = effectiveMs,
                        fontSizeSp = fontSizeSp,
                        weight = weight,
                        advancedFx = advancedFx,
                        lineHeightFactor = spec.lineHeightFactor,
                        letterSpacingSp = spec.letterSpacingSp,
                        wordByWord = spec.wordByWord,
                        modifier = Modifier
                            .offset { IntOffset(0, top.roundToInt()) }
                            .fillMaxWidth()
                            .height(with(density) { measures[i].heightPx.toDp() })
                            .graphicsLayer {
                                alpha = fade.coerceIn(0f, 1f)
                                if (Build.VERSION.SDK_INT >= 31 && blurPx > 0f) {
                                    renderEffect = BlurEffect(blurPx, blurPx, TileMode.Decal)
                                }
                            },
                    )
                }
            }
        }
    }
}

/** 平滑时间：帧驱动推进（播放中），回退 ≤350ms 的报告忽略、大漂移直接对齐（瓜子 rememberSmoothTime 口径）。 */
@Composable
private fun rememberSmoothTime(positionMs: Long, isPlaying: Boolean): Long {
    var smooth by remember { mutableLongStateOf(positionMs) }
    val playingState = rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        var lastFrame = -1L
        while (true) {
            withFrameMillis { frame ->
                if (lastFrame >= 0L && playingState.value) {
                    smooth += (frame - lastFrame).coerceIn(0L, 100L)
                }
                lastFrame = frame
            }
        }
    }
    LaunchedEffect(positionMs, isPlaying) {
        val drift = positionMs - smooth
        if (!isPlaying || drift > SMOOTH_TOLERANCE_MS || drift < -SMOOTH_TOLERANCE_MS) {
            smooth = positionMs
        }
    }
    return smooth
}

/** 当前行＝时间 ≤ now 的最后一行（瓜子同口径二分）。 */
private fun currentLineIndex(lines: List<LyricUiLine>, nowMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= nowMs) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

private fun measureLines(
    lines: List<LyricUiLine>,
    measurer: androidx.compose.ui.text.TextMeasurer,
    maxWidthPx: Int,
    fontSizeSp: Float,
    weight: FontWeight,
    density: Float,
    showTrans: Boolean,
    showRoma: Boolean,
    lineHeightFactor: Float,
    letterSpacingSp: Float,
): List<LineMeasure> {
    val mainStyle = TextStyle(
        fontSize = fontSizeSp.sp,
        fontWeight = weight,
        lineHeight = (fontSizeSp * lineHeightFactor).sp,
        letterSpacing = letterSpacingSp.sp,
    )
    // 行高用探针实测（不手算密度，免得系统字号缩放下量测与渲染对不上）
    val lineHeightPx = measurer.measure("Ag", mainStyle).size.height.toFloat()
    val subSizeSp = fontSizeSp * 0.62f
    val subStyle = TextStyle(
        fontSize = subSizeSp.sp,
        fontWeight = weight,
        lineHeight = (subSizeSp * lineHeightFactor).sp,
    )
    val subLineHeightPx = measurer.measure("Ag", subStyle).size.height.toFloat()
    val subGapPx = 6f * density
    return lines.map { line ->
        var wordRows: List<List<Int>> = emptyList()
        var plainText = line.text
        var mainHeight: Float
        if (line.words.isNotEmpty()) {
            // 段＝词（瓜子原口径）：词宽逐词量，断点走 calcBalancedBreaks
            val segs = ArrayList<WrapSegment>(line.words.size)
            var charCursor = 0
            var searchFrom = 0
            for (w in line.words) {
                val idx = line.text.indexOf(w.text, searchFrom)
                val start = if (idx >= 0) idx else charCursor
                if (idx >= 0) searchFrom = idx + w.text.length
                charCursor = start + w.text.length
                val width = measurer.measure(w.text, mainStyle).size.width.toFloat()
                segs.add(WrapSegment(start, width, w.text))
            }
            val breaks = calcBalancedBreaks(segs, maxWidthPx.toFloat())
            val rows = mutableListOf<MutableList<Int>>()
            var row = mutableListOf<Int>()
            var breakIdx = 0
            for (wi in segs.indices) {
                if (breakIdx < breaks.size && segs[wi].sentenceStart >= breaks[breakIdx] && wi > 0) {
                    rows.add(row)
                    row = mutableListOf()
                    breakIdx++
                }
                row.add(wi)
            }
            rows.add(row)
            wordRows = rows
            mainHeight = rows.size * lineHeightPx
            if (breaks.isNotEmpty()) {
                // 断后文本（瓜子同法：断点字符前插 \n），非当前行整行渲染时用
                val sb = StringBuilder(line.text.length + breaks.size)
                var bi = 0
                for (ci in line.text.indices) {
                    while (bi < breaks.size && breaks[bi] == ci) {
                        sb.append('\n')
                        bi++
                    }
                    sb.append(line.text[ci])
                }
                plainText = sb.toString()
            }
        } else {
            val result = measurer.measure(
                line.text,
                mainStyle,
                constraints = Constraints(maxWidth = maxWidthPx),
            )
            mainHeight = result.lineCount.coerceAtLeast(1) * lineHeightPx
        }
        val subText = when {
            showTrans && line.translation != null -> line.translation
            showRoma && line.romanization != null -> line.romanization
            else -> null
        }
        var subHeight = 0f
        if (subText != null) {
            val result = measurer.measure(
                subText,
                subStyle,
                constraints = Constraints(maxWidth = maxWidthPx),
            )
            subHeight = subGapPx + result.lineCount.coerceAtLeast(1) * subLineHeightPx
        }
        LineMeasure(
            wordRows = wordRows,
            plainText = plainText,
            mainHeightPx = mainHeight,
            subText = subText,
            subHeightPx = subHeight,
            heightPx = mainHeight + subHeight,
        )
    }
}

/** 一行块：主行（词行走逐字布局、否则整行文本）＋副行（翻译/音译，×0.62、白 .55）。 */
@Composable
private fun LyricLineBlock(
    line: LyricUiLine,
    measure: LineMeasure,
    isCurrent: Boolean,
    isSungLine: Boolean,
    effectiveMs: Long,
    fontSizeSp: Float,
    weight: FontWeight,
    advancedFx: Boolean,
    lineHeightFactor: Float,
    letterSpacingSp: Float,
    wordByWord: Boolean,
    modifier: Modifier = Modifier,
) {
    val lineColor = when {
        isCurrent -> ColorCurrentUnsung
        isSungLine -> ColorSungSoft
        else -> ColorInactive
    }
    Column(modifier = modifier.lyricSkew()) {
        if (isCurrent && wordByWord && line.words.isNotEmpty() && measure.wordRows.isNotEmpty()) {
            // 逐字只走当前行（瓜子同口径：非当前行整行渲染，断行结果共用）
            WordRows(
                line = line,
                measure = measure,
                isCurrent = isCurrent,
                isSungLine = isSungLine,
                effectiveMs = effectiveMs,
                fontSizeSp = fontSizeSp,
                weight = weight,
                advancedFx = advancedFx,
                lineHeightFactor = lineHeightFactor,
                letterSpacingSp = letterSpacingSp,
            )
        } else {
            Text(
                text = measure.plainText,
                color = if (isCurrent) Color.White else lineColor,
                fontSize = fontSizeSp.sp,
                fontWeight = weight,
                lineHeight = (fontSizeSp * lineHeightFactor).sp,
                letterSpacing = letterSpacingSp.sp,
            )
        }
        if (measure.subText != null) {
            Text(
                text = measure.subText,
                color = ColorTranslation,
                fontSize = (fontSizeSp * 0.62f).sp,
                fontWeight = weight,
                lineHeight = (fontSizeSp * 0.62f * lineHeightFactor).sp,
                modifier = Modifier.offset(y = 6.dp),
            )
        }
    }
}

/** 逐字行布局：词按均衡断行分好的视觉行逐词摆位，逐词唱色/上浮/强调都在词级。 */
@Composable
private fun WordRows(
    line: LyricUiLine,
    measure: LineMeasure,
    isCurrent: Boolean,
    isSungLine: Boolean,
    effectiveMs: Long,
    fontSizeSp: Float,
    weight: FontWeight,
    advancedFx: Boolean,
    lineHeightFactor: Float,
    letterSpacingSp: Float,
) {
    val density = LocalDensity.current
    val lineHeightPx = with(density) { (fontSizeSp * lineHeightFactor).sp.toPx() }
    val rows = measure.wordRows
    Layout(
        content = {
            for (rowWords in rows) {
                for (wi in rowWords) {
                    WordPiece(
                        word = line.words[wi],
                        isCurrentLine = isCurrent,
                        isSungLine = isSungLine,
                        isLastWord = wi == line.words.size - 1,
                        effectiveMs = effectiveMs,
                        fontSizeSp = fontSizeSp,
                        weight = weight,
                        advancedFx = advancedFx,
                        lineHeightFactor = lineHeightFactor,
                        letterSpacingSp = letterSpacingSp,
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { measure.mainHeightPx.toDp() }),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, measure.mainHeightPx.roundToInt()) {
            var k = 0
            for ((r, rowWords) in rows.withIndex()) {
                var x = 0
                for (wi in rowWords) {
                    if (k >= placeables.size) break
                    val p = placeables[k]
                    p.placeRelative(x, (r * lineHeightPx).roundToInt())
                    x += p.width
                    k++
                }
            }
        }
    }
}

/** 一字：唱色按 [start,end] 线性插值（画笔渐变），上浮 −0.09em easeOut，长音光晕＋放大＋正弦浮动。 */
@Composable
private fun WordPiece(
    word: LyricUiWord,
    isCurrentLine: Boolean,
    isSungLine: Boolean,
    isLastWord: Boolean,
    effectiveMs: Long,
    fontSizeSp: Float,
    weight: FontWeight,
    advancedFx: Boolean,
    lineHeightFactor: Float,
    letterSpacingSp: Float,
) {
    val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
    // 唱到的比例（线性插值，靠平滑时间保证单调不回退）
    val sungFraction = when {
        !isCurrentLine -> if (isSungLine) 1f else 0f
        effectiveMs >= word.endMs -> 1f
        effectiveMs <= word.startMs -> 0f
        else -> (effectiveMs - word.startMs).toFloat() / dur.toFloat()
    }
    val baseStyle = TextStyle(
        fontSize = fontSizeSp.sp,
        fontWeight = weight,
        lineHeight = (fontSizeSp * lineHeightFactor).sp,
        letterSpacing = letterSpacingSp.sp,
    )
    val density = LocalDensity.current
    val emPx = with(density) { fontSizeSp.sp.toPx() }

    // 上浮：唱起后 easeOut 到 −0.09em，时长下限 1100ms（WORD_LIFT 口径）；高级动效关则不动
    val liftProgress = if (advancedFx && isCurrentLine && effectiveMs > word.startMs) {
        val span = maxOf(WORD_LIFT_MIN_MS, dur)
        EaseOut.transform(((effectiveMs - word.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f))
    } else if (advancedFx && isSungLine) {
        1f
    } else {
        0f
    }

    // 长音强调：≥1000ms 的字在唱中正弦起伏（放大 ≤1.2、浮 −0.05em、光晕），行末字唱完拖尾 400ms
    var emph = 0f
    if (advancedFx && isCurrentLine && dur >= EMPH_MIN_MS) {
        emph = when {
            effectiveMs in word.startMs until word.endMs -> {
                val p = (effectiveMs - word.startMs).toFloat() / dur.toFloat()
                sin(Math.PI * p).toFloat()
            }
            isLastWord && effectiveMs in word.endMs until word.endMs + EMPH_RELEASE_TAIL_MS ->
                0.6f * (1f - (effectiveMs - word.endMs).toFloat() / EMPH_RELEASE_TAIL_MS.toFloat())
            else -> 0f
        }
    }

    val liftPx = WORD_LIFT_EM * emPx * liftProgress + (-0.05f * emPx * emph)
    val scale = 1f + 0.2f * emph

    Box(
        modifier = Modifier.graphicsLayer {
            translationY = liftPx
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    ) {
        if (emph > 0.02f) {
            // 光晕：同字糊一层在底（瓜子 BlurMaskFilter 光晕的 Compose 等价面）
            Text(
                text = word.text,
                style = baseStyle,
                color = ColorSungSoft.copy(alpha = 0.85f * emph),
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.blur(6.dp),
            )
        }
        val style = when {
            !isCurrentLine -> baseStyle.copy(color = if (isSungLine) ColorSungSoft else ColorInactive)
            sungFraction <= 0f -> baseStyle.copy(color = ColorCurrentUnsung)
            sungFraction >= 1f -> baseStyle.copy(color = ColorSungSoft)
            else -> baseStyle.copy(
                brush = Brush.horizontalGradient(
                    0f to ColorSungSoft,
                    sungFraction to ColorSungSoft,
                    sungFraction to ColorCurrentUnsung,
                    1f to ColorCurrentUnsung,
                ),
            )
        }
        Text(
            text = word.text,
            style = style,
            softWrap = false,
            maxLines = 1,
        )
    }
}
