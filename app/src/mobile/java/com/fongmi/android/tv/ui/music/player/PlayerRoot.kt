package com.fongmi.android.tv.ui.music.player

import android.graphics.Rect
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

private enum class SheetKind { QUEUE, TOOLS, LYRIC_VERSIONS, QUALITY }

/**
 * 播放树根（播放页 Activity 内唯一棵树）：订中枢一份快照，向下分发。
 *
 * 条⇄页同体开合（本单验收死线：不跳、不闪、封面/歌名/进度全程连续）的做法：
 * 进场时若带了条的窗口矩形，整页先被裁进那个矩形里（页面以全屏尺寸画、
 * 只露出矩形内的一块），矩形再动画长满全屏——页面内容从不重排、不重画，
 * 露出的部分连续变大；条本体（同一 Composable、同一份状态）在长大的
 * 前段压在最上层，交叉淡出。封面、歌名、进度在两端是同一份状态的同值，
 * 所以全程连续。收回走逆动画，完后才 finish（无系统转场兜底闪帧）。
 * 无矩形（通知点开）直接展开态。
 *
 * 页内横滑：同一棵树里挂一个两页 pager——0＝主题播放页（经典）、1＝全屏歌词面
 * （FullLyricPlayer，与页内共用同一台歌词引擎、同一份快照）。左划进歌词页、
 * 右划回（他亲定方向：歌词页在播放页右侧）；系统返回在歌词页先回播放页、
 * 在播放页才收回成条。不另起 Activity、不另起转场体系。
 *
 * 形变时长/曲线是容器形变的通行值（瓜子案卷未给此数，下单对照再抠），
 * 已单列在交卷未定项。
 */
@Composable
fun PlayerRoot(
    startRect: Rect?,
    onFinish: () -> Unit,
) {
    val state by PlayerStateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val progress = remember { Animatable(if (startRect == null) 1f else 0f) }
    var collapsing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    // 当前主题（存档键在 MusicPlayerSettings，回落 classic；切换写存档后整树按新主题分发）
    var themeId by remember { mutableStateOf(MusicPlayerSettings.getTheme()) }

    // 播放页不熄屏（存档 MusicStore 单一事实源）：初值与工具面板开关变更
    // （中枢 settingsRev）都经这里落到窗口旗——开关写存档＋bump 后即达。
    val rootContext = LocalContext.current
    val settingsRev by PlayerStateHolder.settingsRev.collectAsState()
    LaunchedEffect(settingsRev) {
        val window = rootContext.findActivity()?.window ?: return@LaunchedEffect
        if (MusicStore.isPlayerKeepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(Unit) {
        if (startRect != null) {
            progress.animateTo(1f, tween(380, easing = Bs4A))
        }
    }

    fun collapse() {
        if (collapsing) return
        collapsing = true
        scope.launch {
            if (startRect != null) {
                progress.animateTo(0f, tween(320, easing = Bs4A))
            }
            onFinish()
        }
    }

    val p = progress.value
    val expanded = p >= 0.999f

    // 页内横滑两页：0 播放页 / 1 全屏歌词面（同树、同快照，跟手过渡走 pager 本体）
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    BackHandler(enabled = !collapsing) {
        if (pagerState.currentPage == 1) {
            scope.launch { pagerState.animateScrollToPage(0) }
        } else {
            collapse()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fullW = constraints.maxWidth
        val fullH = constraints.maxHeight
        val fullWDp = with(density) { fullW.toDp() }
        val fullHDp = with(density) { fullH.toDp() }

        val page: @Composable () -> Unit = {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                if (pageIndex == 0) {
                    // 主题是纯函数快照分发：五主题共用同一份状态、同一台歌词引擎
                    when (themeFamilyOf(themeId)) {
                        ThemeFamily.CLASSIC -> ClassicPlayer(
                            state = state,
                            onToggle = { PlayerStateHolder.toggle() },
                            onNext = { PlayerStateHolder.next() },
                            onPrev = { PlayerStateHolder.prev() },
                            onSeek = { PlayerStateHolder.seekTo(it) },
                            onOpenQueue = { sheet = SheetKind.QUEUE },
                            onOpenTools = { sheet = SheetKind.TOOLS },
                            onOpenLyricVersions = { sheet = SheetKind.LYRIC_VERSIONS },
                            onOpenQuality = { sheet = SheetKind.QUALITY },
                            onCollapse = { collapse() },
                        )
                        ThemeFamily.COVER -> CoverImmersivePlayer(
                            state = state,
                            onToggle = { PlayerStateHolder.toggle() },
                            onNext = { PlayerStateHolder.next() },
                            onPrev = { PlayerStateHolder.prev() },
                            onSeek = { PlayerStateHolder.seekTo(it) },
                            onOpenQueue = { sheet = SheetKind.QUEUE },
                            onOpenTools = { sheet = SheetKind.TOOLS },
                            onOpenLyricVersions = { sheet = SheetKind.LYRIC_VERSIONS },
                            onOpenQuality = { sheet = SheetKind.QUALITY },
                            onToggleFavorite = { PlayerStateHolder.toggleFavorite() },
                            onCollapse = { collapse() },
                        )
                        ThemeFamily.APPLE -> AppleMusicPlayer(
                            state = state,
                            onToggle = { PlayerStateHolder.toggle() },
                            onNext = { PlayerStateHolder.next() },
                            onPrev = { PlayerStateHolder.prev() },
                            onSeek = { PlayerStateHolder.seekTo(it) },
                            onOpenQueue = { sheet = SheetKind.QUEUE },
                            onOpenTools = { sheet = SheetKind.TOOLS },
                            onOpenLyrics = {
                                scope.launch { pagerState.animateScrollToPage(1) }
                            },
                            onToggleFavorite = { PlayerStateHolder.toggleFavorite() },
                            onCollapse = { collapse() },
                        )
                        ThemeFamily.CD -> LuminousCdPlayer(
                            state = state,
                            surfaceThemeId = themeId,
                            onSurfaceThemeChange = { themeId = it },
                            onToggle = { PlayerStateHolder.toggle() },
                            onNext = { PlayerStateHolder.next() },
                            onPrev = { PlayerStateHolder.prev() },
                            onSeek = { PlayerStateHolder.seekTo(it) },
                            onOpenQueue = { sheet = SheetKind.QUEUE },
                            onOpenTools = { sheet = SheetKind.TOOLS },
                            onOpenLyricVersions = { sheet = SheetKind.LYRIC_VERSIONS },
                            onToggleFavorite = { PlayerStateHolder.toggleFavorite() },
                            onCycleMode = { PlayerStateHolder.cycleMode() },
                            onCollapse = { collapse() },
                        )
                        ThemeFamily.SHELF -> RecordShelfPlayer(
                            state = state,
                            onToggle = { PlayerStateHolder.toggle() },
                            onNext = { PlayerStateHolder.next() },
                            onPrev = { PlayerStateHolder.prev() },
                            onSeek = { PlayerStateHolder.seekTo(it) },
                            onOpenQueue = { sheet = SheetKind.QUEUE },
                            onOpenTools = { sheet = SheetKind.TOOLS },
                            onPlayAt = { PlayerStateHolder.playAt(it) },
                            onToggleFavorite = { PlayerStateHolder.toggleFavorite() },
                            onCycleMode = { PlayerStateHolder.cycleMode() },
                            onCollapse = { collapse() },
                        )
                    }
                } else {
                    FullLyricPlayer(
                        state = state,
                        onToggle = { PlayerStateHolder.toggle() },
                        onOpenQueue = { sheet = SheetKind.QUEUE },
                        onOpenTools = { sheet = SheetKind.TOOLS },
                        onSeek = { PlayerStateHolder.seekTo(it) },
                        onBack = { scope.launch { pagerState.animateScrollToPage(0) } },
                    )
                }
            }
        }

        if (startRect == null || expanded) {
            Box(modifier = Modifier.fillMaxSize()) { page() }
        } else {
            val left = lerp(startRect.left.toFloat(), 0f, p)
            val top = lerp(startRect.top.toFloat(), 0f, p)
            val right = lerp(startRect.right.toFloat(), fullW.toFloat(), p)
            val bottom = lerp(startRect.bottom.toFloat(), fullH.toFloat(), p)
            val wPx = (right - left).coerceAtLeast(1f)
            val hPx = (bottom - top).coerceAtLeast(1f)
            val radiusDp = (34f * (1f - p)).dp
            val surface = colorLerp(
                Color(0xF2333333),
                Color(state.dominantColor),
                ((p - 0.3f) / 0.7f).coerceIn(0f, 1f),
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .clip(RoundedCornerShape(radiusDp))
                    .background(surface),
            ) {
                // 页面以全屏尺寸画在长大的容器里、反向偏移对齐窗口，只露容器内一块
                val pageAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .requiredSize(fullWDp, fullHDp)
                        .offset { IntOffset(-left.roundToInt(), -top.roundToInt()) }
                        .alpha(pageAlpha),
                ) {
                    page()
                }
                // 条本体压顶，前段交叉淡出（同 Composable 同状态，与首页那条一模一样）
                val barAlpha = (1f - ((p - 0.2f) / 0.5f)).coerceIn(0f, 1f)
                if (barAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(barAlpha),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        PlayerBar(
                            state = state,
                            onOpen = {},
                        )
                    }
                }
                // 形变中挡手：动画没走完不许点进页面
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }

    if (expanded) {
        when (sheet) {
            SheetKind.QUEUE -> QueueSheet(
                state = state,
                onPlayAt = { index ->
                    PlayerStateHolder.playAt(index)
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
            SheetKind.TOOLS -> ToolsSheet(
                state = state,
                currentThemeId = themeId,
                onThemeSelected = { id ->
                    MusicPlayerSettings.setTheme(id)
                    themeId = MusicPlayerSettings.getTheme()
                    sheet = null
                },
                onOpenLyricVersions = { sheet = SheetKind.LYRIC_VERSIONS },
                onOpenQuality = { sheet = SheetKind.QUALITY },
                onDismiss = { sheet = null },
                fullLyricSurface = pagerState.currentPage == 1,
            )
            SheetKind.LYRIC_VERSIONS -> LyricVersionSheet(
                state = state,
                onPick = { version ->
                    PlayerStateHolder.applyLyricVersion(version)
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
            SheetKind.QUALITY -> QualitySheet(
                state = state,
                onDismiss = { sheet = null },
            )
            null -> Unit
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
