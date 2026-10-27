package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 瓜子真图标路径（gz-smali-missing-dig.md 存档：MyPlay/MyPause/PlaylistIcon）。 */
const val GZ_PLAY_PATH =
    "M7.75194,5.43872 L18.2596,11.5682 C18.4981,11.7073 18.5787,12.0135 18.4396,12.252 C18.3961,12.3265 18.3341,12.3885 18.2596,12.432 L7.75194,18.5615 C7.51341,18.7006 7.20725,18.62 7.06811,18.3815 C7.0235,18.305 7,18.2181 7,18.1296 L7,5.87061 C7,5.59446 7.22386,5.37061 7.5,5.37061 C7.58853,5.37061 7.67547,5.39411 7.75194,5.43872 Z"
const val GZ_PAUSE_PATH =
    "M15,7 C15,6.44772 15.4477,6 16,6 C16.5523,6 17,6.44772 17,7 L17,17 C17,17.5523 16.5523,18 16,18 C15.4477,18 15,17.5523 15,17 L15,7 Z M7,7 C7,6.44772 7.44772,6 8,6 C8.55228,6 9,6.44772 9,7 L9,17 C9,17.5523 8.55228,18 8,18 C7.44772,18 7,17.5523 7,17 L7,7 Z"
const val GZ_QUEUE_PATH =
    "M2,18 L12,18 L12,20 L2,20 L2,18 Z M2,11 L16,11 L16,13 L2,13 L2,11 Z M2,4 L22,4 L22,6 L2,6 L2,4 Z M19,15.1707 L19,9 L24,9 L24,11 L21,11 L21,18 C21,19.6569 19.6569,21 18,21 C16.3431,21 15,19.6569 15,18 C15,16.3431 16.3431,15 18,15 C18.3506,15 18.6872,15.0602 19,15.1707 Z M18,19 C18.5523,19 19,18.5523 19,18 C19,17.4477 18.5523,17 18,17 C17.4477,17 17,17.4477 17,18 C17,18.5523 17.4477,19 18,19 Z"

/**
 * 经典主题（默认，gz-player-values.md §三）：氛围层由根垫底 →
 * 封面台整宽置顶 → SongMeta 锚封面台左下＋下移 12dp → 页内歌词窗 →
 * 进度栈 → 底部留 45dp。封面竖滑切歌、双击收藏＋爱心爆发都在本件内。
 */
@Composable
fun ClassicPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onMenuClick: () -> Unit,
    onQueueClick: () -> Unit,
    onArtistClick: () -> Unit,
    onQualityClick: () -> Unit,
    onLyricChipClick: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            ClassicCoverStage(state, Modifier.fillMaxSize())
            SongMeta(
                state = state,
                onMenuClick = onMenuClick,
                onQueueClick = onQueueClick,
                onArtistClick = onArtistClick,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = 12.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
            )
        }
        LyricStage(
            lines = state.lyricLines,
            untimed = state.lyricUntimed,
            wordByWord = true,
            fontSizeSp = MusicPlayerSettings.getCoverLyricFontSize(),
            lineHeightFactor = 4f / 3f,
            lineGapDp = 20f,
            fullAnchor = false,
            anchorFraction = MusicPlayerSettings.lyricAnchorFraction(),
            windowBefore = 2,
            windowAfter = 0,
            blurGroupB = false,
            blurCoefficient = MusicPlayerSettings.blurCoefficient(),
            palette = LyricPalette.ImmersiveDark,
            showTranslation = MusicPlayerSettings.isShowTranslation(),
            alignMode = MusicPlayerSettings.getLyricAlign(),
            weightLevel = MusicPlayerSettings.getLyricWeight(),
            lyricOffsetMs = MusicPlayerSettings.getLyricOffsetMs().toLong(),
            maxVisualLines = 2,
            topFadeDp = 0f,
            letterSpacingEm = 0f,
            onLineSeek = { PlayerStateHolder.seekTo(it) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        )
        Spacer(Modifier.height(6.dp))
        MiniProgressStack(
            state = state,
            progress = progress,
            onQualityClick = onQualityClick,
            onLyricChipClick = onLyricChipClick,
            modifier = Modifier.padding(horizontal = 28.dp)
        )
        Spacer(Modifier.height(45.dp))
    }
}

/** 歌名行＋第二行＋右三键（§三装配：与文本列间隔 8dp、三键盒 40dp 贴连）。 */
@Composable
private fun SongMeta(
    state: PlayerUiState,
    onMenuClick: () -> Unit,
    onQueueClick: () -> Unit,
    onArtistClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.track
    val title = track?.displayTitle()?.takeIf { it.isNotEmpty() } ?: "未在播放"
    val artist = track?.displayArtist() ?: ""
    val album = track?.displayAlbum() ?: ""
    Row(modifier, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
                AnimatedSongTitle(
                    text = title,
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W800,
                        lineHeight = 24.sp,
                        letterSpacing = (-0.4).sp
                    ),
                    color = Color.White.copy(alpha = 0.95f)
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
                    fontWeight = FontWeight.W500,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .pressScale(0.85f)
                        .clickableNoRipple(onClick = onArtistClick)
                )
                Box(
                    Modifier
                        .padding(horizontal = 7.dp)
                        .size(13.dp)
                        .pressScale(0.85f)
                        .clickableNoRipple { PlayerStateHolder.toggleFavorite() },
                    contentAlignment = Alignment.Center
                ) {
                    HeartIcon(
                        size = 13.dp,
                        color = Color.White.copy(alpha = if (state.isFavorite) 0.45f else 0.30f),
                        filled = state.isFavorite
                    )
                }
                if (album.isNotEmpty()) {
                    Text(
                        text = album,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W500,
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .pressScale(0.85f)
                    .clickableNoRipple(onClick = onMenuClick),
                contentAlignment = Alignment.Center
            ) {
                MenuIcon(21.dp, Color.White)
            }
            Box(
                Modifier
                    .size(40.dp)
                    .pressScale(0.85f)
                    .clickableNoRipple(onClick = onQueueClick),
                contentAlignment = Alignment.Center
            ) {
                PathIcon(20.dp, Color.White, GZ_QUEUE_PATH)
            }
            val playIconSize by animateDpAsState(
                targetValue = if (state.isPlaying) 28.dp else 25.dp,
                animationSpec = tween(300, easing = EaseBs4A),
                label = "classicPlayIconSize"
            )
            Box(
                Modifier
                    .size(40.dp)
                    .pressScale(0.85f)
                    .clickableNoRipple { PlayerStateHolder.toggle() },
                contentAlignment = Alignment.Center
            ) {
                PathIcon(
                    playIconSize,
                    Color.White,
                    if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
                )
            }
        }
    }
}

/**
 * 封面台：切歌转场 400ms/bs4.e（入竖滑 .5 尺寸＋缩放 .94→1、出竖滑 .5＋缩放 .96＋alpha→0）；
 *  pausa 缩放 .9；竖滑切歌（触发 36dp、甩速 600dp/s、轴锁 |dy|>|dx|×1.5、跟手 1:1、
 * 提交 tween(150, bs4.f) 滑出再切、回中 spring(0.9,1400)、过阈震动一次）；
 * 双击收藏＋爱心爆发（粒子 8、tween(1200, 线性)）。拖动量写普通状态、图层 lambda 消费。
 */
@Composable
private fun ClassicCoverStage(state: PlayerUiState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val settle = remember { Animatable(0f) }
    val favScale = remember { Animatable(1f) }
    var burstKey by remember { mutableIntStateOf(0) }
    var burstPoint by remember { mutableStateOf(Offset.Zero) }
    val pauseScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.9f,
        animationSpec = tween(300, easing = EaseBs4A),
        label = "coverPauseScale"
    )
    val trackId = state.track?.id ?: ""

    fun vibrate() {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(15)
                }
            }
        } catch (t: Throwable) {
            // 震动不可用不带崩页面
        }
    }

    Box(
        modifier
            .graphicsLayer {
                val settleNow = if (dragging) dragY else settle.value
                translationY = settleNow
                val s = pauseScale * favScale.value
                scaleX = s
                scaleY = s
            }
            .pointerInput(trackId) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 顶部死区：起点在状态栏区且下拉不触发切歌
                    var totalDy = 0f
                    var totalDx = 0f
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var locked = false
                    var vibrated = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    dragging = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) break
                        val ddx = change.position.x - lastX
                        val ddy = change.position.y - lastY
                        lastX = change.position.x
                        lastY = change.position.y
                        totalDx += ddx
                        totalDy += ddy
                        if (!locked && (abs(totalDy) > 8f || abs(totalDx) > 8f)) {
                            locked = abs(totalDy) > abs(totalDx) * 1.5f
                        }
                        if (locked) {
                            dragY = totalDy.coerceIn(-size.height / 2f, size.height / 2f)
                            if (!vibrated && abs(dragY) > 36.dp.toPx()) {
                                vibrated = true
                                vibrate()
                            }
                            change.consume()
                        }
                    }
                    dragging = false
                    val velocity = tracker.calculateVelocity().y
                    val trigger = 36.dp.toPx()
                    val outTarget = size.height * 0.5f
                    when {
                        locked && (dragY <= -trigger || velocity < -600.dp.toPx()) -> {
                            val from = dragY
                            scope.launch {
                                settle.snapTo(from)
                                settle.animateTo(-outTarget, tween(150, easing = EaseBs4F))
                                PlayerStateHolder.next()
                                settle.snapTo(0f)
                            }
                            dragY = 0f
                        }
                        locked && (dragY >= trigger || velocity > 600.dp.toPx()) -> {
                            val from = dragY
                            scope.launch {
                                settle.snapTo(from)
                                settle.animateTo(outTarget, tween(150, easing = EaseBs4F))
                                PlayerStateHolder.prev()
                                settle.snapTo(0f)
                            }
                            dragY = 0f
                        }
                        else -> {
                            val from = dragY
                            dragY = 0f
                            scope.launch {
                                settle.snapTo(from)
                                settle.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.9f, stiffness = 1400f)
                                )
                            }
                        }
                    }
                }
            }
            .pointerInput(trackId) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        burstPoint = offset
                        burstKey++
                        if (!state.isFavorite) PlayerStateHolder.toggleFavorite()
                        scope.launch {
                            favScale.animateTo(1.045f, tween(200, easing = EaseBs4B))
                            favScale.animateTo(
                                1f,
                                spring(dampingRatio = 0.9f, stiffness = 300f)
                            )
                        }
                    }
                )
            }
    ) {
        AnimatedContent(
            targetState = trackId,
            transitionSpec = {
                (slideInVertically(tween(400, easing = EaseBs4E)) { it / 2 } +
                    scaleIn(initialScale = 0.94f, animationSpec = tween(400, easing = EaseBs4E)) +
                    fadeIn(tween(400, easing = EaseBs4E))) togetherWith
                    (slideOutVertically(tween(400, easing = EaseBs4E)) { it / 2 } +
                        scaleOut(targetScale = 0.96f, animationSpec = tween(400, easing = EaseBs4E)) +
                        fadeOut(tween(400, easing = EaseBs4E)))
            },
            label = "coverSwap"
        ) {
            CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
        }
        if (burstKey > 0) {
            BurstHearts(triggerKey = burstKey, origin = burstPoint)
        }
    }
}

/** 爱心爆发（§三：粒子 8、angle −60~60°、travel 42–90dp、size 9–19dp、delay 随机×.08＋序×.035、总时长 1200ms 线性、>0.55 淡出）。 */
@Composable
private fun BurstHearts(triggerKey: Int, origin: Offset) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(triggerKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1200, easing = LinearEasing))
    }
    val particles = remember(triggerKey) {
        val rnd = kotlin.random.Random(triggerKey * 7919L)
        List(8) { i ->
            floatArrayOf(
                -60f + rnd.nextFloat() * 120f,
                42f + rnd.nextFloat() * 48f,
                9f + rnd.nextFloat() * 10f,
                -25f + rnd.nextFloat() * 50f,
                rnd.nextFloat() * 0.08f + i * 0.035f
            )
        }
    }
    val p = progress.value
    if (p <= 0f || p >= 1f) return
    val easeOutQuad = 1f - (1f - p) * (1f - p)
    Box(Modifier.fillMaxSize()) {
        particles.forEach { pt ->
            val (angleDeg, travelDp, sizeDp, rotDeg, delayFrac) = pt
            val local = ((p - delayFrac) / (1f - delayFrac)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val dist = travelDp * (1f - (1f - local) * (1f - local))
            val rad = Math.toRadians((angleDeg - 90f).toDouble())
            val dx = (kotlin.math.cos(rad) * dist).toFloat()
            val dy = (kotlin.math.sin(rad) * dist).toFloat()
            val alpha = if (p > 0.55f) 1f - (p - 0.55f) / 0.45f else 1f
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (origin.x + dx.dp.toPx()).roundToInt(),
                            (origin.y + dy.dp.toPx()).roundToInt()
                        )
                    }
                    .graphicsLayer {
                        this.alpha = alpha.coerceIn(0f, 1f)
                        rotationZ = rotDeg * easeOutQuad
                    }
            ) {
                HeartIcon(sizeDp.dp, Color(0xFFFF2D55), filled = true)
            }
        }
    }
}

/**
 * 播放页迷你进度条（星辰自有交互，瓜子无此件）：平时 52dp 宽短条
 * （视觉条 3dp 高、轨道白 20%、填充白 45%），点开长成完整底部进度栈；
 * 闲置 3 秒自动缩回；暂停保持展开并停计时、播放重起 3 秒计时。
 */
@Composable
fun MiniProgressStack(
    state: PlayerUiState,
    progress: PlayerProgress,
    onQualityClick: () -> Unit,
    onLyricChipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, nonce, state.isPlaying) {
        if (expanded && state.isPlaying) {
            delay(3000)
            expanded = false
        }
    }
    if (!expanded) {
        val fraction = if (progress.durationMs > 0) {
            (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
        Box(
            modifier
                .fillMaxWidth()
                .height(26.dp)
                .clickableNoRipple { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(width = 52.dp, height = 3.dp)) {
                val r = size.height / 2f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.20f),
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                )
                val fillW = size.width * fraction
                if (fillW > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.45f),
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(fillW, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                    )
                }
            }
        }
    } else {
        PlaybackScrubber(
            progress = progress,
            audioLabel = state.audioLabel,
            lyricLabel = lyricChipLabel(state),
            onSeek = {
                PlayerStateHolder.seekTo(it)
                nonce++
            },
            onQualityClick = {
                onQualityClick()
                nonce++
            },
            onLyricChipClick = {
                onLyricChipClick()
                nonce++
            },
            modifier = modifier
        )
    }
}
