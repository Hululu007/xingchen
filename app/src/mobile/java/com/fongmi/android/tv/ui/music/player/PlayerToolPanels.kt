package com.fongmi.android.tv.ui.music.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.bean.MusicPlaylist
import com.fongmi.android.tv.music.MusicCache
import com.fongmi.android.tv.music.MusicDownloadStore
import com.fongmi.android.tv.music.MusicLibrary
import com.fongmi.android.tv.music.MusicRepository
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.service.MusicBeatState
import com.fongmi.android.tv.service.MusicPlaybackService
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/**
 * 工具面板子面板集（第五单）：定时/歌词样式/外观/歌曲信息/加到歌单/下载选档。
 * 全部接保留区真能力——睡眠定时走 MusicController→服务既有计时链（含自定义
 * 分钟编码与「播完整首后再停止」等待态）、歌词样式读写 MusicStore 既有存档键
 * 并经中枢 bumpSettings 当场进引擎、歌单走 MusicLibrary、下载走
 * MusicDownloadStore 既有选档实测路。底一律深面令牌（SheetBg 系），不用白底。
 */

// ================= 定时 =================

/**
 * 睡眠定时子面板（案卷九节 SleepTimerPanel）：状态卡（进行中才出：边框 1 白 .22、
 * 底白 .1、内边距 14×12、图标盒 36 圆角 10、剩余 18sp）→段标「时长」→预设筹
 * （关闭/15/30/45/60/90 分＋本首结束＋自定义）→自定义卡（数字输入＋开启）→
 * 底行「播完整首后再停止」＋自绘开关。档值映射服务 SLEEP_* 常量：45 分无预设
 * 常量、走自定义编码 1000＋45（服务同链计时，非假档）。
 */
@Composable
internal fun SleepTimerPanel(
    state: PlayerUiState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var customText by remember { mutableStateOf("") }
    val customFocus = remember { FocusRequester() }
    val mode = state.sleepMode
    val active = mode != MusicPlaybackService.SLEEP_OFF || state.sleepWaiting
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        SubPanelHeader(title = "定时关闭", onBack = onBack)
        if (active) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SheetListBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_timer),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "进行中", color = TextMedium, fontSize = 11.sp)
                    Text(
                        text = sleepRemainingLabel(state),
                        color = TextHigh,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SheetListBg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { PlayerStateHolder.setSleep(MusicPlaybackService.SLEEP_OFF) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(text = "关闭", color = TextHigh, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel(text = "时长")
        val presets = listOf(
            "关闭" to MusicPlaybackService.SLEEP_OFF,
            "15 分钟" to MusicPlaybackService.SLEEP_15,
            "30 分钟" to MusicPlaybackService.SLEEP_30,
            "45 分钟" to (MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE + 45),
            "60 分钟" to MusicPlaybackService.SLEEP_60,
            "90 分钟" to MusicPlaybackService.SLEEP_90,
            "本首结束" to MusicPlaybackService.SLEEP_AFTER_CURRENT,
        )
        presets.chunked(4).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((label, value) in rowItems) {
                    SheetChip(
                        label = label,
                        selected = mode == value && !state.sleepWaiting,
                        onClick = { PlayerStateHolder.setSleep(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 3) {
                    // 末行补自定义筹（选中＝当前是自定义编码档；1045 与 45 分预设筹同值不重复亮）
                    val customActive = mode >= MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE &&
                        mode != MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE + 45
                    SheetChip(
                        label = if (customActive) {
                            "自定义 ${mode - MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE} 分"
                        } else {
                            "自定义"
                        },
                        selected = customActive,
                        onClick = { runCatching { customFocus.requestFocus() } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // 自定义卡（圆角同筹、边框 toolsBorder、底 toolsCardBg、内边距 14×10）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, SheetBorder, RoundedCornerShape(10.dp))
                .background(SheetListBg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = customText,
                onValueChange = { v -> customText = v.filter { it.isDigit() }.take(3) },
                textStyle = TextStyle(
                    color = TextHigh,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(TextHigh),
                singleLine = true,
                modifier = Modifier
                    .width(64.dp)
                    .focusRequester(customFocus),
                decorationBox = { inner ->
                    if (customText.isEmpty()) {
                        Text(text = "0", color = TextLow, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    inner()
                },
            )
            Text(text = "分钟", color = TextMedium, fontSize = 12.sp)
            Spacer(modifier = Modifier.weight(1f))
            val openSrc = rememberPressSource()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .pressScale(0.96f, openSrc)
                    .clickable(interactionSource = openSrc, indication = null) {
                        val minutes = customText.toIntOrNull()
                        if (minutes == null || minutes !in 1..999) {
                            sheetToast(context, "请输入 1–999 分钟")
                        } else {
                            PlayerStateHolder.setSleep(
                                MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE + minutes
                            )
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(text = "开启", color = Color(0xFF1C1C1E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 底行：播完整首后再停止（开关存 MusicStore，服务到点时现读；关时若已在等待态同步清等待）
        var finishCurrent by remember { mutableStateOf(state.sleepFinishCurrent) }
        LaunchedEffect(state.sleepFinishCurrent) { finishCurrent = state.sleepFinishCurrent }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "播完整首后再停止",
                color = TextHigh,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            SheetToggle(checked = finishCurrent) { on ->
                finishCurrent = on
                MusicStore.setSleepFinishCurrent(on)
                if (!on) PlayerStateHolder.clearSleepWaitingForSongEnd()
                PlayerStateHolder.bumpSettings()
            }
        }
    }
}

/** 状态卡剩余文案：等待态/本首档优先，分钟档走剩余计时，再回落服务档名。 */
private fun sleepRemainingLabel(state: PlayerUiState): String = when {
    state.sleepWaiting -> "本首结束后停止"
    state.sleepMode == MusicPlaybackService.SLEEP_AFTER_CURRENT -> "本首结束时停止"
    state.sleepRemainingMs > 0 -> "还剩 " + formatSleepRemaining(state.sleepRemainingMs)
    else -> MusicPlaybackService.sleepName(state.sleepMode)
}

private fun formatSleepRemaining(ms: Long): String {
    val totalSec = (ms + 999) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ================= 歌词样式 =================

/**
 * 歌词样式子面板（案卷九节 CoverLyricStylePanel）：预览卡（当前歌词迷你预览，
 * 读现行样式真值渲染）→字号（A＋值＋滑杆）/粗细/对齐/模糊/高级动效/主歌词
 * 各行（标签宽 60）→恢复默认。全部读写 MusicStore 既有存档键，写完经中枢
 * bumpSettings 当场进引擎（引擎快照以修订号为键重读）。
 * 字号目标键按开面板的面分：全屏面写全屏键（16–40）、播放页写页内键
 * （14–32）——两页口径不同、互不并值（MusicStore 既定口径）。
 * 预览卡底装配为 SheetListBg：案卷原值 #FF1C1C1E 与面板底同色、在深面面板里
 * 分不出卡界，故抬一档到卡底色（版式数值照原：圆角 14、高 132、内边距 14×8）。
 */
@Composable
internal fun LyricStylePanel(
    state: PlayerUiState,
    fullSurface: Boolean,
    onBack: () -> Unit,
) {
    var fontSize by remember(fullSurface) {
        mutableFloatStateOf(
            if (fullSurface) MusicStore.getFullLyricFontSize() else MusicStore.getCoverLyricFontSize()
        )
    }
    var weight by remember { mutableIntStateOf(MusicStore.getLyricWeight()) }
    var align by remember { mutableIntStateOf(MusicStore.getLyricAlign()) }
    var blur by remember { mutableIntStateOf(MusicStore.getCoverLyricBlurLevel()) }
    var advancedFx by remember { mutableStateOf(MusicStore.isAdvancedFx()) }
    var primary by remember { mutableIntStateOf(MusicStore.getLyricPrimary()) }

    fun writeFont(v: Float) {
        fontSize = v
        if (fullSurface) MusicStore.setFullLyricFontSize(v) else MusicStore.setCoverLyricFontSize(v)
        PlayerStateHolder.bumpSettings()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        SubPanelHeader(title = "歌词样式", onBack = onBack)
        // 预览卡（顶距 12、高 132、圆角 14、内边距 14×8）
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SheetListBg)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            LyricStylePreview(
                state = state,
                fontSizeSp = fontSize,
                weight = weight,
                align = align,
                primary = primary,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        // 字号行：标签宽 60＋「A」16sp＋滑杆＋值 11sp 宽 22 右对齐
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyleRowLabel("字号")
            Text(text = "A", color = TextHigh, fontSize = 16.sp)
            Slider(
                value = fontSize,
                onValueChange = { writeFont(it) },
                valueRange = if (fullSurface) 16f..40f else 14f..32f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = AccentRed,
                    inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                ),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${fontSize.toInt()}",
                color = TextMedium,
                fontSize = 11.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(22.dp),
            )
        }
        StyleChipRow(
            label = "粗细",
            options = listOf("标准", "加粗", "特粗"),
            selected = weight,
            onSelect = {
                weight = it
                MusicStore.setLyricWeight(it)
                PlayerStateHolder.bumpSettings()
            },
        )
        StyleChipRow(
            label = "对齐",
            options = listOf("左", "中", "右"),
            selected = align,
            onSelect = {
                align = it
                MusicStore.setLyricAlign(it)
                PlayerStateHolder.bumpSettings()
            },
        )
        StyleChipRow(
            label = "模糊",
            options = listOf("关闭", "轻度", "中度"),
            selected = blur,
            onSelect = {
                blur = it
                MusicStore.setCoverLyricBlurLevel(it)
                PlayerStateHolder.bumpSettings()
            },
        )
        // 高级动效行（标签宽 60＋开关）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyleRowLabel("高级动效")
            Spacer(modifier = Modifier.weight(1f))
            SheetToggle(checked = advancedFx) { on ->
                advancedFx = on
                MusicStore.setAdvancedFx(on)
                PlayerStateHolder.bumpSettings()
            }
        }
        StyleChipRow(
            label = "主歌词",
            options = listOf("原文", "翻译", "音译"),
            selected = primary,
            onSelect = {
                primary = it
                MusicStore.setLyricPrimary(it)
                PlayerStateHolder.bumpSettings()
            },
        )
        // 恢复默认（高 28、左内边距 12、字 11sp、按压 0.94）
        Spacer(modifier = Modifier.height(10.dp))
        val resetSrc = rememberPressSource()
        Box(
            modifier = Modifier
                .height(28.dp)
                .padding(start = 12.dp)
                .pressScale(0.94f, resetSrc)
                .clickable(interactionSource = resetSrc, indication = null) {
                    MusicStore.setCoverLyricFontSize(22f)
                    MusicStore.setFullLyricFontSize(24f)
                    MusicStore.setLyricWeight(0)
                    MusicStore.setLyricAlign(0)
                    MusicStore.setCoverLyricBlurLevel(2)
                    MusicStore.setAdvancedFx(true)
                    MusicStore.setLyricPrimary(0)
                    fontSize = if (fullSurface) 24f else 22f
                    weight = 0
                    align = 0
                    blur = 2
                    advancedFx = true
                    primary = 0
                    PlayerStateHolder.bumpSettings()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "恢复默认", color = TextMedium, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StyleRowLabel(text: String) {
    Text(
        text = text,
        color = TextMedium,
        fontSize = 13.sp,
        modifier = Modifier
            .width(60.dp)
            .padding(top = 6.dp),
    )
}

@Composable
private fun StyleChipRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StyleRowLabel(label)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { index, option ->
                SheetChip(
                    label = option,
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 预览：当前行前后各一，用面板现行样式值（字号/粗细/对齐/主歌词）渲染真歌词行。 */
@Composable
private fun LyricStylePreview(
    state: PlayerUiState,
    fontSizeSp: Float,
    weight: Int,
    align: Int,
    primary: Int,
) {
    val content = state.lyrics as? LyricUiState.Content
    if (content == null || content.lines.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(116.dp), contentAlignment = Alignment.Center) {
            Text(text = "暂无歌词", color = TextLow, fontSize = 13.sp)
        }
        return
    }
    val cur = state.lyricIndex.coerceIn(0, content.lines.size - 1)
    val fontWeight = when (weight) {
        1 -> FontWeight.ExtraBold
        2 -> FontWeight.Black
        else -> FontWeight.Bold
    }
    val textAlign = when (align) {
        1 -> TextAlign.Center
        2 -> TextAlign.End
        else -> TextAlign.Start
    }
    val previewSize = minOf(fontSizeSp, 24f)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = when (align) {
            1 -> Alignment.CenterHorizontally
            2 -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        for (i in (cur - 1)..(cur + 1)) {
            val line = content.lines.getOrNull(i) ?: continue
            val main = when (primary) {
                1 -> line.translation ?: line.text
                2 -> line.romanization ?: line.text
                else -> line.text
            }
            Text(
                text = main,
                color = if (i == cur) Color.White else Color.White.copy(alpha = 0.30f),
                fontSize = previewSize.sp,
                fontWeight = fontWeight,
                lineHeight = (previewSize * 4f / 3f).sp,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ================= 外观 =================

/**
 * 外观子面板（案卷九节 AppearancePanel）：段标「背景动效」→「动态流体背景」
 * 开关→展开区（「流动速度」标签＋慢/标准/快速三筹）→「音乐律动」开关。
 * 开关写 MusicPlayerSettings 既有口（流体/速度自有键、律动委托总仓），
 * 流体/律动变更同口径重算服务节拍门 MusicBeatState.requestEnabled，
 * 并经中枢 bumpSettings 让氛围层当场换层/换速。
 */
@Composable
internal fun AppearancePanel(
    onBack: () -> Unit,
) {
    var fluid by remember { mutableStateOf(MusicPlayerSettings.isFluidBg()) }
    var beat by remember { mutableStateOf(MusicPlayerSettings.isFluidBeat()) }
    var speed by remember { mutableIntStateOf(MusicPlayerSettings.getFluidSpeed()) }

    fun syncBeatGate() {
        MusicBeatState.requestEnabled =
            MusicPlayerSettings.isFluidBg() && MusicPlayerSettings.isFluidBeat()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        SubPanelHeader(title = "外观设置", onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel(text = "背景动效")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "动态流体背景",
                color = TextHigh,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            SheetToggle(checked = fluid) { on ->
                fluid = on
                MusicPlayerSettings.setFluidBg(on)
                syncBeatGate()
                PlayerStateHolder.bumpSettings()
            }
        }
        if (fluid) {
            Text(
                text = "流动速度",
                color = TextMedium,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("慢速", "标准", "快速").forEachIndexed { index, label ->
                    // SpeedChip 高 34（案卷值；SheetChip 本体纵内边距 7，此处以定高盒对齐）
                    Box(modifier = Modifier.weight(1f).height(34.dp)) {
                        SheetChip(
                            label = label,
                            selected = speed == index,
                            onClick = {
                                speed = index
                                MusicPlayerSettings.setFluidSpeed(index)
                                PlayerStateHolder.bumpSettings()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "音乐律动",
                color = TextHigh,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            SheetToggle(checked = beat) { on ->
                beat = on
                MusicPlayerSettings.setFluidBeat(on)
                syncBeatGate()
                PlayerStateHolder.bumpSettings()
            }
        }
    }
}

// ================= 歌曲信息 =================

/** 歌曲信息子面板：当前曲真字段（歌名/歌手/专辑/来源/时长/请求音质/队列位置），无值不摆行。 */
@Composable
internal fun SongInfoPanel(
    state: PlayerUiState,
    onBack: () -> Unit,
) {
    val track = state.track
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        SubPanelHeader(title = "歌曲信息", onBack = onBack)
        Spacer(modifier = Modifier.height(14.dp))
        if (track == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "未在播放", color = TextLow, fontSize = 13.sp)
            }
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SheetListBg)
                .padding(vertical = 6.dp),
        ) {
            InfoRow("歌名", state.title)
            InfoRow("歌手", state.artist)
            if (state.album.isNotEmpty()) InfoRow("专辑", state.album)
            val platformName = runCatching {
                MusicRepository.sourceDisplayName(track.platform)
            }.getOrNull()
            if (!platformName.isNullOrEmpty()) InfoRow("来源", platformName)
            if (state.durationMs > 0) InfoRow("时长", formatPlayerTime(state.durationMs))
            InfoRow("音质", MusicStore.qualityName(state.quality))
            if (state.queue.isNotEmpty() && state.queueIndex >= 0) {
                InfoRow("队列", "${state.queueIndex + 1} / ${state.queue.size}")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextMedium,
            fontSize = 13.sp,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            color = TextHigh,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ================= 加到歌单 =================

/**
 * 加到歌单子面板：歌单走 MusicLibrary 既有口（列表/新建/添加全真行为，
 * 添加结果如实提示：已添加/已在歌单中/失败）。现成 MusicPlaylistPicker 是
 * AppCompatActivity 弹窗、播放页是 ComponentActivity 挂不上，故新树内同路落面。
 */
@Composable
internal fun AddPlaylistPanel(
    state: PlayerUiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val track = state.track
    var playlists by remember { mutableStateOf<List<MusicPlaylist>?>(null) }
    var newName by remember { mutableStateOf("") }

    fun reload() {
        MusicLibrary.playlists { playlists = it }
    }
    LaunchedEffect(track) { reload() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        SubPanelHeader(title = "加到歌单", onBack = onBack)
        Spacer(modifier = Modifier.height(12.dp))
        // 新建行：输入＋创建（创建后直接把当前曲加进新歌单）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = newName,
                onValueChange = { newName = it },
                textStyle = TextStyle(color = TextHigh, fontSize = 14.sp),
                cursorBrush = SolidColor(TextHigh),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SheetInset)
                    .padding(horizontal = 12.dp),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (newName.isEmpty()) {
                            Text(text = "新建歌单", color = TextLow, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "创建",
                color = AccentRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    val name = newName.trim()
                    when {
                        track == null -> sheetToast(context, "未在播放")
                        name.isEmpty() -> sheetToast(context, "先输入歌单名")
                        else -> MusicLibrary.createPlaylist(name) { created ->
                            if (created == null) {
                                sheetToast(context, "创建失败")
                            } else {
                                newName = ""
                                MusicLibrary.addToPlaylist(created.id, track) { added ->
                                    sheetToast(
                                        context,
                                        if (added) "已添加到「$name」" else "已在「$name」中",
                                    )
                                    onDone()
                                }
                            }
                        }
                    }
                },
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        val list = playlists
        when {
            list == null -> PanelHint("正在加载歌单")
            list.isEmpty() -> PanelHint("暂无歌单")
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(list) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (track == null) {
                                    sheetToast(context, "未在播放")
                                } else {
                                    MusicLibrary.addToPlaylist(playlist.id, track) { added ->
                                        sheetToast(
                                            context,
                                            if (added) {
                                                "已添加到「${playlist.name}」"
                                            } else {
                                                "已在「${playlist.name}」中"
                                            },
                                        )
                                        onDone()
                                    }
                                }
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SheetInset),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_music_queue),
                                contentDescription = null,
                                tint = TextMedium,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = playlist.name ?: "",
                            color = TextHigh,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (playlist.trackCount >= 0) {
                            Text(
                                text = "${playlist.trackCount} 首",
                                color = TextLow,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ================= 下载选档 =================

/**
 * 下载选档子面板（每次询问模式的落点，与列表页 downloadTrack 同路）：
 * 四档逐档实测大小（MusicDownloadStore.probeQualitySizes，测不到不显大小、
 * 不估算），点档走 downloadWithQuality 真入队。
 */
@Composable
internal fun DownloadQualityPanel(
    state: PlayerUiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val track = state.track
    val values = remember {
        intArrayOf(
            MusicStore.QUALITY_LOW,
            MusicStore.QUALITY_STANDARD,
            MusicStore.QUALITY_HIGH,
            MusicStore.QUALITY_SUPER,
        )
    }
    var sizes by remember(track) { mutableStateOf<LongArray?>(null) }
    LaunchedEffect(track) {
        if (track != null) {
            MusicDownloadStore.probeQualitySizes(track, values) { sizes = it }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        SubPanelHeader(title = "下载音质", onBack = onBack)
        Spacer(modifier = Modifier.height(12.dp))
        if (track == null) {
            PanelHint("未在播放")
            return@Column
        }
        val fixed = MusicStore.getDownloadQualityFixed()
        values.forEachIndexed { i, q ->
            val size = sizes?.getOrNull(i) ?: -1L
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SheetInset)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        MusicDownloadStore.downloadWithQuality(track, q)
                        sheetToast(context, "已加入下载")
                        onDone()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SheetListBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_download),
                        contentDescription = null,
                        tint = TextHigh,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = MusicStore.qualityName(q),
                        color = TextHigh,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = when {
                            sizes == null -> "正在查询大小…"
                            size > 0 -> MusicCache.formatSize(size)
                            else -> "大小未测得"
                        },
                        color = TextLow,
                        fontSize = 11.sp,
                    )
                }
                if (q == fixed) {
                    Text(text = "默认", color = TextMedium, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun PanelHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = TextLow, fontSize = 13.sp)
    }
}
