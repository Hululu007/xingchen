package com.fongmi.android.tv.ui.music.player

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 流光 CD 主题 luminous_cd（含深色/自动变体）（瓜子 LuminousCdPlayerKt.java:3432→LuminousCdContent:2213；
 * 数值认 gz-player-values.md §六与 CdPalette.java/a21.java 逐行亲读）：
 * 根＝色板派生背景渐变＋status/nav insets；内容横 16：顶行 64 → 中段（盘＋标题＋歌词摘要 /
 * 展开歌词 / 歌曲信息三态）→ 五图标行 52 → 进度 → 运输行 80。
 * 色板 mix 走 animateFloat(500, bs4.a)；AUTO 跟系统夜间（星辰无 App 深色设置，等价装配注名）。
 */
@Composable
fun CdPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    mode: String,
    onClose: () -> Unit,
    onToolsClick: () -> Unit,
    onQueueClick: () -> Unit,
    onQualityClick: () -> Unit,
    onLyricVersionClick: () -> Unit,
    onArtistSearch: (String) -> Unit,
    onThemeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 展开歌词逐字开关读现行存档键（与经典/全屏页同一键，本页无切换件）
    val wordMode = MusicPlayerSettings.isFullLyricWordByWord()
    val context = LocalContext.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val targetSpace = when (mode) {
        MusicPlayerSettings.THEME_LUMINOUS_CD_DARK -> 1f
        MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO -> if (systemDark) 1f else 0f
        else -> 0f
    }
    val surfaceAnim = remember { Animatable(targetSpace) }
    LaunchedEffect(targetSpace) {
        surfaceAnim.animateTo(targetSpace, tween(500, easing = EaseBs4A))
    }
    val mixed = remember(surfaceAnim.value) { mixCdPalette(CD_PEARL, CD_SPACE, surfaceAnim.value) }
    val coverColor = state.toneColor?.let { Color(it) } ?: mixed.base
    val derived = remember(mixed, coverColor) { deriveCdPalette(mixed, coverColor) }

    var detail by remember { mutableStateOf(CdDetail.LYRICS) }
    var lyricsExpanded by remember { mutableStateOf(false) }
    // 切歌自动回 LYRICS（瓜子 setValue(LYRICS) 亲验）；展开态一并收起（同批装配）
    LaunchedEffect(state.track?.id) {
        detail = CdDetail.LYRICS
        lyricsExpanded = false
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = derived.bgStops.zip(derived.bgColors).toTypedArray()))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // 顶行（高 64）：左收起、右三个点（盒 44、图标 23、muted、按压 0.9）
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).pressScale(0.9f).clickableNoRipple(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(23.dp, derived.muted, PATH_CHEVRON_DOWN)
                }
                if (detail == CdDetail.SONG_INFO) {
                    // 歌曲信息态顶行中部：40dp 缩图＋歌名/歌手（据 CD_THUMB_TEXT_INDENT=40 与
                    // CdMarqueeTitle 两变体推定的装配，交卷注名待真机对照）
                    Row(
                        Modifier.weight(1f).padding(start = 40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        ) {
                            CoverImage(state, androidx.compose.ui.layout.ContentScale.Crop, Modifier.size(40.dp))
                        }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(
                                text = state.track?.displayTitle() ?: "",
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.W600,
                                color = derived.solidInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.track?.displayArtist() ?: "",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = derived.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Box(
                    Modifier.size(44.dp).pressScale(0.9f)
                        .clickableNoRipple(onClick = onToolsClick),
                    contentAlignment = Alignment.Center
                ) {
                    MoreDotsIcon(23.dp, derived.muted)
                }
            }

            // 中段（weight 区，三态）
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    detail == CdDetail.SONG_INFO -> SongInfoFace(
                        state = state,
                        colors = SongInfoColors(
                            high = derived.solidInk,
                            medium = derived.muted,
                            low = derived.muted,
                            inset = derived.ink.copy(alpha = 0.06f),
                            accent = derived.accent
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                    lyricsExpanded -> CdLyricsList(
                        state = state,
                        progress = progress,
                        accent = derived.accent,
                        solidInk = derived.solidInk,
                        muted = derived.muted,
                        wordMode = wordMode,
                        onLineClick = { index ->
                            state.lyricLines.getOrNull(index)?.let {
                                PlayerStateHolder.seekTo(it.main.timeMs)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> Column(Modifier.fillMaxSize()) {
                        CdDiscArea(state, derived, Modifier.fillMaxWidth())
                        // 标题块（盘下）：歌名跑马灯 15sp/21/600 solidInk；歌手 11sp/15/400 muted 可点
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text(
                                text = state.track?.displayTitle() ?: "",
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.W600,
                                color = derived.solidInk,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                            val artist = state.track?.displayArtist() ?: ""
                            Text(
                                text = artist,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                fontWeight = FontWeight.W400,
                                color = derived.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickableNoRipple {
                                    if (artist.isNotEmpty()) onArtistSearch(artist)
                                }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        CdLyricSummary(
                            state = state,
                            progress = progress,
                            solidInk = derived.solidInk,
                            muted = derived.muted,
                            onClick = { lyricsExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 五图标行（高 52、左右 2、每格 weight 1、图标盒 26、按压 0.9）
            CdUtilityRow(
                state = state,
                derived = derived,
                detail = detail,
                mode = mode,
                onQualityClick = onQualityClick,
                onLyricVersionClick = onLyricVersionClick,
                onToggleInfo = {
                    detail = if (detail == CdDetail.SONG_INFO) CdDetail.LYRICS else CdDetail.SONG_INFO
                },
                onSurfaceCycle = {
                    val (nextId, nextName) = when (mode) {
                        MusicPlayerSettings.THEME_LUMINOUS_CD -> MusicPlayerSettings.THEME_LUMINOUS_CD_DARK to "流光 CD 深色"
                        MusicPlayerSettings.THEME_LUMINOUS_CD_DARK -> MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO to "流光 CD 自动"
                        else -> MusicPlayerSettings.THEME_LUMINOUS_CD to "流光 CD"
                    }
                    onThemeSelected(nextId)
                    Toast.makeText(context, nextName, Toast.LENGTH_SHORT).show()
                }
            )
            // 进度 CdSeekBar（外层左右 8、本体高 34、轨容器高 10、线 2dp；时间 9sp muted×0.74）
            CdSeekBar(
                progress = progress,
                accent = derived.accent,
                trackColor = derived.ink.copy(alpha = 0.10f),
                timeColor = derived.muted.copy(alpha = 0.74f),
                onSeek = { PlayerStateHolder.seekTo(it) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            // 运输行（高 80、左右 4、offset y −10）
            CdTransportRow(
                state = state,
                derived = derived,
                onQueueClick = onQueueClick,
                modifier = Modifier.padding(horizontal = 4.dp).offset(y = (-10).dp)
            )
        }
    }
}

private enum class CdDetail { LYRICS, SONG_INFO }

// ================= 色板（CdPalette.java 全文亲读：Pearl/Space 两套＋派生公式） =================

private fun lerpF(a: Float, b: Float, f: Float): Float = a + (b - a) * f

private data class CdPaletteBase(
    val base: Color,
    val mist: Color,
    val ink: Color,
    val muted: Color,
    val solidAlpha: Float,
    val bgMix: Float,
    val accentMaxLum: Float,
    val stop1: Float,
    val upperF1: Float,
    val upperF2: Float,
    val discA: List<Color>,
    val discB: List<Color>,
    val discC: Color,
    val discD: Float,
    val discE: Float,
    val discF: Float,
    val discG: Color,
    val discH: Color,
    val discI: Color,
    val discJ: Color,
    val discK: Color,
    val discL: Color
)

private fun argb(hex: Long): Color = Color(hex.toInt())

private val CD_PEARL = CdPaletteBase(
    base = argb(0xFFF4F2EE), mist = argb(0xFFE9EFF5), ink = argb(0xFF282D36), muted = argb(0xFF747D89),
    solidAlpha = 1.0f, bgMix = 0f, accentMaxLum = 1.0f, stop1 = 0.34f, upperF1 = 0.48f, upperF2 = 0.73f,
    discA = listOf(0xFF9FAAB7, 0xFFF0F3F6, 0xFFB6C7D0, 0xFFE9E3D4, 0xFF929FAC, 0xFFF7F8FA, 0xFFD4CADB, 0xFF9FAAB7).map { argb(it) },
    discB = listOf(0xFF9FAAB7, 0xFFF0F3F6, 0xFFB6C7D0, 0xFFE9E3D4, 0xFFCFC6D8, 0xFF929FAC, 0xFFF7F8FA, 0xFFD4CADB, 0xFFBACCD2, 0xFF9FAAB7).map { argb(it) },
    discC = Color.White, discD = 0.55f, discE = 0.25f, discF = 0.55f,
    discG = argb(0xFF526170).copy(alpha = 0.10f), discH = Color.White.copy(alpha = 0.16f),
    discI = Color.White.copy(alpha = 0.50f), discJ = Color.Black.copy(alpha = 0.28f),
    discK = Color.White.copy(alpha = 0.65f), discL = argb(0xFF6F7E8D)
)

private val CD_SPACE = CdPaletteBase(
    base = argb(0xFF14161C), mist = argb(0xFF14161C), ink = argb(0xFFF2F2F7), muted = argb(0xFF8E96A3),
    solidAlpha = 0.72f, bgMix = 1.0f, accentMaxLum = 0.45f, stop1 = 0.42f, upperF1 = 0.42f, upperF2 = 0.68f,
    discA = listOf(0xFF2E3742, 0xFF8792A0, 0xFF3B4653, 0xFF4E493F, 0xFF262D37, 0xFF8C97A5, 0xFF46405A, 0xFF2E3742).map { argb(it) },
    discB = listOf(0xFF2E3742, 0xFF8792A0, 0xFF3B4653, 0xFF4E493F, 0xFF443C52, 0xFF262D37, 0xFF8C97A5, 0xFF46405A, 0xFF3C4A55, 0xFF2E3742).map { argb(it) },
    discC = argb(0xFF2A3038), discD = 0.50f, discE = 0f, discF = 0.34f,
    discG = argb(0xFF04060A).copy(alpha = 0.42f), discH = Color.White.copy(alpha = 0.10f),
    discI = Color.White.copy(alpha = 0.22f), discJ = Color.Black.copy(alpha = 0.55f),
    discK = Color.White.copy(alpha = 0.30f), discL = argb(0xFF1A1F26)
)

/** CdPalette.mix：逐字段 lerp（含 disc 材料表）。 */
private fun mixCdPalette(a: CdPaletteBase, b: CdPaletteBase, f: Float): CdPaletteBase = CdPaletteBase(
    base = lerp(a.base, b.base, f),
    mist = lerp(a.mist, b.mist, f),
    ink = lerp(a.ink, b.ink, f),
    muted = lerp(a.muted, b.muted, f),
    solidAlpha = lerpF(a.solidAlpha, b.solidAlpha, f),
    bgMix = lerpF(a.bgMix, b.bgMix, f),
    accentMaxLum = lerpF(a.accentMaxLum, b.accentMaxLum, f),
    stop1 = lerpF(a.stop1, b.stop1, f),
    upperF1 = lerpF(a.upperF1, b.upperF1, f),
    upperF2 = lerpF(a.upperF2, b.upperF2, f),
    discA = a.discA.zip(b.discA) { x, y -> lerp(x, y, f) },
    discB = a.discB.zip(b.discB) { x, y -> lerp(x, y, f) },
    discC = lerp(a.discC, b.discC, f),
    discD = lerpF(a.discD, b.discD, f),
    discE = lerpF(a.discE, b.discE, f),
    discF = lerpF(a.discF, b.discF, f),
    discG = lerp(a.discG, b.discG, f),
    discH = lerp(a.discH, b.discH, f),
    discI = lerp(a.discI, b.discI, f),
    discJ = lerp(a.discJ, b.discJ, f),
    discK = lerp(a.discK, b.discK, f),
    discL = lerp(a.discL, b.discL, f)
)

private class CdDerived(
    val bgStops: List<Float>,
    val bgColors: List<Color>,
    val solidInk: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val faceColors: List<Color>,
    val hubStops: List<Pair<Float, Color>>,
    val rimColor: Color
)

/**
 * 派生（CdPalette.java 亲读公式）：fresh＝lerp(base,c,.22)；upper＝lerp(base, fresh, mixF(f1,f2,toneMix))；
 * middle＝lerp(base, lerp(base,c,.12), toneMix)；bottom＝lerp(mist, lerp(base,c,.08), toneMix)；
 * 背景首停点＝lerp(base, fresh, .92)、停点 [0, stop1, .68, 1]；
 * accent＝lerp(c, ink, .52)，亮度超 accentMaxLum 时向 base 回混（分母 lum−lumBase，=base 时不混）。
 * 盘面染色（a21.java:60-75）：tint＝lerp(材料.c→c, min((1−封面亮度)×e＋d, .92))，
 * 每档色 lerp(色→tint, (1−该色亮度×0.65)×f)。亮度函数瓜子侧为其自有口径，
 * 本处用 androidx 相对亮度（公式结构照原式、交卷注名）。
 */
private fun deriveCdPalette(p: CdPaletteBase, c: Color): CdDerived {
    val toneMix = p.bgMix
    val fresh = lerp(p.base, c, 0.22f)
    val upper = lerp(p.base, fresh, lerpF(p.upperF1, p.upperF2, toneMix))
    val middle = lerp(p.base, lerp(p.base, c, 0.12f), toneMix)
    val bottom = lerp(p.mist, lerp(p.base, c, 0.08f), toneMix)
    val g0 = lerp(p.base, fresh, 0.92f)
    var accent = lerp(c, p.ink, 0.52f)
    val lumA = accent.luminance()
    val lumBase = p.base.luminance()
    if (lumA > p.accentMaxLum && lumA > lumBase) {
        accent = lerp(accent, p.base, (lumA - p.accentMaxLum) / (lumA - lumBase))
    }
    val tintT = min((1f - c.luminance()) * p.discE + p.discD, 0.92f)
    val tint = lerp(p.discC, c, tintT)
    fun shade(col: Color): Color = lerp(col, tint, (1f - col.luminance() * 0.65f) * p.discF)
    return CdDerived(
        bgStops = listOf(0f, p.stop1, 0.68f, 1f),
        bgColors = listOf(g0, upper, middle, bottom),
        solidInk = p.ink.copy(alpha = p.solidAlpha),
        ink = p.ink,
        muted = p.muted,
        accent = accent,
        faceColors = p.discA.map(::shade),
        hubStops = CD_HUB_STOPS.zip(p.discB.map(::shade)),
        rimColor = p.discJ
    )
}

/** 盘芯 hub 10 档固定 stop（定谳）；盘面 sweep 8 档均匀（a 表 8 色首尾同色收口）。 */
private val CD_HUB_STOPS = listOf(0f, 0.15f, 0.27f, 0.31f, 0.38f, 0.5f, 0.67f, 0.79f, 0.84f, 1f)
private const val CD_COVER_RATIO = 0.89285713f
private const val CD_HUB_RADIUS_RATIO = 0.16f

// ================= 盘面（CdDisc/CdArtwork 定谳层：sweep 面＋hub 渐变＋旋转封面＋切歌扫光） =================

/**
 * 盘面：外框 f＝min(可用宽, 400dp)（瓜子另有约束高参，星辰中段高度随布局、取宽与上限）；
 * 封面目标 f×0.89285713、圆角＝尺寸×0.05 的圆角方封面随盘旋转（16°/s、帧积分 dt≤0.05、暂停保留角度）；
 * 切歌扫光 mix tween(600, bs4.a)、sweep 角＝mix×方向×90°、presence＝max(0, sin(mix·π)×0.65)。
 * 未落层（值在 a21、绘制通道未逐个定死，交卷注名）：sheen 三层 b21 与 g/h/i/k 的确切通道；
 * hub 半径未在 CdArtwork 抠到确值，取盘面直径×0.16 装配。
 */
@Composable
private fun CdDiscArea(state: PlayerUiState, derived: CdDerived, modifier: Modifier = Modifier) {
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        var lastNanos = -1L
        while (true) {
            withFrameNanos { frame ->
                if (lastNanos >= 0L) {
                    val dt = (frame - lastNanos) / 1_000_000_000f
                    spin = (spin + min(dt, 0.05f) * 16f) % 360f
                }
                lastNanos = frame
            }
        }
    }
    // 切歌扫光：方向按队列序号变化符号（中枢无方向信号，按序号差装配）
    val sweepAnim = remember { Animatable(1f) }
    var prevIndex by remember { mutableIntStateOf(state.queueIndex) }
    var sweepDir by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(state.track?.id) {
        val idx = state.queueIndex
        sweepDir = if (idx >= prevIndex) 1f else -1f
        prevIndex = idx
        if (state.track != null) {
            sweepAnim.snapTo(0f)
            sweepAnim.animateTo(1f, tween(600, easing = EaseBs4A))
        }
    }
    val sweepAngle = sweepAnim.value * sweepDir * 90f
    val sweepPresence = maxOf(0f, sin(sweepAnim.value * Math.PI.toFloat()) * 0.65f)

    BoxWithConstraints(modifier) {
        val f = minOf(maxWidth, 400.dp)
        val bmp = state.coverBitmap
        Box(
            Modifier
                .size(f)
                .align(Alignment.TopCenter)
                .shadow(12.dp, androidx.compose.foundation.shape.CircleShape)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                withTransform({ rotate(spin, center) }) {
                    drawCircle(
                        brush = Brush.sweepGradient(derived.faceColors),
                        radius = radius,
                        center = center
                    )
                    if (bmp != null) {
                        val coverSize = size.minDimension * CD_COVER_RATIO
                        val corner = coverSize * 0.05f
                        val coverPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    center.x - coverSize / 2f,
                                    center.y - coverSize / 2f,
                                    center.x + coverSize / 2f,
                                    center.y + coverSize / 2f,
                                    CornerRadius(corner, corner)
                                )
                            )
                        }
                        clipPath(coverPath) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(
                                    (center.x - coverSize / 2f).roundToInt(),
                                    (center.y - coverSize / 2f).roundToInt()
                                ),
                                dstSize = IntSize(coverSize.roundToInt(), coverSize.roundToInt())
                            )
                        }
                    }
                    drawCircle(
                        brush = Brush.sweepGradient(*derived.hubStops.toTypedArray()),
                        radius = radius * CD_HUB_RADIUS_RATIO,
                        center = center
                    )
                }
                if (sweepPresence > 0.01f) {
                    withTransform({ rotate(sweepAngle, center) }) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.5f * sweepPresence),
                                    Color.Transparent
                                )
                            ),
                            radius = radius,
                            center = center
                        )
                    }
                }
                drawCircle(
                    color = derived.rimColor,
                    radius = radius - 0.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
    }
}

// ================= 收起摘要（CdLyricSummary:1031：主行 16sp、副行 12sp、行高 78、左右每侧合计 40） =================

@Composable
private fun CdLyricSummary(
    state: PlayerUiState,
    progress: PlayerProgress,
    solidInk: Color,
    muted: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val offsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong()
    val nowMs = rememberLyricNowMs(state.lyricLines, state.lyricUntimed, progress, state.isPlaying, offsetMs)
    val lines = state.lyricLines
    val cur = if (lines.isEmpty() || state.lyricUntimed) -1 else findCurrentLine(lines, nowMs)
    val mainText = when {
        lines.isEmpty() -> "暂无歌词"
        state.lyricUntimed -> lines.first().main.text
        cur >= 0 -> lines[cur].main.text
        else -> lines.first().main.text
    }
    val subText = when {
        lines.isEmpty() || state.lyricUntimed -> ""
        cur >= 0 && cur + 1 < lines.size -> lines[cur + 1].main.text
        cur < 0 && lines.size > 1 -> lines[1].main.text
        else -> ""
    }
    Column(
        modifier
            .height(78.dp)
            .offset(y = (-10).dp)
            .padding(horizontal = 24.dp)
            .clickableNoRipple(onClick = onClick),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text(
            text = mainText,
            fontSize = 16.sp,
            color = solidInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subText.isNotEmpty()) {
            Text(
                text = subText,
                fontSize = 12.sp,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ================= 展开歌词（CdLyricsList:1197：21sp/BOLD/居中、行高 28、行距 20、左右每侧合计 28） =================

/**
 * 四色（定谳）：已唱 accent、当前行未唱 accent×0.42、非当前 accent×0.38、翻译 accent×0.42。
 * 当前行逐字按词时界分段上色（同一引擎的词数据；无词行整行 accent，同引擎标准模式口径）。
 * 展开/收起动画 600/500(bs4.d) 在根的态切换处未单独装配（列表本体直入，交卷注名）；
 * 翻译行字号未抠到，暂取 13sp 注名。
 */
@Composable
private fun CdLyricsList(
    state: PlayerUiState,
    progress: PlayerProgress,
    accent: Color,
    solidInk: Color,
    muted: Color,
    wordMode: Boolean,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lines = state.lyricLines
    val offsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong()
    val nowMs = rememberLyricNowMs(state.lyricLines, state.lyricUntimed, progress, state.isPlaying, offsetMs)
    val showTranslation = MusicPlayerSettings.isShowTranslation()
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(text = "暂无歌词", fontSize = 16.sp, color = muted)
        }
        return
    }
    val cur = if (state.lyricUntimed) -1 else findCurrentLine(lines, nowMs)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemPx = with(density) { 28.sp.toPx() + 20.dp.toPx() }
    LaunchedEffect(cur) {
        if (cur >= 0) {
            // 当前行滚至约 1/3 处（瓜子展开列表锚点值未单独抠到，按居中偏上装配、交卷注名）
            listState.animateScrollToItem(cur, -(itemPx * 1.2f).roundToInt())
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .padding(horizontal = 12.dp)
            .clipToBounds(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(lines.size) { i ->
            val line = lines[i]
            val isCurrent = i == cur
            val sungLine = cur >= 0 && i < cur
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .clickableNoRipple { if (!state.lyricUntimed) onLineClick(i) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isCurrent && wordMode && line.main.hasWords()) {
                    Text(
                        text = buildAnnotatedString {
                            for (w in line.main.words) {
                                // 词时界与引擎 WordCell 同口径：行起始＋词偏移＋词时长
                                val endAbs = line.main.timeMs + w.startOffsetMs + w.durationMs
                                val sung = nowMs >= endAbs
                                withStyle(
                                    SpanStyle(
                                        color = if (sung) accent else accent.copy(alpha = 0.42f)
                                    )
                                ) {
                                    append(w.text)
                                }
                            }
                        },
                        fontSize = 21.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = line.main.text,
                        fontSize = 21.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isCurrent || sungLine -> accent
                            else -> accent.copy(alpha = 0.38f)
                        },
                        textAlign = TextAlign.Center
                    )
                }
                val translation = line.translation?.takeIf { showTranslation && it.isNotEmpty() }
                if (translation != null) {
                    Text(
                        text = translation,
                        fontSize = 13.sp,
                        color = accent.copy(alpha = 0.42f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ================= 五图标行（CdUtilityGlyph/CdUtilityAction 定谳构成） =================

@Composable
private fun CdUtilityRow(
    state: PlayerUiState,
    derived: CdDerived,
    detail: CdDetail,
    mode: String,
    onQualityClick: () -> Unit,
    onLyricVersionClick: () -> Unit,
    onToggleInfo: () -> Unit,
    onSurfaceCycle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 歌曲信息（激活 accent、余 muted）
        CdUtilityCell(
            active = detail == CdDetail.SONG_INFO,
            derived = derived,
            onClick = onToggleInfo,
            modifier = Modifier.weight(1f)
        ) {
            InfoIcon(26.dp, it)
        }
        // 音质（切换中激活态无信号源，常态 muted）
        CdUtilityCell(active = false, derived = derived, onClick = onQualityClick, modifier = Modifier.weight(1f)) {
            QualityBarsIcon(26.dp, it)
        }
        // 歌词版本（后续单接版本面板本体，本单留槽）
        CdUtilityCell(active = false, derived = derived, onClick = onLyricVersionClick, modifier = Modifier.weight(1f)) {
            LyricVersionIcon(26.dp, it)
        }
        // 收藏（isFavorite → accent）
        CdUtilityCell(
            active = state.isFavorite,
            derived = derived,
            onClick = { PlayerStateHolder.toggleFavorite() },
            modifier = Modifier.weight(1f)
        ) {
            HeartIcon(26.dp, it, filled = state.isFavorite)
        }
        // 表面切换（glyph 随当前 mode：浅=太阳、深=月亮、自动=半圆）
        CdUtilityCell(active = false, derived = derived, onClick = onSurfaceCycle, modifier = Modifier.weight(1f)) {
            when (mode) {
                MusicPlayerSettings.THEME_LUMINOUS_CD_DARK -> PathIcon(26.dp, it, PATH_MOON)
                MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO -> AutoSurfaceIcon(26.dp, it)
                else -> SunIcon(26.dp, it)
            }
        }
    }
}

@Composable
private fun CdUtilityCell(
    active: Boolean,
    derived: CdDerived,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit
) {
    Box(
        modifier
            .height(52.dp)
            .pressScale(0.9f)
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        icon(if (active) derived.accent else derived.muted)
    }
}

// ================= 进度（CdSeekBar:1529 / CdSliderTrack:1746） =================

@Composable
private fun CdSeekBar(
    progress: PlayerProgress,
    accent: Color,
    trackColor: Color,
    timeColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = progress.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (progress.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shown = if (dragging) dragFraction else liveFraction
    val shownPos = if (dragging) (dragFraction * duration).toLong() else progress.positionMs
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        dragFraction =
                            if (size.width > 0) (down.position.x / size.width).coerceIn(0f, 1f) else 0f
                        var completed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    completed = true
                                    break
                                }
                                if (size.width > 0) {
                                    dragFraction =
                                        (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                                change.consume()
                            }
                        } finally {
                            dragging = false
                            if (completed && duration > 0) {
                                onSeek((dragFraction * duration).toLong())
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val midY = size.height / 2f
                val lineH = 2.dp.toPx()
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, midY - lineH / 2f),
                    size = Size(size.width, lineH),
                    cornerRadius = CornerRadius(lineH / 2f, lineH / 2f)
                )
                val fillW = size.width * shown
                if (fillW > 0f) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, midY - lineH / 2f),
                        size = Size(fillW, lineH),
                        cornerRadius = CornerRadius(lineH / 2f, lineH / 2f)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(text = formatClock(shownPos), fontSize = 9.sp, color = timeColor)
            Spacer(Modifier.weight(1f))
            Text(text = formatClock(duration), fontSize = 9.sp, color = timeColor)
        }
    }
}

// ================= 运输行（高 80：模式/上一首/播停/下一首/队列） =================

@Composable
private fun CdTransportRow(
    state: PlayerUiState,
    derived: CdDerived,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().height(80.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        // 模式键（44 盒/23 图标/muted）：字形随当前模式（瓜子 PlayMode 段位映射）
        Box(
            Modifier.size(44.dp).pressScale(0.9f)
                .clickableNoRipple { PlayerStateHolder.cycleMode() },
            contentAlignment = Alignment.Center
        ) {
            val glyph = when (state.playMode) {
                com.fongmi.android.tv.music.MusicQueue.MODE_REPEAT_ONE -> PATH_REPEAT_ONE
                com.fongmi.android.tv.music.MusicQueue.MODE_REPEAT_ALL -> PATH_REPEAT
                com.fongmi.android.tv.music.MusicQueue.MODE_SHUFFLE -> PATH_SHUFFLE
                else -> PATH_REORDER
            }
            PathIcon(23.dp, derived.muted, glyph)
        }
        Box(
            Modifier.size(44.dp).pressScale(0.9f)
                .clickableNoRipple(enabled = state.queueIndex > 0) { PlayerStateHolder.prev() },
            contentAlignment = Alignment.Center
        ) {
            PathIcon(32.dp, derived.solidInk, PATH_PREV)
        }
        // 播停（盒 68；图标：暂停 58、播放 54——§六原值；加载圈 30/2.5 无信号源不落，注名）
        Box(
            Modifier.size(68.dp).pressScale(0.9f)
                .clickableNoRipple { PlayerStateHolder.toggle() },
            contentAlignment = Alignment.Center
        ) {
            if (state.isPlaying) {
                PauseIcon(58.dp, derived.solidInk)
            } else {
                PlayIcon(54.dp, derived.solidInk)
            }
        }
        Box(
            Modifier.size(44.dp).pressScale(0.9f)
                .clickableNoRipple(enabled = state.canNext) { PlayerStateHolder.next() },
            contentAlignment = Alignment.Center
        ) {
            PathIcon(32.dp, derived.solidInk, PATH_NEXT)
        }
        Box(
            Modifier.size(44.dp).pressScale(0.9f).clickableNoRipple(onClick = onQueueClick),
            contentAlignment = Alignment.Center
        ) {
            QueueIcon(23.dp, derived.muted)
        }
    }
}
