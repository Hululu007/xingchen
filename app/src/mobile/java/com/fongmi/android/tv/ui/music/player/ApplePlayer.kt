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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

/**
 * Apple Music 主题主页（瓜子 AppleMusicPlayer 内容 lambda，PlayerScreenKt.java:2853 起；
 * 数值认 gz-player-values.md §五）：内部三页交叉淡入栈的页 1——页 2 歌词页即根的 FullLyricPlayer
 * （同一 AppleLyricsView），页 0 歌曲信息页不在本单范围（后续单）。
 * 纵列：封面盒（weight 1 铺满、切歌 600ms/bs4.a 缩放淡入）→ 标题列（相对封面下沿上叠 −12）
 * → Scrubber（轨 6、无顶盒无底行）→ 控制行（高 96、上叠 −18）→ 间隔 6 → 音量行
 * → 间隔 2 → 功能行（高 52）→ 底部间隔 22。
 */
@Composable
fun ApplePlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onToolsClick: () -> Unit,
    onQueueClick: () -> Unit,
    onQualityClick: () -> Unit,
    onLyricClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.track
    Column(modifier.fillMaxSize()) {
        // 封面盒：weight 1 铺满（AppleMusicCoverArt:1622，Crop 铺满；圆角值未抠到、不自造方角直铺）
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = state.coverUrl,
                transitionSpec = {
                    (fadeIn(tween(600, easing = EaseBs4A)) + scaleIn(
                        initialScale = 0.97f,
                        animationSpec = tween(600, easing = EaseBs4A)
                    )) togetherWith (fadeOut(tween(600, easing = EaseBs4A)) + scaleOut(
                        targetScale = 1.03f,
                        animationSpec = tween(600, easing = EaseBs4A)
                    ))
                },
                label = "appleCover",
                modifier = Modifier.fillMaxSize()
            ) { _ ->
                // 位图由中枢按 url 守卫发布（切歌先清旧图）：过渡期两层同图或空，不挂上一首的脸
                CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
            }
        }
        // 标题列：相对封面下沿上叠 −12dp、横边距 24
        Column(
            Modifier
                .offset(y = (-12).dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.CenterStart) {
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
                Spacer(Modifier.width(8.dp))
                // 爱心（34dp 圆玻璃、图标 20 白 0.85、按压 0.88）
                GlassCircleButton(34, 20, onClick = { PlayerStateHolder.toggleFavorite() }) { tint ->
                    HeartIcon(20.dp, if (state.isFavorite) Color.White else tint, filled = state.isFavorite)
                }
                Spacer(Modifier.width(12.dp))
                // 三个点 → 工具面板（本主题的主题切换入口）
                GlassCircleButton(34, 20, onClick = onToolsClick) { tint ->
                    MoreDotsIcon(20.dp, tint)
                }
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(5.dp))
            // 歌手（高 22、14sp/500/白 0.6）
            Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
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
            // 专辑（高 18、12sp/500/白 0.42；仅专辑非空且非「未知专辑」时画）
            val album = track?.displayAlbum()?.takeIf { it.isNotEmpty() && it != "未知专辑" }
            if (album != null) {
                Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
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
        // Scrubber（横 24、轨 6dp、填充 0.58→0.9、无顶盒、无底行——Apple 不显示时间，§五定谳）
        PlaybackScrubber(
            progress = progress,
            audioLabel = null,
            lyricLabel = null,
            onSeek = { PlayerStateHolder.seekTo(it) },
            onQualityClick = onQualityClick,
            onLyricChipClick = {},
            modifier = Modifier.padding(horizontal = 24.dp),
            trackHeightDp = 6f,
            fillFromAlpha = 0.58f,
            fillToAlpha = 0.9f,
            showTopBox = false,
            showBottomRow = false
        )
        // 控制行（高 96、上叠 −18；三键间距 24）
        Row(
            Modifier
                .fillMaxWidth()
                .offset(y = (-18).dp)
                .height(96.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                24.dp, Alignment.CenterHorizontally
            )
        ) {
            val canPrev = state.queueIndex > 0
            Box(
                Modifier
                    .size(56.dp)
                    .pressScale(0.88f)
                    .alpha(if (canPrev) 0.9f else 0.35f)
                    .clickableNoRipple(enabled = canPrev) { PlayerStateHolder.prev() },
                contentAlignment = Alignment.Center
            ) {
                PathIconBox(48.dp, 42.dp, Color.White, PATH_PREV)
            }
            Box(
                Modifier
                    .size(84.dp)
                    .pressScale(0.9f)
                    .alpha(0.95f)
                    .clickableNoRipple { PlayerStateHolder.toggle() },
                contentAlignment = Alignment.Center
            ) {
                if (state.isPlaying) {
                    PathIconBox(60.dp, 52.5.dp, Color.White, PATH_PAUSE_BARS)
                } else {
                    PathIconBox(60.dp, 52.5.dp, Color.White, PATH_PLAY_TRIANGLE)
                }
            }
            Box(
                Modifier
                    .size(56.dp)
                    .pressScale(0.88f)
                    .alpha(if (state.canNext) 0.9f else 0.35f)
                    .clickableNoRipple(enabled = state.canNext) { PlayerStateHolder.next() },
                contentAlignment = Alignment.Center
            ) {
                PathIconBox(48.dp, 42.dp, Color.White, PATH_NEXT)
            }
        }
        Spacer(Modifier.height(6.dp))
        // 音量行（横 24、行高 32）：瓜子走播放器音量、星辰无此口 → 系统媒体音量（口径差注名）；
        // 两端图标尺寸与滑杆浓度未逐项抠到，按同族值装配（交卷注名）
        AppleVolumeRow(Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(2.dp))
        // 功能行（高 52、图标间距 48：歌词 24/音质 24/播放列表 28；启用 alpha 0.5）
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                48.dp, Alignment.CenterHorizontally
            )
        ) {
            Box(
                Modifier.pressScale(0.9f).alpha(0.5f).clickableNoRipple(onClick = onLyricClick),
                contentAlignment = Alignment.Center
            ) {
                LyricStyleGlyph(24.dp, Color.White)
            }
            Box(
                Modifier.pressScale(0.9f).alpha(0.5f)
                    .clickableNoRipple(onClick = onQualityClick),
                contentAlignment = Alignment.Center
            ) {
                QualityBarsIcon(24.dp, Color.White)
            }
            Box(
                Modifier.pressScale(0.9f).alpha(0.5f).clickableNoRipple(onClick = onQueueClick),
                contentAlignment = Alignment.Center
            ) {
                QueueIcon(28.dp, Color.White)
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

/** 音量行：低/高音图标＋滑杆（拖动写系统媒体音量）。 */
@Composable
private fun AppleVolumeRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var fraction by remember { mutableFloatStateOf(systemVolumeFraction(context)) }
    Row(
        modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PathIcon(18.dp, Color.White.copy(alpha = 0.5f), PATH_VOLUME_LOW)
        Canvas(
            Modifier
                .weight(1f)
                .height(32.dp)
                .padding(horizontal = 10.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun apply(x: Float) {
                            val f = if (size.width > 0) (x / size.width).coerceIn(0f, 1f) else 0f
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
                color = Color.White.copy(alpha = 0.20f),
                topLeft = Offset(0f, midY - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius
            )
            val fillW = size.width * fraction
            if (fillW > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.58f),
                    topLeft = Offset(0f, midY - trackH / 2f),
                    size = Size(fillW, trackH),
                    cornerRadius = radius
                )
            }
        }
        PathIcon(18.dp, Color.White.copy(alpha = 0.5f), PATH_VOLUME_HIGH)
    }
}

private const val PATH_PLAY_TRIANGLE = "M8 5v14l11-7z"
private const val PATH_PAUSE_BARS = "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
