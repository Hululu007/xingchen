package com.fongmi.android.tv.ui.music.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.music.MusicStore

/**
 * 音质选择面（案卷十一节 PlayerVersionSheet 口径，与歌词版本面同构）：
 * 头（标题 20sp＋关闭圆钮 36）→歌曲行（封面 52 圆角 10、发丝边 .5）→
 * 卡式列表（项距 6、卡高 56 圆角 16；选中＝Accent 竖渐变 .12→.04＋勾，
 * 未选＝sheetInset）。数据＝仓库现行四档请求音质（值与存档口径不变，
 * 显示名照 MusicStore.qualityName：Hi-Res/无损/极高/标准）；点选经中枢
 * setQuality 写存档并递服务按新档重取当前曲，当场生效。所选只是请求上限、
 * 实际播源能给的最高档（既定口径，面板不冒充实际音质）。
 * 档序装配为由高到低（Hi-Res→标准）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    state: PlayerUiState,
    onDismiss: () -> Unit,
) {
    val tiers = remember {
        listOf(
            MusicStore.QUALITY_SUPER,
            MusicStore.QUALITY_HIGH,
            MusicStore.QUALITY_STANDARD,
            MusicStore.QUALITY_LOW,
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
        ) {
            // 头：标题＋关闭（PlayerVersionSheet 同构）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "音质",
                    color = TextHigh,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    lineHeight = 26.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "×", color = TextMedium, fontSize = 18.sp)
                }
            }
            // 歌曲行（封面 52、圆角 10、发丝边 0.5）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF8E8E93).copy(alpha = 0.12f))
                        .border(
                            0.5.dp,
                            Color.White.copy(alpha = 0.14f),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = state.coverBitmap
                    if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
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
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.artist.isNotEmpty()) {
                        Text(
                            text = state.artist,
                            color = TextMedium,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "当前请求：${MusicStore.qualityName(state.quality)}",
                        color = TextLow,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 档位卡列表（横内边距 16、上 4 下 12、项距 6）
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                items(tiers) { q ->
                    QualityTierCard(
                        quality = q,
                        selected = state.quality == q,
                        onClick = {
                            PlayerStateHolder.setQuality(q)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** 档位卡（高 56、圆角 16、内边距 12×8；左图标盒 36 圆角 10；选中尾勾）。 */
@Composable
private fun QualityTierCard(
    quality: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    val bg = if (selected) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(AccentRed.copy(alpha = 0.12f), AccentRed.copy(alpha = 0.04f)),
            ),
        )
    } else {
        Modifier.background(SheetInset)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(bg)
            .pressScale(0.98f, src)
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) AccentRed.copy(alpha = 0.13f) else SheetListBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = if (selected) AccentRed else TextHigh,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = MusicStore.qualityName(quality),
            color = TextHigh,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(AccentRed),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_check_14),
                    contentDescription = "当前档位",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
