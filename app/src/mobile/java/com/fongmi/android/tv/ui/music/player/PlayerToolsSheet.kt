package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.music.MusicQueue
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/**
 * 播放工具面板（瓜子 PlayerToolsSheet，PlayerToolsSheetKt.java:2644；数值认 gz-player-values.md §九/§十）：
 * 子面板枚举照瓜子 ToolsSubPanel——本单落 Main＋Theme 两面，其余（Sleep/LyricStyle/Appearance/
 * OutputDevices）与 CustomCover（不搬）、付费锁、「一起听」不在本单，相关件动作一律留槽。
 * 容器照 GlassSheetPanel/GzBottomSheet 形态：顶圆角 16、横内边距 16、底 10、深 token 一套
 * （他定：面板底不用白底、用深色玻璃）——星辰以树内覆盖层装配保同体一棵树（ModalBottomSheet 是
 * 独立窗口、会断开合连续性，装配差注名）；scrim 浓度与滑入时长无瓜子定名值，取 0.45/300ms 注名。
 */
enum class ToolsSubPanel { MAIN, THEME, SLEEP, LYRIC_STYLE, APPEARANCE, OUTPUT_DEVICES }

@Composable
fun PlayerToolsSheet(
    state: PlayerUiState,
    subPanel: ToolsSubPanel,
    onSubPanelChange: (ToolsSubPanel) -> Unit,
    onClose: () -> Unit,
    onThemeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadClick: () -> Unit = {},
    onLyricTileClick: () -> Unit = {},
    onTimerClick: () -> Unit = {},
    onAddToPlaylistClick: () -> Unit = {},
    onSongInfoRowClick: () -> Unit = {},
    onEqClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onOutputDeviceClick: () -> Unit = {}
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(
        targetValue = if (shown) 0f else 1f,
        animationSpec = tween(300, easing = EaseBs4A),
        label = "toolsSlide"
    )
    Box(modifier.fillMaxSize()) {
        // 遮罩（点关）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * (1f - slide)))
                .clickableNoRipple(onClick = onClose)
        )
        // 面板本体（底部滑入；自身点击消费、不穿透遮罩）
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset(y = (slide * 420).dp)
                .shadow(16.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(
                    SheetTokens.PanelBg,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .clickableNoRipple {}
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp)
        ) {
            // 拖柄（sheetHandleColor 深 白 0.28）
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(SheetTokens.Handle, RoundedCornerShape(2.dp))
            )
            AnimatedContent(
                targetState = subPanel,
                transitionSpec = {
                    fadeIn(tween(180, easing = EaseBs4A)) togetherWith fadeOut(tween(120, easing = EaseBs4A))
                },
                label = "toolsSubPanel"
            ) { panel ->
                when (panel) {
                    ToolsSubPanel.THEME -> ThemePanel(
                        state = state,
                        onBack = { onSubPanelChange(ToolsSubPanel.MAIN) },
                        onClose = onClose,
                        onThemeSelected = onThemeSelected
                    )
                    else -> ToolsMainPanel(
                        state = state,
                        onOpenTheme = { onSubPanelChange(ToolsSubPanel.THEME) },
                        onDownloadClick = onDownloadClick,
                        onLyricTileClick = onLyricTileClick,
                        onTimerClick = onTimerClick,
                        onAddToPlaylistClick = onAddToPlaylistClick,
                        onSongInfoRowClick = onSongInfoRowClick,
                        onEqClick = onEqClick,
                        onAppearanceClick = onAppearanceClick,
                        onOutputDeviceClick = onOutputDeviceClick
                    )
                }
            }
        }
    }
}

// ================= 主面板（ToolsMainPanel:8323 构成） =================

@Composable
private fun ToolsMainPanel(
    state: PlayerUiState,
    onOpenTheme: () -> Unit,
    onDownloadClick: () -> Unit,
    onLyricTileClick: () -> Unit,
    onTimerClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onSongInfoRowClick: () -> Unit,
    onEqClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onOutputDeviceClick: () -> Unit
) {
    val track = state.track
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // SongInfoHero（封面 48 圆角 12、占位底 sheetCoverPlaceholder、回落图标 22 TextLow；
        // 歌名 16sp/字距 −0.3/行高 21、歌手 12sp/行高 16 TextMedium）
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(SheetTokens.CoverPlaceholder, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (state.coverBitmap != null) {
                    CoverImage(state, ContentScale.Crop, Modifier.size(48.dp))
                } else {
                    QueueIcon(22.dp, SheetTokens.TextLow)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = track?.displayTitle() ?: "",
                    fontSize = 16.sp,
                    letterSpacing = (-0.3).sp,
                    lineHeight = 21.sp,
                    color = SheetTokens.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track?.displayArtist() ?: "",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = SheetTokens.TextMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // 快捷瓷砖行（间距 8、每砖 weight 1；砖体 56×56 圆角 16 底 CardBg 阴影 2、图标 26、标题 12 居中）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            QuickTile("下载", Modifier.weight(1f), onDownloadClick) {
                PathIcon(26.dp, SheetTokens.TextHigh, PATH_DOWNLOAD)
            }
            QuickTile("歌词", Modifier.weight(1f), onLyricTileClick) {
                LyricStyleGlyph(26.dp, SheetTokens.TextHigh)
            }
            QuickTile("定时", Modifier.weight(1f), onTimerClick) {
                PathIcon(26.dp, SheetTokens.TextHigh, PATH_TIMER)
            }
            QuickTile("加到歌单", Modifier.weight(1f), onAddToPlaylistClick) {
                PathIcon(26.dp, SheetTokens.TextHigh, PATH_PLAYLIST_ADD)
            }
        }
        Spacer(Modifier.height(16.dp))
        // 播放模式四段（M3Segmented 本体口径见 PlayModeSegmented 注）
        PlayModeSegmented(currentMode = state.playMode)
        Spacer(Modifier.height(20.dp))
        // SettingsListCard（圆角 16、阴影 2、底 CardBg、边 toolsBorder；分隔线左内边距 16 高 0.5）
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(16.dp))
                .background(SheetTokens.CardBg, RoundedCornerShape(16.dp))
                .border(1.dp, SheetTokens.Border, RoundedCornerShape(16.dp))
        ) {
            SettingsRow(title = "歌曲信息", onClick = onSongInfoRowClick)
            SheetDivider()
            // 功能网格（ToolsGridEntry：高 48、内边距 16×12、行间距 8、图标 20、标题 13/18）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                GridEntry("均衡器", Modifier.weight(1f), onEqClick) {
                    PathIcon(20.dp, SheetTokens.TextHigh, PATH_TUNE)
                }
                GridEntry("播放页主题", Modifier.weight(1f), onOpenTheme) {
                    PathIcon(20.dp, SheetTokens.TextHigh, PATH_REORDER)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                GridEntry("外观设置", Modifier.weight(1f), onAppearanceClick) {
                    AutoSurfaceIcon(20.dp, SheetTokens.TextHigh)
                }
                Spacer(Modifier.weight(1f))
            }
            SheetDivider()
            // 开关行（存档键未立、本单本地态留槽——后续单接真键；开关本体按玻璃口径画）
            var keepScreenOn by remember { mutableStateOf(false) }
            var leftHand by remember { mutableStateOf(false) }
            SwitchRow("播放页不熄屏", keepScreenOn) { keepScreenOn = it }
            SwitchRow("左手布局（横屏）", leftHand) { leftHand = it }
            SheetDivider()
            SystemVolumeRow(onOutputDeviceClick)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun QuickTile(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .pressScale(0.96f)
                .shadow(2.dp, RoundedCornerShape(16.dp))
                .background(SheetTokens.CardBg, RoundedCornerShape(16.dp))
                .clickableNoRipple(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            color = SheetTokens.TextHigh,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun GridEntry(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Row(
        modifier
            .height(48.dp)
            .pressScale(0.98f)
            .background(SheetTokens.InsetBg, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = SheetTokens.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** SettingsListRow（:4878）：高 56、左内边距 16、按压 0.98、标题 15sp、尾 chevron 20 TextLow。 */
@Composable
private fun SettingsRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressScale(0.98f)
            .clickableNoRipple(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = SheetTokens.TextHigh,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // chevron（右向小箭头，结构级线条）
        Canvas(Modifier.size(20.dp)) {
            val s = size.minDimension / 24f
            drawLine(
                SheetTokens.TextLow,
                Offset(9f * s, 5f * s),
                Offset(16f * s, 12f * s),
                strokeWidth = 2f * s,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                SheetTokens.TextLow,
                Offset(16f * s, 12f * s),
                Offset(9f * s, 19f * s),
                strokeWidth = 2f * s,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SheetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

/** 开关行（高 56、左内边距 16、标题 15sp）＋玻璃开关（他的开关全玻璃令：带色面＋亮边＋按压发亮口径装配）。 */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = SheetTokens.TextHigh,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        GlassSwitch(checked, onCheckedChange)
    }
}

@Composable
private fun GlassSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(200, easing = EaseBs4A),
        label = "switchTrack"
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = tween(200, easing = EaseBs4A),
        label = "switchKnob"
    )
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .pressScale(0.96f)
            .background(trackColor, RoundedCornerShape(percent = 50))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(percent = 50))
            .clickableNoRipple { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .align(Alignment.CenterStart)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

/** SystemVolumeCard（:6585，装卡内）：图标 20 TextMedium＋滑杆（weight 1、高 48）＋百分比（宽 36、12sp 右对齐）＋播放设备键（盒 40×48、按压 0.94、图标 20）。 */
@Composable
private fun SystemVolumeRow(onOutputDeviceClick: () -> Unit) {
    val context = LocalContext.current
    var fraction by remember { mutableFloatStateOf(systemVolumeFraction(context)) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PathIcon(20.dp, SheetTokens.TextMedium, PATH_VOLUME_LOW)
        Canvas(
            Modifier
                .weight(1f)
                .height(48.dp)
                .padding(horizontal = 10.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun apply(x: Float) {
                            val f = if (size.width > 0) (x / size.width).coerceIn(0f, 1f) else 0f
                            fraction = f
                            setSystemVolumeFraction(context, f)
                        }
                        apply(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            apply(change.position.x)
                            change.consume()
                        }
                    }
                }
        ) {
            val midY = size.height / 2f
            val trackH = 4.dp.toPx()
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(0f, midY - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius
            )
            val fillW = size.width * fraction
            if (fillW > 0f) {
                drawRoundRect(
                    color = SheetTokens.TextHigh.copy(alpha = 0.85f),
                    topLeft = Offset(0f, midY - trackH / 2f),
                    size = Size(fillW, trackH),
                    cornerRadius = radius
                )
            }
        }
        Text(
            text = "${(fraction * 100).toInt()}%",
            fontSize = 12.sp,
            color = SheetTokens.TextMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp)
        )
        Box(
            Modifier
                .size(width = 40.dp, height = 48.dp)
                .pressScale(0.94f)
                .clickableNoRipple(onClick = onOutputDeviceClick),
            contentAlignment = Alignment.Center
        ) {
            PathIcon(20.dp, SheetTokens.TextHigh, PATH_VOLUME_HIGH)
        }
    }
}

// ================= 播放模式四段（M3Segmented 本体亲读口径，§九） =================

/**
 * 高 56、外圆角 14、指示器圆角 12/10、指示器动画 250ms(e92.a)、段 weight 1、按压 0.96、
 * 图标 16、图标文距 5、字 12sp/行高 16、选中字重 600/未选 400（sq2 映射推定注名）。
 * 轨道底/边深色面无定名值：取白 0.08/0.10 装配（指示器深 白 0.14 是定谳值）。
 * 段位映射（瓜子 PlayMode ↔ MusicQueue）：顺序=SEQUENTIAL、列表循环=REPEAT_ALL、单曲循环=REPEAT_ONE、随机=SHUFFLE；
 * 段签短名 （顺序/列表/单曲/随机） 未逐字抠到、按显示名截短装配注名。点击直接 setMode（真口）。
 */
@Composable
private fun PlayModeSegmented(currentMode: Int) {
    val segments = listOf(
        Triple(MusicQueue.MODE_SEQUENTIAL, PATH_REORDER, "顺序"),
        Triple(MusicQueue.MODE_REPEAT_ALL, PATH_REPEAT, "列表"),
        Triple(MusicQueue.MODE_REPEAT_ONE, PATH_REPEAT_ONE, "单曲"),
        Triple(MusicQueue.MODE_SHUFFLE, PATH_SHUFFLE, "随机")
    )
    val selectedIndex = segments.indexOfFirst { it.first == currentMode }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(2.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
    ) {
        val segW = maxWidth / segments.size
        val indicatorOffset by animateDpAsState(
            targetValue = segW * selectedIndex,
            animationSpec = tween(250, easing = EaseFastOutSlowIn),
            label = "modeIndicator"
        )
        Box(
            Modifier
                .offset(x = indicatorOffset + 2.dp, y = 2.dp)
                .size(width = segW - 4.dp, height = 52.dp)
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
        )
        Row(Modifier.fillMaxSize()) {
            segments.forEachIndexed { index, (mode, glyph, label) ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) SheetTokens.TextHigh else SheetTokens.TextMedium,
                    animationSpec = tween(150, easing = EaseFastOutSlowIn),
                    label = "modeText"
                )
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .pressScale(0.96f)
                        .clickableNoRipple { PlayerStateHolder.setMode(mode) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                        5.dp, Alignment.CenterHorizontally
                    )
                ) {
                    PathIcon(16.dp, textColor, glyph)
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                        color = textColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ================= 主题子面板（ThemePanel:7618） =================

private data class ThemeCard(val id: String, val name: String)

private val THEME_CARDS = listOf(
    ThemeCard(MusicPlayerSettings.THEME_CLASSIC, "经典"),
    ThemeCard(MusicPlayerSettings.THEME_COVER_IMMERSIVE, "封面卡片"),
    ThemeCard(MusicPlayerSettings.THEME_APPLE_MUSIC, "Apple Music"),
    ThemeCard(MusicPlayerSettings.THEME_LUMINOUS_CD, "流光 CD"),
    ThemeCard(MusicPlayerSettings.THEME_RECORD_SHELF, "黑胶唱片架")
)

@Composable
private fun ThemePanel(
    state: PlayerUiState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onThemeSelected: (String) -> Unit
) {
    val current = MusicPlayerSettings.getTheme()
    val currentCardId = if (MusicPlayerSettings.isCdTheme(current)) {
        MusicPlayerSettings.THEME_LUMINOUS_CD
    } else {
        current
    }
    Column(Modifier.fillMaxWidth()) {
        // SheetSubPanelHeader（:194）：行高 44、左右 SheetCircleButton 40、标题 17/字距 −0.3 居中
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SheetCircleButton(onClick = onBack) {
                PathIcon(20.dp, SheetTokens.TextHigh, PATH_ARROW_BACK)
            }
            Text(
                text = "播放页主题",
                fontSize = 17.sp,
                letterSpacing = (-0.3).sp,
                color = SheetTokens.TextHigh,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            SheetCircleButton(onClick = onClose) {
                PathIcon(20.dp, SheetTokens.TextHigh, PATH_CLOSE)
            }
        }
        // 说明字（11sp/行高 16/TextLow、左内边距 4）
        Text(
            text = "选择播放详情页的整体布局，新增主题会自动出现在这里。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = SheetTokens.TextLow,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp)
        )
        // 主题卡横滑行（间距 12、卡宽 112）
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            items(THEME_CARDS) { card ->
                ThemePreviewCard(
                    card = card,
                    selected = card.id == currentCardId,
                    state = state,
                    cdMode = if (MusicPlayerSettings.isCdTheme(current)) current else MusicPlayerSettings.THEME_LUMINOUS_CD,
                    onClick = {
                        // CD 卡：已在 CD 变体时传当前变体、否则传本体（瓜子 fromId2 口径亲验）
                        val target = if (card.id == MusicPlayerSettings.THEME_LUMINOUS_CD &&
                            MusicPlayerSettings.isCdTheme(current)
                        ) {
                            current
                        } else {
                            card.id
                        }
                        onThemeSelected(target)
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** SheetCircleButton（GlassSheetPanelKt.java:135）：40dp 圆、按压 0.9、面 inset。 */
@Composable
private fun SheetCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .pressScale(0.9f)
            .background(SheetTokens.InsetBg, CircleShape)
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * ThemePreviewCard（:7753）：圆角 16、底 animateColorAsState(quickActionBg, 150/bs4.a)——
 * 选中态是否切 toolsSelectedBg 未在抠读中定死，本处取选中→白 0.20 注名；
 * 阴影 2；内小样（ThemeMiniPreview 高 104 圆角 12）＋名称 12sp 居中（内边距 8×9）；
 * 选中勾：20 圆底 TextHigh、勾 13 面板底色。Pro 角标不搬。
 */
@Composable
private fun ThemePreviewCard(
    card: ThemeCard,
    selected: Boolean,
    state: PlayerUiState,
    cdMode: String,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (selected) SheetTokens.SelectedBg else SheetTokens.CardBg,
        animationSpec = tween(150, easing = EaseBs4A),
        label = "themeCardBg"
    )
    Box(
        Modifier
            .width(112.dp)
            .pressScale(0.98f)
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .background(bg, RoundedCornerShape(16.dp))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ThemeMiniPreview(card.id, state, cdMode)
            Spacer(Modifier.height(8.dp))
            Text(
                text = card.name,
                fontSize = 12.sp,
                color = SheetTokens.TextHigh,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(SheetTokens.TextHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                PathIcon(13.dp, SheetTokens.PanelBg, PATH_CHECK)
            }
        }
    }
}

/**
 * ThemeMiniPreview（:6888）：高 104、圆角 12、底 CoverPlaceholder，按主题画「当前封面＋文字条」小样。
 * 条＝ThemePreviewTextBar（:8101）：高 7、圆角 3.5、宽比例参、alpha 参。
 * 各分支条宽比：classic/cover 0.66（实）＋0.4（淡）；apple 0.6＋0.34＋底部小条行；
 * record_shelf 底渐变 #3A3A3C→#1C1C1E（定谳）；luminous_cd 盘形小样细节未逐行抠完——
 * 本处一律结构级复刻（交卷标注），不冒充逐值对照。
 */
@Composable
private fun ThemeMiniPreview(themeId: String, state: PlayerUiState, cdMode: String) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(SheetTokens.CoverPlaceholder, shape)
            .clip(shape)
    ) {
        when (themeId) {
            MusicPlayerSettings.THEME_RECORD_SHELF -> {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)))
                    )
                )
                Column(
                    Modifier.fillMaxSize().padding(10.dp, 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PreviewCover(state, Modifier.size(46.dp))
                    Spacer(Modifier.weight(1f))
                    PreviewTextBar(0.66f, faint = false, Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(5.dp))
                    PreviewTextBar(0.4f, faint = true, Modifier.align(Alignment.Start))
                }
            }
            MusicPlayerSettings.THEME_LUMINOUS_CD -> {
                // 盘形小样：sweep 环＋中心封面圆（Pearl 材料色结构级）
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension * 0.38f
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color(0xFF9FAAB7), Color(0xFFF0F3F6), Color(0xFFB6C7D0),
                                Color(0xFFE9E3D4), Color(0xFF9FAAB7)
                            )
                        ),
                        radius = radius,
                        center = center
                    )
                }
                Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape)) {
                    CoverImage(state, ContentScale.Crop, Modifier.size(40.dp))
                }
            }
            MusicPlayerSettings.THEME_APPLE_MUSIC -> {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
                    }
                    Column(Modifier.padding(10.dp, 9.dp)) {
                        PreviewTextBar(0.6f, faint = false)
                        Spacer(Modifier.height(5.dp))
                        PreviewTextBar(0.34f, faint = true)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(3) {
                                Box(
                                    Modifier
                                        .size(width = 14.dp, height = 3.dp)
                                        .background(
                                            Color.White.copy(alpha = 0.30f),
                                            RoundedCornerShape(1.5.dp)
                                        )
                                )
                            }
                        }
                    }
                }
            }
            MusicPlayerSettings.THEME_COVER_IMMERSIVE -> {
                Box(Modifier.fillMaxSize()) {
                    CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp, 9.dp)
                    ) {
                        PreviewTextBar(0.66f, faint = false)
                        Spacer(Modifier.height(5.dp))
                        PreviewTextBar(0.4f, faint = true)
                    }
                }
            }
            else -> {
                // classic：封面块＋条（内边距 10/9、条距 5）
                Column(Modifier.fillMaxSize().padding(10.dp, 9.dp)) {
                    PreviewCover(state, Modifier.fillMaxWidth().height(48.dp))
                    Spacer(Modifier.height(6.dp))
                    PreviewTextBar(0.66f, faint = false)
                    Spacer(Modifier.height(5.dp))
                    PreviewTextBar(0.4f, faint = true)
                }
            }
        }
    }
}

@Composable
private fun PreviewCover(state: PlayerUiState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
    ) {
        CoverImage(state, ContentScale.Crop, Modifier.fillMaxSize())
    }
}

/** ThemePreviewTextBar（:8101）：高 7、圆角 3.5、宽＝比例、alpha 参（实/淡两档装配值）。 */
@Composable
private fun PreviewTextBar(fraction: Float, faint: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(fraction)
            .height(7.dp)
            .background(
                Color.White.copy(alpha = if (faint) 0.18f else 0.38f),
                RoundedCornerShape(3.5.dp)
            )
    )
}
