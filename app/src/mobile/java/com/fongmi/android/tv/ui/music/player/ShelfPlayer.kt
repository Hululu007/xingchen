package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.MusicQueue
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val SHELF_BG = Color(0xFFF1F0ED)
private val SHELF_INK = Color(0xFF252522)
private val SHELF_MUTED = Color(0xFF86857E)

/**
 * 黑胶唱片架主题（gz-player-values.md §七＋smali 定谳）：根实色 #F1F0ED；
 * 头行 64；架列 3 槽 f＝clamp（宽×.66, 180, 300)、槽位移 spring(0.9, 300)；
 * 当前槽黑胶（直径 .97f3、内嵌封面 .22f3、中心垫 #D4D0C5、中心点 #080808）旋转 30°/s 读统一时钟；
 * 点当前槽＝收藏切换＋reveal 300/bs4.c 隐→600/bs4.a 现；白面上圆角 24＋顶部渐变；
 * 歌词列 ShelfLyricTextStyle（封面字号档 22、行高 ×4/3、行距 20、浅色板）；进度区 72；底行 76。
 */
@Composable
fun ShelfPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onClose: () -> Unit,
    onToolsClick: () -> Unit,
    onQueueClick: () -> Unit
) {
    rememberPlayerClock(progress, state.isPlaying)
    val tone = state.toneColor?.let { Color(it) } ?: SHELF_INK
    val h0 = androidx.compose.ui.graphics.lerp(tone, SHELF_INK, 0.65f)
    val queueIndex = state.queueIndex

    Box(
        Modifier
            .fillMaxSize()
            .background(SHELF_BG)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 头行（高 64、左右 16）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(24.dp, SHELF_INK.copy(alpha = 0.9f), PATH_CHEVRON_DOWN)
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.track?.displayTitle() ?: "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W500,
                        lineHeight = 21.sp,
                        color = SHELF_INK,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = state.track?.displayArtist() ?: "",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = SHELF_MUTED,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple(onClick = onToolsClick),
                    contentAlignment = Alignment.Center
                ) {
                    MoreDotsIcon(24.dp, SHELF_INK.copy(alpha = 0.9f))
                }
            }
            // 架列 3 槽
            ShelfDeck(state = state, queueIndex = queueIndex)
            // 白面（weight 1、上圆角 24、顶部渐变压边）
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color.White)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.13f),
                                0.5f to Color.Black.copy(alpha = 0.055f),
                                1f to Color.Transparent
                            )
                        )
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    LyricStage(
                        lines = state.lyricLines,
                        untimed = state.lyricUntimed,
                        wordByWord = MusicPlayerSettings.isFullLyricWordByWord(),
                        fontSizeSp = MusicPlayerSettings.getCoverLyricFontSize(),
                        lineHeightFactor = 4f / 3f,
                        lineGapDp = 20f,
                        fullAnchor = false,
                        anchorFraction = MusicPlayerSettings.lyricAnchorFraction(),
                        windowBefore = 2,
                        windowAfter = 0,
                        blurGroupB = false,
                        blurCoefficient = MusicPlayerSettings.blurCoefficient(),
                        palette = LyricPalette(
                            sung = h0,
                            activeUnsung = h0.copy(alpha = 0.48f),
                            inactive = SHELF_MUTED.copy(alpha = 0.72f),
                            translation = SHELF_MUTED
                        ),
                        showTranslation = MusicPlayerSettings.isShowTranslation(),
                        alignMode = MusicPlayerSettings.getLyricAlign(),
                        weightLevel = MusicPlayerSettings.getLyricWeight(),
                        lyricOffsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong(),
                        maxVisualLines = 0,
                        topFadeDp = 0f,
                        letterSpacingEm = 0f,
                        onLineSeek = { PlayerStateHolder.seekTo(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                    )
                    // 进度区（左右 28＝页 16＋内 12、高 72）
                    ShelfProgress(
                        progress = progress,
                        fill = h0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .padding(horizontal = 12.dp)
                    )
                    // 底行（高 76、左右 20＝页 16＋内 4）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(76.dp)
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .pressScale(0.9f)
                                .clickableNoRipple { PlayerStateHolder.cycleMode() },
                            contentAlignment = Alignment.Center
                        ) {
                            PathIcon(
                                24.dp,
                                SHELF_INK,
                                when (state.playMode) {
                                    MusicQueue.MODE_REPEAT_ONE -> PATH_REPEAT_ONE
                                    MusicQueue.MODE_REPEAT_ALL -> PATH_REPEAT
                                    MusicQueue.MODE_SHUFFLE -> PATH_SHUFFLE
                                    else -> PATH_REORDER
                                }
                            )
                        }
                        Box(
                            Modifier
                                .size(48.dp)
                                .pressScale(0.9f)
                                .clickableNoRipple { PlayerStateHolder.prev() },
                            contentAlignment = Alignment.Center
                        ) {
                            PathIcon(32.dp, SHELF_INK, PATH_PREV)
                        }
                        Box(
                            Modifier
                                .size(68.dp)
                                .pressScale(0.9f)
                                .clickableNoRipple { PlayerStateHolder.toggle() },
                            contentAlignment = Alignment.Center
                        ) {
                            PathIcon(
                                52.dp,
                                SHELF_INK,
                                if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
                            )
                        }
                        Box(
                            Modifier
                                .size(48.dp)
                                .pressScale(0.9f)
                                .clickableNoRipple { PlayerStateHolder.next() },
                            contentAlignment = Alignment.Center
                        ) {
                            PathIcon(32.dp, SHELF_INK, PATH_NEXT)
                        }
                        Box(
                            Modifier
                                .size(48.dp)
                                .pressScale(0.9f)
                                .clickableNoRipple(onClick = onQueueClick),
                            contentAlignment = Alignment.Center
                        ) {
                            PathIcon(24.dp, SHELF_INK, GZ_QUEUE_PATH)
                        }
                    }
                }
            }
        }
    }
}

/** 架列：3 槽、当前槽黑胶旋转；槽位移 spring(0.9, 300) 跟 queueIndex。 */
@Composable
private fun ShelfDeck(state: PlayerUiState, queueIndex: Int) {
    val scope = rememberCoroutineScope()
    val deckPos = remember { Animatable(queueIndex.toFloat()) }
    LaunchedEffect(queueIndex) {
        deckPos.animateTo(
            queueIndex.toFloat(),
            spring(dampingRatio = 0.9f, stiffness = 300f)
        )
    }
    val reveal = remember { Animatable(1f) }
    var revealBusy by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value
        val f = (widthDp * 0.66f).coerceIn(180f, 300f)
        val f2 = minOf(widthDp * 0.5f, f - 36f)
        val f3 = f2 * 0.95f
        val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            (f3 * 0.58f).dp.toPx()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height((f - 23f).dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            val shift = deckPos.value - queueIndex
            // 侧槽先画（在下层）、当前槽后画
            for (slot in listOf(-1, 1, 0)) {
                val trackIndex = queueIndex + slot
                if (trackIndex !in state.queue.indices && slot != 0) continue
                val slotCenterOffset = (slot + shift) * stepPx
                val slotW = if (slot == 0) f3 * 1.48f else f3
                Box(
                    Modifier
                        .offset { IntOffset(slotCenterOffset.roundToInt(), 0) }
                        .size(slotW.dp, f3.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (slot == 0) {
                            // 当前槽：黑胶（旋转读统一时钟 smoothPosition 派生 30°/s）＋reveal 收藏动效
                            Box(
                                Modifier
                                    .size((f3 * 0.97f).dp)
                                    .graphicsLayer {
                                        rotationZ =
                                            (PlayerClock.smoothPositionMs.longValue % 12000L) *
                                                360f / 12000f
                                        alpha = reveal.value
                                    }
                                    .pressScale(0.97f)
                                    .clickableNoRipple {
                                        if (!revealBusy) {
                                            revealBusy = true
                                            scope.launch {
                                                try {
                                                    reveal.animateTo(
                                                        0f,
                                                        tween(300, easing = EaseBs4C)
                                                    )
                                                    PlayerStateHolder.toggleFavorite()
                                                    reveal.animateTo(
                                                        1f,
                                                        tween(600, easing = EaseBs4A)
                                                    )
                                                } finally {
                                                    revealBusy = false
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                ShelfVinyl(state = state, diameterDp = (f3 * 0.97f).dp)
                            }
                        } else {
                            val bmp = if (slot < 0) {
                                state.prevCoverBitmap
                            } else {
                                state.nextCoverBitmap
                            }
                            Box(
                                Modifier
                                    .size(f3.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFE4E1D8))
                                    .pressScale(0.97f)
                                    .clickableNoRipple {
                                        if (trackIndex in state.queue.indices) {
                                            PlayerStateHolder.playAt(trackIndex)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        // 架板条（宽 1.1f3、高 6）
                        Box(
                            Modifier
                                .size((f3 * 1.1f).dp, 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(SHELF_INK.copy(alpha = 0.16f))
                        )
                    }
                }
            }
        }
    }
}

/** 黑胶面：黑盘＋内嵌封面（.22 倍）＋中心垫（.065、#D4D0C5）＋中心点（.025、#080808）。 */
@Composable
private fun ShelfVinyl(state: PlayerUiState, diameterDp: androidx.compose.ui.unit.Dp) {
    val cover = state.coverBitmap
    Canvas(Modifier.size(diameterDp)) {
        val d = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFF17171A), radius = d / 2f, center = c)
        drawCircle(
            Color.White.copy(alpha = 0.06f),
            radius = d * 0.36f,
            center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = d * 0.10f)
        )
        if (cover != null) {
            val inner = d * 0.22f
            with(drawContext.canvas) {
                save()
                clipPath(
                    androidx.compose.ui.graphics.Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(
                                c.x - inner / 2f, c.y - inner / 2f,
                                c.x + inner / 2f, c.y + inner / 2f
                            )
                        )
                    }
                )
                drawImage(
                    image = cover.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (c.x - inner / 2f).roundToInt(),
                        (c.y - inner / 2f).roundToInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        inner.roundToInt(),
                        inner.roundToInt()
                    )
                )
                restore()
            }
        }
        drawCircle(Color(0xFFD4D0C5), radius = d * 0.065f / 2f, center = c)
        drawCircle(Color(0xFF080808), radius = d * 0.025f / 2f, center = c)
    }
}

/** 进度区：滑杆行 48（Canvas 轨高 4/线宽 2、底 Ink.12、填充 h0）＋时间行 24（10sp、Muted）。 */
@Composable
private fun ShelfProgress(
    progress: PlayerProgress,
    fill: Color,
    modifier: Modifier = Modifier
) {
    val duration = progress.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (progress.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shown = if (dragging) dragFraction else liveFraction
    val shownPos = if (dragging) {
        (dragFraction * duration).roundToLong()
    } else {
        progress.positionMs
    }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        dragFraction = if (size.width > 0) {
                            (down.position.x / size.width).coerceIn(0f, 1f)
                        } else 0f
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
                                PlayerStateHolder.seekTo((dragFraction * duration).roundToLong())
                            }
                        }
                    }
                }
        ) {
            val midY = size.height / 2f
            val lineH = 2.dp.toPx()
            drawRoundRect(
                color = SHELF_INK.copy(alpha = 0.12f),
                topLeft = Offset(0f, midY - lineH / 2f),
                size = Size(size.width, lineH),
                cornerRadius = CornerRadius(lineH / 2f, lineH / 2f)
            )
            val fillW = size.width * shown
            if (fillW > 0f) {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(0f, midY - lineH / 2f),
                    size = Size(fillW, lineH),
                    cornerRadius = CornerRadius(lineH / 2f, lineH / 2f)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatClock(shownPos),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = SHELF_MUTED
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatClock(duration),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = SHELF_MUTED
            )
        }
    }
}
