package com.fongmi.android.tv.ui.music.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.MusicStore

/**
 * 进度栈一件（照瓜子 PlaybackScrubber 经典调用实参，案卷第二节）：
 * 顶盒 28dp（左右时间 11sp 等宽 40dp，静止 alpha 0、拖动 0.72；中双筹：
 * 音质筹＋歌词筹，全圆胶囊、内边距 12×5、11sp/700/白 .72、白 .14 面）→
 * 轨道 Canvas 高 18dp（轨 4dp、拖动 +1；底轨白 .14→拖动 .22；填充渐变
 * 白 .42→.78、圆头、无 thumb）→ 底行已播/−剩余 12sp 白 .65。
 * 拖动中显示拖动位时间，松手才 seek（不打断在播）。
 * 歌词筹文案只用中文四档（逐字/逐行/纯文本/无词，他 2026-10-27 令），
 * 找词中显「找词」。
 */
@Composable
fun PlaybackScrubber(
    state: PlayerUiState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onLyricChipClick: (() -> Unit)? = null,
    onInteract: (() -> Unit)? = null,
) {
    val duration = state.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fraction = if (dragging) dragFraction else liveFraction
    val shownMs = if (dragging) (dragFraction * duration).toLong() else state.positionMs

    Column(modifier = modifier.fillMaxWidth()) {
        // 顶盒：时间只在拖动时浮出（瓜子原样），筹常驻
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(shownMs),
                color = Color.White.copy(alpha = if (dragging) 0.72f else 0f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                ScrubberChip(text = MusicStore.qualityName(state.quality))
                Spacer(modifier = Modifier.width(6.dp))
                // 歌词筹＝歌词版本面板入口（瓜子口径，他 2026-10-27 点名：不许只做开关翻译）
                ScrubberChip(text = lyricChipText(state.lyrics), onClick = onLyricChipClick)
                Spacer(modifier = Modifier.weight(1f))
            }
            Text(
                text = formatTime(duration),
                color = Color.White.copy(alpha = if (dragging) 0.72f else 0f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
        }

        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            onInteract?.invoke()
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragFraction * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            dragFraction =
                                (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val stroke = (if (dragging) 5.dp else 4.dp).toPx()
                val trackAlpha = if (dragging) 0.22f else 0.14f
                drawLine(
                    color = Color.White.copy(alpha = trackAlpha),
                    start = Offset(0f, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                if (fraction > 0f) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.42f),
                                Color.White.copy(alpha = 0.78f),
                            ),
                        ),
                        start = Offset(0f, cy),
                        end = Offset(size.width * fraction, cy),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        // 底行：已播 / −剩余
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(state.positionMs),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "−" + formatTime((duration - state.positionMs).coerceAtLeast(0L)),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ScrubberChip(text: String, onClick: (() -> Unit)? = null) {
    var box = Modifier
        .clip(RoundedCornerShape(percent = 50))
        .background(Color.White.copy(alpha = 0.14f))
        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(percent = 50))
    if (onClick != null) {
        box = box.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) { onClick() }
    }
    Box(
        modifier = box.padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
    }
}

private fun lyricChipText(lyrics: LyricUiState): String = when (lyrics) {
    is LyricUiState.Content -> when {
        lyrics.wordLevel -> "逐字"
        lyrics.timed -> "逐行"
        else -> "纯文本"
    }
    LyricUiState.Searching -> "找词"
    else -> "无词"
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
