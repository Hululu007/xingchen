package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlinx.coroutines.launch

/**
 * Apple Music 主题（gz-player-values.md §五）：封面盒 weight=1 铺满、横滑 pager 三页
 * （信息/封面/全屏歌词）；标题列相对封面下沿上叠 −12dp、横边距 24；底部栈＝
 * Scrubber（轨 6、填充 .58/.9、无顶盒无底行＝不显时间）→控制行（高 96、上叠 −18）→
 * 音量行→功能行（歌词/音质/播放列表）。封面切歌转场 600ms/bs4.a 缩放系。
 */
@Composable
fun ApplePlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onToolsClick: () -> Unit,
    onQueueClick: () -> Unit,
    onQualityClick: () -> Unit,
    onLyricClick: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val track = state.track
    val album = track?.displayAlbum() ?: ""
    val showAlbum = album.isNotEmpty() && album != "未知专辑"

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> Box(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(24.dp)
                ) {
                    SongInfoFace(
                        state = state,
                        colors = SongInfoColors(
                            high = Color.White.copy(alpha = 0.96f),
                            medium = Color.White.copy(alpha = 0.6f),
                            low = Color.White.copy(alpha = 0.42f),
                            inset = Color.White.copy(alpha = 0.10f),
                            accent = Color(0xFFFA243C)
                        )
                    )
                }
                2 -> LyricStage(
                    lines = state.lyricLines,
                    untimed = state.lyricUntimed,
                    wordByWord = MusicPlayerSettings.isFullLyricWordByWord(),
                    fontSizeSp = MusicPlayerSettings.getFullLyricFontSize(),
                    lineHeightFactor = 1.3f,
                    lineGapDp = 6f,
                    fullAnchor = true,
                    anchorFraction = 0f,
                    windowBefore = 3,
                    windowAfter = 15,
                    blurGroupB = true,
                    blurCoefficient = MusicPlayerSettings.blurCoefficient(),
                    palette = LyricPalette.ImmersiveDark,
                    showTranslation = MusicPlayerSettings.isShowTranslation(),
                    alignMode = MusicPlayerSettings.getLyricAlign(),
                    weightLevel = MusicPlayerSettings.getLyricWeight(),
                    lyricOffsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong(),
                    maxVisualLines = 0,
                    topFadeDp = 40f,
                    letterSpacingEm = -0.2f / MusicPlayerSettings.getFullLyricFontSize(),
                    onLineSeek = { PlayerStateHolder.seekTo(it) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp)
                )
                else -> AnimatedContent(
                    targetState = track?.id ?: "",
                    transitionSpec = {
                        (scaleIn(initialScale = 0.97f, animationSpec = tween(600, easing = EaseBs4A)) +
                            fadeIn(tween(600, easing = EaseBs4A))) togetherWith
                            (scaleOut(targetScale = 1.03f, animationSpec = tween(600, easing = EaseBs4A)) +
                                fadeOut(tween(600, easing = EaseBs4A)))
                    },
                    label = "appleCoverSwap"
                ) {
                    CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
                }
            }
        }
        // 标题列＋底部栈（整列上叠 −12dp：标题压封面下沿；末间隔按 22−12 补回视觉底距）
        Column(Modifier.offset(y = (-12).dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
                        AnimatedSongTitle(
                            text = track?.displayTitle() ?: "",
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.W800,
                                lineHeight = 26.sp,
                                letterSpacing = (-0.4).sp
                            ),
                            color = Color.White.copy(alpha = 0.96f)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = track?.displayArtist() ?: "",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W500,
                            lineHeight = 18.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (showAlbum) {
                        Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                            Text(
                                text = album,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.W500,
                                lineHeight = 16.sp,
                                color = Color.White.copy(alpha = 0.42f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                GlassCircleButton(
                    sizeDp = 34,
                    iconSizeDp = 20,
                    onClick = { PlayerStateHolder.toggleFavorite() }
                ) { tint ->
                    HeartIcon(20.dp, tint, filled = state.isFavorite)
                }
                Spacer(Modifier.size(12.dp))
                GlassCircleButton(
                    sizeDp = 34,
                    iconSizeDp = 20,
                    onClick = onToolsClick
                ) { tint ->
                    MoreDotsIcon(20.dp, tint)
                }
            }
            PlaybackScrubber(
                progress = progress,
                audioLabel = null,
                lyricLabel = null,
                onSeek = { PlayerStateHolder.seekTo(it) },
                onQualityClick = onQualityClick,
                onLyricChipClick = onLyricClick,
                modifier = Modifier.padding(horizontal = 24.dp),
                trackHeightDp = 6f,
                fillFromAlpha = 0.58f,
                fillToAlpha = 0.9f,
                showTopBox = false,
                showBottomRow = false
            )
            // 控制行（高 96、上叠 −18）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .offset(y = (-18).dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canPrev = state.queueIndex > 0
                Box(
                    Modifier
                        .size(56.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple(enabled = canPrev) { PlayerStateHolder.prev() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIconBox(
                        48.dp, 42.dp,
                        Color.White.copy(alpha = if (canPrev) 0.9f else 0.35f),
                        PATH_PREV
                    )
                }
                Box(
                    Modifier
                        .size(84.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple { PlayerStateHolder.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIconBox(
                        60.dp, 52.5.dp,
                        Color.White.copy(alpha = 0.95f),
                        if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
                    )
                }
                Box(
                    Modifier
                        .size(56.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple(enabled = state.canNext) { PlayerStateHolder.next() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIconBox(
                        48.dp, 42.dp,
                        Color.White.copy(alpha = if (state.canNext) 0.9f else 0.35f),
                        PATH_NEXT
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            VolumeRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(32.dp)
            )
            Spacer(Modifier.height(2.dp))
            // 功能行（高 52、图标间距 48、共用 alpha .5）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple {
                            // Apple 歌词面就是 pager 第三页（§五），不另开根级全屏歌词页
                            scope.launch { pagerState.animateScrollToPage(2) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LyricStyleGlyph(24.dp, Color.White.copy(alpha = 0.5f))
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple(onClick = onQualityClick),
                    contentAlignment = Alignment.Center
                ) {
                    QualityBarsIcon(24.dp, Color.White.copy(alpha = 0.5f))
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple(onClick = onQueueClick),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(28.dp, Color.White.copy(alpha = 0.5f), GZ_QUEUE_PATH)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 系统音量行（瓜子 SystemVolumeCard 同型；星辰落系统媒体音量，ThemeCommon 口）。 */
@Composable
private fun VolumeRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var fraction by remember { mutableFloatStateOf(systemVolumeFraction(context)) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PathIcon(18.dp, Color.White.copy(alpha = 0.55f), PATH_VOLUME_LOW)
        Canvas(
            Modifier
                .weight(1f)
                .height(32.dp)
                .padding(horizontal = 10.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun apply(x: Float) {
                            val f = if (size.width > 0) {
                                (x / size.width).coerceIn(0f, 1f)
                            } else 0f
                            fraction = f
                            setSystemVolumeFraction(context, f)
                        }
                        apply(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            apply(change.position.x)
                            change.consume()
                        }
                    }
                }
        ) {
            val midY = size.height / 2f
            val trackH = 4.dp.toPx()
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.14f),
                topLeft = Offset(0f, midY - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius
            )
            val fillW = size.width * fraction
            if (fillW > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(0f, midY - trackH / 2f),
                    size = Size(fillW, trackH),
                    cornerRadius = radius
                )
            }
        }
        PathIcon(18.dp, Color.White.copy(alpha = 0.55f), PATH_VOLUME_HIGH)
    }
}
