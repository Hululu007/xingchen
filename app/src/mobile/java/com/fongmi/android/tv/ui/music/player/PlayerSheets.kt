package com.fongmi.android.tv.ui.music.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.fongmi.android.tv.R
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/**
 * 同树面板（照瓜子：面板不是独立页，是播放树里的 sheet，读同一份快照）。
 * 底色走瓜子 SheetTokens 深值（sheetBg #FF1C1C1E）——他定过音乐弹窗
 * 用深色玻璃一系，此处先以深面落位，材料细化随本体单。
 * 本单范围：队列面板是真数据真动作（点行即播）；工具面板先落入口位
 * （英雄行是真数据，各功能行只占位、不可点，内容按下单再填）。
 */

private val SheetBg = Color(0xFF1C1C1E)
private val SheetListBg = Color(0xFF2C2C2E)
private val TextHigh = Color(0xFFF2F2F7)
private val TextLow = Color(0xFF636366)
private val AccentRed = Color(0xFFFA243C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerUiState,
    onPlayAt: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "播放列表",
                    color = TextHigh,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${state.queue.size} 首",
                    color = TextLow,
                    fontSize = 12.sp,
                )
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSheet(
    state: PlayerUiState,
    currentThemeId: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showThemePanel by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        dragHandle = null,
    ) {
        if (showThemePanel) {
            ThemeSubPanel(
                state = state,
                currentThemeId = currentThemeId,
                onBack = { showThemePanel = false },
                onThemeSelected = onThemeSelected,
            )
            return@ModalBottomSheet
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 28.dp),
        ) {
            // 英雄行（真数据）
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
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifEmpty { "未在播放" },
                        color = TextHigh,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.artist,
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // 播放页主题（点进主题子面板，五主题在播放页明面可达）
            ToolsSlotRow(
                title = "播放页主题",
                tail = themeDisplayName(currentThemeId),
                onClick = { showThemePanel = true },
            )
            ToolsSlotRow(title = "音质", tail = MusicStore.qualityName(state.quality))
            ToolsSlotRow(title = "定时关闭", tail = "")
            ToolsSlotRow(title = "歌词样式", tail = "")
        }
    }
}

@Composable
private fun ToolsSlotRow(title: String, tail: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 4.dp),
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
        }
        Text(text = "›", color = TextLow, fontSize = 20.sp)
    }
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
