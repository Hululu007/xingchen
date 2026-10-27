package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import kotlin.math.roundToInt

/**
 * Apple Music 主题（案卷五节）：页内顶距 0；封面盒 weight=1 铺满；标题列相对封面
 * 下沿上叠 −12dp、横边距 24——头右爱心＋三个点（盒 34、图标 20、白 .85、面白 .14
 * 圆形玻璃、按压 0.88，爱心前间隔 8、两键间 12），无队列键（队列在功能行）；
 * 歌名 20sp/800/行高 26/字距 −0.4/白 .96（高 30）→间隔 5→歌手 14sp/500/行高 18/
 * 白 .6（高 22）→专辑行 12sp/500/行高 16/白 .42（高 18，仅专辑非空且≠「未知专辑」）。
 * 底部栈：PlaybackScrubber 的 Apple 调用值（横 24、轨 6、填充 .58/.9、顶盒关、
 * 底行关）→控制行（高 96、上叠 −18；三键间距 24；上一首/下一首 盒 56、图标 48×42、
 * 按压 0.88、alpha .9/.35；播放/暂停 盒 84、图标 60×52.5、按压 0.9、alpha .95/.35）
 * →间隔 6→音量行（横 24、高 32，系统媒体音量）→间隔 2→功能行（高 52、图标间距
 * 48：歌词 24/音质 24/播放列表 28，共用 alpha 启用 .5/禁用 .25）→底部间隔 22。
 * 根＝共用外壳（主色动画底＋氛围层，与经典同件）。封面盒下滑收回成条（手势装配）。
 */
@Composable
fun AppleMusicPlayer(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = Color(state.dominantColor),
        animationSpec = tween(450, easing = Bs4A),
        label = "appleRootBg",
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
            // 封面盒（weight=1 铺满；下滑 48 收回成条）
            val density = LocalDensity.current
            val collapsePx = with(density) { 48.dp.toPx() }
            var pullDown by remember { mutableStateOf(0f) }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp)
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
            ) {
                val side = minOf(maxWidth, maxHeight)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(side)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
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
                                    .background(Color(0xFF3A3A3C)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_music_note),
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                    }
                }
            }
            // 标题列（相对封面下沿上叠 −12、横 24；列首 8dp 高间隔为头右注的装配落位）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-12).dp)
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.title.ifEmpty { "未在播放" },
                        color = Color.White.copy(alpha = 0.96f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 26.sp,
                        letterSpacing = (-0.4).sp,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .basicMarquee(),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 头右：爱心（盒 34、图标 20、面白 .14 圆形玻璃、按压 0.88）
                    GlassCircleKey(
                        icon = if (state.favorite) R.drawable.ic_music_heart_fill else R.drawable.ic_music_heart,
                        desc = "收藏",
                        alpha = 0.85f,
                    ) { onToggleFavorite() }
                    Spacer(modifier = Modifier.width(12.dp))
                    GlassCircleKey(
                        icon = R.drawable.ic_music_more,
                        desc = "更多",
                        alpha = 0.85f,
                    ) { onOpenTools() }
                }
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = state.artist,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.album.isNotEmpty() && state.album != "未知专辑") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = state.album,
                            color = Color.White.copy(alpha = 0.42f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 进度（Apple 调用值：轨 6、填充 .58/.9、顶盒关、底行关）
            PlaybackScrubber(
                state = state,
                onSeek = onSeek,
                modifier = Modifier.padding(horizontal = 24.dp),
                trackHeightDp = 6f,
                fillStartAlpha = 0.58f,
                fillEndAlpha = 0.9f,
                showTopBox = false,
                showBottomRow = false,
            )
            // 控制行（高 96、上叠 −18、三键间距 24）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .offset(y = (-18).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TransportKey(
                    icon = R.drawable.ic_music_prev,
                    desc = "上一首",
                    boxSize = 56.dp,
                    iconW = 48.dp,
                    iconH = 42.dp,
                    press = 0.88f,
                    enabled = state.canPrev,
                    enabledAlpha = 0.9f,
                ) { onPrev() }
                Spacer(modifier = Modifier.width(24.dp))
                TransportKey(
                    icon = if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play,
                    desc = if (state.isPlaying) "暂停" else "播放",
                    boxSize = 84.dp,
                    iconW = 60.dp,
                    iconH = 52.5.dp,
                    press = 0.9f,
                    enabled = true,
                    enabledAlpha = 0.95f,
                ) { onToggle() }
                Spacer(modifier = Modifier.width(24.dp))
                TransportKey(
                    icon = R.drawable.ic_music_next,
                    desc = "下一首",
                    boxSize = 56.dp,
                    iconW = 48.dp,
                    iconH = 42.dp,
                    press = 0.88f,
                    enabled = state.canNext,
                    enabledAlpha = 0.9f,
                ) { onNext() }
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 音量行（横 24、高 32）
            VolumeRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(32.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            // 功能行（高 52、图标间距 48：歌词 24/音质 24/播放列表 28，alpha 启用 .5）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                FunctionKey(
                    icon = R.drawable.ic_music_note,
                    desc = "歌词",
                    iconSize = 24.dp,
                    enabled = state.lyrics is LyricUiState.Content,
                ) { onOpenLyrics() }
                Spacer(modifier = Modifier.width(48.dp))
                FunctionKey(
                    icon = R.drawable.ic_music_eq,
                    desc = "音质",
                    iconSize = 24.dp,
                    enabled = true,
                ) { onOpenTools() }
                Spacer(modifier = Modifier.width(48.dp))
                FunctionKey(
                    icon = R.drawable.ic_music_queue,
                    desc = "播放列表",
                    iconSize = 28.dp,
                    enabled = true,
                ) { onOpenQueue() }
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

/** 头右圆形玻璃键：盒 34、图标 20、白 .85×alpha、面白 .14、按压 0.88。 */
@Composable
private fun GlassCircleKey(icon: Int, desc: String, alpha: Float, onClick: () -> Unit) {
    val src = rememberPressSource()
    Box(
        modifier = Modifier
            .size(34.dp)
            .pressScale(0.88f, src)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = src, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 运输键：盒/图标尺寸与 alpha 随调用值；不可用 alpha .35。 */
@Composable
private fun TransportKey(
    icon: Int,
    desc: String,
    boxSize: androidx.compose.ui.unit.Dp,
    iconW: androidx.compose.ui.unit.Dp,
    iconH: androidx.compose.ui.unit.Dp,
    press: Float,
    enabled: Boolean,
    enabledAlpha: Float,
    onClick: () -> Unit,
) {
    val src = rememberPressSource()
    Box(
        modifier = Modifier
            .size(boxSize)
            .pressScale(press, src)
            .clickable(
                interactionSource = src,
                indication = null,
                enabled = enabled,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = Color.White.copy(alpha = if (enabled) enabledAlpha else 0.35f),
            modifier = Modifier.size(iconW, iconH),
        )
    }
}

/** 功能行键：共用 alpha 启用 .5／禁用 .25。 */
@Composable
private fun FunctionKey(icon: Int, desc: String, iconSize: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = Color.White.copy(alpha = if (enabled) 0.5f else 0.25f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 系统媒体音量行（AudioManager STREAM_MUSIC 直读直写；行内自带小/大音量图标，
 * 图标规格案卷未给、为装配）。拖动即设系统音量，与瓜子 SystemVolumeCard 同源口径。
 */
@Composable
private fun VolumeRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVol = remember { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }
    var fraction by remember {
        mutableFloatStateOf(
            if (audioManager != null && maxVol > 0) {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat()
            } else {
                0f
            },
        )
    }
    fun applyFraction(f: Float) {
        val v = f.coerceIn(0f, 1f)
        fraction = v
        if (audioManager != null && maxVol > 0) {
            runCatching {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (v * maxVol).roundToInt(),
                    0,
                )
            }
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_widget_volume_low),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(maxVol) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> applyFraction(offset.x / size.width) },
                        onDragEnd = {},
                        onDragCancel = {},
                        onHorizontalDrag = { change, _ ->
                            applyFraction(change.position.x / size.width)
                        },
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            val cy = size.height / 2f
            val stroke = 3.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (fraction > 0f) {
                drawLine(
                    color = Color.White.copy(alpha = 0.75f),
                    start = Offset(0f, cy),
                    end = Offset(size.width * fraction, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Icon(
            painter = painterResource(R.drawable.ic_widget_volume_high),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}
