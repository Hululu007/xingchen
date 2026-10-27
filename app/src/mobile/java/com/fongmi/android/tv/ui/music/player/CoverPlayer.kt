package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlin.math.min

/**
 * 封面卡片主题 cover_immersive（瓜子 CoverImmersivePlayer，PlayerScreenKt.java:8339 起；
 * 数值认 gz-player-values.md §四与本轮 smali 逐行亲验）：
 * 纵列：间隔 42 → 歌名（高 30、左右 28、20sp/800/白 0.96/字距 −0.4/行高 26）→ 间隔 4
 * → 歌手（高 22、14sp/500/白 0.65/行高 18）→ 间隔 22 → 权重区（左右 24）封面槽
 * → 间隔 14 → 玻璃歌词条 → 底部间隔 12。点封面进展开态（工具行＋进度＋页内歌词窗），
 * 再点收起。根背景与歌词页手势归 PlayerRoot，本文件只画本主题面。
 */
@Composable
fun CoverPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onQueueClick: () -> Unit,
    onQualityClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.track
    var expanded by remember { mutableStateOf(false) }
    // 展开态歌词/信息两态（照 CD 详情区两态的同屋装配；封面卡本体未定谳、交卷注名）
    var showInfo by remember { mutableStateOf(false) }
    // 歌词模式与经典页同口径：读现行存档键、筹切换即回写（命令走中枢命令面，与经典页一致）
    var wordMode by remember { mutableStateOf(MusicPlayerSettings.isFullLyricWordByWord()) }
    val hasLyricWords = state.lyricLines.any { it.main.hasWords() }
    // coverBlurAlpha：收起 0→展开 1（remember 键名亲验、tween 250/bs4.a）；目标值本体未在源码定名，
    // 按展开布尔直取 0/1 装配（待真机对照瓜子本体）
    val blurAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(250, easing = EaseBs4A),
        label = "coverBlurAlpha"
    )

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(42.dp))
        // 歌名行（高 30、左右 28）
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = track?.displayTitle() ?: "",
                fontSize = 20.sp,
                fontWeight = FontWeight.W800,
                letterSpacing = (-0.4).sp,
                lineHeight = 26.sp,
                color = Color.White.copy(alpha = 0.96f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        // 歌手行（高 22、左右 28）
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = track?.displayArtist() ?: "",
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(22.dp))

        if (!expanded) {
            // 权重区：封面槽（左右 24）→ 间隔 14 → 玻璃条
            Column(Modifier.weight(1f).fillMaxWidth()) {
                CoverSlot(
                    state = state,
                    expanded = false,
                    blurAlpha = blurAlpha,
                    onToggle = { expanded = true },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(14.dp))
                CoverLyricGlassBar(state, progress, Modifier.padding(horizontal = 24.dp))
            }
        } else {
            // 展开态（结构推定、待真机对照：瓜子展开 lambda 内含页内歌词窗＋四键行＋Scrubber，
            // 纵列次序按其构成装配）：封面槽（四键压卡内底部）→ 间隔 12 → Scrubber → 歌词窗
            Column(Modifier.weight(1f).fillMaxWidth()) {
                CoverSlot(
                    state = state,
                    expanded = true,
                    blurAlpha = blurAlpha,
                    onToggle = { expanded = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    ExpandedToolRow(
                        state = state,
                        infoSelected = showInfo,
                        onToggleInfo = { showInfo = !showInfo },
                        onQueueClick = onQueueClick
                    )
                }
                Spacer(Modifier.height(12.dp))
                PlaybackScrubber(
                    progress = progress,
                    audioLabel = state.audioLabel,
                    lyricLabel = coverLyricChipLabel(state, wordMode),
                    onSeek = { PlayerStateHolder.seekTo(it) },
                    onQualityClick = onQualityClick,
                    onLyricChipClick = {
                        if (hasLyricWords) {
                            wordMode = !wordMode
                            MusicPlayerSettings.setFullLyricWordByWord(wordMode)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                if (showInfo) {
                    SongInfoFace(
                        state = state,
                        colors = SongInfoColors(
                            high = Color.White.copy(alpha = 0.96f),
                            medium = Color.White.copy(alpha = 0.65f),
                            low = Color.White.copy(alpha = 0.42f),
                            inset = Color.White.copy(alpha = 0.10f),
                            accent = Color.White.copy(alpha = 0.92f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    )
                } else {
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
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 封面槽：收起居中横卡；展开满宽。卡几何（§四定谳）：宽＝min(可用宽×0.98, 可用高/0.65)、
 * 高＝宽×0.65、圆角 22；卡身动画 tween(250, bs4.a)、错位步进 snap(0)。
 * 黑罩竖渐变（smali 亲验 stop：0→.18／0.4→.26／0.75→.40／1.0→.50）。
 * 展开面 BackdropCard：渐变 #3A3A3C→#1C1C1E＋内铺模糊封面（alpha＝blurAlpha）＋黑 0.35。
 * 本体 alpha 梯队（0.99→0.01）是多卡错位队列的层间衰减；星辰单卡取队首 0.99。
 */
@Composable
private fun CoverSlot(
    state: PlayerUiState,
    expanded: Boolean,
    blurAlpha: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandedOverlay: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(modifier) {
        val availW = maxWidth
        val availH = maxHeight
        val cardW = if (expanded) {
            availW
        } else {
            min(availW.value * COVER_W_RATIO, availH.value / COVER_ASPECT).dp
        }
        val cardH = cardW * COVER_ASPECT
        val shape = RoundedCornerShape(COVER_RADIUS_DP.dp)
        Box(
            Modifier
                .align(if (expanded) Alignment.TopCenter else Alignment.Center)
                .size(cardW, cardH)
                .alpha(CARD_HEAD_ALPHA)
                .clip(shape)
                .background(
                    if (expanded) {
                        Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)))
                    } else {
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f)))
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle
                )
        ) {
            if (expanded) {
                // BackdropCard 内铺：模糊封面（氛围缩图重糊近似——星辰无 RenderEffect 实时糊层，
                // 用中枢 64×64 氛围图拉满当糊面，口径差注名）＋黑 0.35
                if (state.ambientBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = state.ambientBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(blurAlpha)
                    )
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            } else {
                CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
                // 黑罩竖渐变（四 stop 亲验值）
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.18f),
                                0.4f to Color.Black.copy(alpha = 0.26f),
                                0.75f to Color.Black.copy(alpha = 0.40f),
                                1f to Color.Black.copy(alpha = 0.50f)
                            )
                        )
                    )
                )
            }
            if (expanded && expandedOverlay != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    expandedOverlay()
                }
            }
        }
    }
}

/**
 * 展开态工具行（§四）：四枚 LiquidGlassButton 42dp（图标 22、面白 0.14、歌曲信息选中 0.28），
 * 行间距 24、内边距 10；序＝上一首/播放列表/歌曲信息/下一首。键面无背板折射层时以白 0.14 近似
 * （同全屏页玻璃键口径，交卷注名）。歌曲信息键本单无选中态来源，按未选中画、动作走回调槽。
 */
@Composable
private fun ExpandedToolRow(
    state: PlayerUiState,
    infoSelected: Boolean,
    onToggleInfo: () -> Unit,
    onQueueClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
            24.dp, Alignment.CenterHorizontally
        )
    ) {
        LiquidToolButton(enabled = state.queueIndex > 0, onClick = { PlayerStateHolder.prev() }) {
            PathIcon(22.dp, Color.White, PATH_PREV)
        }
        LiquidToolButton(enabled = true, onClick = onQueueClick) {
            QueueIcon(22.dp, Color.White)
        }
        LiquidToolButton(enabled = true, selected = infoSelected, onClick = onToggleInfo) {
            InfoIcon(22.dp, Color.White)
        }
        LiquidToolButton(enabled = state.canNext, onClick = { PlayerStateHolder.next() }) {
            PathIcon(22.dp, Color.White, PATH_NEXT)
        }
    }
}

@Composable
private fun LiquidToolButton(
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(42.dp)
            .pressScale(0.9f)
            .background(
                Color.White.copy(alpha = if (selected) 0.28f else 0.14f),
                RoundedCornerShape(percent = 50)
            )
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 收起态玻璃歌词条 CoverLyricGlassBar（PlayerScreenKt.java:9495）：高 48、圆角 22、左右内边距 16；
 * 面＝基底 #FF1E1E22＋白渐变 0.26→0.08＋描边 1dp 白 0.12＋阴影 4（星辰无背板折射层，面以此近似，
 * 与全屏页玻璃键同口径）；左收藏 40 区/图标 20；中单行歌词 13sp/500/白 0.92 居中（无逐字）；
 * 右播/停 40 区/图标 24（加载圈 22 白 0.8——中枢无加载态信号，本单不落、注名待补）。
 */
@Composable
private fun CoverLyricGlassBar(
    state: PlayerUiState,
    progress: PlayerProgress,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    val lineText = currentLineText(state, progress)
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(4.dp, shape)
            .background(Color(0xFF1E1E22), shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.08f))
                ),
                shape
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左：收藏（40 区、图标 20；已收藏白 1.0、未收藏 0.85、不可用 0.45）
        Box(
            Modifier
                .width(40.dp)
                .pressScale(0.9f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { PlayerStateHolder.toggleFavorite() }
                ),
            contentAlignment = Alignment.Center
        ) {
            HeartIcon(
                20.dp,
                if (state.isFavorite) Color.White else Color.White.copy(alpha = 0.85f),
                filled = state.isFavorite
            )
        }
        // 中：当前行纯文本（13sp/500/白 0.92；空 0.45）
        Text(
            text = lineText ?: "",
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
            color = Color.White.copy(alpha = if (lineText != null) 0.92f else 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        // 右：播放/暂停（40 区、图标 24）
        Box(
            Modifier
                .width(40.dp)
                .pressScale(0.9f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { PlayerStateHolder.toggle() }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (state.isPlaying) {
                PauseIcon(24.dp, Color.White)
            } else {
                PlayIcon(24.dp, Color.White)
            }
        }
    }
}

/**
 * 当前行纯文本（玻璃条中段）：按最近一帧进度＋歌词偏移二分定位（条上文本与中枢 ticker 同步、
 * 不跑平滑时钟——瓜子条面本就是状态流里的当前行文本，此处以同源数据同口径定位）。
 * 无行/纯文本无当前行概念时：纯文本取首行、有行未到首行取首行，均无 → null（空态 0.45）。
 */
private fun currentLineText(state: PlayerUiState, progress: PlayerProgress): String? {
    val lines = state.lyricLines
    if (lines.isEmpty()) return null
    if (state.lyricUntimed) return lines.first().main.text
    val posMs = progress.positionMs - MusicPlayerSettings.getLyricOffsetMs()
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].main.timeMs <= posMs) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    val idx = if (ans >= 0) ans else 0
    return lines[idx].main.text
}

/** 展开态 Scrubber 歌词筹签：与经典页 lyricChipLabel 同口径（ClassicPlayer 私有、本处镜像同逻辑）。 */
private fun coverLyricChipLabel(state: PlayerUiState, wordMode: Boolean): String? {
    if (state.track == null) return null
    if (state.lyricLines.isEmpty()) return "无词"
    if (state.lyricUntimed) return "纯文本"
    if (state.lyricLines.none { it.main.hasWords() }) return "逐行"
    return if (wordMode) "逐字" else "标准"
}

private const val COVER_W_RATIO = 0.98f
private const val COVER_ASPECT = 0.65f
private const val COVER_RADIUS_DP = 22
private const val CARD_HEAD_ALPHA = 0.99f
