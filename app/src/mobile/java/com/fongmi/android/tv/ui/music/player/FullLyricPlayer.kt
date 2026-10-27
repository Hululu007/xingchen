package com.fongmi.android.tv.ui.music.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/**
 * 全屏歌词页（2026-10-27 瓜子源码对死订正，装配认 AppleLyricsView 一链，不认截图）：
 * 氛围层垫底（与经典页共用 AmbientLayer，封面在全屏页只有这一层糊底＋页头小图，无锐封面块）
 * → 纵列（fillMaxSize＋页级状态栏/导航栏 insets，照 AppleMusicLyricsPage 包装口径）：
 * 12dp 间隔 → 页头（AppleLyricsSongHeader 装配）→ 6dp 间隔 → 歌词区（weight 1，
 * LyricEngine 全屏链装配，横 28dp）。
 * 页内没有进度条与模式筹：瓜子此页底部无此二件（进度归播放屏的 Scrubber、Std/逐字归歌词样式面板），
 * 此前按定案图装的方块封面＋三键行＋双筹＋细进度线经源码核查全不成立，已整段撤除。
 * 与经典页同一棵树、共用中枢状态与引擎，不另建第二套状态/歌词逻辑。
 */
@Composable
fun FullLyricPlayer(
    state: PlayerUiState,
    progress: PlayerProgress,
    onLyricStyleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 歌词模式（Std/逐字）：读现行存档键（与经典页共用同一键）；本页无切换件，切换在歌词样式面板（后续单）
    val wordMode = MusicPlayerSettings.isFullLyricWordByWord()
    Box(modifier.fillMaxSize()) {
        AmbientLayer(state)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Spacer(Modifier.height(12.dp))
            FullSongHeader(state = state, onLyricStyleClick = onLyricStyleClick)
            Spacer(Modifier.height(6.dp))
            // 歌词区（LyricEngine 全屏链装配；空态与经典页同文同位）
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
            ) {
                if (state.lyricLines.isEmpty()) {
                    Text(
                        text = "暂无歌词",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    FullLyricList(
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * 全屏页头（AppleLyricsSongHeader 逐项对照 LyricsViewKt 亲读）：
 * 横行（内边距 横 28／纵 6、子件间距 12）：封面小图 40dp（＝LYRIC_SIZE_MAX、圆角 6 裁剪、
 * 底为黑 0.20→0.28 竖渐变——jadx mg2.I 两色读法、参数位未定名）＋歌名/歌手列（weight 1：
 * 歌名 16sp/800/行高 21/字距 −0.3/白、1dp 间隔、歌手 13sp/500/行高 17/白 0.55，均一行省略）
 * ＋歌词样式玻璃键 34dp（面白 0.10、按压 0.88、图标 18dp 白 0.85）。
 * 瓜子页头整行另带点击（回调自 PlayerScreen 侧传入、语义未定名）——本单未自造行为、未落整行点击；
 * 玻璃键的背板折射机制星辰播放器无此层，以面色近似、待后续面板单与背板一并裁。
 */
@Composable
private fun FullSongHeader(
    state: PlayerUiState,
    onLyricStyleClick: () -> Unit
) {
    val track = state.track
    val title = track?.displayTitle()?.takeIf { it.isNotEmpty() } ?: "未在播放"
    val artist = track?.displayArtist()?.takeIf { it.isNotEmpty() } ?: "—"

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.20f),
                            Color.Black.copy(alpha = 0.28f)
                        )
                    )
                )
        ) {
            val bmp = state.coverBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W800,
                lineHeight = 21.sp,
                letterSpacing = (-0.3).sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = artist,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 17.sp,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val btnSource = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .pressScale(btnSource, 0.88f)
                .clickable(
                    interactionSource = btnSource,
                    indication = null,
                    onClick = onLyricStyleClick
                ),
            contentAlignment = Alignment.Center
        ) {
            LyricStyleGlyph(size = 18.dp, color = Color.White.copy(alpha = 0.85f))
        }
    }
}
