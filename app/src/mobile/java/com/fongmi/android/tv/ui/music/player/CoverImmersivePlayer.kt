package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R

/**
 * 封面卡片主题 cover_immersive（案卷四节）：纵列＝间隔 42→歌名（高 30、左右 28、
 * 20sp/800/白 .96/字距 −0.4/行高 26）→间隔 4→歌手（高 22、14sp/500/白 .65/行高 18）
 * →间隔 22→封面槽（左右 24）→间隔 14→玻璃歌词条（左右 24）→底部间隔 12。
 * 封面几何：宽＝min(可用宽×0.98, 可用高/0.65)、高＝宽×0.65、圆角 22；身后错位卡
 * （圆角 22、阴影 6、渐变白 .08→.03、描边 1 白 .12）。默认态无进度条、无菜单键、
 * 无队列键；点封面展开（本体切换 snap(0)、一处 tween(250, bs4.a) 驱动罩/键浮现）：
 * 封面框变 BackdropCard（渐变 #3A3A3C→#1C1C1E＋内铺模糊封面＋黑 .35）、框内四玻璃键
 * （各 42、图标 22、白 tint：上一首/播放列表/歌曲信息/下一首）、其下间隔 12 接
 * PlaybackScrubber（经典调用值、底行开）、再下歌词窗（内边距 横18/纵14，走同一台
 * 引擎的封面窗喂参）。
 * 根＝共用外壳（主色 450ms 动画底＋BlurredCoverAmbient 氛围层，与经典同件）。
 */
@Composable
fun CoverImmersivePlayer(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenLyricVersions: () -> Unit,
    onOpenQuality: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = Color(state.dominantColor),
        animationSpec = tween(450, easing = Bs4A),
        label = "coverRootBg",
    )
    var expanded by remember { mutableStateOf(false) }
    val expandAnim by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(250, easing = Bs4A),
        label = "coverExpand",
    )

    Box(modifier = modifier.fillMaxSize().background(bgColor)) {
        val bmp = state.coverBitmap
        if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
            AmbientLayer(bitmap = bmp, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Spacer(modifier = Modifier.height(42.dp))
            // 歌名（高 30、左右 28）；标题区下滑收回成条（与经典页标题行同手势口径）
            val density = androidx.compose.ui.platform.LocalDensity.current
            val collapsePx = with(density) { 48.dp.toPx() }
            var pullDown by remember { mutableStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .padding(horizontal = 28.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { pullDown = 0f },
                            onDragEnd = {
                                if (pullDown >= collapsePx) onCollapse()
                                pullDown = 0f
                            },
                            onDragCancel = { pullDown = 0f },
                            onVerticalDrag = { _, amount ->
                                if (amount > 0f) pullDown += amount
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = state.title.ifEmpty { "未在播放" },
                    color = Color.White.copy(alpha = 0.96f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 26.sp,
                    letterSpacing = (-0.4).sp,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 歌手（高 22、左右 28）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = state.artist,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(22.dp))
            // 封面槽（左右 24、权重区）
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
            ) {
                val coverW = minOf(maxWidth * 0.98f, maxHeight / 0.65f)
                val coverH = coverW * 0.65f
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 错位卡（身后探出的一张空卡）
                    Box(
                        modifier = Modifier
                            .size(coverW, coverH)
                            .offset(x = (-8).dp, y = 8.dp)
                            .rotate(-3f)
                            .shadow(6.dp, RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.08f),
                                        Color.White.copy(alpha = 0.03f),
                                    ),
                                ),
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp)),
                    )
                    // 封面卡本体（点按展开/收起）
                    Box(
                        modifier = Modifier
                            .size(coverW, coverH)
                            .shadow(6.dp, RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { expanded = !expanded },
                    ) {
                        if (expanded) {
                            // BackdropCard：渐变底＋内铺模糊封面＋黑 .35
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)),
                                        ),
                                    ),
                            )
                            if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .blur(24.dp),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                            )
                        } else if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)),
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_music_note),
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                        // 展开态：框内四玻璃键（各 42、图标 22）
                        if (expandAnim > 0.01f) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 14.dp)
                                    .alpha(expandAnim),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GlassKey(icon = R.drawable.ic_music_prev, desc = "上一首") { onPrev() }
                                Spacer(modifier = Modifier.weight(1f))
                                GlassKey(icon = R.drawable.ic_music_queue, desc = "播放列表") { onOpenQueue() }
                                Spacer(modifier = Modifier.weight(1f))
                                GlassKey(icon = R.drawable.ic_player_info, desc = "歌曲信息") { onOpenTools() }
                                Spacer(modifier = Modifier.weight(1f))
                                GlassKey(icon = R.drawable.ic_music_next, desc = "下一首") { onNext() }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (!expanded) {
                // 默认态：玻璃歌词条（左右 24、高 48、圆角 22）
                CoverLyricGlassBar(
                    state = state,
                    onToggle = onToggle,
                    onToggleFavorite = onToggleFavorite,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                // 展开态：Scrubber（间隔 12）＋歌词窗
                PlaybackScrubber(
                    state = state,
                    onSeek = onSeek,
                    onLyricChipClick = onOpenLyricVersions,
                    onQualityChipClick = onOpenQuality,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .padding(horizontal = 24.dp)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    when (val lyrics = state.lyrics) {
                        is LyricUiState.Content -> {
                            if (lyrics.lines.isEmpty()) {
                                LyricHint(text = "暂无歌词")
                            } else {
                                AnchoredLyricStage(
                                    lines = lyrics.lines,
                                    timed = lyrics.timed,
                                    positionMs = state.positionMs,
                                    isPlaying = state.isPlaying,
                                    onSeekLine = onSeek,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        LyricUiState.Searching -> LyricHint(text = "正在找歌词")
                        LyricUiState.None -> LyricHint(text = "暂无歌词")
                        LyricUiState.Idle -> Unit
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** 展开态封面框内的玻璃键：42 盒、图标 22、白 tint（面＝白 .14＋描边，玻璃件等价面）。 */
@Composable
private fun GlassKey(icon: Int, desc: String, onClick: () -> Unit) {
    val src = rememberPressSource()
    Box(
        modifier = Modifier
            .size(42.dp)
            .pressScale(0.92f, src)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(interactionSource = src, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 默认态玻璃歌词条 CoverLyricGlassBar（案卷四节）：高 48、圆角 22、左右内边距 16；
 * 面＝基底 #FF1E1E22＋糊封面（chromeFrost 模糊回退 18）＋渐变白 .26→.08＋描边 1＋阴影 4；
 * 左收藏（40 区/图标 20）、中单行歌词（13sp/500/白 .92、空 .45、居中、无逐字）、
 * 右播放/暂停（40 区/图标 24）。
 */
@Composable
private fun CoverLyricGlassBar(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp = state.coverBitmap
    val small = remember(bmp) {
        bmp?.let { runCatching { android.graphics.Bitmap.createScaledBitmap(it, 64, 64, true) }.getOrNull() }
    }
    val currentLine = (state.lyrics as? LyricUiState.Content)?.let { content ->
        if (state.lyricIndex in content.lines.indices) content.lines[state.lyricIndex].text else ""
    } ?: ""
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(4.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E22)),
        )
        if (small != null && state.coverBitmapUrl == state.coverUrl) {
            Image(
                bitmap = small.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(18.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.26f),
                            Color.White.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp)),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左收藏 40 区／图标 20
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleFavorite() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (state.favorite) R.drawable.ic_music_heart_fill else R.drawable.ic_music_heart
                    ),
                    contentDescription = "收藏",
                    tint = Color.White.copy(alpha = if (state.favorite) 1f else 0.45f),
                    modifier = Modifier.size(20.dp),
                )
            }
            // 中单行歌词（居中、无逐字）
            Text(
                text = currentLine,
                color = Color.White.copy(alpha = if (currentLine.isEmpty()) 0.45f else 0.92f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 右播放/暂停 40 区／图标 24
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play
                    ),
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
