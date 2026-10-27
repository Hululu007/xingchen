package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.fongmi.android.tv.R
import com.fongmi.android.tv.music.MusicDownloadStore
import com.fongmi.android.tv.music.MusicQueue
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.ui.music.MusicPlayerSettings
import kotlinx.coroutines.launch

/**
 * 同树面板（照瓜子：面板不是独立页，是播放树里的 sheet，读同一份快照）。
 * 底色走瓜子 SheetTokens 深值（sheetBg #FF1C1C1E）——他定：音乐面板不用白底、
 * 用深面一系（2026-10-25/27 口径），令牌照案卷十节深色档。
 * 第五单起工具面板内容全填实：主面板（英雄/瓷砖/模式分段/设置卡）＋定时、
 * 歌词样式、外观、歌曲信息、加到歌单、下载选档六个子面板（子面板在
 * PlayerToolPanels.kt），音质走独立 QualitySheet（版本选择器口径）；
 * 队列面板补来源卡/清空/定位当前。全部接保留区真数据真行为，不摆占位。
 */

internal val SheetBg = Color(0xFF1C1C1E)
internal val SheetListBg = Color(0xFF2C2C2E)
internal val SheetInset = Color.White.copy(alpha = 0.08f)
internal val SheetBorder = Color.White.copy(alpha = 0.14f)
internal val SheetDivider = Color.White.copy(alpha = 0.08f)
internal val TextHigh = Color(0xFFF2F2F7)
internal val TextMedium = Color(0xFF8E8E93)
internal val TextLow = Color(0xFF636366)
internal val AccentRed = Color(0xFFFA243C)

/** 子面板头（案卷九节 SheetSubPanelHeader：行高 44、左圆钮 40 按压 0.9、标题 17sp 居中）。 */
@Composable
internal fun SubPanelHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val src = rememberPressSource()
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(SheetListBg)
                .pressScale(0.9f, src)
                .clickable(interactionSource = src, indication = null) { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_music_chevron_left),
                contentDescription = "返回",
                tint = TextHigh,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            color = TextHigh,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(40.dp))
    }
}

/** 段标（案卷九节 SheetSectionLabel：左内边距 4、下 8、14sp、字距 0.2、sheetTextMedium）。 */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextMedium,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/**
 * 自绘开关（案卷九节 SleepToggle：轨道 40×24 圆角 12、圆钮 20 左偏 2；
 * 开＝AccentRed 实色、关＝深面白 .16）。工具面板全部开关共用这一件。
 */
@Composable
internal fun SheetToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) AccentRed else Color.White.copy(alpha = 0.16f),
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "toggleTrack",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "toggleKnob",
    )
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .align(Alignment.CenterStart)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * 筹（案卷九节 SleepChip/SpeedChip 共用形：圆角 10、纵内边距 7、字 12sp；
 * 选中＝白底深字加粗、未选＝卡底 .3＋低字色；底/字色 animateColor 150ms FastOutSlowIn）。
 */
@Composable
internal fun SheetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) Color.White else SheetListBg.copy(alpha = 0.3f),
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "chipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Color(0xFF1C1C1E) else TextLow,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "chipFg",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 设置卡内分隔线（左内边距 16、高 0.5、sheetDivider）。 */
@Composable
internal fun CardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(SheetDivider),
    )
}

/** 面板内短提示（行为结果如实报：已加入下载/已在歌单中/添加失败，不无条件报成功）。 */
internal fun sheetToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** 从 Compose 上下文找宿主 Activity（系统音效面板/窗口旗要真 Activity）。 */
internal fun Context.findActivity(): android.app.Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 队列面板（案卷十二节）：列高 0.68；头＝SubPanelHeader 形——左清空、中标题、
 * 右播放模式钮（着色 #FFAF909F，案卷原值、语义未定名，按模式钮落：点按循环模式）；
 * 来源卡（队列有来源才出：圆角 10、底 sheetInset、内边距 12×8，「来自」＋来源名；
 * 来源 route 跳转未接——route 落点是旧音乐页，本树不另起跳转，卡只展示）；
 * 行高 56、行距 2（pitch 58），当前行红名（他的口径：正在播放行标红）；
 * 打开自动定位当前行，另有「定位当前」钮；清空走控制器既有口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerUiState,
    onPlayAt: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.queueIndex) {
        val idx = state.queueIndex
        if (idx in state.queue.indices) {
            runCatching { listState.scrollToItem(idx) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.68f)
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            // 头行（高 44）：左清空（有队列才出）／中标题／右模式钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(40.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.queue.isNotEmpty()) {
                        Text(
                            text = "清空",
                            color = TextLow,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { PlayerStateHolder.clearQueue() },
                        )
                    }
                }
                Text(
                    text = "播放列表",
                    color = TextHigh,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { PlayerStateHolder.cycleMode() },
                    contentAlignment = Alignment.Center,
                ) {
                    ModeIcon(
                        mode = state.mode,
                        tint = Color(0xFFAF909F),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 来源卡（有来源才出）
            val source = state.queueSource
            if (!source.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SheetInset)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_queue),
                        contentDescription = null,
                        tint = TextMedium,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "来自", color = TextLow, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = source,
                        color = TextHigh,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (state.queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "队列为空", color = TextLow, fontSize = 13.sp)
                }
            } else {
                // 定位当前钮（右对齐一行）
                if (state.queueIndex in state.queue.indices) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "定位当前",
                            color = TextMedium,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                scope.launch {
                                    runCatching { listState.animateScrollToItem(state.queueIndex) }
                                }
                            },
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(state.queue) { index, track ->
                        val current = index == state.queueIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (current) SheetListBg else Color.Transparent)
                                .clickable { onPlayAt(index) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "%02d".format(index + 1),
                                color = if (current) AccentRed.copy(alpha = 0.7f) else TextLow,
                                fontSize = 11.sp,
                                modifier = Modifier.width(22.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.displayTitle(),
                                    color = if (current) AccentRed else TextHigh,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.displayArtist().ifEmpty { "未知歌手" },
                                    color = if (current) AccentRed.copy(alpha = 0.72f) else TextLow,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 工具面板子面板集（主题子面板第四单已落，其余第五单填实）。 */
internal enum class ToolsPanel {
    MAIN, THEME, SLEEP, LYRIC_STYLE, APPEARANCE, SONG_INFO, ADD_PLAYLIST, DL_QUALITY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSheet(
    state: PlayerUiState,
    currentThemeId: String,
    onThemeSelected: (String) -> Unit,
    onOpenLyricVersions: () -> Unit,
    onOpenQuality: () -> Unit,
    onDismiss: () -> Unit,
    /** 从全屏歌词面开的面板：歌词样式字号写全屏键（两页字号口径不同、不并值）。 */
    fullLyricSurface: Boolean,
) {
    var panel by remember { mutableStateOf(ToolsPanel.MAIN) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        dragHandle = null,
    ) {
        when (panel) {
            ToolsPanel.MAIN -> ToolsMainPanel(
                state = state,
                currentThemeId = currentThemeId,
                onOpenPanel = { panel = it },
                onOpenQuality = onOpenQuality,
                onOpenLyricVersions = onOpenLyricVersions,
            )
            ToolsPanel.THEME -> ThemeSubPanel(
                state = state,
                currentThemeId = currentThemeId,
                onBack = { panel = ToolsPanel.MAIN },
                onThemeSelected = onThemeSelected,
            )
            ToolsPanel.SLEEP -> SleepTimerPanel(
                state = state,
                onBack = { panel = ToolsPanel.MAIN },
            )
            ToolsPanel.LYRIC_STYLE -> LyricStylePanel(
                state = state,
                fullSurface = fullLyricSurface,
                onBack = { panel = ToolsPanel.MAIN },
            )
            ToolsPanel.APPEARANCE -> AppearancePanel(
                onBack = { panel = ToolsPanel.MAIN },
            )
            ToolsPanel.SONG_INFO -> SongInfoPanel(
                state = state,
                onBack = { panel = ToolsPanel.MAIN },
            )
            ToolsPanel.ADD_PLAYLIST -> AddPlaylistPanel(
                state = state,
                onBack = { panel = ToolsPanel.MAIN },
                onDone = onDismiss,
            )
            ToolsPanel.DL_QUALITY -> DownloadQualityPanel(
                state = state,
                onBack = { panel = ToolsPanel.MAIN },
                onDone = onDismiss,
            )
        }
    }
}

/**
 * 工具主面板（案卷九节 ToolsMainPanel）：英雄行（当前曲真数据）→快捷瓷砖行
 * （下载/歌词/定时/加到歌单，「一起听」不搬）→播放模式四段分段→间隔 20→
 * 设置卡（歌曲信息行＋音质行＋功能网格＋不熄屏开关行）。
 * 左手布局行裁掉：该能力已在体检中定性为假开关摘除（全树无读取方），不摆假行。
 */
@Composable
private fun ToolsMainPanel(
    state: PlayerUiState,
    currentThemeId: String,
    onOpenPanel: (ToolsPanel) -> Unit,
    onOpenQuality: () -> Unit,
    onOpenLyricVersions: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 28.dp),
    ) {
        // 英雄行（真数据：封面 48 圆角 12、歌名 16sp、歌手 12sp）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF8E8E93).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = state.coverBitmap
                if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else if (state.coverUrl == null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = TextLow,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifEmpty { "未在播放" },
                    color = TextHigh,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 21.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.artist,
                    color = TextMedium,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        // 快捷瓷砖行（56×56 圆角 16、图标 26、标题 12sp 居中）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickTile(
                label = "下载",
                iconRes = R.drawable.ic_music_download,
                modifier = Modifier.weight(1f),
            ) {
                val track = state.track
                when {
                    track == null -> sheetToast(context, "未在播放")
                    MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK ->
                        onOpenPanel(ToolsPanel.DL_QUALITY)
                    else -> when (MusicDownloadStore.download(track)) {
                        MusicDownloadStore.DL_LOCAL -> sheetToast(context, "本地歌曲无需下载")
                        MusicDownloadStore.DL_ASK -> onOpenPanel(ToolsPanel.DL_QUALITY)
                        else -> sheetToast(context, "已加入下载")
                    }
                }
            }
            QuickTile(
                label = "歌词",
                iconRes = R.drawable.ic_music_note,
                modifier = Modifier.weight(1f),
            ) {
                // 开歌词版本面板（根树切 Sheet，不是翻译开关）
                onOpenLyricVersions()
            }
            QuickTile(
                label = "定时",
                iconRes = R.drawable.ic_music_timer,
                modifier = Modifier.weight(1f),
            ) { onOpenPanel(ToolsPanel.SLEEP) }
            QuickTile(
                label = "加到歌单",
                iconRes = R.drawable.ic_music_playlist_add,
                modifier = Modifier.weight(1f),
            ) {
                if (state.track == null) {
                    sheetToast(context, "未在播放")
                } else {
                    onOpenPanel(ToolsPanel.ADD_PLAYLIST)
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        // 播放模式四段（顺序/列表循环/单曲循环/随机，接中枢现行模式、点了真切换）
        PlayModeSegmented(
            mode = state.mode,
            onSelect = { PlayerStateHolder.setMode(it) },
        )
        Spacer(modifier = Modifier.height(20.dp))
        // 设置卡（圆角 16、底 quickActionBg #FF2C2C2E）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SheetListBg),
        ) {
            SettingsCardRow(
                title = "歌曲信息",
                onClick = { onOpenPanel(ToolsPanel.SONG_INFO) },
            )
            CardDivider()
            SettingsCardRow(
                title = "音质",
                tail = MusicStore.qualityName(state.quality),
                onClick = onOpenQuality,
            )
            CardDivider()
            GridEntryRow(
                title = "均衡器",
                iconRes = R.drawable.ic_music_eq,
                onClick = { openSystemEqualizer(context) },
            )
            GridEntryRow(
                title = "播放页主题",
                iconRes = R.drawable.ic_music_stack_14,
                tail = themeDisplayName(currentThemeId),
                onClick = { onOpenPanel(ToolsPanel.THEME) },
            )
            GridEntryRow(
                title = "外观设置",
                iconRes = R.drawable.ic_music_speed,
                onClick = { onOpenPanel(ToolsPanel.APPEARANCE) },
            )
            GridEntryRow(
                title = "歌词样式",
                iconRes = R.drawable.ic_music_note,
                onClick = { onOpenPanel(ToolsPanel.LYRIC_STYLE) },
            )
            CardDivider()
            // 开关行：播放页不熄屏（存档在 MusicStore，生效由播放页窗口旗读中枢修订号应用）
            var keepOn by remember { mutableStateOf(MusicStore.isPlayerKeepScreenOn()) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "播放页不熄屏",
                    color = TextHigh,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                SheetToggle(checked = keepOn) { on ->
                    keepOn = on
                    MusicStore.setPlayerKeepScreenOn(on)
                    PlayerStateHolder.bumpSettings()
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    label: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(2.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(SheetListBg)
                .pressScale(0.94f, src)
                .clickable(interactionSource = src, indication = null) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = TextHigh,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = TextHigh,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 播放模式分段控件（案卷九节 PlayModeSegmented＋十八节尺寸：高 56、外圆角 14、
 * 指示器圆角 12/10、选中底＝白 .2）：段序照瓜子——顺序/列表循环/单曲循环/随机，
 * 段值映射 MusicQueue 模式常量；图标走 ModeIcon 既有件。
 */
@Composable
private fun PlayModeSegmented(mode: Int, onSelect: (Int) -> Unit) {
    val segments = listOf(
        Triple(MusicQueue.MODE_SEQUENTIAL, "顺序", "顺序播放"),
        Triple(MusicQueue.MODE_REPEAT_ALL, "列表循环", "列表循环"),
        Triple(MusicQueue.MODE_REPEAT_ONE, "单曲循环", "单曲循环"),
        Triple(MusicQueue.MODE_SHUFFLE, "随机", "随机播放"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SheetInset)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((value, label, desc) in segments) {
            val selected = mode == value
            val bg by animateColorAsState(
                targetValue = if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                animationSpec = tween(150, easing = FastOutSlowInEasing),
                label = "segBg",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                ModeIcon(
                    mode = value,
                    tint = if (selected) TextHigh else TextMedium,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = label,
                    color = if (selected) TextHigh else TextMedium,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 设置卡行（案卷九节 SettingsListRow：高 56、左内边距 16、标题 15sp、尾 chevron 20）。 */
@Composable
private fun SettingsCardRow(
    title: String,
    tail: String = "",
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressScale(0.98f, src)
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = TextHigh,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (tail.isNotEmpty()) {
            Text(text = tail, color = TextLow, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text = "›", color = TextLow, fontSize = 20.sp)
    }
}

/** 设置卡内网格行（案卷九节 ToolsGridEntry：高 48、内边距 16×12、图标 20、标题 13sp）。 */
@Composable
private fun GridEntryRow(
    title: String,
    iconRes: Int,
    tail: String = "",
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressScale(0.98f, src)
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = TextHigh,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = TextHigh,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (tail.isNotEmpty()) {
            Text(text = tail, color = TextLow, fontSize = 12.sp)
        }
    }
}

/**
 * 均衡器瓷砖/网格行落点：系统音效面板（AudioEffect 标准口，会话 id 走中枢现行值）。
 * 服务未起（会话 0）或设备无此面板时如实提示，不假装打开。
 */
private fun openSystemEqualizer(context: Context) {
    val session = PlayerStateHolder.audioSessionId()
    if (session == 0) {
        sheetToast(context, "音效面板暂不可用")
        return
    }
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { sheetToast(context, "未找到系统音效面板") }
}

/**
 * 主题子面板（案卷九节 SheetSubPanelHeader＋主题卡行）：
 * 头行高 44（左圆钮 40、按压 0.9、标题 17sp 居中）；段说明 11sp/行高 16/
 * sheetTextLow、左垫 4；卡行 LazyRow 间距 12、卡宽 112——卡圆角 16、底
 * quickActionBg(#FF2C2C2E)、阴影 2（装配）、名称 12sp 居中内边距 水平 8/垂直 9、
 * 选中勾＝20 圆底 sheetTextHigh＋对勾 13 色 #FF1C1C1E（位置装配为右上）。
 * 预览用当前封面真图渲染各主题迷你形（几何为装配；唱片架迷你渐变
 * #FF3A3A3C→#FF1C1C1E、文本条 高 7 圆角 3.5 为案卷值）。
 * 五卡顺序与站内 PlayerTheme 一致：经典／封面卡片／Apple Music／流光 CD／
 * 黑胶唱片架；流光卡选中判 isCdTheme，点选时已在 CD 族保留现变体、否则写
 * luminous_cd；点选经 Root 写既有存档键并整树换主题。
 */
@Composable
private fun ThemeSubPanel(
    state: PlayerUiState,
    currentThemeId: String,
    onBack: () -> Unit,
    onThemeSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        // 子面板头（行高 44、圆钮 40、标题 17sp 居中）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val src = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SheetListBg)
                    .pressScale(0.9f, src)
                    .clickable(interactionSource = src, indication = null) { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_chevron_left),
                    contentDescription = "返回",
                    tint = TextHigh,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "播放页主题",
                color = TextHigh,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(40.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "选择播放详情页的整体布局，新增主题会自动出现在这里。",
            color = TextLow,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        val families = listOf(
            ThemeFamily.CLASSIC,
            ThemeFamily.COVER,
            ThemeFamily.APPLE,
            ThemeFamily.CD,
            ThemeFamily.SHELF,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(families) { family ->
                val selected = when (family) {
                    ThemeFamily.CD -> MusicPlayerSettings.isCdTheme(currentThemeId)
                    else -> themeFamilyOf(currentThemeId) == family
                }
                ThemeCard(
                    family = family,
                    state = state,
                    selected = selected,
                    onClick = {
                        val target = when (family) {
                            ThemeFamily.CLASSIC -> MusicPlayerSettings.THEME_CLASSIC
                            ThemeFamily.COVER -> MusicPlayerSettings.THEME_COVER_IMMERSIVE
                            ThemeFamily.APPLE -> MusicPlayerSettings.THEME_APPLE_MUSIC
                            ThemeFamily.CD ->
                                if (MusicPlayerSettings.isCdTheme(currentThemeId)) {
                                    currentThemeId
                                } else {
                                    MusicPlayerSettings.THEME_LUMINOUS_CD
                                }
                            ThemeFamily.SHELF -> MusicPlayerSettings.THEME_RECORD_SHELF
                        }
                        onThemeSelected(target)
                    },
                )
            }
        }
    }
}

/** 主题预览卡（宽 112、圆角 16、底 #FF2C2C2E、名称 12sp 居中 8×9、选中勾）。 */
@Composable
private fun ThemeCard(
    family: ThemeFamily,
    state: PlayerUiState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(112.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(SheetListBg)
            .clickable { onClick() },
    ) {
        Column {
            ThemeMiniPreview(
                family = family,
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = themeDisplayName(
                        when (family) {
                            ThemeFamily.CLASSIC -> MusicPlayerSettings.THEME_CLASSIC
                            ThemeFamily.COVER -> MusicPlayerSettings.THEME_COVER_IMMERSIVE
                            ThemeFamily.APPLE -> MusicPlayerSettings.THEME_APPLE_MUSIC
                            ThemeFamily.CD -> MusicPlayerSettings.THEME_LUMINOUS_CD
                            ThemeFamily.SHELF -> MusicPlayerSettings.THEME_RECORD_SHELF
                        }
                    ),
                    color = TextHigh,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(TextHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_check_14),
                    contentDescription = "当前主题",
                    tint = Color(0xFF1C1C1E),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** 文本条（案卷九节 TextBar：高 7、圆角 3.5、宽＝比例参、alpha 参）。 */
@Composable
private fun PreviewTextBar(widthFraction: Float, alpha: Float, color: Color = TextHigh) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(7.dp)
            .clip(RoundedCornerShape(3.5.dp))
            .background(color.copy(alpha = alpha)),
    )
}

/**
 * 各主题迷你预览（用当前封面渲染；几何为装配，唯唱片架渐变与文本条规格是案卷值）。
 */
@Composable
private fun ThemeMiniPreview(
    family: ThemeFamily,
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val bmp = state.coverBitmap
    val hasCover = bmp != null && state.coverBitmapUrl == state.coverUrl
    Box(modifier = modifier.background(Color(0xFF1C1C1E))) {
        when (family) {
            ThemeFamily.CLASSIC -> {
                if (hasCover) {
                    Image(
                        bitmap = bmp!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                ) {
                    PreviewTextBar(0.62f, 0.55f)
                    Spacer(modifier = Modifier.height(5.dp))
                    PreviewTextBar(0.4f, 0.35f)
                }
            }
            ThemeFamily.COVER -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF3A3A3C)),
                ) {
                    if (hasCover) {
                        Image(
                            bitmap = bmp!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .width(56.dp),
                ) {
                    PreviewTextBar(1f, 0.4f)
                }
            }
            ThemeFamily.APPLE -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF3A3A3C)),
                ) {
                    if (hasCover) {
                        Image(
                            bitmap = bmp!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                ) {
                    PreviewTextBar(0.55f, 0.55f)
                    Spacer(modifier = Modifier.height(5.dp))
                    PreviewTextBar(0.85f, 0.3f)
                }
            }
            ThemeFamily.CD -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color(0xFF9FAAB7), Color(0xFFF0F3F6), Color(0xFFB6C7D0),
                                    Color(0xFFE9E3D4), Color(0xFF929FAC), Color(0xFF9FAAB7),
                                )
                            )
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(46.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF3A3A3C)),
                    ) {
                        if (hasCover) {
                            Image(
                                bitmap = bmp!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
            ThemeFamily.SHELF -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E))
                            )
                        ),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(3) { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == 1) 30.dp else 24.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFFF1F0ED).copy(alpha = if (i == 1) 0.9f else 0.45f)),
                        ) {
                            if (hasCover && i == 1) {
                                Image(
                                    bitmap = bmp!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
