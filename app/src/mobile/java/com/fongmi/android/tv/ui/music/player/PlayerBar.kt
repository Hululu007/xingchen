package com.fongmi.android.tv.ui.music.player

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 音乐首页播放条（定案形态 2026-10-21，与旧 View 条 home_minibar_music 逐项同形）：
 * 暗磨砂胶囊（钉死深灰 #333333@.85＋白 .25 淡边＋12dp 浮影）、内 padding 6dp；
 * 左 56dp 方框（进度环满框＋45dp 圆形封面居中，播放转圈 12 秒/圈、暂停停角）；
 * 歌名 15sp 粗白、长名滚动、左边距 10dp；下一首 44dp 盒（图标 22dp，不可切 alpha .35）；
 * 右大圆播放键 56dp（同源磨砂，左边距 4dp）。
 * 点条身带条窗口矩形开页（PlayerOpenContract）；横划跟手切歌：左划下一首、右划上一首，
 * 拖过 56dp 松手触发，拖动中对侧露「下一首/上一首」提示，松手弹簧回位。
 * 数据只读 PlayerStateHolder 一份快照，与播放页同树同源。
 */
@Composable
fun PlayerBar(onOpen: (Rect) -> Unit, modifier: Modifier = Modifier) {
    val state by PlayerStateHolder.state.collectAsState()
    val progress by PlayerStateHolder.progress.collectAsState()
    PlayerBarContent(
        state = state,
        progress = progress,
        interactive = true,
        onOpen = onOpen,
        modifier = modifier
    )
}

/**
 * 条本体：interactive=false 时是 PlayerRoot 形变期间压顶淡出的静态副本
 * （同一份状态渲染，保证长开起点与真条像素级一致）。
 */
@Composable
fun PlayerBarContent(
    state: PlayerUiState,
    progress: PlayerProgress,
    interactive: Boolean,
    onOpen: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxWidth()) {
        // 横划提示（条身滑动后从对侧露出）
        if (interactive && dragOffset.value != 0f) {
            val toNext = dragOffset.value < 0f
            Text(
                text = if (toNext) "下一首" else "上一首",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(
                    alpha = (dragOffset.value.absoluteValue / 160f).coerceIn(0f, 0.9f)
                ),
                modifier = Modifier.align(if (toNext) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 18.dp)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { bounds = it.boundsInWindow().toAndroidRect() }
                .graphicsLayer { translationX = dragOffset.value }
                .shadow(12.dp, RoundedCornerShape(999.dp))
                .background(Color(0xFF333333).copy(alpha = 0.85f), RoundedCornerShape(999.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                .then(
                    if (interactive) {
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { bounds?.let(onOpen) }
                            .pointerInput(Unit) {
                                val threshold = 56.dp.toPx()
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        when {
                                            dragOffset.value <= -threshold ->
                                                PlayerStateHolder.next()
                                            dragOffset.value >= threshold ->
                                                PlayerStateHolder.prev()
                                        }
                                        scope.launch {
                                            dragOffset.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 1400f))
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            dragOffset.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 1400f))
                                        }
                                    }
                                ) { change, amount ->
                                    change.consume()
                                    scope.launch { dragOffset.snapTo(dragOffset.value + amount) }
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarCover(state, progress)
            Text(
                text = state.track?.displayTitle()?.takeIf { it.isNotEmpty() } ?: "",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
                    .basicMarquee()
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = interactive && state.canNext
                    ) { PlayerStateHolder.next() },
                contentAlignment = Alignment.Center
            ) {
                NextIcon(
                    size = 22.dp,
                    color = Color.White.copy(alpha = if (state.canNext) 1f else 0.35f)
                )
            }
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(56.dp)
                    .background(Color(0xFF333333).copy(alpha = 0.85f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = interactive
                    ) { PlayerStateHolder.toggle() },
                contentAlignment = Alignment.Center
            ) {
                if (state.isPlaying) {
                    PauseIcon(size = 26.dp, color = Color.White)
                } else {
                    PlayIcon(size = 26.dp, color = Color.White)
                }
            }
        }
    }
}

/** 条左封面组：56dp 环框——外圈进度环（白环）＋45dp 圆封面（播放转圈、暂停停角；无图素字占位）。 */
@Composable
private fun BarCover(state: PlayerUiState, progress: PlayerProgress) {
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            var last = -1L
            while (true) {
                withFrameNanos { now ->
                    if (last > 0L) {
                        val dtMs = (now - last) / 1_000_000f
                        rotation = (rotation + dtMs * 360f / 12_000f) % 360f
                    }
                    last = now
                }
            }
        }
    }
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(56.dp)) {
            val sw = 2.5.dp.toPx()
            val inset = sw / 2f
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = sw)
            )
            if (fraction > 0f) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = fraction * 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
        }
        val bitmap = state.coverBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(45.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .clip(CircleShape)
            )
        } else {
            Box(
                Modifier
                    .size(45.dp)
                    .background(Color(0xFF444444), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.track?.displayTitle()?.take(1) ?: "",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

private fun androidx.compose.ui.geometry.Rect.toAndroidRect(): Rect =
    Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
