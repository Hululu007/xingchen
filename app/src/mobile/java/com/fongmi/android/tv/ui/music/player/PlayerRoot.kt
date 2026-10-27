package com.fongmi.android.tv.ui.music.player

import android.content.Intent
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.fongmi.android.tv.ui.activity.MusicSearchActivity
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 播放页树根（条与页一棵树）：根底色＝中枢 baseColor，450ms/bs4.a 动画（瓜子外壳口径）。
 * 有条矩形时做同体形变：整页按全尺寸画进一个从条矩形长开的窗口里反向偏移裁切——
 * 内容不重排、封面/歌名/进度全程在原位连续；条本体（同一份状态的静态副本）压顶交叉淡出。
 * 长开 380ms、收回 320ms（星辰自有形态，无瓜子对应值，曲线借 bs4.a）。
 * 系统返回走收回动画后 finish；无条矩形（通知等直开）整页直显。
 * 主题分发（照瓜子 PlayerScreen 的主题 switch）：classic / cover_immersive / apple_music 走
 * 根的 pager 容器（左划进全屏歌词页）；luminous_cd（含深/自动变体）与 record_shelf 是独立页、
 * 无根歌词页（各自内部歌词面）。主题存档读 MusicPlayerSettings.getTheme（回落 classic）。
 * 工具面板（PlayerToolsSheet）挂根覆盖层：各主题明面入口开同一面板，主题子面板点卡即换主题；
 * 队列/音质/歌词版本等面板本体是后续单，各主题对应键先留空槽（与经典页既有槽位同口径）。
 * 全屏歌词页与播放页同在本树：播放页左划进（歌词页在右）、歌词页右划或系统返回退回，
 * 共用同一份中枢状态与歌词引擎；横向页滑过渡 300ms/bs4.a 是星辰自有形态（瓜子以 pagerCrossfade
 * 交叉淡入组页、无对应横滑值——根页机制本单不改，沿现行形态）。
 */
@Composable
fun PlayerRoot(barRect: Rect?, onClose: () -> Unit) {
    val state by PlayerStateHolder.state.collectAsState()
    val progress by PlayerStateHolder.progress.collectAsState()
    val context = LocalContext.current
    val baseColor by animateColorAsState(
        targetValue = Color(state.baseColor),
        animationSpec = tween(450, easing = EaseBs4A),
        label = "rootBaseColor"
    )
    val scope = rememberCoroutineScope()
    val expand = remember { Animatable(if (barRect == null) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf(MusicPlayerSettings.getTheme()) }
    var toolsOpen by remember { mutableStateOf(false) }
    var toolsPanel by remember { mutableStateOf(ToolsSubPanel.MAIN) }

    // 根歌词页只对经典/封面/Apple 开放（CD/唱片架在瓜子无 pager 歌词页，各自内部处理）
    val lyricsAllowed = theme == MusicPlayerSettings.THEME_CLASSIC ||
        theme == MusicPlayerSettings.THEME_COVER_IMMERSIVE ||
        theme == MusicPlayerSettings.THEME_APPLE_MUSIC
    LaunchedEffect(theme) {
        if (!lyricsAllowed && showLyrics) showLyrics = false
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
            if (barRect != null) {
                expand.animateTo(0f, tween(320, easing = EaseBs4A))
            }
            onClose()
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
    BackHandler(enabled = !closing && !showLyrics) { close() }
    // 全屏歌词页在场时，系统返回先退回播放页（与上行使能互斥，不会双响）
    BackHandler(enabled = showLyrics && !closing) { showLyrics = false }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(baseColor)
    ) {
        // 先把全尺寸摘出来：内层 Box 的作用域会遮住 BoxWithConstraints 的隐式接收者
        val scopeW = maxWidth
        val scopeH = maxHeight
        val p = expand.value
        if (barRect == null || p >= 1f) {
            // 页间横滑（他亲定方向：歌词页在播放页右侧，左划进、右划退）：
            // 只认横向占优的拖动；起点落在底部 120dp（进度/筹区）内不算翻页，不抢进度拖动。
            // 工具面板开着时不翻页；主题不在根 pager 容器内（CD/唱片架）时不翻页。
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(showLyrics, toolsOpen, lyricsAllowed) {
                        awaitEachGesture {
                            if (toolsOpen) return@awaitEachGesture
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
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = {
                        if (targetState) {
                            (slideInHorizontally(tween(300, easing = EaseBs4A)) { it } +
                                fadeIn(tween(300, easing = EaseBs4A))) togetherWith
                                (slideOutHorizontally(tween(300, easing = EaseBs4A)) { -it } +
                                    fadeOut(tween(300, easing = EaseBs4A)))
                        } else {
                            (slideInHorizontally(tween(300, easing = EaseBs4A)) { -it } +
                                fadeIn(tween(300, easing = EaseBs4A))) togetherWith
                                (slideOutHorizontally(tween(300, easing = EaseBs4A)) { it } +
                                    fadeOut(tween(300, easing = EaseBs4A)))
                        }
                    },
                    label = "playerPages"
                ) { lyrics ->
                    if (lyrics) {
                        FullLyricPlayer(
                            state = state,
                            progress = progress,
                            onLyricStyleClick = {}
                        )
                    } else {
                        ThemedPlayer(
                            state = state,
                            progress = progress,
                            theme = theme,
                            onOpenTools = { openTools() },
                            onClosePage = { close() },
                            onShowLyrics = { showLyrics = true },
                            onArtistSearch = { artistSearch(it) },
                            onThemeSelected = { selectTheme(it) }
                        )
                    }
                }
            }
        } else {
            val density = LocalDensity.current
            val fullW = with(density) { scopeW.toPx() }
            val fullH = with(density) { scopeH.toPx() }
            val curX = lerp(barRect.left.toFloat(), 0f, p)
            val curY = lerp(barRect.top.toFloat(), 0f, p)
            val curW = lerp(barRect.width().toFloat(), fullW, p)
            val curH = lerp(barRect.height().toFloat(), fullH, p)
            val corner = with(density) { (curH / 2f * (1f - p)).toDp() }
            // 生长窗口：全尺寸内容反向偏移，窗口外裁掉（内容不重排）
            Box(
                Modifier
                    .offset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
                    .size(with(density) { curW.toDp() }, with(density) { curH.toDp() })
                    .clip(RoundedCornerShape(corner))
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(-curX.roundToInt(), -curY.roundToInt()) }
                        .size(scopeW, scopeH)
                ) {
                    ThemedPlayer(
                        state = state,
                        progress = progress,
                        theme = theme,
                        onOpenTools = { openTools() },
                        onClosePage = { close() },
                        onShowLyrics = { showLyrics = true },
                        onArtistSearch = { artistSearch(it) },
                        onThemeSelected = { selectTheme(it) }
                    )
                }
            }
            // 条本体压顶：形变前段交叉淡出（收回时对称淡入，落点与真条重合）
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
        // 工具面板覆盖层（挂根末尾：面板开时返回先关面板——BackHandler 在下方最后注册、优先响）
        if (toolsOpen) {
            PlayerToolsSheet(
                state = state,
                subPanel = toolsPanel,
                onSubPanelChange = { toolsPanel = it },
                onClose = { toolsOpen = false },
                onThemeSelected = { selectTheme(it) }
            )
        }
    }
    BackHandler(enabled = toolsOpen && !closing) { toolsOpen = false }
}

/**
 * 主题分发（照瓜子 PlayerScreen 主题 switch 的页构成）：
 * classic/cover/apple 共用根 pager 容器；CD 三变体走同一 CdPlayer（mode 分发）；
 * record_shelf 独立页。队列/音质/歌词版本面板本体是后续单，对应键统一留空槽。
 */
@Composable
private fun ThemedPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    theme: String,
    onOpenTools: () -> Unit,
    onClosePage: () -> Unit,
    onShowLyrics: () -> Unit,
    onArtistSearch: (String) -> Unit,
    onThemeSelected: (String) -> Unit
) {
    when {
        theme == MusicPlayerSettings.THEME_COVER_IMMERSIVE -> CoverPlayer(
            state = state,
            progress = progress,
            onQueueClick = {},
            onQualityClick = {}
        )
        theme == MusicPlayerSettings.THEME_APPLE_MUSIC -> ApplePlayer(
            state = state,
            progress = progress,
            onToolsClick = onOpenTools,
            onQueueClick = {},
            onQualityClick = {},
            onLyricClick = onShowLyrics
        )
        MusicPlayerSettings.isCdTheme(theme) -> CdPlayer(
            state = state,
            progress = progress,
            mode = theme,
            onClose = onClosePage,
            onToolsClick = onOpenTools,
            onQueueClick = {},
            onQualityClick = {},
            onLyricVersionClick = {},
            onArtistSearch = onArtistSearch,
            onThemeSelected = onThemeSelected
        )
        theme == MusicPlayerSettings.THEME_RECORD_SHELF -> ShelfPlayer(
            state = state,
            progress = progress,
            onClose = onClosePage,
            onToolsClick = onOpenTools,
            onQueueClick = {}
        )
        else -> ClassicPlayer(
            state = state,
            progress = progress,
            onMenuClick = onOpenTools,
            onQueueClick = {},
            onArtistClick = { onArtistSearch(state.track?.displayArtist().orEmpty()) },
            onAlbumClick = {},
            onQualityClick = {},
            onLyricChipClick = {}
        )
    }
}
