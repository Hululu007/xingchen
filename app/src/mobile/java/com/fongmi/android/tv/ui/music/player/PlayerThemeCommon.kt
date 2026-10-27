package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.music.MusicPlayerSettings

/** 瓜子缓动 bs4.b（案卷十五节）：(0, 0, 0, 1)。 */
val Bs4B = CubicBezierEasing(0f, 0f, 0f, 1f)

/** 瓜子缓动 bs4.c（案卷十五节）：(0.3, 0, 1, 1)。 */
val Bs4C = CubicBezierEasing(0.3f, 0f, 1f, 1f)

/**
 * 瓜子缓动 bs4.d（案卷十五节亲验）：PathEasing——(0,0) → 三次 (0.05,0)(0.133333,0.06)
 * (0.166666,0.4) → 三次 (0.208333,0.82)(0.25,1)(1,1)。流光 CD 扫光/光泽/歌词展开用。
 */
val Bs4D: PathEasing = PathEasing(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    },
)

/**
 * 按压回弹（瓜子 bounceClick 通则，案卷一节）：tween(150, bs4.a) 缩到指定值。
 * 与 clickable 共用同一个 interactionSource：调用方把返回的 source 传给 clickable。
 */
@Composable
fun Modifier.pressScale(scaleDown: Float, interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = tween(150, easing = Bs4A),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun rememberPressSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/** 时间格式 m:ss（与 PlaybackScrubber 的 formatTime 同口径，供主题自有进度件用）。 */
fun formatPlayerTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * 播放模式图标（模式值＝MusicQueue：0 顺序／1 单曲循环／2 列表循环／3 随机）。
 * 顺序＝自绘 Reorder（三线＋点，瓜子 PlayMode 顺序段同为自绘 Reorder 路径的等价画法）；
 * 循环走 ic_player_loop，单曲循环同图加「1」角标，随机走 ic_music_shuffle。
 */
@Composable
fun ModeIcon(
    mode: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        1 -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_player_loop),
                contentDescription = "单曲循环",
                tint = tint,
                modifier = Modifier.matchParentSize(),
            )
            Text(
                text = "1",
                color = tint,
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        2 -> Icon(
            painter = painterResource(R.drawable.ic_player_loop),
            contentDescription = "列表循环",
            tint = tint,
            modifier = modifier,
        )
        3 -> Icon(
            painter = painterResource(R.drawable.ic_music_shuffle),
            contentDescription = "随机播放",
            tint = tint,
            modifier = modifier,
        )
        else -> Canvas(modifier = modifier) {
            val stroke = 1.8.dp.toPx()
            val w = size.width
            val h = size.height
            val ys = listOf(0.25f, 0.5f, 0.75f)
            for (fy in ys) {
                val y = h * fy
                drawCircle(
                    color = tint,
                    radius = stroke * 0.85f,
                    center = Offset(w * 0.14f, y),
                )
                drawLine(
                    color = tint,
                    start = Offset(w * 0.33f, y),
                    end = Offset(w * 0.92f, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 主题族（PlayerRoot 分发用）：流光 CD 三变体同族。 */
enum class ThemeFamily { CLASSIC, COVER, APPLE, CD, SHELF }

fun themeFamilyOf(themeId: String): ThemeFamily = when {
    themeId == MusicPlayerSettings.THEME_COVER_IMMERSIVE -> ThemeFamily.COVER
    themeId == MusicPlayerSettings.THEME_APPLE_MUSIC -> ThemeFamily.APPLE
    MusicPlayerSettings.isCdTheme(themeId) -> ThemeFamily.CD
    themeId == MusicPlayerSettings.THEME_RECORD_SHELF -> ThemeFamily.SHELF
    else -> ThemeFamily.CLASSIC
}

/** 主题显示名（瓜子 PlayerTheme 的 displayName 亲验，主题面板与工具行尾共用）。 */
fun themeDisplayName(themeId: String): String = when (themeId) {
    MusicPlayerSettings.THEME_COVER_IMMERSIVE -> "封面卡片"
    MusicPlayerSettings.THEME_APPLE_MUSIC -> "Apple Music"
    MusicPlayerSettings.THEME_LUMINOUS_CD -> "流光 CD"
    MusicPlayerSettings.THEME_LUMINOUS_CD_DARK -> "流光 CD 深色"
    MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO -> "流光 CD 自动"
    MusicPlayerSettings.THEME_RECORD_SHELF -> "黑胶唱片架"
    else -> "经典"
}
