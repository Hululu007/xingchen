package com.fongmi.android.tv.ui.music.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/**
 * 全屏歌词页（gz-player-values.md §八＋§十四）：封面拉满整屏垫底＋黑罩；
 * 头行＝经典 SongMeta 同尺寸（歌名＋歌手♡专辑）＋右三裸键（队列/歌词样式/播停，40dp 盒）；
 * 歌词舞台全屏装配（前 3/后 15、B 组模糊、顶淡 40dp、横边距 28）；
 * 底部 Std/逐字双筹＋版本筹＋细进度线（轨 2）；样式面板同树深色玻璃覆盖层。
 */
@Composable
fun FullLyricPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onQueueClick: () -> Unit,
    onLyricChipClick: () -> Unit
) {
    val track = state.track
    var stylePanel by remember { mutableStateOf(false) }
    var wordByWord by remember { mutableStateOf(MusicPlayerSettings.isFullLyricWordByWord()) }
    // 样式面板本地镜像（改动即时写回存档＋本地刷新，舞台随之重读）
    var fontSizeSp by remember { mutableFloatStateOf(MusicPlayerSettings.getFullLyricFontSize()) }
    var weightLevel by remember { mutableIntStateOf(MusicPlayerSettings.getLyricWeight()) }
    var alignMode by remember { mutableIntStateOf(MusicPlayerSettings.getLyricAlign()) }
    var blurLevel by remember { mutableIntStateOf(MusicStore.getCoverLyricBlurLevel()) }
    var offsetMs by remember { mutableIntStateOf(MusicPlayerSettings.getLyricOffsetMs()) }
    var primaryMode by remember { mutableIntStateOf(MusicPlayerSettings.getLyricPrimary()) }
    var showTranslation by remember { mutableStateOf(MusicPlayerSettings.isShowTranslation()) }
    var showRomanization by remember { mutableStateOf(MusicPlayerSettings.isShowRomanization()) }

    Box(Modifier.fillMaxSize()) {
        CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 头行：左 SongMeta（经典同尺寸）、右三裸键
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.height(24.dp), contentAlignment = Alignment.CenterStart) {
                        AnimatedSongTitle(
                            text = track?.displayTitle()?.takeIf { it.isNotEmpty() }
                                ?: "未在播放",
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
                            text = track?.displayArtist() ?: "",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W500,
                            lineHeight = 18.sp,
                            color = Color.White.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .pressScale(0.96f)
                                .clickableNoRipple {
                                    // 歌手点搜由根层处理；此处与经典同位保留点击面
                                }
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 7.dp)
                                .pressScale(0.85f)
                                .clickableNoRipple { PlayerStateHolder.toggleFavorite() },
                            contentAlignment = Alignment.Center
                        ) {
                            HeartIcon(
                                13.dp,
                                Color.White.copy(alpha = if (state.isFavorite) 0.85f else 0.45f),
                                filled = state.isFavorite
                            )
                        }
                        val album = track?.displayAlbum() ?: ""
                        if (album.isNotEmpty()) {
                            Text(
                                text = album,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.W500,
                                lineHeight = 18.sp,
                                color = Color.White.copy(alpha = 0.45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple(onClick = onQueueClick),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(20.dp, Color.White.copy(alpha = 0.9f), GZ_QUEUE_PATH)
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple { stylePanel = true },
                    contentAlignment = Alignment.Center
                ) {
                    LyricStyleGlyph(20.dp, Color.White.copy(alpha = 0.9f))
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .pressScale(0.88f)
                        .clickableNoRipple { PlayerStateHolder.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(
                        if (state.isPlaying) 28.dp else 25.dp,
                        Color.White.copy(alpha = 0.95f),
                        if (state.isPlaying) GZ_PAUSE_PATH else GZ_PLAY_PATH
                    )
                }
            }
            LyricStage(
                lines = state.lyricLines,
                untimed = state.lyricUntimed,
                wordByWord = wordByWord,
                fontSizeSp = fontSizeSp,
                lineHeightFactor = 1.3f,
                lineGapDp = 6f,
                fullAnchor = true,
                anchorFraction = 0f,
                windowBefore = 3,
                windowAfter = 15,
                blurGroupB = true,
                blurCoefficient = MusicPlayerSettings.blurCoefficient(),
                palette = LyricPalette.ImmersiveDark,
                showTranslation = showTranslation,
                alignMode = alignMode,
                weightLevel = weightLevel,
                lyricOffsetMs = offsetMs.toLong(),
                maxVisualLines = 0,
                topFadeDp = 40f,
                letterSpacingEm = -0.2f / fontSizeSp,
                onLineSeek = { PlayerStateHolder.seekTo(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 28.dp)
            )
            // 底部：双筹＋版本筹＋细进度线
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LyricModeChip(
                    text = "逐字",
                    selected = wordByWord,
                    onClick = {
                        wordByWord = true
                        MusicPlayerSettings.setFullLyricWordByWord(true)
                    }
                )
                LyricModeChip(
                    text = "逐行",
                    selected = !wordByWord,
                    onClick = {
                        wordByWord = false
                        MusicPlayerSettings.setFullLyricWordByWord(false)
                    }
                )
                Spacer(Modifier.weight(1f))
                LyricModeChip(
                    text = "版本",
                    selected = false,
                    onClick = onLyricChipClick
                )
            }
            Spacer(Modifier.height(8.dp))
            PlaybackScrubber(
                progress = progress,
                audioLabel = null,
                lyricLabel = null,
                onSeek = { PlayerStateHolder.seekTo(it) },
                onQualityClick = {},
                onLyricChipClick = {},
                modifier = Modifier.padding(horizontal = 28.dp),
                trackHeightDp = 2f,
                showTopBox = false,
                showBottomRow = false
            )
            Spacer(Modifier.height(16.dp))
        }
        if (stylePanel) {
            LyricStylePanel(
                fontSizeSp = fontSizeSp,
                weightLevel = weightLevel,
                alignMode = alignMode,
                blurLevel = blurLevel,
                offsetMs = offsetMs,
                primaryMode = primaryMode,
                showTranslation = showTranslation,
                showRomanization = showRomanization,
                onFontSize = {
                    fontSizeSp = it
                    MusicStore.setFullLyricFontSize(it)
                },
                onWeight = {
                    weightLevel = it
                    MusicStore.setLyricWeight(it)
                },
                onAlign = {
                    alignMode = it
                    MusicStore.setLyricAlign(it)
                },
                onBlur = {
                    blurLevel = it
                    MusicStore.setCoverLyricBlurLevel(it)
                },
                onOffset = {
                    offsetMs = it
                    MusicStore.setLyricOffsetMs(it)
                },
                onPrimary = {
                    primaryMode = it
                    MusicStore.setLyricPrimary(it)
                },
                onTranslation = {
                    showTranslation = it
                    MusicStore.setShowTranslation(it)
                },
                onRomanization = {
                    showRomanization = it
                    MusicStore.setShowRomanization(it)
                },
                onClose = { stylePanel = false }
            )
        }
    }
}

/** 模式筹（§十四 LyricStyleChip：高 34、圆角 8、选中白 .18＋边 .32、未选 .04＋边 .08、字 12sp）。 */
@Composable
private fun LyricModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                if (selected) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(8.dp)
            )
            .pressScale(0.96f)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = if (selected) 0.95f else 0.6f)
        )
    }
}

/** 歌词样式面板（§十四，深色玻璃同树覆盖层）：字号/粗细/对齐/模糊/偏移/主歌词/翻译/音译。 */
@Composable
private fun LyricStylePanel(
    fontSizeSp: Float,
    weightLevel: Int,
    alignMode: Int,
    blurLevel: Int,
    offsetMs: Int,
    primaryMode: Int,
    showTranslation: Boolean,
    showRomanization: Boolean,
    onFontSize: (Float) -> Unit,
    onWeight: (Int) -> Unit,
    onAlign: (Int) -> Unit,
    onBlur: (Int) -> Unit,
    onOffset: (Int) -> Unit,
    onPrimary: (Int) -> Unit,
    onTranslation: (Boolean) -> Unit,
    onRomanization: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickableNoRipple(onClick = onClose)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SheetTokens.PanelBg)
                .border(1.dp, SheetTokens.Border, RoundedCornerShape(24.dp))
                .clickableNoRipple {}
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "歌词样式",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                    color = SheetTokens.TextHigh,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .pressScale(0.9f)
                        .clickableNoRipple(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    PathIcon(16.dp, SheetTokens.TextMedium, PATH_CLOSE)
                }
            }
            Spacer(Modifier.height(12.dp))
            // 字号步进（A 16sp、盒 32×24、钳 16–40）
            StyleRow(label = "字号") {
                StepButton(text = "−") {
                    onFontSize((fontSizeSp - 1f).coerceIn(16f, 40f))
                }
                Text(
                    text = "A",
                    fontSize = 16.sp,
                    color = SheetTokens.TextHigh,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                Text(
                    text = fontSizeSp.toInt().toString(),
                    fontSize = 13.sp,
                    color = SheetTokens.TextMedium,
                    modifier = Modifier.padding(end = 10.dp)
                )
                StepButton(text = "+") {
                    onFontSize((fontSizeSp + 1f).coerceIn(16f, 40f))
                }
            }
            StyleRow(label = "粗细") {
                LyricModeChip("标准", weightLevel == 0) { onWeight(0) }
                Spacer(Modifier.width(8.dp))
                LyricModeChip("加粗", weightLevel == 1) { onWeight(1) }
                Spacer(Modifier.width(8.dp))
                LyricModeChip("特粗", weightLevel == 2) { onWeight(2) }
            }
            StyleRow(label = "对齐") {
                LyricModeChip("居左", alignMode == 0) { onAlign(0) }
                Spacer(Modifier.width(8.dp))
                LyricModeChip("居中", alignMode == 1) { onAlign(1) }
            }
            StyleRow(label = "模糊") {
                LyricModeChip("关", blurLevel == 0) { onBlur(0) }
                Spacer(Modifier.width(8.dp))
                LyricModeChip("轻", blurLevel == 1) { onBlur(1) }
                Spacer(Modifier.width(8.dp))
                LyricModeChip("中", blurLevel == 2) { onBlur(2) }
            }
            StyleRow(label = "歌词偏移") {
                StepButton(text = "−") {
                    onOffset((offsetMs - 100).coerceIn(-3000, 3000))
                }
                Text(
                    text = when {
                        offsetMs > 0 -> "+${offsetMs / 1000f}s"
                        offsetMs < 0 -> "${offsetMs / 1000f}s"
                        else -> "0s"
                    },
                    fontSize = 13.sp,
                    color = SheetTokens.TextHigh,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                StepButton(text = "+") {
                    onOffset((offsetMs + 100).coerceIn(-3000, 3000))
                }
            }
            StyleRow(label = "主歌词") {
                LyricModeChip("原文", primaryMode == 0) { onPrimary(0) }
                Spacer(Modifier.width(8.dp))
                LyricModeChip("翻译", primaryMode == 1) { onPrimary(1) }
                Spacer(Modifier.width(8.dp))
                LyricModeChip("音译", primaryMode == 2) { onPrimary(2) }
            }
            StyleRow(label = "显示翻译") {
                LyricModeChip(if (showTranslation) "开" else "关", showTranslation) {
                    onTranslation(!showTranslation)
                }
            }
            StyleRow(label = "显示音译") {
                LyricModeChip(if (showRomanization) "开" else "关", showRomanization) {
                    onRomanization(!showRomanization)
                }
            }
        }
    }
}

@Composable
private fun StyleRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = SheetTokens.TextMedium,
            modifier = Modifier.width(72.dp)
        )
        content()
    }
}

/** 步进键（§十四：盒 32×24）。 */
@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 32.dp, height = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .pressScale(0.92f)
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 14.sp, color = SheetTokens.TextHigh)
    }
}
