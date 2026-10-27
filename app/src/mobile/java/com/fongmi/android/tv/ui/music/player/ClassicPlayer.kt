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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
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
import com.fongmi.android.tv.service.MusicBeatState
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.cos
import kotlin.math.sin

/** 瓜子缓动 bs4.a（案卷十五节亲验）：根背景换色 450ms 用它。 */
val Bs4A = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 瓜子缓动 bs4.e：封面切歌转场 400ms 用它。 */
val Bs4E = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

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
    onOpenLyricVersions: () -> Unit,
    onOpenQuality: () -> Unit,
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
            LyricSlot(
                state = state,
                onSeek = onSeek,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            PlayerProgressSlot(
                state = state,
                onSeek = onSeek,
                onLyricChipClick = onOpenLyricVersions,
                onQualityChipClick = onOpenQuality,
            )
            Spacer(modifier = Modifier.height(45.dp))
        }
    }
}

/**
 * 氛围层二选一（案卷一节：BlurredCoverAmbient／流光开时 FluidCoverAmbient）：
 * 开关读 MusicPlayerSettings.isFluidBg（存档单一事实源），以中枢 settingsRev
 * 为键重读——外观子面板改完 bump 即整树换层。明度罩两路共用同一份。
 * 流体本体保留区无现成实现，本文件 FluidAmbient 按案卷一节速度/节拍参数落最小版
 * （色团取样自封面、慢速漂移＋节拍脉冲缩放），装配细节见该函数头注。
 */
@Composable
internal fun AmbientLayer(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val settingsRev by PlayerStateHolder.settingsRev.collectAsState()
    val fluid = remember(settingsRev) { MusicPlayerSettings.isFluidBg() }
    Box(modifier = modifier) {
        if (fluid) {
            FluidAmbient(bitmap = bitmap, modifier = Modifier.fillMaxSize())
        } else {
            BlurredAmbient(bitmap = bitmap, modifier = Modifier.fillMaxSize())
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
private fun BlurredAmbient(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val small = remember(bitmap) {
        runCatching { Bitmap.createScaledBitmap(bitmap, 64, 64, true) }.getOrNull()
    }
    if (small != null) {
        Image(
            bitmap = small.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .blur(24.dp)
                .alpha(0.8f),
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * 流体氛围（最小版装配）：封面缩到 16×16 后取四象限平均色成四团，径向渐变
 * 画在整层上、层外加 48dp 糊；每团沿利萨如轨迹慢漂，漂移相位速度＝案卷
 * 速度倍率（慢 0.6／常 1.0／快 2.5，MusicPlayerSettings.fluidSpeedFactor）；
 * 律动开时团半径随服务侧节拍帧（MusicBeatState.beatPulse）脉冲，幅度＝节拍
 * 倍率（0.7／1.0／1.6，fluidBeatFactor）。开关组合变化时同口径重算节拍门
 * requestEnabled（服务注释定的氛围层职责）。帧驱动只在播放中推进，
 * 暂停定格（与引擎平滑时间同纪律：不空转）。
 */
@Composable
private fun FluidAmbient(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val settingsRev by PlayerStateHolder.settingsRev.collectAsState()
    val speedFactor = remember(settingsRev) { MusicPlayerSettings.fluidSpeedFactor() }
    val beatFactor = remember(settingsRev) { MusicPlayerSettings.fluidBeatFactor() }
    val beatOn = remember(settingsRev) { MusicPlayerSettings.isFluidBeat() }
    val state by PlayerStateHolder.state.collectAsState()
    val colors = remember(bitmap) { sampleFluidColors(bitmap) }
    var phaseMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(settingsRev) {
        // 节拍门同口径重算（流体开且律动开才发布节拍帧；开关变更 bump 后即达，不等换封面）
        MusicBeatState.requestEnabled = MusicPlayerSettings.isFluidBg() && MusicPlayerSettings.isFluidBeat()
    }
    LaunchedEffect(state.isPlaying, speedFactor) {
        var last = -1L
        while (true) {
            withFrameMillis { frame ->
                if (last >= 0L && state.isPlaying) {
                    phaseMs += ((frame - last) * speedFactor).toLong()
                }
                last = frame
            }
        }
    }

    Box(modifier = modifier.blur(48.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = phaseMs / 1000f
            val pulse = if (beatOn) {
                1f + 0.10f * MusicBeatState.beatPulse * beatFactor
            } else {
                1f
            }
            val baseR = maxOf(size.width, size.height) * 0.62f
            val anchors = arrayOf(
                floatArrayOf(0.26f, 0.30f),
                floatArrayOf(0.76f, 0.24f),
                floatArrayOf(0.70f, 0.74f),
                floatArrayOf(0.28f, 0.78f),
            )
            for (i in colors.indices) {
                val a = anchors[i % anchors.size]
                val wob = 0.05f + 0.012f * i
                val cx = (a[0] + wob * sin(t * 0.11f + i * 1.7f)) * size.width
                val cy = (a[1] + wob * cos(t * 0.09f + i * 2.3f)) * size.height
                val r = baseR * (0.82f + 0.10f * i) * pulse
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors[i].copy(alpha = 0.55f),
                            colors[i].copy(alpha = 0f),
                        ),
                        center = Offset(cx, cy),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }
        }
    }
}

/** 流体四色取样：封面缩 16×16、四象限逐像素平均（装配取样法，色源是真封面）。 */
private fun sampleFluidColors(bitmap: Bitmap): List<Color> {
    val fallback = listOf(
        Color(0xFF3A4A5C), Color(0xFF5C4A3A), Color(0xFF44584A), Color(0xFF54455E),
    )
    return runCatching {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val sums = Array(4) { LongArray(3) }
        val counts = IntArray(4)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val q = (if (y < 8) 0 else 2) + (if (x < 8) 0 else 1)
                val px = small.getPixel(x, y)
                sums[q][0] += android.graphics.Color.red(px).toLong()
                sums[q][1] += android.graphics.Color.green(px).toLong()
                sums[q][2] += android.graphics.Color.blue(px).toLong()
                counts[q]++
            }
        }
        (0 until 4).map { q ->
            if (counts[q] == 0) {
                fallback[q]
            } else {
                Color(
                    red = (sums[q][0] / counts[q]).toInt(),
                    green = (sums[q][1] / counts[q]).toInt(),
                    blue = (sums[q][2] / counts[q]).toInt(),
                )
            }
        }
    }.getOrElse { fallback }
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
internal fun TitleRow(
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
internal fun ArtistRow(state: PlayerUiState) {
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
 * 页内歌词位：有词走 AnchoredLyricStage 引擎（一件多处，本页只是喂经典页内口径）；
 * 找词/无词/未起只留一行提示（原骨架口径）。引擎占满歌名行与进度槽之间的整段。
 */
@Composable
private fun LyricSlot(
    state: PlayerUiState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (val lyrics = state.lyrics) {
            is LyricUiState.Content -> {
                if (lyrics.lines.isEmpty()) {
                    LyricHint(text = "暂无歌词", modifier = Modifier.align(Alignment.BottomStart))
                } else {
                    AnchoredLyricStage(
                        lines = lyrics.lines,
                        timed = lyrics.timed,
                        positionMs = state.positionMs,
                        isPlaying = state.isPlaying,
                        onSeekLine = onSeek,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            LyricUiState.Searching -> LyricHint(
                text = "正在找歌词",
                modifier = Modifier.align(Alignment.BottomStart),
            )
            LyricUiState.None -> LyricHint(
                text = "暂无歌词",
                modifier = Modifier.align(Alignment.BottomStart),
            )
            LyricUiState.Idle -> Unit
        }
    }
}

@Composable
internal fun LyricHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 14.sp,
        modifier = modifier,
    )
}
