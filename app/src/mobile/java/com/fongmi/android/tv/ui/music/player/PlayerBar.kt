package com.fongmi.android.tv.ui.music.player

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.music.MusicFrostDrawable
import kotlin.math.abs

/**
 * 播放条本体（新树唯一一份）：音乐首页贴底一份、播放页形变起点一份，
 * 同一个 Composable、同一份 PlayerUiState——所以开合两端长得一模一样、
 * 数值一模一样，不存在「两个条对齐」的问题。
 *
 * 形态照现行定案（2026-10-21 定案＋2026-10-27 暗磨砂试点）：
 * 圆角胶囊暗磨砂（MusicFrostDrawable 真身作背板，材料与首页同源）；
 * 左 56dp 框＝进度环满框＋45dp 圆封面居中（播放转圈 12 秒/圈、暂停停住、
 * 与旧 View 条同速）；歌名 15sp 粗白、长了自己滚；下一首 44dp；
 * 右 56dp 大圆播放键（同源磨砂圆）。
 * 条上横划切歌：跟手偏移、露出侧出「下一首／上一首」提示，过 72dp 松手切。
 * 点条身（非钮区）＝开页，矩形经 onOpen 交开页契约。
 */
@Composable
fun PlayerBar(
    state: PlayerUiState,
    onOpen: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxDragPx = with(density) { 120.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var barRect by remember { mutableStateOf<Rect?>(null) }

    // 封面转圈：播放转、暂停停在原角度（旧条同口径，12s/圈线性）
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            var last = -1L
            while (true) {
                withFrameMillis { t ->
                    if (last >= 0L) {
                        angle = (angle + (t - last).toFloat() * 360f / 12_000f) % 360f
                    }
                    last = t
                }
            }
        }
    }

    val fraction = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val hintAlpha = (abs(dragX) / thresholdPx).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(percent = 50))
            .onGloballyPositioned { c ->
                val pos = c.positionInWindow()
                barRect = Rect(
                    pos.x.toInt(),
                    pos.y.toInt(),
                    pos.x.toInt() + c.size.width,
                    pos.y.toInt() + c.size.height,
                )
            }
            .clickable { barRect?.let(onOpen) }
            .pointerInput(state.canNext, state.canPrev) {
                detectHorizontalDragGestures(
                    onDragStart = { dragX = 0f },
                    onDragEnd = {
                        if (dragX <= -thresholdPx && state.canNext) PlayerStateHolder.next()
                        else if (dragX >= thresholdPx && state.canPrev) PlayerStateHolder.prev()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, amount ->
                        val next = (dragX + amount).coerceIn(-maxDragPx, maxDragPx)
                        // 无下一首/上一首的方向不许拖出假提示
                        if ((next < 0f && !state.canNext) || (next > 0f && !state.canPrev)) {
                            dragX = 0f
                        } else {
                            dragX = next
                            change.consume()
                        }
                    },
                )
            },
    ) {
        // 背板：暗磨砂真身（与首页其余磨砂件同材料同色，钉死深灰不随背景染）
        AndroidView(
            factory = { ctx -> View(ctx).apply { background = MusicFrostDrawable(ctx, 999f) } },
            modifier = Modifier.fillMaxSize(),
        )

        // 切歌提示：拖动时从露出侧浮出（照 Kafuno 录屏的做法）
        if (dragX < 0f && state.canNext) {
            Text(
                text = "下一首",
                color = Color.White.copy(alpha = 0.9f * hintAlpha),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
            )
        } else if (dragX > 0f && state.canPrev) {
            Text(
                text = "上一首",
                color = Color.White.copy(alpha = 0.9f * hintAlpha),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .graphicsLayer { translationX = dragX * 0.55f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左：进度环＋圆封面
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 2.5.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.16f),
                        radius = (size.minDimension - stroke) / 2f,
                        style = Stroke(width = stroke),
                    )
                    if (fraction > 0f) {
                        drawArc(
                            color = Color.White.copy(alpha = 0.92f),
                            startAngle = -90f,
                            sweepAngle = 360f * fraction,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(
                                width = stroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            ),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(45.dp)
                        .clip(CircleShape)
                        .graphicsLayer { rotationZ = angle },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = state.coverBitmap
                    if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else if (state.coverUrl == null) {
                        // 真无封面地址才上素面（有址加载中/失败保持透明，占位令）
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF3A3A3C)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = state.title.trim().take(1).ifEmpty { "♪" },
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // 歌名：长了滚动（定案）
            Text(
                text = state.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
                    .basicMarquee(),
            )

            // 下一首（不可用 0.35 置灰，与旧条同口径）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(enabled = state.canNext) { PlayerStateHolder.next() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_next),
                    contentDescription = "下一首",
                    tint = Color.White.copy(alpha = if (state.canNext) 1f else 0.35f),
                    modifier = Modifier.size(22.dp),
                )
            }

            // 右：大圆播放键（与条底同源磨砂：钉死深灰 .72＋白 .25 一线边）
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333).copy(alpha = 0.72f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable { PlayerStateHolder.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play
                    ),
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
