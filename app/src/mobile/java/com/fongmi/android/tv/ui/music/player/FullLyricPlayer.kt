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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.roundToLong

/**
 * 全屏歌词页（定案图 113209/113250 构成，数值认 gz-player-values.md §八）：
 * 氛围层垫底（与经典页共用 AmbientLayer）→ 封面方形块整宽置顶（底部 0.78 溶边，与经典页同件）
 * → 歌名行（右三裸键＝队列／歌词样式／播放暂停，与经典页的菜单/队列/播停不同，别装错）
 * → 第二行「歌手 ♡ 专辑」→ 歌词区（LyricEngine 全屏链装配，横 28dp）
 * → 底部 Std/逐字双筹（居中）＋细进度线。
 * 播放暂停当场可用；队列与歌词样式两键只留回调槽，面板本体是后续单。
 * 与经典页同一棵树、共用中枢状态与引擎，不另建第二套状态/歌词逻辑。
 */
@Composable
fun FullLyricPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onQueueClick: () -> Unit,
    onLyricStyleClick: () -> Unit,
    onArtistClick: () -> Unit,
    onAlbumClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 歌词模式（Std/逐字）：与经典页共用同一存档键、初值同读、切换即回写
    var wordMode by remember { mutableStateOf(MusicPlayerSettings.isFullLyricWordByWord()) }
    val hasLyricWords = state.lyricLines.any { it.main.hasWords() }
    Box(modifier.fillMaxSize()) {
        AmbientLayer(state)
        Column(Modifier.fillMaxSize()) {
            FullCoverLayer(state)
            FullSongMeta(
                state = state,
                onQueueClick = onQueueClick,
                onLyricStyleClick = onLyricStyleClick,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick
            )
            // 歌词区（LyricEngine 全屏链装配；空态与经典页同文同位）
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
                    FullLyricList(
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
            // 底部：Std/逐字双筹（居中）＋细进度线（间距照定案图样子装配，源码未抠）
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeChip(label = "Std", selected = !wordMode) {
                        if (hasLyricWords && wordMode) {
                            wordMode = false
                            MusicPlayerSettings.setFullLyricWordByWord(false)
                        }
                    }
                    ModeChip(label = "逐字", selected = wordMode) {
                        if (hasLyricWords && !wordMode) {
                            wordMode = true
                            MusicPlayerSettings.setFullLyricWordByWord(true)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ThinProgressLine(
                    progress = progress,
                    onSeek = { PlayerStateHolder.seekTo(it) }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 封面方形块：整宽 1:1 置顶、切歌 400ms/bs4.e 转场（与经典页 CoverArtLayer 同规格）、底部 0.78 溶进氛围。 */
@Composable
private fun FullCoverLayer(state: PlayerUiState) {
    AnimatedContent(
        targetState = state.coverBitmap,
        transitionSpec = {
            (fadeIn(tween(400, easing = EaseBs4E)) +
                slideInVertically(tween(400, easing = EaseBs4E)) { it / 2 }) togetherWith
                (fadeOut(tween(400, easing = EaseBs4E)) +
                    scaleOut(targetScale = 0.96f, animationSpec = tween(400, easing = EaseBs4E)))
        },
        label = "fullCoverArt",
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) { bitmap ->
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .dissolveBottom()
            )
        }
    }
}

/**
 * 全屏页歌名行（度量与经典页 SongMeta 逐项同：横 28、标题容器 28dp、歌名 18sp/800/行高 24/字距 −0.4/白 0.95；
 * 第二行距标题 2dp、高 22dp：歌手 14sp/500 白 0.45＋爱心 13dp＋专辑同字）——
 * 唯三键不同：队列（21dp）／歌词样式（20dp）／播放暂停（28 播·25 停、300ms），键盒同 MetaKey 40dp。
 * 本行不在屏顶，不吃状态栏 insets；上 12 下 8 与经典页同数。
 */
@Composable
private fun FullSongMeta(
    state: PlayerUiState,
    onQueueClick: () -> Unit,
    onLyricStyleClick: () -> Unit,
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
                        label = "fullHeartAlpha"
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
                MetaKey(onClick = onQueueClick) {
                    QueueGlyphIcon(size = 21.dp, color = Color.White)
                }
                MetaKey(onClick = onLyricStyleClick) {
                    LyricStyleIcon(size = 20.dp, color = Color.White)
                }
                val playSize by animateDpAsState(
                    targetValue = if (state.isPlaying) 28.dp else 25.dp,
                    animationSpec = tween(300),
                    label = "fullPlayPauseSize"
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

/**
 * 模式筹（Std/逐字）：形照 PlaybackScrubber 筹面（全圆胶囊、内边距 12×5、11sp/700/字距 0.6、描边白 0.18）；
 * 选中态深浅源码未抠——照定案图样子：选中面白 0.22/字白 0.95、未选面白 0.10/字白 0.55。
 */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val face = if (selected) 0.22f else 0.10f
    Box(
        Modifier
            .background(Color.White.copy(alpha = face), shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))
                ),
                shape
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = Color.White.copy(alpha = if (selected) 0.95f else 0.55f)
        )
    }
}

/**
 * 细进度线（全屏页底部）：只有轨道本体（瓜子 PlaybackScrubber 经典数值——Canvas 18dp、
 * 轨高 4（拖动 5）、底轨白 0.14（拖动 0.22）、填充横渐变白 0.42→0.78、圆头无拇指），
 * 无顶盒无时间行；拖动跟随、松手 seek（与 PlaybackScrubber 同口径）。
 */
@Composable
private fun ThinProgressLine(
    progress: PlayerProgress,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = progress.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (progress.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shownFraction = if (dragging) dragFraction else liveFraction

    Canvas(
        modifier
            .fillMaxWidth()
            .height(18.dp)
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
                            onSeek((dragFraction * duration).roundToLong())
                        }
                    }
                }
            }
    ) {
        val midY = size.height / 2f
        val trackH = (if (dragging) 5.dp else 4.dp).toPx()
        val radius = CornerRadius(trackH / 2f, trackH / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = if (dragging) 0.22f else 0.14f),
            topLeft = Offset(0f, midY - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = radius
        )
        val fillW = size.width * shownFraction
        if (fillW > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.78f)
                    ),
                    startX = 0f,
                    endX = fillW
                ),
                topLeft = Offset(0f, midY - trackH / 2f),
                size = Size(fillW, trackH),
                cornerRadius = radius
            )
        }
    }
}
