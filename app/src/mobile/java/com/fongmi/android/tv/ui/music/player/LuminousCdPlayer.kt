package com.fongmi.android.tv.ui.music.player

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.sin

/**
 * 流光 CD 色板（瓜子 CdPalette 移植，公式 jadx 亲验）：
 * fresh＝base 向封面色混 .22；middle＝混 .12 后按 bgMix 向 base；bottom＝混 .08 后
 * 按 bgMix 向 mist；upper(f,f2)＝base 向 fresh 混 mixF（f＋(f2−f)×bgMix）；
 * accent＝封面色向 ink 混 .52，亮度超 accentMaxLum 时按 (lum−max)/(lum−baseLum)
 * 比例向 base 回混。Pearl/Space 两板可按 Companion.mix 逐字段混（表面切换动画用）。
 */
private data class CdPaletteDef(
    val base: Color,
    val mist: Color,
    val bgMix: Float,
    val ink: Color,
    val solidAlpha: Float,
    val muted: Color,
    val dark: Boolean,
    val accentMaxLum: Float,
    /** 盘面 sweep 渐变色（均匀分布、首尾同色闭合）。 */
    val sweep: List<Color>,
    /** 盘芯 sweep 10 档（停点 [0,.15,.27,.31,.38,.5,.67,.79,.84,1] 一一对应）。 */
    val hubSweep: List<Color>,
    val hubBase: Color,
    /** 盘芯染色三参（a21 的 d/e/f）：hub 色经 (1−lum)×e＋d 向 hubBase 混、封顶 .92。 */
    val hubTintD: Float,
    val hubTintE: Float,
    val hubTintF: Float,
)

private fun hex(v: Long): Color = Color(v)

private val CdPearl = CdPaletteDef(
    base = hex(0xFFF4F2EE), mist = hex(0xFFE9EFF5), bgMix = 0f,
    ink = hex(0xFF282D36), solidAlpha = 1.0f, muted = hex(0xFF747D89),
    dark = false, accentMaxLum = 1.0f,
    sweep = listOf(
        hex(0xFF9FAAB7), hex(0xFFF0F3F6), hex(0xFFB6C7D0), hex(0xFFE9E3D4),
        hex(0xFF929FAC), hex(0xFFF7F8FA), hex(0xFFD4CADB), hex(0xFF9FAAB7),
    ),
    hubSweep = listOf(
        hex(0xFF9FAAB7), hex(0xFFF0F3F6), hex(0xFFB6C7D0), hex(0xFFCFC6D8), hex(0xFFE9E3D4),
        hex(0xFF929FAC), hex(0xFFF7F8FA), hex(0xFFBACCD2), hex(0xFFD4CADB), hex(0xFF9FAAB7),
    ),
    hubBase = hex(0xFFFFFFFF), hubTintD = 0.55f, hubTintE = 0.25f, hubTintF = 0.55f,
)

private val CdSpace = CdPaletteDef(
    base = hex(0xFF14161C), mist = hex(0xFF14161C), bgMix = 1.0f,
    ink = hex(0xFFF2F2F7), solidAlpha = 0.72f, muted = hex(0xFF8E96A3),
    dark = true, accentMaxLum = 0.45f,
    sweep = listOf(
        hex(0xFF2E3742), hex(0xFF8792A0), hex(0xFF3B4653), hex(0xFF4E493F),
        hex(0xFF262D37), hex(0xFF8C97A5), hex(0xFF46405A), hex(0xFF2E3742),
    ),
    hubSweep = listOf(
        hex(0xFF2E3742), hex(0xFF8792A0), hex(0xFF3B4653), hex(0xFF443C52), hex(0xFF4E493F),
        hex(0xFF262D37), hex(0xFF8C97A5), hex(0xFF3C4A55), hex(0xFF46405A), hex(0xFF2E3742),
    ),
    hubBase = hex(0xFF2A3038), hubTintD = 0.5f, hubTintE = 0.0f, hubTintF = 0.34f,
)

/** 两板逐字段混（CdPalette.Companion.mix 等价）：dark 取 f≥0.5 侧。 */
private fun mixCdPalette(a: CdPaletteDef, b: CdPaletteDef, f: Float): CdPaletteDef = CdPaletteDef(
    base = lerp(a.base, b.base, f),
    mist = lerp(a.mist, b.mist, f),
    bgMix = a.bgMix + (b.bgMix - a.bgMix) * f,
    ink = lerp(a.ink, b.ink, f),
    solidAlpha = a.solidAlpha + (b.solidAlpha - a.solidAlpha) * f,
    muted = lerp(a.muted, b.muted, f),
    dark = f >= 0.5f,
    accentMaxLum = a.accentMaxLum + (b.accentMaxLum - a.accentMaxLum) * f,
    sweep = a.sweep.zip(b.sweep).map { (x, y) -> lerp(x, y, f) },
    hubSweep = a.hubSweep.zip(b.hubSweep).map { (x, y) -> lerp(x, y, f) },
    hubBase = lerp(a.hubBase, b.hubBase, f),
    hubTintD = a.hubTintD + (b.hubTintD - a.hubTintD) * f,
    hubTintE = a.hubTintE + (b.hubTintE - a.hubTintE) * f,
    hubTintF = a.hubTintF + (b.hubTintF - a.hubTintF) * f,
)

private fun CdPaletteDef.freshTone(cover: Color): Color = lerp(base, cover, 0.22f)

private fun CdPaletteDef.middleTone(cover: Color): Color =
    lerp(base, lerp(base, cover, 0.12f), bgMix)

private fun CdPaletteDef.bottomTone(cover: Color): Color =
    lerp(mist, lerp(base, cover, 0.08f), bgMix)

private fun CdPaletteDef.upperTone(cover: Color, f: Float, f2: Float): Color {
    val mixF = f + (f2 - f) * bgMix
    return lerp(base, freshTone(cover), mixF)
}

private fun CdPaletteDef.accent(cover: Color): Color {
    val h0 = lerp(cover, ink, 0.52f)
    val lum = h0.luminance()
    if (lum <= accentMaxLum) return h0
    val t = ((lum - accentMaxLum) / (lum - base.luminance())).coerceIn(0f, 1f)
    return lerp(h0, base, t)
}

private fun CdPaletteDef.solidInk(): Color = ink.copy(alpha = solidAlpha)

/** 盘芯一档色经材料染色（a21.b 公式）：(1−lum)×e＋d（封顶 .92）从 hubBase 向该色混。 */
private fun CdPaletteDef.hubColor(stop: Color): Color {
    val t = (((1f - stop.luminance()) * hubTintE) + hubTintD).coerceAtMost(0.92f)
    return lerp(hubBase, stop, t)
}

private val HubStops = listOf(0f, 0.15f, 0.27f, 0.31f, 0.38f, 0.5f, 0.67f, 0.79f, 0.84f, 1f)

/**
 * 流光 CD 主题 luminous_cd（案卷六节）：页 Column 左右 16；顶行高 64（左收起、
 * 右三个点菜单 盒 44/图标 23/muted/按压 0.9＝播放工具）；盘面 f＝min(约束宽, 高, 400)、
 * 封面 f×0.89285713、圆角＝尺寸×0.05、旋转 16°/s 帧积分（dt≤0.05s、暂停保留角度）、
 * 切歌扫光 mix tween(600, bs4.d)（sweep＝mix×方向×90°、presence＝max(0,sin(mix·π)×.65)）、
 * 表面切换 500/bs4.a（Pearl/Space 逐字段混）；标题 15sp/行高 21/600/solidInk（超长
 * 跑马灯）、歌手 11sp/行高 15/400/muted（无爱心）；歌词区（展开 21sp/BOLD/居中、
 * 行高 28、行距 20、左右每侧合计 28、四色走 accent palette；收起摘要 主 16/副 12、
 * 行高 78、左右每侧合计 40；展开 600/bs4.d、收起 500/bs4.d）；五图标行（高 52、
 * 左右 2、每格 weight 1、图标盒 26、按压 0.9：歌曲信息/音质/歌词版本/收藏/表面切换，
 * SONG_INFO 激活 accent、余 muted、不可用 alpha×.35）；CdSeekBar（外层左右 8、
 * 本体高 34、轨道容器高 10、线宽 2、未播 ink×.10、已播 accent；时间 9sp、muted×.74）；
 * 运输行（高 80、左右 4：模式 44/23/muted→上一首 44/32/solidInk→播放暂停 盒 68、
 * 图标 播放中 54/暂停 58→下一首 44/32→队列 44/23/muted；按压 0.9）。
 * 三变体 LIGHT/DARK/AUTO 存档即主题 id（luminous_cd/_dark/_auto），表面切换键循环
 * 三档＋Toast 档名；AUTO 按封面亮度选板（装配规则：亮度 ≤.5 取 Space）。
 * 页背景渐变停点：0→base 与 fresh 混 .92、0.34→upper(.48,.73)、0.68→middle、1→bottom。
 */
@Composable
fun LuminousCdPlayer(
    state: PlayerUiState,
    surfaceThemeId: String,
    onSurfaceThemeChange: (String) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenLyricVersions: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCycleMode: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 封面色（根背景同口径 450ms 动画后喂色板混色）
    val coverColor by animateColorAsState(
        targetValue = Color(state.dominantColor),
        animationSpec = tween(450, easing = Bs4A),
        label = "cdCoverColor",
    )
    // 表面：0＝Pearl、1＝Space；AUTO 按封面亮度定目标（装配规则）
    val targetDark = when (surfaceThemeId) {
        MusicPlayerSettings.THEME_LUMINOUS_CD_DARK -> 1f
        MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO ->
            if (coverColor.luminance() <= 0.5f) 1f else 0f
        else -> 0f
    }
    val darkMix by animateFloatAsState(
        targetValue = targetDark,
        animationSpec = tween(500, easing = Bs4A),
        label = "cdSurfaceMix",
    )
    val palette = remember(darkMix) { mixCdPalette(CdPearl, CdSpace, darkMix) }
    val accent = remember(palette, coverColor) { palette.accent(coverColor) }
    val solidInk = palette.solidInk()

    val bgBrush = remember(palette, coverColor) {
        val fresh = palette.freshTone(coverColor)
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to lerp(palette.base, fresh, 0.92f),
                0.34f to palette.upperTone(coverColor, 0.48f, 0.73f),
                0.68f to palette.middleTone(coverColor),
                1f to palette.bottomTone(coverColor),
            ),
        )
    }

    var detail by remember { mutableStateOf(CdDetail.LYRICS) }
    var lyricsExpanded by remember { mutableStateOf(false) }
    val lyricAreaHeight by animateDpAsState(
        targetValue = if (detail == CdDetail.SONG_INFO || lyricsExpanded) 300.dp else 78.dp,
        animationSpec = tween(
            if (lyricsExpanded || detail == CdDetail.SONG_INFO) 600 else 500,
            easing = Bs4D,
        ),
        label = "cdLyricArea",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgBrush)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        // 顶行（高 64）：左收起、右三个点菜单（盒 44、图标 23、muted、按压 0.9）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val srcL = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pressScale(0.9f, srcL)
                    .clickable(interactionSource = srcL, indication = null) { onCollapse() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_chevron),
                    contentDescription = "收起",
                    tint = palette.muted,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            val srcR = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pressScale(0.9f, srcR)
                    .clickable(interactionSource = srcR, indication = null) { onOpenTools() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_more),
                    contentDescription = "播放工具",
                    tint = palette.muted,
                    modifier = Modifier.size(23.dp),
                )
            }
        }

        // 盘面（f＝min(约束宽, 约束高, 400dp)）
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val f = minOf(maxWidth, maxHeight, 400.dp)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CdDisc(
                    state = state,
                    palette = palette,
                    sizeDp = f,
                )
            }
        }

        // 标题（15sp/行高 21/600/solidInk，超长跑马灯）
        Text(
            text = state.title.ifEmpty { "未在播放" },
            color = solidInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 21.sp,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(),
        )
        // 歌手（11sp/行高 15/400/muted，无爱心；瓜子可点跳搜索，本树无此回调、暂不可点）
        Text(
            text = state.artist,
            color = palette.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        // 歌词/歌曲信息区（高度动画：展开 600/bs4.d、收起 500/bs4.d）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(lyricAreaHeight)
                .clipToBounds(),
        ) {
            if (detail == CdDetail.SONG_INFO) {
                CdSongInfo(state = state, palette = palette)
            } else if (lyricsExpanded) {
                // 展开歌词：左右每侧合计 28（页 16 已占、层内再 12）
                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    when (val lyrics = state.lyrics) {
                        is LyricUiState.Content -> {
                            if (lyrics.lines.isEmpty()) {
                                CdLyricHint(text = "暂无歌词", palette = palette)
                            } else {
                                AnchoredLyricStage(
                                    lines = lyrics.lines,
                                    timed = lyrics.timed,
                                    positionMs = state.positionMs,
                                    isPlaying = state.isPlaying,
                                    onSeekLine = onSeek,
                                    spec = LyricStageSpec(
                                        fontSizeSp = 21f,
                                        lineHeightFactor = 28f / 21f,
                                        lineGapDp = 20f,
                                        palette = LyricPalette(
                                            sung = accent,
                                            currentUnsung = accent.copy(alpha = accent.alpha * 0.42f),
                                            inactive = accent.copy(alpha = accent.alpha * 0.38f),
                                            translation = accent.copy(alpha = accent.alpha * 0.42f),
                                        ),
                                        centered = true,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        LyricUiState.Searching -> CdLyricHint(text = "正在找歌词", palette = palette)
                        LyricUiState.None -> CdLyricHint(text = "暂无歌词", palette = palette)
                        LyricUiState.Idle -> Unit
                    }
                }
            } else {
                // 收起摘要（主行 16、副行 12，左右每侧合计 40＝页 16＋摘要 24；点开展开）
                val lines = (state.lyrics as? LyricUiState.Content)?.lines
                val cur = lines?.getOrNull(state.lyricIndex)?.text ?: ""
                val next = lines?.getOrNull(state.lyricIndex + 1)?.text ?: ""
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { lyricsExpanded = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = cur,
                        color = solidInk,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = next,
                        color = palette.muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 五图标行（高 52、左右 2、每格 weight 1、图标盒 26、按压 0.9）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CdIconCell(
                icon = R.drawable.ic_player_info,
                desc = "歌曲信息",
                tint = if (detail == CdDetail.SONG_INFO) accent else palette.muted,
                enabled = true,
                modifier = Modifier.weight(1f),
            ) {
                detail = if (detail == CdDetail.SONG_INFO) CdDetail.LYRICS else CdDetail.SONG_INFO
            }
            CdIconCell(
                icon = R.drawable.ic_music_eq,
                desc = "音质",
                tint = palette.muted,
                enabled = true,
                modifier = Modifier.weight(1f),
            ) { onOpenTools() }
            CdIconCell(
                icon = R.drawable.ic_music_note,
                desc = "歌词版本",
                tint = palette.muted,
                enabled = state.lyrics is LyricUiState.Content,
                modifier = Modifier.weight(1f),
            ) { onOpenLyricVersions() }
            CdIconCell(
                icon = if (state.favorite) R.drawable.ic_music_heart_fill else R.drawable.ic_music_heart,
                desc = "收藏",
                tint = palette.muted,
                enabled = true,
                modifier = Modifier.weight(1f),
            ) { onToggleFavorite() }
            // 表面切换（自绘半深半浅圆：循环 LIGHT→DARK→AUTO＋Toast 档名）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                val src = rememberPressSource()
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .pressScale(0.9f, src)
                        .clickable(interactionSource = src, indication = null) {
                            val nextId = when (surfaceThemeId) {
                                MusicPlayerSettings.THEME_LUMINOUS_CD -> MusicPlayerSettings.THEME_LUMINOUS_CD_DARK
                                MusicPlayerSettings.THEME_LUMINOUS_CD_DARK -> MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO
                                else -> MusicPlayerSettings.THEME_LUMINOUS_CD
                            }
                            MusicPlayerSettings.setTheme(nextId)
                            onSurfaceThemeChange(nextId)
                            Toast.makeText(context, themeDisplayName(nextId), Toast.LENGTH_SHORT).show()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        drawCircle(color = palette.muted, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()))
                        val r = size.minDimension / 2f
                        drawArc(
                            color = palette.muted,
                            startAngle = -90f,
                            sweepAngle = 180f,
                            useCenter = true,
                            topLeft = Offset(size.width / 2f - r, size.height / 2f - r),
                            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        )
                    }
                }
            }
        }

        // CdSeekBar（外层左右 8、本体高 34、轨道容器高 10、线宽 2）
        CdSeekBar(
            state = state,
            palette = palette,
            accent = accent,
            onSeek = onSeek,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        // 运输行（高 80、左右 4）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            val srcMode = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pressScale(0.9f, srcMode)
                    .clickable(interactionSource = srcMode, indication = null) { onCycleMode() },
                contentAlignment = Alignment.Center,
            ) {
                ModeIcon(mode = state.mode, tint = palette.muted, modifier = Modifier.size(23.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            CdTransportKey(
                icon = R.drawable.ic_music_prev,
                desc = "上一首",
                box = 44.dp,
                iconSize = 32.dp,
                tint = solidInk,
                enabled = state.canPrev,
            ) { onPrev() }
            Spacer(modifier = Modifier.width(6.dp))
            // 播放/暂停（盒 68、图标 播放中 54／暂停 58）
            val srcPlay = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .pressScale(0.9f, srcPlay)
                    .clickable(interactionSource = srcPlay, indication = null) { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play
                    ),
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = solidInk,
                    modifier = Modifier.size(if (state.isPlaying) 54.dp else 58.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            CdTransportKey(
                icon = R.drawable.ic_music_next,
                desc = "下一首",
                box = 44.dp,
                iconSize = 32.dp,
                tint = solidInk,
                enabled = state.canNext,
            ) { onNext() }
            Spacer(modifier = Modifier.width(6.dp))
            val srcQueue = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pressScale(0.9f, srcQueue)
                    .clickable(interactionSource = srcQueue, indication = null) { onOpenQueue() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_queue),
                    contentDescription = "播放列表",
                    tint = palette.muted,
                    modifier = Modifier.size(23.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

private enum class CdDetail { LYRICS, SONG_INFO }

/**
 * 盘面：旋转组（16°/s 帧积分、dt≤0.05s、暂停保留角度）＝sweep 渐变盘面（色表均匀
 * 分布）＋封面圆角卡（f×0.89285713、圆角＝尺寸×0.05）＋盘芯（10 档停点 sweep、
 * 色经材料染色）；切歌扫光：mix tween(600, bs4.d)、光泽层转 mix×方向×90°、
 * presence＝max(0, sin(mix·π)×.65)。盘芯直径与光泽层画法为装配（案卷未给）。
 */
@Composable
private fun CdDisc(
    state: PlayerUiState,
    palette: CdPaletteDef,
    sizeDp: androidx.compose.ui.unit.Dp,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            var last = -1L
            while (true) {
                withFrameMillis { t ->
                    if (last >= 0L) {
                        val dt = (t - last).coerceIn(0L, 50L)
                        angle = (angle + dt.toFloat() * 16f / 1000f) % 360f
                    }
                    last = t
                }
            }
        }
    }
    // 切歌扫光
    val sweep = remember { Animatable(0f) }
    var sweepDir by remember { mutableFloatStateOf(1f) }
    var lastIndex by remember { mutableIntStateOf(state.queueIndex) }
    var lastTrack by remember { mutableStateOf(state.track) }
    LaunchedEffect(state.queueIndex, state.track) {
        val oldIndex = lastIndex
        val oldTrack = lastTrack
        lastIndex = state.queueIndex
        lastTrack = state.track
        if (oldTrack != null && state.track != null && oldTrack != state.track) {
            sweepDir = if (state.queueIndex >= oldIndex) 1f else -1f
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(600, easing = Bs4D))
        }
    }
    val presence = maxOf(0f, sin(sweep.value * Math.PI.toFloat())) * 0.65f
    val sweepRotation = sweep.value * sweepDir * 90f

    val bmp = state.coverBitmap
    val hasCover = bmp != null && state.coverBitmapUrl == state.coverUrl
    Box(
        modifier = Modifier.size(sizeDp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle },
        ) {
            // 盘面（sweep 渐变、均匀分布）
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(brush = Brush.sweepGradient(palette.sweep))
            }
            // 封面（f×0.89285713、圆角＝尺寸×0.05）
            val coverSize = sizeDp * 0.89285713f
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(coverSize)
                    .clip(RoundedCornerShape(coverSize * 0.05f)),
            ) {
                if (hasCover) {
                    Image(
                        bitmap = bmp!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.hubBase),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = palette.muted,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            // 盘芯（10 档停点 sweep、材料染色）
            val hubD = sizeDp * 0.30f
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(hubD),
            ) {
                val stops = HubStops.zip(palette.hubSweep.map { palette.hubColor(it) }).toTypedArray()
                drawCircle(brush = Brush.sweepGradient(colorStops = stops))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(hubD * 0.34f)
                    .clip(CircleShape)
                    .background(palette.hubBase),
            )
        }
        // 扫光光泽层（不随盘转：转 sweep 角、alpha＝presence）
        if (presence > 0.01f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = sweepRotation }
                    .alpha(presence),
            ) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.5f),
                            Color.Transparent,
                            Color.Transparent,
                        ),
                    ),
                )
            }
        }
    }
}

/** 五图标行一格：盒 26、按压 0.9；不可用 alpha×.35。 */
@Composable
private fun CdIconCell(
    icon: Int,
    desc: String,
    tint: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    Box(
        modifier = modifier.height(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .pressScale(0.9f, src)
                .alpha(if (enabled) 1f else 0.35f)
                .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = desc,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 歌曲信息面（CdDetailContent 的 SONG_INFO 态：两态枚举已定谳、内容排版为装配）。 */
@Composable
private fun CdSongInfo(state: PlayerUiState, palette: CdPaletteDef) {
    val ink = palette.solidInk()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        InfoLine(label = "歌曲", value = state.title, ink = ink, muted = palette.muted)
        InfoLine(label = "歌手", value = state.artist, ink = ink, muted = palette.muted)
        InfoLine(label = "专辑", value = state.album, ink = ink, muted = palette.muted)
        InfoLine(label = "音质", value = MusicStore.qualityName(state.quality), ink = ink, muted = palette.muted)
        InfoLine(label = "时长", value = formatPlayerTime(state.durationMs), ink = ink, muted = palette.muted)
    }
}

@Composable
private fun InfoLine(label: String, value: String, ink: Color, muted: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = muted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
        Text(
            text = value.ifEmpty { "—" },
            color = ink,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CdLyricHint(text: String, palette: CdPaletteDef) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = palette.muted, fontSize = 14.sp)
    }
}

/**
 * CdSeekBar（案卷六节）：外层左右 8（调用方给）、本体高 34、轨道容器高 10、
 * 线宽 2dp、未播 ink×.10、已播 accent；时间行 9sp、muted×.74、左已播右总长。
 * 拖动松手 seek（与共用 Scrubber 同口径）。
 */
@Composable
private fun CdSeekBar(
    state: PlayerUiState,
    palette: CdPaletteDef,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fraction = if (dragging) dragFraction else liveFraction
    val shownMs = if (dragging) (dragFraction * duration).toLong() else state.positionMs
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragFraction * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            ) {
                val cy = size.height / 2f
                val stroke = 2.dp.toPx()
                drawLine(
                    color = palette.ink.copy(alpha = 0.10f),
                    start = Offset(0f, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                if (fraction > 0f) {
                    drawLine(
                        color = accent,
                        start = Offset(0f, cy),
                        end = Offset(size.width * fraction, cy),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatPlayerTime(shownMs),
                color = palette.muted.copy(alpha = palette.muted.alpha * 0.74f),
                fontSize = 9.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatPlayerTime(duration),
                color = palette.muted.copy(alpha = palette.muted.alpha * 0.74f),
                fontSize = 9.sp,
            )
        }
    }
}

/** CD 运输键（44 盒、图标 32、solidInk；不可用置灰为装配）。 */
@Composable
private fun CdTransportKey(
    icon: Int,
    desc: String,
    box: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    Box(
        modifier = Modifier
            .size(box)
            .pressScale(0.9f, src)
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = tint.copy(alpha = tint.alpha * if (enabled) 1f else 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}
