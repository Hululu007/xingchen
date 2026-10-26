package com.fongmi.android.tv.ui.music.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.fongmi.android.tv.music.MusicStore

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
            // 功能入口位（本单只占位：主题切换在此、内容按下单填）
            ToolsSlotRow(title = "播放页主题", tail = "经典")
            ToolsSlotRow(title = "音质", tail = MusicStore.qualityName(state.quality))
            ToolsSlotRow(title = "定时关闭", tail = "")
            ToolsSlotRow(title = "歌词样式", tail = "")
        }
    }
}

@Composable
private fun ToolsSlotRow(title: String, tail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
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
