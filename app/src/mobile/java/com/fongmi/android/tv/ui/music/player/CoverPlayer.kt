package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.min

/**
 * 封面卡片主题（gz-player-values.md §四）：纵列 42 间隔→歌名→4→歌手→22→封面槽→14→
 * 玻璃歌词条→底部 12。封面几何 宽＝min(可用宽×.98, 可用高/.65)、高＝宽×.65、圆角 22，
 * 黑罩四档 .18/.26/.40/.50。点封面展开（snap 切换）：BackdropCard＋四玻璃键＋
 * Scrubber（全默认）＋歌词窗（前2/后6）。歌名行三键与经典同位装配（待真机对照项，
 * 见 gz-smali-missing-dig.md 封面卡头行定谳）。
 */
@Composable
fun CoverPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onQueueClick: () -> Unit,
    onQualityClick: () -> Unit,
    onLyricChipClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var infoShown by remember { mutableStateOf(false) }
    val track = state.track
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(42.dp))
        // 歌名行（高 30）＋歌手行（高 22）：左文本列、右三键（与经典同位装配）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = track?.displayTitle() ?: "",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.W800,
                        letterSpacing = (-0.4).sp,
                        lineHeight = 26.sp,
                        color = Color.White.copy(alpha = 0.96f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = track?.displayArtist() ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W500,
                        lineHeight = 18.sp,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.85f)
                        .clickableNoRipple(onClick = onMenuClick),
                    contentAlignment = Alignment.Center
                ) {
                    MenuIcon(21.dp, Color.White)
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.85f)
                        .clickableNoRipple(onClick = onQueueClick),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(20.dp, Color.White, GZ_QUEUE_PATH)
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.85f)
                        .clickableNoRipple { PlayerStateHolder.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(
                        if (state.isPlaying) 28.dp else 25.dp,
                        Color.White,
                        if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        if (!expanded) {
            // 封面槽（权重区、左右 24）
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp)
            ) {
                val cardW = min(maxWidth.value * 0.98f, maxHeight.value / 0.65f).dp
                val cardH = cardW * 0.65f
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(cardW, cardH)
                ) {
                    // 错位卡 PlaceholderCard（圆角 22、阴影 6、渐变白 .08→.03、描边 1 白 .12；错位量未抠、结构级偏移）
                    Box(
                        Modifier
                            .offset(x = 6.dp, y = 8.dp)
                            .size(cardW, cardH)
                            .shadow(6.dp, RoundedCornerShape(22.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.08f),
                                        Color.White.copy(alpha = 0.03f)
                                    )
                                ),
                                RoundedCornerShape(22.dp)
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.12f),
                                RoundedCornerShape(22.dp)
                            )
                    )
                    // 主卡：封面＋黑罩四档
                    Box(
                        Modifier
                            .size(cardW, cardH)
                            .clip(RoundedCornerShape(22.dp))
                            .clickableNoRipple { expanded = true }
                    ) {
                        CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Black.copy(alpha = 0.18f),
                                            0.4f to Color.Black.copy(alpha = 0.26f),
                                            0.75f to Color.Black.copy(alpha = 0.40f),
                                            1f to Color.Black.copy(alpha = 0.50f)
                                        )
                                    )
                                )
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            CoverLyricGlassBar(
                state = state,
                progress = progress,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(12.dp))
        } else {
            // 展开态：BackdropCard（渐变 #3A3A3C→#1C1C1E＋模糊封面＋黑 .35）
            val blurAlpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(250, easing = EaseBs4A),
                label = "coverBlurAlpha"
            )
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp)
            ) {
                val cardW = min(maxWidth.value * 0.98f, maxHeight.value / 0.65f).dp
                val cardH = cardW * 0.65f
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(cardW, cardH)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E))
                            )
                        )
                        .clickableNoRipple { expanded = false }
                ) {
                    val ambient = state.ambientBitmap
                    if (ambient != null) {
                        Image(
                            bitmap = ambient.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = blurAlpha }
                                .then(
                                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                                        Modifier.blur(18.dp)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                    )
                    // 封面内四玻璃键（各 42、图标 22、间距 24、内边距 10）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                            24.dp,
                            Alignment.CenterHorizontally
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassKey42(selected = false, onClick = { PlayerStateHolder.prev() }) {
                            PathIcon(22.dp, Color.White, PATH_PREV)
                        }
                        GlassKey42(selected = false, onClick = onQueueClick) {
                            PathIcon(22.dp, Color.White, GZ_QUEUE_PATH)
                        }
                        GlassKey42(selected = infoShown, onClick = { infoShown = !infoShown }) {
                            InfoIcon(22.dp, Color.White)
                        }
                        GlassKey42(selected = false, onClick = { PlayerStateHolder.next() }) {
                            PathIcon(22.dp, Color.White, PATH_NEXT)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PlaybackScrubber(
                progress = progress,
                audioLabel = state.audioLabel,
                lyricLabel = lyricChipLabel(state),
                onSeek = { PlayerStateHolder.seekTo(it) },
                onQualityClick = onQualityClick,
                onLyricChipClick = onLyricChipClick,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (infoShown) {
                    SongInfoFace(
                        state = state,
                        colors = SongInfoColors(
                            high = SheetTokens.TextHigh,
                            medium = SheetTokens.TextMedium,
                            low = SheetTokens.TextLow,
                            inset = Color.White.copy(alpha = 0.08f),
                            accent = Color(0xFFFA243C)
                        )
                    )
                } else {
                    LyricStage(
                        lines = state.lyricLines,
                        untimed = state.lyricUntimed,
                        wordByWord = true,
                        fontSizeSp = MusicPlayerSettings.getCoverLyricFontSize(),
                        lineHeightFactor = 4f / 3f,
                        lineGapDp = 20f,
                        fullAnchor = false,
                        anchorFraction = MusicPlayerSettings.lyricAnchorFraction(),
                        windowBefore = 2,
                        windowAfter = 6,
                        blurGroupB = false,
                        blurCoefficient = MusicPlayerSettings.blurCoefficient(),
                        palette = LyricPalette.ImmersiveDark,
                        showTranslation = MusicPlayerSettings.isShowTranslation(),
                        alignMode = MusicPlayerSettings.getLyricAlign(),
                        weightLevel = MusicPlayerSettings.getLyricWeight(),
                        lyricOffsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong(),
                        maxVisualLines = 2,
                        topFadeDp = 0f,
                        letterSpacingEm = 0f,
                        onLineSeek = { PlayerStateHolder.seekTo(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 展开态四玻璃键（42dp、图标 22dp 白 tint；歌曲信息键选中态面白 .28、普通 .14）。 */
@Composable
private fun GlassKey42(
    selected: Boolean,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(42.dp)
            .pressScale(0.9f)
            .background(
                Color.White.copy(alpha = if (selected) 0.28f else 0.14f),
                RoundedCornerShape(percent = 50)
            )
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 默认态玻璃歌词条 CoverLyricGlassBar（§四：高 48、圆角 22、基底 #FF1E1E22＋渐变白 .26→.08＋描边 1＋阴影 4）。 */
@Composable
private fun CoverLyricGlassBar(
    state: PlayerUiState,
    progress: PlayerProgress,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    val lines = state.lyricLines
    val currentText = remember(lines, progress.positionMs, state.lyricUntimed) {
        if (lines.isEmpty()) {
            ""
        } else if (state.lyricUntimed) {
            lines.firstOrNull()?.main?.text ?: ""
        } else {
            var idx = -1
            for (i in lines.indices) {
                if ((lines[i].main?.timeMs ?: Long.MAX_VALUE) <= progress.positionMs) {
                    idx = i
                } else {
                    break
                }
            }
            if (idx >= 0) lines[idx].main?.text ?: "" else ""
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(4.dp, shape)
            .background(Color(0xFF1E1E22), shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.26f),
                        Color.White.copy(alpha = 0.08f)
                    )
                ),
                shape
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .pressScale(0.85f)
                .clickableNoRipple { PlayerStateHolder.toggleFavorite() },
            contentAlignment = Alignment.Center
        ) {
            HeartIcon(
                size = 20.dp,
                color = Color.White.copy(alpha = if (state.isFavorite) 1f else 0.45f),
                filled = state.isFavorite
            )
        }
        Text(
            text = currentText,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = Color.White.copy(alpha = if (currentText.isEmpty()) 0.45f else 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .size(40.dp)
                .pressScale(0.85f)
                .clickableNoRipple { PlayerStateHolder.toggle() },
            contentAlignment = Alignment.Center
        ) {
            PathIcon(
                24.dp,
                Color.White,
                if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
            )
        }
    }
}
