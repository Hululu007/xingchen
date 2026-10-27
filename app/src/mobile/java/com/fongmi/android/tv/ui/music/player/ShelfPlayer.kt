package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.MusicQueue
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 黑胶唱片架主题 record_shelf（瓜子 RecordShelfPlayerKt.java；数值认 gz-player-values.md §七与深夜补抠）：
 * 根实色 #FFF1F0ED＋status/nav insets、immersiveDark=FALSE；头行 64 → 三槽唱片架台
 * → 白面（圆角 下 24；内页 歌词/歌曲信息两态 ＋ ShelfSeekBar ＋ ShelfControls）。
 * 架 palette：h0＝lerp(封面 tone, Ink, .65)（hp1.h0 亲验；无封面回落 Ink）。
 */
private val SHELF_SURFACE = Color(0xFFF1F0ED)
private val SHELF_INK = Color(0xFF252522)
private val SHELF_MUTED = Color(0xFF86857E)
private val SHELF_CENTER_PAD = Color(0xFFD4D0C5)
private val SHELF_CENTER_DOT = Color(0xFF080808)

@Composable
fun ShelfPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onClose: () -> Unit,
    onToolsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 歌词逐字开关读现行存档键（与经典/全屏页同一键，本页无切换件）
    val wordMode = com.fongmi.android.tv.ui.music.MusicPlayerSettings.isFullLyricWordByWord()
    val h0 = if (state.toneColor != null) {
        lerp(Color(state.toneColor!!), SHELF_INK, 0.65f)
    } else {
        SHELF_INK
    }
    val lyricPalette = LyricPaletteColors(
        sung = h0,
        activeUnsung = h0.copy(alpha = 0.48f),
        inactive = SHELF_MUTED.copy(alpha = 0.72f),
        translation = SHELF_MUTED
    )
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(state.track?.id) { showInfo = false }

    // 唱片旋转 30°/s（dt≤0.05；仅播放时——瓜子另有「无前景面板」条件，面板在根层、本处未接，注名）
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        var lastNanos = -1L
        while (true) {
            withFrameNanos { frame ->
                if (lastNanos >= 0L) {
                    val dt = (frame - lastNanos) / 1_000_000_000f
                    spin = (spin + min(dt, 0.05f) * 30f) % 360f
                }
                lastNanos = frame
            }
        }
    }
    // 收藏脉冲：reveal 1→0（tween 300/bs4.c）→ 切收藏 → 0→1（tween 600/bs4.a）（点唱片定谳行为）
    val revealAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier
            .fillMaxSize()
            .background(SHELF_SURFACE)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 头行（高 64、横 16）：左收起、右三个点（ShelfAction：盒 48、图标 24、按压 0.9、tint Ink 0.9）
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).pressScale(0.9f).clickableNoRipple(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                PathIcon(24.dp, SHELF_INK.copy(alpha = 0.9f), PATH_CHEVRON_DOWN)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = state.track?.displayTitle()?.takeIf { it.isNotEmpty() } ?: "暂无播放歌曲",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.W500,
                    color = SHELF_INK,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.track?.displayArtist() ?: "",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = SHELF_MUTED,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                Modifier.size(48.dp).pressScale(0.9f).clickableNoRipple(onClick = onToolsClick),
                contentAlignment = Alignment.Center
            ) {
                MoreDotsIcon(24.dp, SHELF_INK.copy(alpha = 0.9f))
            }
        }

        // 唱片架台（三槽 −1/0/1；点当前套面切信息页、点唱片切收藏、点边槽跳播——触发点装配注名见交卷）
        ShelfStage(
            state = state,
            spin = spin,
            reveal = revealAnim.value,
            onSleeveClick = { showInfo = !showInfo },
            onVinylClick = {
                scope.launch {
                    revealAnim.animateTo(0f, tween(300, easing = EaseBs4C))
                    PlayerStateHolder.toggleFavorite()
                    revealAnim.animateTo(1f, tween(600, easing = EaseBs4A))
                }
            },
            onPrevSlot = { PlayerStateHolder.prev() },
            onNextSlot = { PlayerStateHolder.next() },
            modifier = Modifier.fillMaxWidth()
        )

        // 白面（整块白、圆角 上 0/下 24、无 elevation；顶部渐变 黑 0.13→0.055→透明）
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
        ) {
            Column(Modifier.fillMaxSize()) {
                // 内页（AnimatedContent label "shelfInnerPage"）：容器横 16
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    AnimatedContent(
                        targetState = showInfo,
                        transitionSpec = { fadeIn(tween(200, easing = EaseBs4A)) togetherWith fadeOut(tween(150, easing = EaseBs4A)) },
                        label = "shelfInnerPage",
                        modifier = Modifier.fillMaxSize()
                    ) { info ->
                        if (info) {
                            SongInfoFace(
                                state = state,
                                colors = SongInfoColors(
                                    high = SHELF_INK,
                                    medium = SHELF_MUTED,
                                    low = SHELF_MUTED.copy(alpha = 0.72f),
                                    inset = SHELF_SURFACE,
                                    accent = h0
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 12.dp)
                            )
                        } else if (state.lyricLines.isEmpty()) {
                            // 空态文案分档需 LyricsState 信号（中枢未供）：先用「暂无歌词」一档，注名
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "暂无歌词", fontSize = 16.sp, color = SHELF_MUTED)
                            }
                        } else {
                            // 歌词面（列 横 24＝面 16＋内 8、纵 12；封面窗装配＋架 palette）
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
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                palette = lyricPalette
                            )
                        }
                    }
                }
                ShelfSeekBar(
                    progress = progress,
                    fillColor = h0,
                    onSeek = { PlayerStateHolder.seekTo(it) }
                )
                ShelfControls(state = state, onQueueClick = onQueueClick)
                Spacer(Modifier.height(8.dp))
            }
            // 面顶部渐变带（黑 0.13→0.055→透明）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.13f),
                                Color.Black.copy(alpha = 0.055f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

// ================= 唱片架台（RecordShelfStage:978 定谳几何） =================

/**
 * f＝clamp(约束宽×0.66, 180, 300)；容器高 f−23；架项基准 f2＝min(约束宽×0.5, f−36)；f3＝f2×0.95；
 * 盒高 f3、宽 当前 1.48×f3／非当前 f3；架板条 宽 1.1×f3、高 6dp；封面请求 720 铺满（中枢位图直用）。
 * 黑胶直径 0.97×f3、内嵌封面 0.22×f3、中心垫 0.065×f3、中心点 0.025×f3。
 * 槽位横移 spring(0.9,300) 与抽出/入位 tween 未逐槽装配：边槽以固定偏移叠放（结构级、交卷注名）。
 */
@Composable
private fun ShelfStage(
    state: PlayerUiState,
    spin: Float,
    reveal: Float,
    onSleeveClick: () -> Unit,
    onVinylClick: () -> Unit,
    onPrevSlot: () -> Unit,
    onNextSlot: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        val f = (w * 0.66f).coerceIn(180f, 300f)
        val containerH = (f - 23f).dp
        val f2 = min(w * 0.5f, f - 36f)
        val f3 = f2 * 0.95f
        val prevTrack = state.queue.getOrNull(state.queueIndex - 1)
        val nextTrack = state.queue.getOrNull(state.queueIndex + 1)
        Box(Modifier.fillMaxWidth().height(containerH)) {
            // 边槽（下层）：偏移叠放
            if (prevTrack != null) {
                ShelfSlot(
                    coverBitmap = state.prevCoverBitmap,
                    isCurrent = false,
                    f3 = f3.dp,
                    spin = 0f,
                    reveal = 0f,
                    onSleeveClick = onPrevSlot,
                    onVinylClick = {},
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = (-(f3 * 0.80f)).dp)
                )
            }
            if (nextTrack != null) {
                ShelfSlot(
                    coverBitmap = state.nextCoverBitmap,
                    isCurrent = false,
                    f3 = f3.dp,
                    spin = 0f,
                    reveal = 0f,
                    onSleeveClick = onNextSlot,
                    onVinylClick = {},
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = (f3 * 0.80f).dp)
                )
            }
            // 当前槽（上层）
            ShelfSlot(
                coverBitmap = state.coverBitmap,
                isCurrent = true,
                f3 = f3.dp,
                spin = spin,
                reveal = reveal,
                onSleeveClick = onSleeveClick,
                onVinylClick = onVinylClick,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun ShelfSlot(
    coverBitmap: android.graphics.Bitmap?,
    isCurrent: Boolean,
    f3: androidx.compose.ui.unit.Dp,
    spin: Float,
    reveal: Float,
    onSleeveClick: () -> Unit,
    onVinylClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val slotW = if (isCurrent) (f3.value * 1.48f).dp else f3
    Box(modifier.width(slotW).height(f3)) {
        // 黑胶（套面之下）：当前槽从套面右侧抽出（reveal 比例），随 spin 旋转
        val vinylSize = (f3.value * 0.97f).dp
        val vinylOffset = if (isCurrent) (f3.value * 0.48f * reveal).dp else 0.dp
        Box(
            Modifier
                .size(vinylSize)
                .align(Alignment.TopStart)
                .offset(x = vinylOffset)
                .graphicsLayer { rotationZ = if (isCurrent) spin else 0f }
                .clickableNoRipple(enabled = isCurrent, onClick = onVinylClick)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = Color(0xFF101010), radius = radius, center = center)
                // 纹路（结构级：几圈淡白环，圈数/浓度未逐项抠到）
                for (r in listOf(0.88f, 0.76f, 0.64f, 0.52f, 0.40f)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = radius * r,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                // 内嵌封面（直径 0.22×f3）
                if (coverBitmap != null) {
                    val innerR = (f3.value * 0.22f / 2f).dp.toPx()
                    val innerSize = (innerR * 2f).roundToInt()
                    drawContext.canvas.save()
                    drawContext.canvas.clipPath(
                        androidx.compose.ui.graphics.Path().apply {
                            addOval(
                                androidx.compose.ui.geometry.Rect(
                                    center.x - innerR,
                                    center.y - innerR,
                                    center.x + innerR,
                                    center.y + innerR
                                )
                            )
                        }
                    )
                    drawImage(
                        image = coverBitmap.asImageBitmap(),
                        dstOffset = IntOffset(
                            (center.x - innerR).roundToInt(),
                            (center.y - innerR).roundToInt()
                        ),
                        dstSize = IntSize(innerSize, innerSize)
                    )
                    drawContext.canvas.restore()
                }
                // 中心垫 0.065×f3、中心点 0.025×f3
                drawCircle(
                    color = SHELF_CENTER_PAD,
                    radius = (f3.value * 0.065f / 2f).dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = SHELF_CENTER_DOT,
                    radius = (f3.value * 0.025f / 2f).dp.toPx(),
                    center = center
                )
            }
        }
        // 套面（封面铺满；无封面素面＋音符字形——源没给地址时的面，照封面占位口径）
        Box(
            Modifier
                .size(f3)
                .align(Alignment.TopStart)
                .shadow(4.dp, RoundedCornerShape(4.dp))
                .background(Color(0xFFE7E2D6), RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .clickableNoRipple(onClick = onSleeveClick),
            contentAlignment = Alignment.Center
        ) {
            if (coverBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                QueueIcon(36.dp, SHELF_MUTED)
            }
        }
        // 架板条（宽 1.1×f3、高 6dp；色未逐项抠到，按架面木色系装配、注名）
        Box(
            Modifier
                .width((f3.value * 1.1f).dp)
                .height(6.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-(f3.value * 0.05f)).dp)
                .background(Color(0xFFCFC8B8), RoundedCornerShape(3.dp))
        )
    }
}

// ================= ShelfSeekBar（:2711：总高 72、横 28、滑杆 48＋时间行视觉 24；时间 10sp/14） =================

@Composable
private fun ShelfSeekBar(
    progress: PlayerProgress,
    fillColor: Color,
    onSeek: (Long) -> Unit
) {
    val duration = progress.durationMs
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val liveFraction = if (duration > 0) {
        (progress.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shown = if (dragging) dragFraction else liveFraction
    val shownPos = if (dragging) (dragFraction * duration).toLong() else progress.positionMs
    Column(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 28.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
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
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    completed = true
                                    break
                                }
                                if (size.width > 0) {
                                    dragFraction =
                                        (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                                change.consume()
                            }
                        } finally {
                            dragging = false
                            if (completed && duration > 0) {
                                onSeek((dragFraction * duration).toLong())
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val midY = size.height / 2f
                val lineH = 2.dp.toPx()
                val radius = CornerRadius(lineH / 2f, lineH / 2f)
                drawRoundRect(
                    color = SHELF_INK.copy(alpha = 0.12f),
                    topLeft = Offset(0f, midY - lineH / 2f),
                    size = Size(size.width, lineH),
                    cornerRadius = radius
                )
                val fillW = size.width * shown
                if (fillW > 0f) {
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(0f, midY - lineH / 2f),
                        size = Size(fillW, lineH),
                        cornerRadius = radius
                    )
                }
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

// ================= ShelfControls（:2156：高 76、横 20、键盒 48、按压 0.9；播停盒 68/图标 52） =================

@Composable
private fun ShelfControls(state: PlayerUiState, onQueueClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Box(
            Modifier.size(48.dp).pressScale(0.9f)
                .clickableNoRipple { PlayerStateHolder.cycleMode() },
            contentAlignment = Alignment.Center
        ) {
            val glyph = when (state.playMode) {
                MusicQueue.MODE_REPEAT_ONE -> PATH_REPEAT_ONE
                MusicQueue.MODE_REPEAT_ALL -> PATH_REPEAT
                MusicQueue.MODE_SHUFFLE -> PATH_SHUFFLE
                else -> PATH_REORDER
            }
            PathIcon(24.dp, SHELF_INK, glyph)
        }
        Box(
            Modifier.size(48.dp).pressScale(0.9f)
                .clickableNoRipple(enabled = state.queueIndex > 0) { PlayerStateHolder.prev() },
            contentAlignment = Alignment.Center
        ) {
            PathIcon(32.dp, SHELF_INK, PATH_PREV)
        }
        Box(
            Modifier.size(68.dp).pressScale(0.9f)
                .clickableNoRipple { PlayerStateHolder.toggle() },
            contentAlignment = Alignment.Center
        ) {
            if (state.isPlaying) {
                PauseIcon(52.dp, SHELF_INK)
            } else {
                PlayIcon(52.dp, SHELF_INK)
            }
        }
        Box(
            Modifier.size(48.dp).pressScale(0.9f)
                .clickableNoRipple(enabled = state.canNext) { PlayerStateHolder.next() },
            contentAlignment = Alignment.Center
        ) {
            PathIcon(32.dp, SHELF_INK, PATH_NEXT)
        }
        Box(
            Modifier.size(48.dp).pressScale(0.9f).clickableNoRipple(onClick = onQueueClick),
            contentAlignment = Alignment.Center
        ) {
            QueueIcon(24.dp, SHELF_INK)
        }
    }
}
