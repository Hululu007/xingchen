package com.fongmi.android.tv.ui.music.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.music.MusicTrack
import com.fongmi.android.tv.utils.ImgUtil

/** 唱片架自有色（案卷七节定谳）：根 #FFF1F0ED、Ink #FF252522、Muted #FF86857E。 */
private val ShelfRoot = Color(0xFFF1F0ED)
private val ShelfInk = Color(0xFF252522)
private val ShelfMuted = Color(0xFF86857E)

/**
 * 黑胶唱片架主题 record_shelf（案卷七节）：根实色 #FFF1F0ED、无渐变罩；
 * 头行（高 64、左右 16：左收起 icon「<」、中间标题列＝歌名 15sp/行高 21/500/Ink
 * ＋歌手 11sp/行高 15/Muted、右三个点菜单 盒 48/图标 24/按压 0.9/tint Ink×.9）；
 * 架台三槽：f＝clamp(约束宽×.66, 180, 300)、容器高 f−23、f2＝min(约束宽×.5, f−36)、
 * f3＝f2×.95；槽盒高 f3、宽 当前 1.48×f3／非当前 f3；架板条宽 1.1×f3、高 6；
 * 当前槽＝袖套封面（f3 正方）＋黑胶从右侧探出（直径 .97×f3、内嵌封面圆 .22×f3、
 * 中心垫 .065×f3 #FFD4D0C5、中心点 .025×f3 #FF080808、旋转 30°/s——仅播放时转）；
 * 点当前槽切收藏（reveal 动画 1→0 tween(300, bs4.c)、切换、再 0→1 tween(600, bs4.a)）；
 * 歌词面＝白块（圆角 上 0 下 24、无阴影、顶部渐变黑 .13→.055→透明、左右 16）内
 * 歌词列（横 24/纵 12、四色 palette：主色＝lerp(封面 tone, Ink, .65)、当前未唱 ×.48、
 * 非当前 Muted×.72、翻译 Muted）；进度区（横 28、高 72：滑杆行 48——轨道画布高 4、
 * 底轨 Ink×.12 线宽 2、填充 主色 线宽 2；时间行 10sp/行高 14/Muted 左已播右总长）；
 * ShelfControls（高 76、左右 20、键盒 48、按压 0.9：模式 24/上一首 32/播放暂停
 * 盒 68 图标 52 无环底 tint Ink/下一首 32/列表 24，模式与列表 tint Muted）。
 * 邻居槽封面是主题内小状态（ShelfDeck 式），经 ImgUtil 按地址取图；当前曲封面与
 * 取色仍走状态层一份（h0 公式用状态层 dominantColor）。
 */
@Composable
fun RecordShelfPlayer(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTools: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onCycleMode: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 歌词/进度主色 h0：封面主色向 Ink 混 .65（smali 亲验）
    val h0 = remember(state.dominantColor) {
        lerp(Color(state.dominantColor), ShelfInk, 0.65f)
    }
    val reveal = remember { Animatable(1f) }
    var favAnimating by remember { mutableStateOf(false) }
    // 点当前槽收藏：reveal 1→0（300/bs4.c）→切换收藏→0→1（600/bs4.a）驱动黑胶探出量
    LaunchedEffect(favAnimating) {
        if (favAnimating) {
            reveal.animateTo(0f, tween(300, easing = Bs4C))
            onToggleFavorite()
            reveal.animateTo(1f, tween(600, easing = Bs4A))
            favAnimating = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ShelfRoot)
            .statusBarsPadding(),
    ) {
        // 头行（高 64、左右 16）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val srcL = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(0.9f, srcL)
                    .clickable(interactionSource = srcL, indication = null) { onCollapse() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_chevron),
                    contentDescription = "收起",
                    tint = ShelfInk.copy(alpha = 0.9f),
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifEmpty { "未在播放" },
                    color = ShelfInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.artist,
                    color = ShelfMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val srcR = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(0.9f, srcR)
                    .clickable(interactionSource = srcR, indication = null) { onOpenTools() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_more),
                    contentDescription = "播放工具",
                    tint = ShelfInk.copy(alpha = 0.9f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // 架台（三槽；槽位移动装配：AnimatedContent 横滑 spring(0.9, 300)）
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            val f = (maxWidth * 0.66f).coerceIn(180.dp, 300.dp)
            val f2 = minOf(maxWidth * 0.5f, f - 36.dp)
            val f3 = f2 * 0.95f
            val queue = state.queue
            val idx = state.queueIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(f - 23.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                AnimatedContent(
                    targetState = idx,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(spring(0.9f, 300f)) { w -> dir * w / 3 } + fadeIn())
                            .togetherWith(
                                slideOutHorizontally(spring(0.9f, 300f)) { w -> -dir * w / 3 } + fadeOut()
                            )
                    },
                    label = "shelfSlots",
                ) { curIdx ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        // 左邻居槽
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                            queue.getOrNull(curIdx - 1)?.let { track ->
                                ShelfSlot(
                                    track = track,
                                    f3 = f3,
                                    current = false,
                                    isPlaying = false,
                                    reveal = 1f,
                                    onClick = { onPlayAt(curIdx - 1) },
                                )
                            }
                        }
                        // 当前槽（点它收藏：reveal 1→0 300/bs4.c→切换→0→1 600/bs4.a）
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                            state.track?.let { track ->
                                ShelfSlot(
                                    track = track,
                                    f3 = f3,
                                    current = true,
                                    isPlaying = state.isPlaying,
                                    reveal = reveal.value,
                                    onClick = {
                                        if (!favAnimating) favAnimating = true
                                    },
                                )
                            }
                        }
                        // 右邻居槽
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                            queue.getOrNull(curIdx + 1)?.let { track ->
                                ShelfSlot(
                                    track = track,
                                    f3 = f3,
                                    current = false,
                                    isPlaying = false,
                                    reveal = 1f,
                                    onClick = { onPlayAt(curIdx + 1) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 歌词面（白块：圆角 上 0 下 24、无阴影、顶部渐变；左右 16）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Color.White),
        ) {
            // 顶部渐变带（黑 .13→.055→透明；带高为装配）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.13f),
                            0.55f to Color.Black.copy(alpha = 0.055f),
                            1f to Color.Transparent,
                        )
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                when (val lyrics = state.lyrics) {
                    is LyricUiState.Content -> {
                        if (lyrics.lines.isEmpty()) {
                            ShelfLyricHint()
                        } else {
                            AnchoredLyricStage(
                                lines = lyrics.lines,
                                timed = lyrics.timed,
                                positionMs = state.positionMs,
                                isPlaying = state.isPlaying,
                                onSeekLine = onSeek,
                                spec = LyricStageSpec(
                                    palette = LyricPalette(
                                        sung = h0,
                                        currentUnsung = h0.copy(alpha = h0.alpha * 0.48f),
                                        inactive = ShelfMuted.copy(alpha = ShelfMuted.alpha * 0.72f),
                                        translation = ShelfMuted,
                                    ),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    LyricUiState.Searching -> ShelfLyricHint(text = "正在找歌词")
                    LyricUiState.None -> ShelfLyricHint()
                    LyricUiState.Idle -> Unit
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 进度区（左右 28、高 72：滑杆行 48＋时间行）
        ShelfSeekBar(
            state = state,
            mainColor = h0,
            onSeek = onSeek,
            modifier = Modifier.padding(horizontal = 28.dp),
        )

        // ShelfControls（高 76、左右 20、键盒 48、按压 0.9）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            val srcMode = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(0.9f, srcMode)
                    .clickable(interactionSource = srcMode, indication = null) { onCycleMode() },
                contentAlignment = Alignment.Center,
            ) {
                ModeIcon(mode = state.mode, tint = ShelfMuted, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            ShelfKey(
                icon = R.drawable.ic_music_prev,
                desc = "上一首",
                iconSize = 32.dp,
                tint = ShelfInk,
                enabled = state.canPrev,
            ) { onPrev() }
            Spacer(modifier = Modifier.width(4.dp))
            // 播放/暂停（盒 68、图标 52、无环底、tint Ink）
            val srcPlay = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .pressScale(0.9f, srcPlay)
                    .clickable(interactionSource = srcPlay, indication = null) { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play
                    ),
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = ShelfInk,
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            ShelfKey(
                icon = R.drawable.ic_music_next,
                desc = "下一首",
                iconSize = 32.dp,
                tint = ShelfInk,
                enabled = state.canNext,
            ) { onNext() }
            Spacer(modifier = Modifier.width(4.dp))
            val srcQueue = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(0.9f, srcQueue)
                    .clickable(interactionSource = srcQueue, indication = null) { onOpenQueue() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_queue),
                    contentDescription = "播放列表",
                    tint = ShelfMuted,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

/**
 * 一只槽：盒高 f3、宽 当前 1.48×f3／非当前 f3；架板条宽 1.1×f3、高 6（色为装配）；
 * 当前槽＝袖套封面（f3 正方）＋黑胶从右侧探出：直径 .97×f3、内嵌封面圆 .22×f3、
 * 中心垫 .065×f3 #FFD4D0C5、中心点 .025×f3 #FF080808、旋转 30°/s（仅播放时）。
 * 黑胶探出量＝reveal（1＝全探出）。邻居封面按地址主题内取图（ImgUtil）。
 */
@Composable
private fun ShelfSlot(
    track: MusicTrack,
    f3: androidx.compose.ui.unit.Dp,
    current: Boolean,
    isPlaying: Boolean,
    reveal: Float,
    onClick: () -> Unit,
) {
    val widthDp = if (current) f3 * 1.48f else f3
    var bmp by remember(track.cover) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track.cover) {
        val url = track.cover
        if (!url.isNullOrEmpty()) {
            ImgUtil.load(url, object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?,
                ) {
                    bmp = resource
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
            })
        }
    }
    // 黑胶角度（30°/s、仅播放时帧积分）
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var last = -1L
            while (true) {
                withFrameMillis { t ->
                    if (last >= 0L) {
                        val dt = (t - last).coerceIn(0L, 50L)
                        angle = (angle + dt.toFloat() * 30f / 1000f) % 360f
                    }
                    last = t
                }
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(widthDp)
                .height(f3)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { onClick() },
        ) {
            if (current) {
                // 黑胶（从袖套右侧探出，探出量由 reveal 驱动）
                val discD = f3 * 0.97f
                val peek = discD * 0.48f * reveal
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = peek - f3 * 0.48f)
                        .size(discD),
                ) {
                    // 盘身（近黑圆 + 沟纹为装配画法）
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = Color(0xFF101010))
                        drawCircle(
                            color = Color.White.copy(alpha = 0.10f),
                            radius = size.minDimension * 0.36f,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.07f),
                            radius = size.minDimension * 0.44f,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                        )
                    }
                    // 内嵌封面圆（.22×f3、跟转）＋中心垫＋中心点
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(f3 * 0.22f)
                            .graphicsLayer { rotationZ = angle }
                            .clip(CircleShape),
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFD4D0C5)))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(f3 * 0.065f)
                            .clip(CircleShape)
                            .background(Color(0xFFD4D0C5)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(f3 * 0.025f)
                            .clip(CircleShape)
                            .background(Color(0xFF080808)),
                    )
                }
            }
            // 袖套封面（f3 正方、左对齐压住黑胶根部）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(f3)
                    .background(Color(0xFFE3DFD6)),
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = track.title?.firstOrNull()?.toString() ?: "♪",
                            color = ShelfInk.copy(alpha = 0.5f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        // 架板条（宽 1.1×f3、高 6）
        Box(
            modifier = Modifier
                .width(f3 * 1.1f)
                .height(6.dp)
                .background(ShelfInk.copy(alpha = 0.15f)),
        )
    }
}

@Composable
private fun ShelfLyricHint(text: String = "暂无歌词") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = ShelfMuted, fontSize = 14.sp)
    }
}

/**
 * 唱片架进度（案卷七节）：区高 72、滑杆行 48（轨道画布高 4：底轨 Ink×.12 线宽 2、
 * 填充 主色 线宽 2）、时间行 10sp/行高 14/Muted 左已播右总长。拖动松手 seek。
 */
@Composable
private fun ShelfSeekBar(
    state: PlayerUiState,
    mainColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(duration) {
                    if (duration <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragFraction * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            ) {
                val cy = size.height / 2f
                val stroke = 2.dp.toPx()
                drawLine(
                    color = ShelfInk.copy(alpha = 0.12f),
                    start = Offset(0f, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                if (fraction > 0f) {
                    drawLine(
                        color = mainColor,
                        start = Offset(0f, cy),
                        end = Offset(size.width * fraction, cy),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatPlayerTime(shownMs),
                color = ShelfMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatPlayerTime(duration),
                color = ShelfMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

/** 唱片架运输键（盒 48、按压 0.9）。 */
@Composable
private fun ShelfKey(
    icon: Int,
    desc: String,
    iconSize: androidx.compose.ui.unit.Dp,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    Box(
        modifier = Modifier
            .size(48.dp)
            .pressScale(0.9f, src)
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = tint.copy(alpha = tint.alpha * if (enabled) 1f else 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}
