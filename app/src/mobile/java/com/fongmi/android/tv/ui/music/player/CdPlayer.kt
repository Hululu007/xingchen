package com.fongmi.android.tv.ui.music.player

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.MusicQueue
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import com.fongmi.android.tv.utils.CoverColor
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** 流光 CD 色板（§六＋CdPalette 全解）：Pearl 浅、Space 深。 */
private data class CdPalette(
    val base: Color,
    val mist: Color,
    val ink: Color,
    val muted: Color,
    val solidAlpha: Float,
    val bgMix: Float,
    val accentMaxLum: Float,
    val dark: Boolean
)

private val CD_PEARL = CdPalette(
    Color(0xFFF4F2EE), Color(0xFFE9EFF5), Color(0xFF282D36), Color(0xFF747D89),
    1.0f, 0f, 1.0f, false
)
private val CD_SPACE = CdPalette(
    Color(0xFF14161C), Color(0xFF14161C), Color(0xFFF2F2F7), Color(0xFF8E96A3),
    0.72f, 1.0f, 0.45f, true
)

/** 盘面材料 a21（CdDiscMaterial 全表）：a 扫光 8 档、b 盘芯 10 档、c 基色、d/e/f 浮点。 */
private data class CdMaterial(
    val sweep: List<Color>,
    val hub: List<Color>,
    val c: Color,
    val d: Float,
    val e: Float,
    val f: Float
)

private val CD_MAT_PEARL = CdMaterial(
    sweep = listOf(
        Color(0xFF9FAAB7), Color(0xFFF0F3F6), Color(0xFFB6C7D0), Color(0xFFE9E3D4),
        Color(0xFF929FAC), Color(0xFFF7F8FA), Color(0xFFD4CADB), Color(0xFF9FAAB7)
    ),
    hub = listOf(
        Color(0xFF9FAAB7), Color(0xFFF0F3F6), Color(0xFFB6C7D0), Color(0xFFE9E3D4),
        Color(0xFFCFC6D8), Color(0xFF929FAC), Color(0xFFF7F8FA), Color(0xFFD4CADB),
        Color(0xFFBACCD2), Color(0xFF9FAAB7)
    ),
    c = Color.White, d = 0.55f, e = 0.25f, f = 0.55f
)
private val CD_MAT_SPACE = CdMaterial(
    sweep = listOf(
        Color(0xFF2E3742), Color(0xFF8792A0), Color(0xFF3B4653), Color(0xFF4E493F),
        Color(0xFF262D37), Color(0xFF8C97A5), Color(0xFF46405A), Color(0xFF2E3742)
    ),
    hub = listOf(
        Color(0xFF2E3742), Color(0xFF8792A0), Color(0xFF3B4653), Color(0xFF4E493F),
        Color(0xFF443C52), Color(0xFF262D37), Color(0xFF8C97A5), Color(0xFF46405A),
        Color(0xFF3C4A55), Color(0xFF2E3742)
    ),
    c = Color(0xFF2A3038), d = 0.5f, e = 0f, f = 0.34f
)

private val CD_HUB_STOPS = floatArrayOf(0f, 0.15f, 0.27f, 0.31f, 0.38f, 0.5f, 0.67f, 0.79f, 0.84f, 1f)

private fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t)

/** accent＝封面色向 ink 混 .52、超亮度上限向 base 回混（§六混色定谳）。 */
private fun cdAccent(palette: CdPalette, cover: Color): Color {
    var accent = lerpColor(cover, palette.ink, 0.52f)
    val lum = accent.luminance()
    if (lum > palette.accentMaxLum) {
        val baseLum = palette.base.luminance()
        val denom = lum - baseLum
        val t = if (denom > 0.001f) {
            ((lum - palette.accentMaxLum) / denom).coerceIn(0f, 1f)
        } else 0f
        accent = lerpColor(accent, palette.base, t)
    }
    return accent
}

/**
 * 流光 CD 主题（gz-player-values.md §六）：页背景按 CdPalette 混色渐变；
 * 盘面 a21 材料扫光＋旋转 16°/s 帧积分；歌词两态（展开引擎/收起摘要）＋歌曲信息两态；
 * 五图标行、CdSeekBar、运输行。表面三变体经存档主题 id 循环＋Toast 档名。
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
    onThemeSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = when (mode) {
        MusicPlayerSettings.THEME_LUMINOUS_CD_DARK -> CD_SPACE
        MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO ->
            if (CoverColor.isBright(state.baseColor)) CD_PEARL else CD_SPACE
        else -> CD_PEARL
    }
    val material = if (palette.dark) CD_MAT_SPACE else CD_MAT_PEARL
    val coverTone = state.toneColor?.let { Color(it) } ?: palette.base
    val fresh = lerpColor(palette.base, coverTone, 0.22f)
    val upper = lerpColor(palette.base, fresh, if (palette.dark) 0.68f else 0.48f)
    val middle = lerpColor(palette.base, lerpColor(palette.base, coverTone, 0.12f), palette.bgMix)
    val bottom = lerpColor(palette.mist, lerpColor(palette.base, coverTone, 0.08f), palette.bgMix)
    val top = lerpColor(palette.base, fresh, 0.92f)
    val accent = cdAccent(palette, coverTone)
    val bgStops = if (palette.dark) {
        arrayOf(0f to top, 0.42f to upper, 0.68f to middle, 1f to bottom)
    } else {
        arrayOf(0f to top, 0.34f to upper, 0.68f to middle, 1f to bottom)
    }
    var detailInfo by remember { mutableStateOf(false) }
    var lyricExpanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = bgStops))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // 顶行（高 64）：左收起、右三个点（盒 44、图标 23、muted、按压 .9）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(24.dp, palette.muted, PATH_CHEVRON_DOWN)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple(onClick = onToolsClick),
                    contentAlignment = Alignment.Center
                ) {
                    MoreDotsIcon(23.dp, palette.muted)
                }
            }
            // 盘面（f＝min（宽，400dp)；剩余高归歌词区 weight）
            BoxWithConstraints(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val f = min(maxWidth.value, 400f).dp
                CdDisc(
                    state = state,
                    palette = palette,
                    material = material,
                    coverTone = coverTone,
                    sizeDp = f,
                    modifier = Modifier.size(f)
                )
            }
            // 标题/歌手
            Text(
                text = state.track?.displayTitle() ?: "",
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
                lineHeight = 21.sp,
                color = palette.ink,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee()
            )
            Text(
                text = state.track?.displayArtist() ?: "",
                fontSize = 11.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 15.sp,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .pressScale(0.96f)
                    .clickableNoRipple {
                        onArtistSearch(state.track?.displayArtist().orEmpty())
                    }
            )
            // 歌词/信息区（weight 1）
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AnimatedContent(
                    targetState = when {
                        detailInfo -> 2
                        lyricExpanded -> 1
                        else -> 0
                    },
                    transitionSpec = {
                        fadeIn(tween(600, easing = EaseBs4D)) togetherWith
                            fadeOut(tween(450, easing = EaseBs4A))
                    },
                    label = "cdDetail"
                ) { detail ->
                    when (detail) {
                        2 -> SongInfoFace(
                            state = state,
                            colors = SongInfoColors(
                                high = palette.ink,
                                medium = palette.muted,
                                low = palette.muted.copy(alpha = 0.72f),
                                inset = lerpColor(palette.base, palette.ink, 0.05f),
                                accent = accent
                            ),
                            modifier = Modifier.fillMaxSize()
                        )
                        1 -> LyricStage(
                            lines = state.lyricLines,
                            untimed = state.lyricUntimed,
                            wordByWord = true,
                            fontSizeSp = 21f,
                            lineHeightFactor = 28f / 21f,
                            lineGapDp = 20f,
                            fullAnchor = false,
                            anchorFraction = 0.5f,
                            windowBefore = 2,
                            windowAfter = 0,
                            blurGroupB = true,
                            blurCoefficient = MusicPlayerSettings.blurCoefficient(),
                            palette = LyricPalette(
                                sung = accent,
                                activeUnsung = accent.copy(alpha = 0.42f),
                                inactive = accent.copy(alpha = 0.38f),
                                translation = accent.copy(alpha = 0.42f)
                            ),
                            showTranslation = MusicPlayerSettings.isShowTranslation(),
                            alignMode = 1,
                            weightLevel = 0,
                            lyricOffsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong(),
                            maxVisualLines = 0,
                            topFadeDp = 0f,
                            letterSpacingEm = 0f,
                            onLineSeek = { PlayerStateHolder.seekTo(it) },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                        )
                        else -> CdLyricSummary(
                            state = state,
                            progress = progress,
                            palette = palette,
                            onClick = { lyricExpanded = true }
                        )
                    }
                }
            }
            // 五图标行（高 52、左右 2、每格 weight 1、图标盒 26、按压 .9）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CdGridCell(
                    modifier = Modifier.weight(1f),
                    color = if (detailInfo) accent else palette.muted,
                    onClick = { detailInfo = !detailInfo }
                ) {
                    InfoIcon(26.dp, if (detailInfo) accent else palette.muted)
                }
                CdGridCell(
                    modifier = Modifier.weight(1f),
                    color = palette.muted,
                    onClick = onQualityClick
                ) {
                    QualityBarsIcon(26.dp, palette.muted)
                }
                CdGridCell(
                    modifier = Modifier.weight(1f),
                    color = palette.muted,
                    onClick = onLyricVersionClick
                ) {
                    LyricVersionIcon(26.dp, palette.muted)
                }
                CdGridCell(
                    modifier = Modifier.weight(1f),
                    color = if (state.isFavorite) accent else palette.muted,
                    onClick = { PlayerStateHolder.toggleFavorite() }
                ) {
                    HeartIcon(
                        26.dp,
                        if (state.isFavorite) accent else palette.muted,
                        filled = state.isFavorite
                    )
                }
                CdGridCell(
                    modifier = Modifier.weight(1f),
                    color = palette.muted,
                    onClick = {
                        val (nextId, nextName) = when (mode) {
                            MusicPlayerSettings.THEME_LUMINOUS_CD ->
                                MusicPlayerSettings.THEME_LUMINOUS_CD_DARK to "流光 CD 深色"
                            MusicPlayerSettings.THEME_LUMINOUS_CD_DARK ->
                                MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO to "流光 CD 自动"
                            else ->
                                MusicPlayerSettings.THEME_LUMINOUS_CD to "流光 CD"
                        }
                        onThemeSelected(nextId)
                        Toast.makeText(context, nextName, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    when (mode) {
                        MusicPlayerSettings.THEME_LUMINOUS_CD_DARK ->
                            PathIcon(26.dp, palette.muted, PATH_MOON)
                        MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO ->
                            AutoSurfaceIcon(26.dp, palette.muted)
                        else -> SunIcon(26.dp, palette.muted)
                    }
                }
            }
            CdSeekBar(state = state, progress = progress, palette = palette, accent = accent)
            // 运输行（高 80、左右 4）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple { PlayerStateHolder.cycleMode() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(
                        23.dp,
                        palette.muted,
                        when (state.playMode) {
                            MusicQueue.MODE_REPEAT_ONE -> PATH_REPEAT_ONE
                            MusicQueue.MODE_REPEAT_ALL -> PATH_REPEAT
                            MusicQueue.MODE_SHUFFLE -> PATH_SHUFFLE
                            else -> PATH_REORDER
                        }
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple { PlayerStateHolder.prev() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(32.dp, palette.ink, PATH_PREV)
                }
                Box(
                    Modifier
                        .size(68.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple { PlayerStateHolder.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(
                        if (state.isPlaying) 54.dp else 58.dp,
                        palette.ink,
                        if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple { PlayerStateHolder.next() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(32.dp, palette.ink, PATH_NEXT)
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple(onClick = onQueueClick),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(23.dp, palette.muted, GZ_QUEUE_PATH)
                }
            }
        }
    }
}

@Composable
private fun CdGridCell(
    modifier: Modifier = Modifier,
    color: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .height(52.dp)
            .pressScale(0.9f)
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 收起歌词摘要：主行 16sp＋副行 12sp、行高 78、左右合计 40（页 16＋摘要 24）。 */
@Composable
private fun CdLyricSummary(
    state: PlayerUiState,
    progress: PlayerProgress,
    palette: CdPalette,
    onClick: () -> Unit
) {
    val lines = state.lyricLines
    var mainText = ""
    var subText = ""
    if (lines.isNotEmpty()) {
        if (state.lyricUntimed) {
            mainText = lines[0].main?.text ?: ""
            subText = lines.getOrNull(1)?.main?.text ?: ""
        } else {
            var idx = -1
            for (i in lines.indices) {
                if ((lines[i].main?.timeMs ?: Long.MAX_VALUE) <= progress.positionMs) {
                    idx = i
                } else break
            }
            if (idx >= 0) {
                mainText = lines[idx].main?.text ?: ""
                subText = lines[idx].translation?.takeIf { it.isNotEmpty() }
                    ?: (lines.getOrNull(idx + 1)?.main?.text ?: "")
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(78.dp)
            .padding(horizontal = 24.dp)
            .pressScale(0.98f)
            .clickableNoRipple(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = mainText,
            fontSize = 16.sp,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subText.isNotEmpty()) {
            Text(
                text = subText,
                fontSize = 12.sp,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** CdSeekBar（§六）：外层左右 8、本体高 34、轨道容器 10、线宽 2；时间行 9sp muted×.74。 */
@Composable
private fun CdSeekBar(
    state: PlayerUiState,
    progress: PlayerProgress,
    palette: CdPalette,
    accent: Color
) {
    val duration = progress.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (progress.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shown = if (dragging) dragFraction else liveFraction
    val shownPos = if (dragging) (dragFraction * duration).roundToLong() else progress.positionMs
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        dragFraction = if (size.width > 0) {
                            (down.position.x / size.width).coerceIn(0f, 1f)
                        } else 0f
                        var completed = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    completed = true
                                    break
                                }
                                if (size.width > 0) {
                                    dragFraction =
                                        (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                        } finally {
                            dragging = false
                            if (completed && duration > 0) {
                                PlayerStateHolder.seekTo((dragFraction * duration).roundToLong())
                            }
                        }
                    }
                }
        ) {
            val midY = size.height / 2f
            val lineH = 2.dp.toPx()
            drawRoundRect(
                color = palette.ink.copy(alpha = 0.10f),
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
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatClock(shownPos),
                fontSize = 9.sp,
                color = palette.muted.copy(alpha = 0.74f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatClock(duration),
                fontSize = 9.sp,
                color = palette.muted.copy(alpha = 0.74f)
            )
        }
    }
}

private class CdDiscState {
    var angle = 0f
    var lastFrameMs = -1L
    var lastTrackId: String? = null
    var sweepStartMs = -1L
}

/**
 * 盘面：a21 扫光（每档先经 a() 向盘芯 tint 混）＋封面（f×.89285713、圆角 尺寸×.05、drawImage 裁切）
 * ＋盘芯 10 档固定 stop。旋转 16°/s 帧积分（dt 钳 .05、仅绘制期推进）；
 * 切歌扫光 mix tween 600 派生（presence＝max(0, sin(mix·π)×.65)、sweep＝mix×90°）；
 * 光泽 alpha 播放淡入 700/bs4.d、暂停淡出 450/bs4.a（由调用方 animate 后传入）。
 */
@Composable
private fun CdDisc(
    state: PlayerUiState,
    palette: CdPalette,
    material: CdMaterial,
    coverTone: Color,
    sizeDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val holder = remember { CdDiscState() }
    val gloss by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0f,
        animationSpec = if (state.isPlaying) {
            tween(700, easing = EaseBs4D)
        } else {
            tween(450, easing = EaseBs4A)
        },
        label = "cdGloss"
    )
    val trackId = state.track?.id
    Canvas(modifier) {
        val frameMs = PlayerClock.frameTimeMs.longValue
        val dtMs = if (holder.lastFrameMs < 0L) {
            0L
        } else {
            (frameMs - holder.lastFrameMs).coerceIn(0L, 50L)
        }
        holder.lastFrameMs = frameMs
        if (state.isPlaying && dtMs > 0L) {
            holder.angle = (holder.angle + dtMs * 16f / 1000f) % 360f
        }
        if (trackId != holder.lastTrackId) {
            holder.lastTrackId = trackId
            holder.sweepStartMs = frameMs
        }
        val f = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = f * 0.05f
        // 盘芯染色 tint＝lerp(材料.c → 封面色, min((1−封面亮度)×e＋d, .92))
        val coverLum = coverTone.luminance()
        val tintT = min((1f - coverLum) * material.e + material.d, 0.92f)
        val tint = lerpColor(material.c, coverTone, tintT)
        fun shade(c: Color): Color =
            lerpColor(c, tint, (1f - c.luminance() * 0.65f) * material.f)
        val sweepColors = material.sweep.map { shade(it) }
        val hubColors = material.hub.map { shade(it) }
        val coverBmp = state.coverBitmap

        withTransform({ rotate(holder.angle, center) }) {
            // 盘面扫光（均匀分布）
            drawRoundRect(
                brush = Brush.sweepGradient(sweepColors, center),
                topLeft = Offset(center.x - f / 2f, center.y - f / 2f),
                size = Size(f, f),
                cornerRadius = CornerRadius(radius, radius)
            )
            // 封面（f×.89285713、圆角 尺寸×.05）
            if (coverBmp != null) {
                val coverSize = f * 0.89285713f
                val coverOff = Offset(center.x - coverSize / 2f, center.y - coverSize / 2f)
                drawContext.canvas.save()
                drawContext.canvas.clipPath(
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                coverOff.x, coverOff.y,
                                coverOff.x + coverSize, coverOff.y + coverSize,
                                CornerRadius(coverSize * 0.05f, coverSize * 0.05f)
                            )
                        )
                    }
                )
                drawImage(
                    image = coverBmp.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        coverOff.x.roundToInt(),
                        coverOff.y.roundToInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        coverSize.roundToInt(),
                        coverSize.roundToInt()
                    )
                )
                drawContext.canvas.restore()
            }
            // 盘芯（10 档固定 stop 扫光）
            val hubStops = Array(CD_HUB_STOPS.size) { i -> CD_HUB_STOPS[i] to hubColors[i] }
            drawCircle(
                brush = Brush.sweepGradient(colorStops = hubStops, center = center),
                radius = f * 0.09f,
                center = center
            )
            drawCircle(
                color = tint,
                radius = f * 0.028f,
                center = center
            )
        }
        // 切歌扫光 overlay（不随盘转）
        val mix = if (holder.sweepStartMs >= 0L) {
            ((frameMs - holder.sweepStartMs) / 600f).coerceIn(0f, 1f)
        } else 1f
        if (mix < 1f) {
            val presence = maxOf(0f, sin(mix * Math.PI.toFloat()) * 0.65f)
            val sweepAngle = mix * 90f
            drawArc(
                color = Color.White.copy(alpha = presence * 0.35f * gloss.coerceIn(0f, 1f)),
                startAngle = sweepAngle - 25f,
                sweepAngle = 50f,
                useCenter = true,
                topLeft = Offset(center.x - f / 2f, center.y - f / 2f),
                size = Size(f, f)
            )
        }
    }
}
