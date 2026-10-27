package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.api.music.LxPlatformSearch
import com.fongmi.android.tv.bean.music.MusicTrack

/**
 * 四主题共用件（数值认 gz-player-values.md 各节与本包各主题文件的行号注）：
 * 封面图、标题交叉淡入、圆形玻璃键、歌曲信息面、系统音量、工具面板 token。
 * 本文件不持状态、不读中枢——数据一律由调用方从 PlayerUiState 传入（一套渲染口径）。
 */

/** 无水波点击（与本包既有键同口径：indication=null＋独立 InteractionSource）。 */
@Composable
fun Modifier.clickableNoRipple(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick
    )

/** 封面位图（中枢 coverBitmap；url 对不上不画由中枢保证，这里只负责画）。 */
@Composable
fun CoverImage(state: PlayerUiState, scale: ContentScale, modifier: Modifier = Modifier) {
    val bmp = state.coverBitmap ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        contentScale = scale,
        modifier = modifier
    )
}

/**
 * 标题交叉淡入（瓜子 AnimatedSongText，§五）：入 fadeIn(200, bs4.e)＋尺寸同参、出 fadeOut(150, bs4.c)；
 * 文本切换（切歌换标题）才走，同一文本重组不重播。
 */
@Composable
fun AnimatedSongTitle(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(200, easing = EaseBs4E)) + androidx.compose.animation.scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(200, easing = EaseBs4E)
            )) togetherWith fadeOut(tween(150, easing = EaseBs4C))
        },
        label = "songTitle",
        modifier = modifier
    ) { shown ->
        Text(
            text = shown,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 圆形玻璃键（§五页头键/§九圆键同型）：面白 0.14、按压 0.88（pressScale）。 */
@Composable
fun GlassCircleButton(
    sizeDp: Int,
    iconSizeDp: Int,
    faceAlpha: Float = 0.14f,
    pressTo: Float = 0.88f,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .pressScale(pressTo)
            .background(Color.White.copy(alpha = faceAlpha), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content(Color.White.copy(alpha = 0.85f))
    }
}

// ================= 系统音量（瓜子走播放器音量；星辰 MusicController 无音量口，落系统媒体音量，口径差注名） =================

fun systemVolumeFraction(context: Context): Float {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0f
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0f
    return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max.toFloat()
}

fun setSystemVolumeFraction(context: Context, fraction: Float) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    val target = (fraction.coerceIn(0f, 1f) * max).toInt()
    try {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    } catch (t: Throwable) {
        // 个别机型限制程序调音量：静默失败不崩（防崩口径）
    }
}

// ================= 歌曲信息面（瓜子 SongInfoPage，§十三；CD 与唱片架共用，配色由主题供） =================

data class SongInfoColors(
    val high: Color,
    val medium: Color,
    val low: Color,
    val inset: Color,
    val accent: Color
)

@Composable
fun SongInfoFace(state: PlayerUiState, colors: SongInfoColors, modifier: Modifier = Modifier) {
    val track = state.track
    Column(modifier.fillMaxWidth()) {
        // HeroCard（§十三）：封面 80 圆角 16＋歌名 20sp/800＋副行 12sp
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(20.dp))
                .background(colors.inset, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(80.dp)
                    .shadow(2.dp, RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (state.coverBitmap != null) {
                    CoverImage(state, ContentScale.Crop, Modifier.size(80.dp))
                } else {
                    QueueIcon(28.dp, Color.White.copy(alpha = 0.45f))
                }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    text = track?.displayTitle() ?: "",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W800,
                    color = colors.high,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = track?.displayArtist() ?: "",
                    fontSize = 12.sp,
                    color = colors.medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // 「歌曲信息」段（§十三 GlassSection：圆角 20、段题 12sp 字距 0.5、行距 6）
        GlassSection(title = "歌曲信息", colors = colors) {
            InfoRow("歌手", track?.displayArtist()?.takeIf { it.isNotEmpty() }, colors)
            InfoRow("专辑", track?.displayAlbum()?.takeIf { it.isNotEmpty() }, colors)
            InfoRow(
                "时长",
                track?.let { if (it.duration > 0) formatClock(it.duration * 1000L) else null },
                colors
            )
            InfoRow("音质", state.audioLabel, colors)
            InfoRow(
                "来源",
                track?.platform?.takeIf { it.isNotEmpty() }?.let { platformDisplayName(it) },
                colors
            )
        }
    }
}

private fun platformDisplayName(platform: String): String {
    val name = try {
        LxPlatformSearch.platformName(platform)
    } catch (t: Throwable) {
        null
    }
    return if (!name.isNullOrEmpty()) name else platform
}

@Composable
private fun GlassSection(
    title: String,
    colors: SongInfoColors,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp))
            .background(colors.inset, RoundedCornerShape(20.dp))
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            color = colors.medium,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?, colors: SongInfoColors) {
    if (value == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = colors.medium)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 13.sp,
            color = colors.high,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ================= 工具面板 token（SheetTokens 沉浸深色一套，§九；他令面板底用深色玻璃） =================

object SheetTokens {
    val PanelBg = Color(0xFF1C1C1E)
    val CardBg = Color(0xFF2C2C2E)
    val SelectedBg = Color.White.copy(alpha = 0.20f)
    val Border = Color.White.copy(alpha = 0.14f)
    val InsetBg = Color.White.copy(alpha = 0.10f)
    val TextHigh = Color(0xFFF2F2F7)
    val TextMedium = Color(0xFF8E8E93)
    val TextLow = Color(0xFF636366)
    val CoverPlaceholder = Color(0xFF8E8E93).copy(alpha = 0.12f)
    val Handle = Color.White.copy(alpha = 0.28f)
}
