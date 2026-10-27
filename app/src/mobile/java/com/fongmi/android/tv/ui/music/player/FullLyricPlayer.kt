package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/**
 * 全屏歌词面（PlayerRoot 同树内一页、播放页左划进、右划/返回回播放页；
 * 构成照瓜子案卷第八节，不另起 Activity、不另起转场体系）：
 * 根＝封面主色 450ms 动画底＋氛围层（与经典页同件 AmbientLayer、同一份状态层
 * 位图）＋原封面拉满整页垫底、0.78 起向下溶进根底色（经典 CoverLayer 同口径，
 * 本面不带切歌转场/竖滑手势）→歌词舞台铺满整屏（歌词行可穿标题之下）→
 * 歌名行（右三裸键＝队列/歌词样式/播放暂停，定案图 113209/113250 与案卷第八节
 * 口径、全屏专属行 FullTitleRow——与经典页的菜单/队列/播停不同，故不共用经典页
 * 的 TitleRow，那件一字不动；下滑回播放页）＋歌手♡专辑行（与经典页同件 ArtistRow）
 * 压在上层→底部只有 Std/逐字双筹＋细进度线，无页眉、无播放页进度栈。
 *
 * 歌词走与页内同一台 AnchoredLyricStage 引擎，只喂全屏口径（LyricStageSpec）：
 * 全屏字号键（默认 24、钳 16–40）、行高 ×1.3、字距 −0.2、行距 6dp、行横边距 28dp、
 * 像素锚点公式、逐行模糊 B 组（引擎内共用）；窗口前 3/后 15 由引擎按视口裁行等价覆盖。
 * 双筹：Std＝当前行整行上色、逐字＝逐字推进，切换写存档（MusicPlayerSettings #16）
 * 并当场换引擎档位；细进度线只读显示进度、不可拖（线高与 alpha 是装配值，
 * 瓜子该线数值未抠到，见交卷）。
 */
@Composable
fun FullLyricPlayer(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTools: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = Color(state.dominantColor),
        animationSpec = tween(450, easing = Bs4A),
        label = "fullRootBg",
    )
    // 全屏字号键以中枢修订号为键重读：歌词样式面板（全屏面）改完 bump 即当场生效
    val settingsRev by PlayerStateHolder.settingsRev.collectAsState()
    val fullFontSizeSp = remember(settingsRev) { MusicPlayerSettings.getFullLyricFontSize() }
    var wordMode by remember { mutableStateOf(MusicPlayerSettings.isFullLyricWordByWord()) }

    fun setWordMode(word: Boolean) {
        if (wordMode == word) return
        wordMode = word
        MusicPlayerSettings.setFullLyricWordByWord(word)
    }

    Box(modifier = modifier.fillMaxSize().background(bgColor)) {
        val bmp = state.coverBitmap
        if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
            // 氛围层（经典页同件、同一份位图派生）
            AmbientLayer(bitmap = bmp, modifier = Modifier.fillMaxSize())
            // 锐封面整页垫底、向下溶（0.78 起溶进根底色，经典 CoverLayer 同数）
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
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

        // 歌词舞台（铺满整屏、行横边距 28dp；与页内同一台引擎、全屏喂参）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
        ) {
            when (val lyrics = state.lyrics) {
                is LyricUiState.Content -> {
                    if (lyrics.lines.isEmpty()) {
                        LyricHint(text = "暂无歌词", modifier = Modifier.align(Alignment.Center))
                    } else {
                        AnchoredLyricStage(
                            lines = lyrics.lines,
                            timed = lyrics.timed,
                            positionMs = state.positionMs,
                            isPlaying = state.isPlaying,
                            onSeekLine = onSeek,
                            spec = LyricStageSpec(
                                fontSizeSp = fullFontSizeSp,
                                lineHeightFactor = 1.3f,
                                lineGapDp = 6f,
                                letterSpacingSp = -0.2f,
                                pixelAnchor = true,
                                wordByWord = wordMode,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                LyricUiState.Searching -> LyricHint(
                    text = "正在找歌词",
                    modifier = Modifier.align(Alignment.Center),
                )
                LyricUiState.None -> LyricHint(
                    text = "暂无歌词",
                    modifier = Modifier.align(Alignment.Center),
                )
                LyricUiState.Idle -> Unit
            }
        }

        // 歌名行＋歌手行（覆盖层、无页眉；歌名行是全屏专属行、歌手行与经典页同件）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            FullTitleRow(
                state = state,
                onToggle = onToggle,
                onOpenQueue = onOpenQueue,
                onOpenLyricStyle = onOpenTools,
                onBack = onBack,
            )
            ArtistRow(state = state)
        }

        // 底部：Std/逐字双筹＋细进度线（只这两样）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeChip(text = "Std", selected = !wordMode) { setWordMode(false) }
                Spacer(modifier = Modifier.width(6.dp))
                ModeChip(text = "逐字", selected = wordMode) { setWordMode(true) }
            }
            Spacer(modifier = Modifier.height(10.dp))
            ThinProgressLine(state = state)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 底部模式筹（度量照 PlaybackScrubber 的筹：全圆胶囊、内边距 12×5、11sp/700/字距 0.6、白 .14 面＋描边；选中全亮、未选压暗 0.55——旧全屏页同口径）。 */
@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .alpha(if (selected) 1f else 0.55f)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
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

/**
 * 全屏歌词面专属歌名行（与经典页 TitleRow 同度量：行高 28dp、标题字阶同值、
 * 键盒 40dp 贴连、标题行下滑 48dp 回播放页）：右三裸键按定案图（113209/113250）
 * 与案卷第八节＝队列/歌词样式/播放暂停，与经典页的菜单/队列/播停不同，
 * 故本面不共用 TitleRow（经典页那件一字不动）。
 * 歌词样式键接现行入口：开工具面板（PlayerRoot 的 SheetKind.TOOLS），其内已有
 * 「歌词样式」入口位；独立歌词样式子面板未落前先走这条，不另造一套。
 * 歌词样式键字形用裸「A」（歌词样式面板字号行同用「A」记号，案卷第十四节）。
 */
@Composable
private fun FullTitleRow(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyricStyle: () -> Unit,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current
    val backTriggerPx = with(density) { 48.dp.toPx() }
    var pullDown = remember { mutableFloatStateOf(0f) }
    val playIconSize by animateDpAsState(
        targetValue = if (state.isPlaying) 28.dp else 25.dp,
        animationSpec = tween(300, easing = Bs4A),
        label = "fullPlayIconSize",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { pullDown.floatValue = 0f },
                    onDragEnd = {
                        if (pullDown.floatValue >= backTriggerPx) onBack()
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
        // 队列 20dp
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
        // 歌词样式（裸字形「A」，点开工具面板内的歌词样式入口——现行入口）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable { onOpenLyricStyle() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        // 播放/暂停（尺寸随态 28/25，300ms 过渡，与经典页同口径）
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

/** 细进度线：只读、不可拖（无手势）。底轨白 .18、已播白 .75、高 2dp 为装配值（瓜子该线数值未抠到）。 */
@Composable
private fun ThinProgressLine(state: PlayerUiState, modifier: Modifier = Modifier) {
    val fraction = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(modifier = modifier.fillMaxWidth().height(2.dp)) {
        val midY = size.height / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = size.height,
        )
        if (fraction > 0f) {
            drawLine(
                color = Color.White.copy(alpha = 0.75f),
                start = Offset(0f, midY),
                end = Offset(size.width * fraction, midY),
                strokeWidth = size.height,
            )
        }
    }
}
