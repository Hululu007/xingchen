package com.fongmi.android.tv.ui.music.player

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val BAR_GLASS = Color(0xFF333333).copy(alpha = 0.85f)
private val BAR_BORDER = Color.White.copy(alpha = 0.25f)

/**
 * 播放条（他 2026-10-21 定案形态＋2026-10-27 播放条终定）：暗磨砂胶囊
 * （#333@.85＋白 .25 边＋阴影 12＋圆角 999、内边距 6）；左 56 环框（进度环白 .18/弧白圆头）
 * ＋45 圆封面（12 秒/圈、读统一时钟绘制期派生，不自起帧循环）；歌名 15/800 跑马灯；
 * 下一首 44 盒；右 56 大圆播放键。横划切歌：拖动量走普通状态、图层消费，
 * 阈值 56dp、对侧出提示字，松手一次 spring(0.9, 1400) 回中（不许 per-event 起协程）。
 * 条身点击把自身窗口矩形交给 onOpen，根层据此与播放页同树形变开合。
 */
@Composable
fun PlayerBar(
    onOpen: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by PlayerStateHolder.state.collectAsState()
    val progress by PlayerStateHolder.progress.collectAsState()
    rememberPlayerClock(progress, state.isPlaying)
    PlayerBarContent(
        state = state,
        progress = progress,
        interactive = true,
        onOpen = onOpen,
        modifier = modifier
    )
}

@Composable
fun PlayerBarContent(
    state: PlayerUiState,
    progress: PlayerProgress,
    interactive: Boolean,
    onOpen: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val settle = remember { Animatable(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var lastSwipeAt by remember { mutableLongStateOf(0L) }
    val track = state.track
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                bounds = Rect(
                    r.left.roundToInt(),
                    r.top.roundToInt(),
                    r.right.roundToInt(),
                    r.bottom.roundToInt()
                )
            }
            .graphicsLayer { translationX = dragOffset + settle.value }
    ) {
        // 对侧切歌提示字（拖动时从边缘淡入）
        if (interactive && dragOffset != 0f) {
            val hintAlpha = (abs(dragOffset) / 160f).coerceIn(0f, 0.9f)
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                contentAlignment = if (dragOffset < 0f) {
                    Alignment.CenterEnd
                } else {
                    Alignment.CenterStart
                }
            ) {
                Text(
                    text = if (dragOffset < 0f) "下一首" else "上一首",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W500,
                    color = Color.White.copy(alpha = hintAlpha)
                )
            }
        }
        Row(
            Modifier
                .shadow(12.dp, RoundedCornerShape(999.dp))
                .clip(RoundedCornerShape(999.dp))
                .background(BAR_GLASS)
                .border(1.dp, BAR_BORDER, RoundedCornerShape(999.dp))
                .then(
                    if (interactive) {
                        Modifier.pointerInput(state.queueIndex, state.canNext) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalDx = 0f
                                var totalDy = 0f
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val delta = change.position - change.previousPosition
                                    totalDx += delta.x
                                    totalDy += delta.y
                                    if (abs(totalDx) > abs(totalDy)) {
                                        dragOffset = totalDx
                                        if (abs(totalDx) > 8.dp.toPx()) moved = true
                                    }
                                }
                                val threshold = 56.dp.toPx()
                                if (moved && abs(totalDx) >= threshold) {
                                    lastSwipeAt = System.currentTimeMillis()
                                    if (totalDx < 0f) {
                                        if (state.canNext) PlayerStateHolder.next()
                                    } else {
                                        if (state.queueIndex > 0) PlayerStateHolder.prev()
                                    }
                                }
                                val from = dragOffset
                                dragOffset = 0f
                                if (from != 0f) {
                                    scope.launch {
                                        settle.snapTo(from)
                                        settle.animateTo(
                                            0f,
                                            spring(dampingRatio = 0.9f, stiffness = 1400f)
                                        )
                                    }
                                }
                                if (!moved && abs(totalDx) < 8.dp.toPx()) {
                                    // 点按开页（刚横划过的不响）
                                    if (System.currentTimeMillis() - lastSwipeAt > 250L) {
                                        bounds?.let(onOpen)
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左环框 56：进度环＋圆封面 45（转圈读统一时钟）
            Box(
                Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.5.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Color.White.copy(alpha = 0.18f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    if (fraction > 0f) {
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * fraction,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Box(
                    Modifier
                        .size(45.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF444444))
                        .graphicsLayer {
                            rotationZ =
                                (PlayerClock.smoothPositionMs.longValue % 12000L) *
                                    360f / 12000f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = state.coverBitmap
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = track?.displayTitle()?.take(1) ?: "",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.W800,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
            // 歌名（左距 10、weight 1、跑马灯）
            Text(
                text = track?.displayTitle() ?: "",
                fontSize = 15.sp,
                fontWeight = FontWeight.W800,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
                    .basicMarquee()
            )
            // 下一首（44 盒、图标 22）
            Box(
                Modifier
                    .size(44.dp)
                    .pressScale(0.88f)
                    .clickableNoRipple(enabled = !interactive || state.canNext) {
                        if (interactive) PlayerStateHolder.next()
                    },
                contentAlignment = Alignment.Center
            ) {
                NextIcon(
                    22.dp,
                    Color.White.copy(alpha = if (state.canNext) 1f else 0.35f)
                )
            }
            // 右大圆播放键（56、同磨砂、左距 4、图标 26）
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(BAR_GLASS)
                    .border(1.dp, BAR_BORDER, CircleShape)
                    .pressScale(0.9f)
                    .clickableNoRipple(enabled = interactive) { PlayerStateHolder.toggle() },
                contentAlignment = Alignment.Center
            ) {
                PathIcon(
                    26.dp,
                    Color.White,
                    if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
                )
            }
        }
    }
}
