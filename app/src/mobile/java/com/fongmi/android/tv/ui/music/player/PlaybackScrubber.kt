package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong

/**
 * 底部细进度（瓜子 PlaybackScrubber 经典默认调用，数值认 gz-player-values.md §二）：
 * 顶盒高 28dp（下 padding 4）：左右时间 11sp 等宽——静止不露、拖动 0.72 淡入；
 * 中间双筹（音质筹在前、歌词筹在后，间距 6、全圆胶囊 padding 12×5、11sp/700/白 0.72/字距 0.6，
 * 面白 0.14＋纵渐变 0.10→0.04＋描边）；轨道 Canvas 高 18：轨高 4（拖动 5）、底轨白 0.14（拖动 0.22）、
 * 填充水平渐变白 0.42→0.78、圆头无拇指；底行左已播右「−剩余」、12sp 白 0.65、padding(2,2,2,0)。
 * 拖动中显示跟随手指，松手才 seek（防抖口径：与服务 seek 往返解耦）。
 */
@Composable
fun PlaybackScrubber(
    progress: PlayerProgress,
    audioLabel: String?,
    lyricLabel: String?,
    onSeek: (Long) -> Unit,
    onQualityClick: () -> Unit,
    onLyricChipClick: () -> Unit,
    modifier: Modifier = Modifier,
    trackHeightDp: Float = 4f,
    fillFromAlpha: Float = 0.42f,
    fillToAlpha: Float = 0.78f,
    showTopBox: Boolean = true,
    showBottomRow: Boolean = true
) {
    val duration = progress.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (progress.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shownFraction = if (dragging) dragFraction else liveFraction
    val shownPosition = if (dragging) (dragFraction * duration).roundToLong() else progress.positionMs
    val timeAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.72f else 0f,
        animationSpec = tween(150),
        label = "scrubTimeAlpha"
    )

    Column(modifier.fillMaxWidth()) {
        // 顶盒：左右时间（仅拖动淡入）＋居中双筹（Apple 形态关顶盒：其主调用无顶盒，§五）
        if (showTopBox) Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(40.dp)) {
                if (timeAlpha > 0.01f) {
                    Text(
                        text = formatClock(shownPosition),
                        style = Tabular11,
                        color = Color.White.copy(alpha = timeAlpha),
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (audioLabel != null) {
                    ScrubberChip(audioLabel, onQualityClick)
                }
                if (lyricLabel != null) {
                    ScrubberChip(lyricLabel, onLyricChipClick)
                }
            }
            Box(Modifier.width(40.dp)) {
                if (timeAlpha > 0.01f) {
                    Text(
                        text = formatClock(duration),
                        style = Tabular11,
                        color = Color.White.copy(alpha = timeAlpha),
                        textAlign = TextAlign.End,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
        // 轨道：单 Canvas 横条，按压/拖动跟随
        Canvas(
            Modifier
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
            // 轨高＝调用参（经典 4、Apple 6），拖动 +1（经典定谳；Apple 拖动态未单独抠、沿同一规则）
            val trackH = (if (dragging) trackHeightDp + 1f else trackHeightDp).dp.toPx()
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
                            Color.White.copy(alpha = fillFromAlpha),
                            Color.White.copy(alpha = fillToAlpha)
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
        // 底行：左已播、右 −剩余（Apple 形态关底行：其主调用不显时间，§五）
        if (showBottomRow) Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 0.dp)
        ) {
            Text(
                text = formatClock(shownPosition),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.65f),
                style = TabularStyle
            )
            Box(Modifier.weight(1f))
            Text(
                text = "−" + formatClock((duration - shownPosition).coerceAtLeast(0)),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.65f),
                style = TabularStyle
            )
        }
    }
}

@Composable
private fun ScrubberChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .background(Color.White.copy(alpha = 0.14f), shape)
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
            color = Color.White.copy(alpha = 0.72f)
        )
    }
}

private val TabularStyle = TextStyle(fontFeatureSettings = "tnum")
private val Tabular11 = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum", letterSpacing = 0.2.sp)

/** 毫秒 → m:ss（满一小时 h:mm:ss），与瓜子 ScrubberText 的时钟格式同形。 */
fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
