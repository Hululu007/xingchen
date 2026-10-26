package com.fongmi.android.tv.ui.music.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R

/** 瓜子缓动 bs4.a（案卷十五节亲验）：根背景换色 450ms 用它。 */
val Bs4A = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 瓜子缓动 bs4.e：封面切歌转场 400ms 用它。 */
val Bs4E = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private val SungSoft = Color(0xFFEAEAEA)

/**
 * 经典主题骨架面（默认主题，照案卷一/三节落值；细节按下单再填）：
 * 根＝封面主色 450ms 动画底＋同图派生氛围层（64 缩＋24dp 糊＋明度罩）；
 * 锐封面整页垫底、底边 0.78 起溶进背景（与播放条同一张图、状态层一份，
 * 不是两处取图）；内容列＝歌名行（18sp/800＋右三键 40dp：菜单/队列/播停）
 * →歌手行（14sp＋爱心 13dp＋专辑）→页内歌词位→进度栈（经典调用值）。
 * 封面竖滑切歌（触发 36dp，案卷三节）；暂停封面缩 0.9、播放 1.0。
 */
@Composable
fun ClassicPlayer(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTools: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = Color(state.dominantColor),
        animationSpec = tween(450, easing = Bs4A),
        label = "rootBg",
    )
    val coverScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.9f,
        animationSpec = tween(300, easing = Bs4A),
        label = "coverScale",
    )

    Box(modifier = modifier.fillMaxSize().background(bgColor)) {
        // 氛围层：与锐封面同一张位图，64 缩小＋糊，alpha .8，罩 +0.05/−0.04
        val bmp = state.coverBitmap
        if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
            AmbientLayer(bitmap = bmp, modifier = Modifier.fillMaxSize())
        }

        // 锐封面层（整页垫底、向下溶）
        CoverLayer(
            state = state,
            scale = coverScale,
            bgColor = bgColor,
            onNext = onNext,
            onPrev = onPrev,
            modifier = Modifier.fillMaxSize(),
        )

        // 内容列
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            TitleRow(
                state = state,
                onToggle = onToggle,
                onOpenQueue = onOpenQueue,
                onOpenTools = onOpenTools,
                onCollapse = onCollapse,
            )
            ArtistRow(state = state)
            Spacer(modifier = Modifier.weight(1f))
            LyricWindow(state = state)
            Spacer(modifier = Modifier.height(6.dp))
            PlaybackScrubber(state = state, onSeek = onSeek)
            Spacer(modifier = Modifier.height(45.dp))
        }
    }
}

@Composable
private fun AmbientLayer(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val small = remember(bitmap) {
        runCatching { Bitmap.createScaledBitmap(bitmap, 64, 64, true) }.getOrNull()
    }
    Box(modifier = modifier) {
        if (small != null) {
            Image(
                bitmap = small.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp)
                    .alpha(0.8f),
                contentScale = ContentScale.Crop,
            )
        }
        // 渐变罩：顶 +0.05 提亮 → 底 −0.04 压暗（案卷一节明度口径的等价叠加）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.05f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.04f),
                    )
                ),
        )
    }
}

@Composable
private fun CoverLayer(
    state: PlayerUiState,
    scale: Float,
    bgColor: Color,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val triggerPx = with(density) { 36.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier.pointerInput(state.track) {
            detectVerticalDragGestures(
                onDragStart = { dragY = 0f },
                onDragEnd = {
                    if (dragY <= -triggerPx) onNext()
                    else if (dragY >= triggerPx) onPrev()
                    dragY = 0f
                },
                onDragCancel = { dragY = 0f },
                onVerticalDrag = { change, amount ->
                    dragY += amount
                    change.consume()
                },
            )
        },
    ) {
        AnimatedContent(
            targetState = state.coverUrl,
            transitionSpec = {
                (fadeIn(tween(400, easing = Bs4E)) +
                    slideInVertically(tween(400, easing = Bs4E)) { it / 2 }) togetherWith
                    (fadeOut(tween(400, easing = Bs4E)) +
                        slideOutVertically(tween(400, easing = Bs4E)) { it / 2 })
            },
            label = "coverChange",
            modifier = Modifier.fillMaxSize(),
        ) { coverUrl ->
            Box(modifier = Modifier.fillMaxSize()) {
                val bmp = state.coverBitmap
                if (bmp != null && coverUrl != null && state.coverBitmapUrl == coverUrl) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationY = dragY.coerceIn(-240f, 240f)
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
                // 溶边：0.78 起溶进根底色（案卷外壳口径，与旧溶边件同数）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.78f to Color.Transparent,
                                1f to bgColor,
                            )
                        ),
                )
            }
        }
    }
}

@Composable
private fun TitleRow(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTools: () -> Unit,
    onCollapse: () -> Unit,
) {
    val density = LocalDensity.current
    val collapseTriggerPx = with(density) { 48.dp.toPx() }
    var pullDown = remember { mutableFloatStateOf(0f) }
    val playIconSize by animateDpAsState(
        targetValue = if (state.isPlaying) 28.dp else 25.dp,
        animationSpec = tween(300, easing = Bs4A),
        label = "playIconSize",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { pullDown.floatValue = 0f },
                    onDragEnd = {
                        if (pullDown.floatValue >= collapseTriggerPx) onCollapse()
                        pullDown.floatValue = 0f
                    },
                    onDragCancel = { pullDown.floatValue = 0f },
                    onVerticalDrag = { _, amount ->
                        if (amount > 0f) pullDown.floatValue += amount
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.title.ifEmpty { "未在播放" },
            color = Color.White.copy(alpha = 0.95f),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 24.sp,
            letterSpacing = (-0.4).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // 菜单（自绘三条杠，案卷三节 path 口径：21dp、描边 2.5）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable { onOpenTools() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(21.dp)) {
                val stroke = 2.5.dp.toPx()
                val ys = listOf(5f, 12f, 19f)
                val scale = size.width / 24f
                for (y in ys) {
                    drawLine(
                        color = Color.White,
                        start = Offset(3f * scale, y * scale),
                        end = Offset(21f * scale, y * scale),
                        strokeWidth = stroke,
                    )
                }
            }
        }
        // 播放列表 20dp
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable { onOpenQueue() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_music_queue),
                contentDescription = "播放列表",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        // 播放/暂停（尺寸随态 28/25，300ms 过渡）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play
                ),
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(playIconSize),
            )
        }
    }
}

@Composable
private fun ArtistRow(state: PlayerUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.artist.isNotEmpty()) {
            Text(
                text = state.artist,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(
                if (state.favorite) R.drawable.ic_music_heart_fill else R.drawable.ic_music_heart
            ),
            contentDescription = "收藏",
            tint = Color.White.copy(alpha = if (state.favorite) 0.45f else 0.30f),
            modifier = Modifier
                .padding(horizontal = 7.dp)
                .size(13.dp),
        )
        if (state.album.isNotEmpty()) {
            Text(
                text = state.album,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 页内歌词位（骨架：真行数据、真当前行；窗口与逐字动效是引擎单的活，
 * 这里先摆前 1/当前/后 2 共 4 行，色走案卷八节沉浸深色档：
 * 已唱 #FFEAEAEA、当前 白、未唱 白 0.30；纯文本静态白 .85 不滚动）。
 */
@Composable
private fun LyricWindow(state: PlayerUiState) {
    val lineHeightSp = (22f * 4f / 3f).sp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        when (val lyrics = state.lyrics) {
            is LyricUiState.Content -> {
                if (lyrics.lines.isEmpty()) {
                    LyricHint(text = "暂无歌词")
                } else if (!lyrics.timed) {
                    Column {
                        for (line in lyrics.lines.take(4)) {
                            Text(
                                text = line.text,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 22.sp,
                                lineHeight = lineHeightSp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    val cur = state.lyricIndex
                    val from = maxOf(0, cur - 1)
                    val to = minOf(lyrics.lines.size, cur + 3)
                    Column {
                        for (i in from until to) {
                            val color = when {
                                cur < 0 -> Color.White.copy(alpha = 0.30f)
                                i < cur -> SungSoft
                                i == cur -> Color.White
                                else -> Color.White.copy(alpha = 0.30f)
                            }
                            val animated by animateColorAsState(
                                targetValue = color,
                                animationSpec = tween(300, easing = Bs4A),
                                label = "lyricLine",
                            )
                            Text(
                                text = lyrics.lines[i].text,
                                color = animated,
                                fontSize = 22.sp,
                                fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal,
                                lineHeight = lineHeightSp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            LyricUiState.Searching -> LyricHint(text = "正在找歌词")
            LyricUiState.None -> LyricHint(text = "暂无歌词")
            LyricUiState.Idle -> Unit
        }
    }
}

@Composable
private fun LyricHint(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 14.sp,
    )
}
