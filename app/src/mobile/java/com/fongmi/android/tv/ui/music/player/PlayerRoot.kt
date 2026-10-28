package com.fongmi.android.tv.ui.music.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.request.transition.Transition
import com.fongmi.android.tv.bean.music.LyricVersion
import com.fongmi.android.tv.bean.music.MusicTrack
import com.fongmi.android.tv.impl.CustomTarget
import com.fongmi.android.tv.music.GzLyricMatch
import com.fongmi.android.tv.music.LyricVersionsCache
import com.fongmi.android.tv.music.MusicLyricCache
import com.fongmi.android.tv.music.MusicQueue
import com.fongmi.android.tv.music.MusicRepository
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.ui.activity.MusicSearchActivity
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import com.fongmi.android.tv.utils.ImgUtil
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val AccentRed = Color(0xFFFA243C)

/** 歌词筹文案（§二筹集：逐字/逐行/纯文本/无词，按当前词表形态现算）。 */
fun lyricChipLabel(state: PlayerUiState): String {
    val lines = state.lyricLines
    if (lines.isEmpty()) return "无词"
    if (state.lyricUntimed) return "纯文本"
    val hasWords = lines.any { it.main?.hasWords() == true }
    return if (hasWords) "逐字" else "逐行"
}

/** HSL 明度移位（瓜子 s()：明度＋Δ、钳 [.1,.68]），氛围层渐变罩用。 */
fun shiftLightness(color: Color, delta: Float): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    var l = (max + min) / 2f
    val d = max - min
    val s = if (d == 0f) 0f else if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    var h = 0f
    if (d != 0f) {
        h = when (max) {
            r -> (g - b) / d + if (g < b) 6f else 0f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h /= 6f
    }
    l = (l + delta).coerceIn(0.1f, 0.68f)
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h * 6f) % 2f - 1f))
    val m = l - c / 2f
    val (rr, gg, bb) = when {
        h < 1f / 6f -> Triple(c, x, 0f)
        h < 2f / 6f -> Triple(x, c, 0f)
        h < 3f / 6f -> Triple(0f, c, x)
        h < 4f / 6f -> Triple(0f, x, c)
        h < 5f / 6f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (rr + m).coerceIn(0f, 1f),
        green = (gg + m).coerceIn(0f, 1f),
        blue = (bb + m).coerceIn(0f, 1f),
        alpha = color.alpha
    )
}

/**
 * 播放页树根（一棵树到底）：条矩形形变只动同一容器的 offset/size/clip，
 * p=1 时修饰归零、不换父不换树；根底色＝中枢 baseColor 450ms/bs4.a 动画；
 * 氛围层垫底、主题分发、全屏歌词同树覆盖（左划进、右划/返回退）、
 * 工具/队列/歌词版本三面板同树宿主。长开 380ms、收回 320ms（星辰自有形态）。
 */
@Composable
fun PlayerRoot(barRect: Rect?, onClose: () -> Unit) {
    val state by PlayerStateHolder.state.collectAsState()
    val progress by PlayerStateHolder.progress.collectAsState()
    val context = LocalContext.current
    rememberPlayerClock(progress, state.isPlaying)
    val baseColor by animateColorAsState(
        targetValue = Color(state.baseColor),
        animationSpec = tween(450, easing = EaseBs4A),
        label = "rootBaseColor"
    )
    val scope = rememberCoroutineScope()
    val expand = remember { Animatable(if (barRect == null) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val lyricPage = remember { Animatable(0f) }
    var theme by remember { mutableStateOf(MusicPlayerSettings.getTheme()) }
    var toolsOpen by remember { mutableStateOf(false) }
    var toolsPanel by remember { mutableStateOf(ToolsSubPanel.MAIN) }
    var queueOpen by remember { mutableStateOf(false) }
    var versionOpen by remember { mutableStateOf(false) }

    // 根级左划歌词页只对经典/封面开：Apple 的横滑由其内部 pager 三页接管（§五），不双响
    val lyricsAllowed = theme == MusicPlayerSettings.THEME_CLASSIC ||
        theme == MusicPlayerSettings.THEME_COVER_IMMERSIVE
    LaunchedEffect(theme) {
        if (!lyricsAllowed && showLyrics) showLyrics = false
    }
    LaunchedEffect(showLyrics) {
        lyricPage.animateTo(
            if (showLyrics) 1f else 0f,
            tween(300, easing = EaseBs4A)
        )
    }
    LaunchedEffect(Unit) {
        if (barRect != null) {
            expand.animateTo(1f, tween(380, easing = EaseBs4A))
        }
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            try {
                if (barRect != null) {
                    // 收回动画只是皮：450ms 没演完（被开页动画顶掉/帧时钟卡住）就直接退，
                    // 不许让动画把 finish 永久挡死（2026-10-28 三星真机 ANR 定根）
                    withTimeoutOrNull(450) {
                        expand.animateTo(0f, tween(320, easing = EaseBs4A))
                    }
                }
            } catch (t: Throwable) {
                // 动画取消/异常不许带走退出通道
            } finally {
                onClose()
            }
        }
    }
    fun openTools() {
        toolsPanel = ToolsSubPanel.MAIN
        toolsOpen = true
    }
    fun selectTheme(id: String) {
        MusicPlayerSettings.setTheme(id)
        theme = MusicPlayerSettings.getTheme()
    }
    fun artistSearch(artist: String) {
        if (artist.isEmpty()) return
        context.startActivity(
            Intent(context, MusicSearchActivity::class.java).putExtra("keyword", artist)
        )
    }
    BackHandler(enabled = !closing && !showLyrics && !toolsOpen && !queueOpen && !versionOpen) {
        close()
    }
    BackHandler(enabled = showLyrics && !closing && !toolsOpen && !queueOpen && !versionOpen) {
        showLyrics = false
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(baseColor)
    ) {
        val scopeW = maxWidth
        val scopeH = maxHeight
        val density = LocalDensity.current
        val fullWPx = with(density) { scopeW.toPx() }
        val fullHPx = with(density) { scopeH.toPx() }
        val p = expand.value
        val curX = if (barRect != null) {
            androidx.compose.ui.util.lerp(barRect.left.toFloat(), 0f, p)
        } else 0f
        val curY = if (barRect != null) {
            androidx.compose.ui.util.lerp(barRect.top.toFloat(), 0f, p)
        } else 0f
        val curW = if (barRect != null) {
            androidx.compose.ui.util.lerp(barRect.width().toFloat(), fullWPx, p)
        } else fullWPx
        val curH = if (barRect != null) {
            androidx.compose.ui.util.lerp(barRect.height().toFloat(), fullHPx, p)
        } else fullHPx
        val corner = with(density) { (curH / 2f * (1f - p)).toDp() }

        // 形变容器（同一节点：从条矩形长到全屏，修饰随 p 归零）
        Box(
            Modifier
                .offset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
                .size(with(density) { curW.toDp() }, with(density) { curH.toDp() })
                .clip(RoundedCornerShape(corner))
                .background(baseColor)
        ) {
            PlayerAmbientBackground(state, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(showLyrics, toolsOpen, queueOpen, versionOpen, lyricsAllowed) {
                        awaitEachGesture {
                            if (toolsOpen || queueOpen || versionOpen) return@awaitEachGesture
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.position.y > size.height - 120.dp.toPx()) {
                                return@awaitEachGesture
                            }
                            var dx = 0f
                            var dy = 0f
                            var lastX = down.position.x
                            var lastY = down.position.y
                            var fired = false
                            val threshold = 56.dp.toPx()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                dx += change.position.x - lastX
                                dy += change.position.y - lastY
                                lastX = change.position.x
                                lastY = change.position.y
                                if (!fired && abs(dx) > threshold && abs(dx) > abs(dy)) {
                                    if (!showLyrics && dx < 0 && lyricsAllowed) {
                                        showLyrics = true
                                    } else if (showLyrics && dx > 0) {
                                        showLyrics = false
                                    }
                                    fired = true
                                }
                            }
                        }
                    }
            ) {
                ThemedPlayer(
                    state = state,
                    progress = progress,
                    theme = theme,
                    onOpenTools = { openTools() },
                    onClosePage = { close() },
                    onShowLyrics = { showLyrics = true },
                    onQueueClick = { queueOpen = true },
                    onLyricChipClick = { versionOpen = true },
                    onArtistSearch = { artistSearch(it) },
                    onThemeSelected = { selectTheme(it) }
                )
                val pageP = lyricPage.value
                if (showLyrics || pageP > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = (1f - pageP) * fullWPx
                                alpha = pageP
                            }
                    ) {
                        FullLyricPlayer(
                            state = state,
                            progress = progress,
                            onQueueClick = { queueOpen = true },
                            onLyricChipClick = { versionOpen = true }
                        )
                    }
                }
            }
        }
        // 条本体压顶（形变前段交叉淡出、收回对称淡入，落点与真条重合）
        if (barRect != null && p < 1f) {
            Box(
                Modifier
                    .offset { IntOffset(barRect.left, barRect.top) }
                    .size(
                        with(density) { barRect.width().toDp() },
                        with(density) { barRect.height().toDp() }
                    )
                    .graphicsLayer {
                        alpha = (1f - p / 0.4f).coerceIn(0f, 1f)
                    }
            ) {
                PlayerBarContent(
                    state = state,
                    progress = progress,
                    interactive = false,
                    onOpen = {}
                )
            }
        }
        // 面板覆盖层（同树末尾：返回键先关面板，后注册的 BackHandler 优先）
        if (toolsOpen) {
            PlayerToolsSheet(
                state = state,
                subPanel = toolsPanel,
                onSubPanelChange = { toolsPanel = it },
                onClose = { toolsOpen = false },
                onThemeSelected = { selectTheme(it) }
            )
        }
        if (queueOpen) {
            QueueSheet(state = state, onClose = { queueOpen = false })
        }
        if (versionOpen) {
            LyricVersionSheet(state = state, onClose = { versionOpen = false })
        }
    }
    BackHandler(enabled = toolsOpen && !closing) { toolsOpen = false }
    BackHandler(enabled = queueOpen && !closing) { queueOpen = false }
    BackHandler(enabled = versionOpen && !closing) { versionOpen = false }
}

/** 主题分发（照瓜子 PlayerScreen 主题 switch；命令全走 PlayerStateHolder）。 */
@Composable
private fun ThemedPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    theme: String,
    onOpenTools: () -> Unit,
    onClosePage: () -> Unit,
    onShowLyrics: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricChipClick: () -> Unit,
    onArtistSearch: (String) -> Unit,
    onThemeSelected: (String) -> Unit
) {
    when {
        theme == MusicPlayerSettings.THEME_COVER_IMMERSIVE -> CoverPlayer(
            state = state,
            progress = progress,
            onQueueClick = onQueueClick,
            onQualityClick = {},
            onLyricChipClick = onLyricChipClick,
            onMenuClick = onOpenTools
        )
        theme == MusicPlayerSettings.THEME_APPLE_MUSIC -> ApplePlayer(
            state = state,
            progress = progress,
            onToolsClick = onOpenTools,
            onQueueClick = onQueueClick,
            onQualityClick = {},
            onLyricClick = onShowLyrics
        )
        MusicPlayerSettings.isCdTheme(theme) -> CdPlayer(
            state = state,
            progress = progress,
            mode = theme,
            onClose = onClosePage,
            onToolsClick = onOpenTools,
            onQueueClick = onQueueClick,
            onQualityClick = {},
            onLyricVersionClick = onLyricChipClick,
            onArtistSearch = onArtistSearch,
            onThemeSelected = onThemeSelected
        )
        theme == MusicPlayerSettings.THEME_RECORD_SHELF -> ShelfPlayer(
            state = state,
            progress = progress,
            onClose = onClosePage,
            onToolsClick = onOpenTools,
            onQueueClick = onQueueClick
        )
        else -> ClassicPlayer(
            state = state,
            progress = progress,
            onMenuClick = onOpenTools,
            onQueueClick = onQueueClick,
            onArtistClick = { onArtistSearch(state.track?.displayArtist().orEmpty()) },
            onQualityClick = {},
            onLyricChipClick = onLyricChipClick
        )
    }
}

// ================= 氛围层（CoverAmbient：状态层一份图一份色，主题不重算） =================

/**
 * 氛围层垫底：流体开（MusicPlayerSettings.isFluidBg）走 FluidAmbient，
 * 否则 BlurredCoverAmbient 口径（§深夜定谳：图 alpha .4＋模糊 24dp＋渐变四档 .76/.82/.80/.86）。
 */
@Composable
fun PlayerAmbientBackground(state: PlayerUiState, modifier: Modifier = Modifier) {
    val base = Color(state.baseColor)
    val bmp = state.ambientBitmap
    if (MusicPlayerSettings.isFluidBg() && bmp != null) {
        FluidAmbient(state, modifier)
        return
    }
    Box(modifier.background(base)) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.4f }
                    .then(
                        if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier
                    )
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to shiftLightness(base, 0.05f).copy(alpha = 0.76f),
                            0.4f to base.copy(alpha = 0.82f),
                            0.78f to base.copy(alpha = 0.80f),
                            1f to shiftLightness(base, -0.04f).copy(alpha = 0.86f)
                        )
                    )
                )
        )
    }
}

private class FluidPhases {
    val phase = FloatArray(4)
    var lastFrameMs = -1L
}

/**
 * 流光氛围（FluidCoverAmbient 定谳公式）：相位周期 19/27/34/13，速率＝dt×速度倍率
 * （节拍/流体量在星辰无对应能量路、按接口约定取 0）；图层模糊 18dp＋三色团＋渐变罩 f12=0 档。
 * 相位在绘制 lambda 内推进（绘制期状态），团以圆心偏移＋半径缩放近似整屏图层变换。
 */
@Composable
private fun FluidAmbient(state: PlayerUiState, modifier: Modifier = Modifier) {
    val bmp = state.ambientBitmap ?: return
    val base = Color(state.baseColor)
    val holder = remember { FluidPhases() }
    val speed = MusicPlayerSettings.fluidSpeedFactor()
    Box(modifier.background(base)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier)
        ) {
            val frameMs = PlayerClock.frameTimeMs.longValue
            PlayerClock.requestFrames(500)
            val dt = if (holder.lastFrameMs < 0L) {
                0f
            } else {
                ((frameMs - holder.lastFrameMs) / 1000f).coerceIn(0.0005f, 0.05f)
            }
            holder.lastFrameMs = frameMs
            if (dt > 0f) {
                val periods = floatArrayOf(19f, 27f, 34f, 13f)
                for (i in 0..3) {
                    holder.phase[i] += (dt * speed * 2f * Math.PI.toFloat() / periods[i])
                }
            }
            val p = holder.phase
            val scale = (sin(p[3]) * 0.3f + 2.1f)
            val tx = 72.dp.toPx() * sin(p[0])
            val ty = 52.dp.toPx() * cos(p[1])
            val rot = sin(p[2]) * 8f
            val img = bmp.asImageBitmap()
            val cx = size.width / 2f
            val cy = size.height / 2f
            withTransform({
                translate(cx + tx, cy + ty)
                rotate(rot, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = img,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (-size.width / 2f).roundToInt(),
                        (-size.height / 2f).roundToInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        size.width.roundToInt(),
                        size.height.roundToInt()
                    )
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            // 与下层同订时钟帧（只读丢弃）：相位由下层绘制期推进，本层靠帧失效跟着重绘
            PlayerClock.frameTimeMs.longValue
            PlayerClock.requestFrames(500)
            val p = holder.phase
            val w = size.width
            val h = size.height
            val maxDim = maxOf(w, h)
            fun blob(color: Color, alpha: Float, scale: Float, ox: Float, oy: Float) {
                val a = alpha.coerceIn(0f, 0.9f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = a),
                            color.copy(alpha = a * 0.45f),
                            color.copy(alpha = 0f)
                        ),
                        center = Offset(w / 2f + ox, h / 2f + oy),
                        radius = maxDim * 0.75f * scale
                    ),
                    radius = maxDim * 0.75f * scale,
                    center = Offset(w / 2f + ox, h / 2f + oy)
                )
            }
            blob(
                shiftLightness(base, 0.14f),
                (sin(p[0]) * 0.28f + 0.8f) * 0.45f,
                sin(p[0]) * 0.16f + 1.5f,
                w * (cos(p[0]) * 0.14f - sin(p[2]) * 0.28f),
                h * cos(p[0]) * (-0.32f)
            )
            blob(
                shiftLightness(base, 0.16f),
                (sin(p[3]) * 0.3f + 0.78f) * 0.58f,
                sin(p[3]) * 0.2f + 1.7f,
                w * (sin(p[2]) * 0.12f + sin(p[0]) * 0.34f),
                h * cos(p[1]) * 0.28f
            )
            blob(
                shiftLightness(base, 0.06f),
                (cos(p[3]) * 0.3f + 0.78f) * 0.52f,
                cos(p[3]) * 0.22f + 1.9f,
                w * cos(p[1]) * (-0.32f),
                h * (cos(p[0]) * 0.1f + sin(p[2]) * 0.3f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to shiftLightness(base, 0.05f).copy(alpha = 0.30f),
                            0.4f to base.copy(alpha = 0.38f),
                            0.78f to base.copy(alpha = 0.36f),
                            1f to shiftLightness(base, -0.04f).copy(alpha = 0.48f)
                        )
                    )
                )
        )
    }
}

// ================= 播放队列（PlayQueueSheet，§十二；深色 token 一套） =================

/**
 * 队列面板：高 0.68、顶内边距 12；头行「播放列表」＋右侧播放模式钮（着色 #FFAF909F）；
 * 行高 56/圆角 12、序号 "%02d"、封面 36 圆角 8、当前行 AccentRed 标题＋底 CardBg；
 * 初始滚动位＝当前序号−3。点行即播（PlayerStateHolder.playAt）。行尾动作钮与
 * 来源卡/长按拖序无保留层数据口，本单未落（见交卷未决清单）。
 */
@Composable
fun QueueSheet(state: PlayerUiState, onClose: () -> Unit) {
    val queue = state.queue
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (state.queueIndex - 3).coerceAtLeast(0)
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickableNoRipple(onClick = onClose)
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.68f)
                .shadow(16.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(
                    SheetTokens.PanelBg,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickableNoRipple {}
                .navigationBarsPadding()
                .padding(top = 12.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.9f)
                        .background(SheetTokens.InsetBg, CircleShape)
                        .clickableNoRipple(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(20.dp, SheetTokens.TextHigh, PATH_CLOSE)
                }
                Text(
                    text = "播放列表",
                    fontSize = 17.sp,
                    letterSpacing = (-0.3).sp,
                    color = SheetTokens.TextHigh,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.9f)
                        .background(SheetTokens.InsetBg, CircleShape)
                        .clickableNoRipple { PlayerStateHolder.cycleMode() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(
                        20.dp,
                        Color(0xFFAF909F),
                        when (state.playMode) {
                            MusicQueue.MODE_REPEAT_ONE -> PATH_REPEAT_ONE
                            MusicQueue.MODE_REPEAT_ALL -> PATH_REPEAT
                            MusicQueue.MODE_SHUFFLE -> PATH_SHUFFLE
                            else -> PATH_REORDER
                        }
                    )
                }
            }
            if (queue.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    QueueIcon(40.dp, SheetTokens.TextLow.copy(alpha = 0.45f))
                    Spacer(Modifier.height(8.dp))
                    Text(text = "队列为空", fontSize = 13.sp, color = SheetTokens.TextLow)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(queue, key = { i, t -> "queue_row_" + i + "_" + (t?.id ?: "") }) { i, track ->
                        QueueRow(
                            track = track,
                            index = i,
                            current = i == state.queueIndex,
                            onClick = {
                                PlayerStateHolder.playAt(i)
                                onClose()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(track: MusicTrack?, index: Int, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                if (current) SheetTokens.CardBg else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .pressScale(0.98f)
            .clickableNoRipple(onClick = onClick)
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "%02d".format(index + 1),
            fontSize = 11.sp,
            color = if (current) AccentRed.copy(alpha = 0.7f) else SheetTokens.TextLow,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp)
        )
        QueueCover(track)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track?.displayTitle() ?: "",
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.W600,
                color = if (current) AccentRed else SheetTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track?.displayArtist()?.takeIf { it.isNotEmpty() } ?: "未知歌手",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = if (current) AccentRed.copy(alpha = 0.72f) else SheetTokens.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QueueCover(track: MusicTrack?) {
    var bmp by remember(track?.id, track?.cover) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track?.id, track?.cover) {
        val url = track?.cover
        if (!url.isNullOrEmpty()) {
            ImgUtil.load(url, object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    bmp = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                }
            })
        }
    }
    Box(
        Modifier
            .size(36.dp)
            .background(SheetTokens.CoverPlaceholder, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp)
            )
        } else {
            QueueIcon(16.dp, SheetTokens.TextLow)
        }
    }
}

// ================= 歌词版本面板（PlayerVersionPicker，§十一；深色 token 一套） =================

private val VERSION_FILTERS = listOf("全部", "逐字", "逐行", "翻译", "音译", "文本")

/**
 * 歌词版本面板：点歌词筹打开（瓜子口径，翻译开关不占筹）。
 * 数据＝MusicRepository.lyricVersions（缓存先行＋后台补搜两段回调）；
 * 点选＝saveManualLyricChoice 存档＋MusicLyricCache.put 换缓存（与旧实现同口径）。
 * 匹配度＝GzLyricMatch.percentForLevel（有瓜子定级才显）、偏差＝durationDeltaSec。
 */
@Composable
fun LyricVersionSheet(state: PlayerUiState, onClose: () -> Unit) {
    val track = state.track
    val scope = rememberCoroutineScope()
    var versions by remember(track?.id) { mutableStateOf<List<LyricVersion>>(emptyList()) }
    var settled by remember(track?.id) { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    LaunchedEffect(track?.id) {
        val t = track ?: return@LaunchedEffect
        MusicRepository.lyricVersions(t, false, object : LyricVersionsCache.Callback {
            override fun onPartial(v: MutableList<LyricVersion>) {
                scope.launch { versions = v.toList() }
            }

            override fun onSettled(v: MutableList<LyricVersion>, complete: Boolean) {
                scope.launch {
                    versions = v.toList()
                    settled = true
                }
            }
        })
    }
    val storedKey = remember(track?.id, versions) {
        val t = track
        if (t == null) "" else MusicStore.getManualLyricChoice(MusicLyricCache.key(t)) ?: ""
    }
    val cachedRaw = remember(track?.id) {
        val t = track
        if (t == null) null else MusicLyricCache.get(t)?.rawLrc
    }
    val shown = versions.filter { v ->
        when (filter) {
            1 -> v.isWordTimed
            2 -> v.isLineTimed && !v.isWordTimed
            3 -> !v.lyric?.translation.isNullOrEmpty()
            4 -> !v.lyric?.romanization.isNullOrEmpty()
            5 -> !v.isLineTimed && !v.isWordTimed
            else -> true
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickableNoRipple(onClick = onClose)
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .shadow(16.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(
                    SheetTokens.PanelBg,
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .clickableNoRipple {}
                .navigationBarsPadding()
        ) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "歌词版本",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.W800,
                        letterSpacing = (-0.4).sp,
                        lineHeight = 26.sp,
                        color = SheetTokens.TextHigh,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .size(36.dp)
                            .pressScale(0.9f)
                            .background(SheetTokens.InsetBg, CircleShape)
                            .clickableNoRipple(onClick = onClose),
                        contentAlignment = Alignment.Center
                    ) {
                        PathIcon(18.dp, SheetTokens.TextMedium, PATH_CLOSE)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .background(SheetTokens.CoverPlaceholder, RoundedCornerShape(10.dp))
                            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.coverBitmap != null) {
                            CoverImage(state, ContentScale.Crop, Modifier.size(52.dp))
                        } else {
                            QueueIcon(22.dp, SheetTokens.TextLow)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track?.displayTitle() ?: "",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.W600,
                            lineHeight = 21.sp,
                            color = SheetTokens.TextHigh,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track?.displayArtist() ?: "",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = SheetTokens.TextMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (!settled && versions.isEmpty()) {
                                "正在搜索歌词候选"
                            } else {
                                "共 ${versions.size} 个版本 · 按匹配度排列"
                            },
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = SheetTokens.TextLow,
                            maxLines = 1
                        )
                    }
                }
            }
            // 筛选条（高 40、圆角 12、底 InsetBg、选中滑块 Accent 系）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(40.dp)
                    .background(SheetTokens.InsetBg, RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                VERSION_FILTERS.forEachIndexed { i, label ->
                    val selected = i == filter
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (selected) {
                                    Modifier.background(
                                        AccentRed.copy(alpha = 0.85f),
                                        RoundedCornerShape(9.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .pressScale(0.96f)
                            .clickableNoRipple { filter = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                            color = if (selected) Color.White else SheetTokens.TextMedium
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (settled) "没有找到可用歌词" else "正在搜索歌词候选",
                        fontSize = 13.sp,
                        color = SheetTokens.TextLow
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(shown, key = { i, v ->
                        "ver_" + i + "_" + MusicRepository.lyricVersionKey(v)
                    }) { _, v ->
                        val key = MusicRepository.lyricVersionKey(v)
                        val selected = if (storedKey.isNotEmpty()) {
                            key == storedKey
                        } else {
                            cachedRaw != null && v.lyric?.rawLrc == cachedRaw
                        }
                        VersionCard(
                            version = v,
                            selected = selected,
                            fallbackTitle = track?.displayTitle() ?: "",
                            fallbackArtist = track?.displayArtist() ?: "",
                            onClick = {
                                val t = track
                                if (t != null) {
                                    MusicRepository.saveManualLyricChoice(t, v)
                                    val lyric = v.lyric
                                    if (lyric != null) MusicLyricCache.put(t, lyric)
                                }
                                onClose()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionCard(
    version: LyricVersion,
    selected: Boolean,
    fallbackTitle: String,
    fallbackArtist: String,
    onClick: () -> Unit
) {
    val lyric = version.lyric
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (selected) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(
                                AccentRed.copy(alpha = 0.12f),
                                AccentRed.copy(alpha = 0.04f)
                            )
                        ),
                        shape
                    )
                } else {
                    Modifier.background(SheetTokens.InsetBg, shape)
                }
            )
            .pressScale(0.98f)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(
                    if (selected) AccentRed.copy(alpha = 0.13f) else SheetTokens.CardBg,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            LyricVersionIcon(
                20.dp,
                if (selected) AccentRed else SheetTokens.TextMedium
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = version.source?.takeIf { it.isNotEmpty() } ?: "未知来源",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W600,
                    color = SheetTokens.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(6.dp))
                VersionTag(version.kindLabel())
                if (!lyric?.translation.isNullOrEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    VersionTag("翻译")
                }
                if (!lyric?.romanization.isNullOrEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    VersionTag("音译")
                }
            }
            val candidateTitle = version.candidateTitle
            val candidateArtist = version.candidateArtist
            val sub = when {
                !candidateTitle.isNullOrEmpty() ->
                    candidateTitle + if (!candidateArtist.isNullOrEmpty()) " · $candidateArtist" else ""
                fallbackTitle.isNotEmpty() ->
                    fallbackTitle + if (fallbackArtist.isNotEmpty()) " · $fallbackArtist" else ""
                else -> ""
            }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    fontSize = 11.sp,
                    color = SheetTokens.TextMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (version.hasGzScore()) {
                Text(
                    text = "${GzLyricMatch.percentForLevel(version.gzLevel)}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                    color = if (selected) AccentRed else SheetTokens.TextHigh
                )
            }
            if (version.durationDeltaSec > 0) {
                Text(
                    text = "偏差 ${version.durationDeltaSec}s",
                    fontSize = 10.sp,
                    color = SheetTokens.TextLow
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            PathIcon(18.dp, AccentRed, PATH_CHECK)
        }
    }
}

@Composable
private fun VersionTag(label: String) {
    Box(
        Modifier
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = SheetTokens.TextMedium
        )
    }
}
