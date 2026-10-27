package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/**
 * 经典页（瓜子竖屏经典装配逐项对照 PlayerScreenKt 调用序）：
 * 氛围层（糊封面）→ CoverArt 锐封面整页垫底 → SongMeta（28dp 横边距，状态栏 insets＋上 12 下 8）
 * → 页内歌词区（weight 1，LyricEngine 封面窗装配，无词时空态）→ 6dp → PlaybackScrubber（横 28dp）
 * → 45dp 收尾。经典页没有运输行：播停在标题行第三键，切歌走封面手势（下一单）。
 * 根底色不在本件画：PlayerRoot 用中枢 baseColor 以 450ms/bs4.a 动画铺满整窗，形变长开时不露空。
 */
@Composable
fun ClassicPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onMenuClick: () -> Unit,
    onQueueClick: () -> Unit,
    onArtistClick: () -> Unit,
    onAlbumClick: () -> Unit,
    onQualityClick: () -> Unit,
    onLyricChipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 歌词模式（Std/逐字）：初值读现行存档键、切换即回写（与全屏页共用同一键，引擎口径一致）
    var wordMode by remember { mutableStateOf(MusicPlayerSettings.isFullLyricWordByWord()) }
    val hasLyricWords = state.lyricLines.any { it.main.hasWords() }
    Box(modifier.fillMaxSize()) {
        AmbientLayer(state)
        CoverArtLayer(state)
        Column(Modifier.fillMaxSize()) {
            SongMeta(
                state = state,
                onMenuClick = onMenuClick,
                onQueueClick = onQueueClick,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick
            )
            // 页内歌词区（LyricEngine 封面窗装配；空态与骨架期同文同位）
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
            ) {
                if (state.lyricLines.isEmpty()) {
                    Text(
                        text = "暂无歌词",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    ClassicLyricWindow(
                        lines = state.lyricLines,
                        untimed = state.lyricUntimed,
                        progress = progress,
                        isPlaying = state.isPlaying,
                        wordMode = wordMode,
                        onLineClick = { index ->
                            state.lyricLines.getOrNull(index)?.let {
                                PlayerStateHolder.seekTo(it.main.timeMs)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            PlaybackScrubber(
                progress = progress,
                audioLabel = state.audioLabel,
                lyricLabel = lyricChipLabel(state, wordMode),
                onSeek = { PlayerStateHolder.seekTo(it) },
                onQualityClick = onQualityClick,
                // 歌词筹本单接引擎模式切换（有逐字数据才可切）；版本面板回调（onLyricChipClick）留待面板单
                onLyricChipClick = {
                    if (hasLyricWords) {
                        wordMode = !wordMode
                        MusicPlayerSettings.setFullLyricWordByWord(wordMode)
                    }
                },
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(45.dp))
        }
    }
}

/** 歌词筹签（瓜子筹面口径）：无词/纯文本/逐行是数据形态，逐字/标准是当前引擎模式。 */
private fun lyricChipLabel(state: PlayerUiState, wordMode: Boolean): String? {
    if (state.track == null) return null
    if (state.lyricLines.isEmpty()) return "无词"
    if (state.lyricUntimed) return "纯文本"
    if (state.lyricLines.none { it.main.hasWords() }) return "逐行"
    return if (wordMode) "逐字" else "标准"
}

/** 糊封面氛围（BlurredCoverAmbient）：64 缩图整页拉满＋24dp 糊＋底色派生渐变罩（上明 +0.05／下暗 −0.04）。 */
@Composable
private fun AmbientLayer(state: PlayerUiState) {
    val ambient = state.ambientBitmap ?: return
    val base = Color(state.baseColor)
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = ambient.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.8f,
            modifier = Modifier
                .fillMaxSize()
                .blur(24.dp)
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            shiftLightness(base, 0.05f).copy(alpha = 0.88f),
                            shiftLightness(base, -0.04f).copy(alpha = 0.94f)
                        )
                    )
                )
        )
    }
}

/** 锐封面整页垫底：暂停缩 0.9；切歌 400ms/bs4.e 转场（新下入半高＋.94 起、旧缩 .96 淡出）；底部向下溶进氛围。 */
@Composable
private fun CoverArtLayer(state: PlayerUiState) {
    val coverScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.9f,
        animationSpec = tween(300, easing = EaseBs4A),
        label = "coverPauseScale"
    )
    AnimatedContent(
        targetState = state.coverBitmap,
        transitionSpec = {
            (fadeIn(tween(400, easing = EaseBs4E)) +
                slideInVertically(tween(400, easing = EaseBs4E)) { it / 2 }) togetherWith
                (fadeOut(tween(400, easing = EaseBs4E)) +
                    scaleOut(targetScale = 0.96f, animationSpec = tween(400, easing = EaseBs4E)))
        },
        label = "coverArt",
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = coverScale
                scaleY = coverScale
            }
    ) { bitmap ->
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .dissolveBottom()
            )
        }
    }
}

/** 底部溶边：0.78 起向下溶进氛围色（止点照全屏歌词页抠值借用，经典页自身止点未抠到，见交卷清单）。 */
private fun Modifier.dissolveBottom(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(0.78f to Color.Black, 1f to Color.Transparent),
        blendMode = BlendMode.DstIn
    )
}

/** 明度平移（氛围渐变罩用）：按比例往白/黑方向挪。 */
private fun shiftLightness(color: Color, delta: Float): Color {
    return if (delta >= 0f) {
        Color(
            color.red + (1f - color.red) * delta,
            color.green + (1f - color.green) * delta,
            color.blue + (1f - color.blue) * delta,
            color.alpha
        )
    } else {
        val k = 1f + delta
        Color(color.red * k, color.green * k, color.blue * k, color.alpha)
    }
}

/**
 * 歌名行（SongMeta，内部逐项对照 jadx 24695–25217）：
 * 标题容器高 28dp——歌名 18sp/800/行高 24/字距 −0.4/白 0.95、长名滚动；空名兜底「未在播放」。
 * 第二行距标题容器 2dp、高 22dp：歌手 14sp/500 白 0.45（可点）＋爱心 13dp（左右 padding 7、
 * 已收藏实心 0.45／未收藏描边 0.30、按压 0.85）＋专辑 14sp/500 白 0.45（空不画）。
 * 右侧三键与文本列间隔 8dp，各 40dp 盒贴连：菜单 21dp、队列 20dp、播停 28（播）/25（停）300ms 尺寸过渡。
 */
@Composable
private fun SongMeta(
    state: PlayerUiState,
    onMenuClick: () -> Unit,
    onQueueClick: () -> Unit,
    onArtistClick: () -> Unit,
    onAlbumClick: () -> Unit
) {
    val track = state.track
    val title = track?.displayTitle()?.takeIf { it.isNotEmpty() } ?: "未在播放"
    val artist = track?.displayArtist()?.takeIf { it.isNotEmpty() } ?: "—"
    val album = track?.displayAlbum()?.takeIf { it.isNotEmpty() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .height(28.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W800,
                        lineHeight = 24.sp,
                        letterSpacing = (-0.4).sp,
                        color = Color.White.copy(alpha = 0.95f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.height(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = artist,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onArtistClick
                        )
                    )
                    val heartAlpha by animateFloatAsState(
                        targetValue = if (state.isFavorite) 0.45f else 0.30f,
                        animationSpec = tween(300),
                        label = "heartAlpha"
                    )
                    val heartSource = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .padding(horizontal = 7.dp)
                            .pressScale(heartSource, 0.85f)
                            .clickable(
                                interactionSource = heartSource,
                                indication = null,
                                onClick = { PlayerStateHolder.toggleFavorite() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        HeartIcon(
                            size = 13.dp,
                            color = Color.White.copy(alpha = heartAlpha),
                            filled = state.isFavorite
                        )
                    }
                    if (album != null) {
                        Text(
                            text = album,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp,
                            color = Color.White.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onAlbumClick
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaKey(onClick = onMenuClick) {
                    MenuIcon(size = 21.dp, color = Color.White)
                }
                MetaKey(onClick = onQueueClick) {
                    QueueIcon(size = 20.dp, color = Color.White)
                }
                val playSize by animateDpAsState(
                    targetValue = if (state.isPlaying) 28.dp else 25.dp,
                    animationSpec = tween(300),
                    label = "playPauseSize"
                )
                MetaKey(onClick = { PlayerStateHolder.toggle() }) {
                    if (state.isPlaying) {
                        PauseIcon(size = playSize, color = Color.White)
                    } else {
                        PlayIcon(size = playSize, color = Color.White)
                    }
                }
            }
        }
    }
}

/** 标题行键位：40dp 盒＋按压回弹（MotionKt.c 同型），图标居中。 */
@Composable
private fun MetaKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .pressScale(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
