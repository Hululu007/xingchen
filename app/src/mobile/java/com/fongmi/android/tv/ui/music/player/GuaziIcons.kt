package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放页图标自绘（不引图标库）：路径取通行矢量画法，尺寸由调用方按瓜子数值给。
 * 菜单三线照数值表抠到的 path：M3,5 L21,5 M3,12 L21,12 M3,19 L21,19、描边 2.5、圆头。
 */

/** 瓜子 MotionKt.c 同型按压回弹：按下缩放（默认 0.85，数值表爱心行抠值；键位共用同一修饰）。 */
@Composable
fun Modifier.pressScale(pressedScale: Float = 0.85f): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return pressScale(interaction, pressedScale)
}

@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.85f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(150),
        label = "pressScale"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 菜单（三横线）：24 视窗、三线 y=5/12/19、x 3→21、描边 2.5 圆头。 */
@Composable
fun MenuIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val sw = 2.5f * s
        for (y in listOf(5f, 12f, 19f)) {
            drawLine(
                color = color,
                start = Offset(3f * s, y * s),
                end = Offset(21f * s, y * s),
                strokeWidth = sw,
                cap = StrokeCap.Round
            )
        }
    }
}

/** 播放列表：左侧三短线＋右下小三角（20dp 盒内画法）。 */
@Composable
fun QueueIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val sw = 2f * s
        for ((y, x2) in listOf(5f to 15f, 10f to 15f, 15f to 10f)) {
            drawLine(
                color = color,
                start = Offset(3f * s, y * s),
                end = Offset(x2 * s, y * s),
                strokeWidth = sw,
                cap = StrokeCap.Round
            )
        }
        val tri = Path().apply {
            moveTo(14f * s, 15.5f * s)
            lineTo(14f * s, 22f * s)
            lineTo(21f * s, 18.75f * s)
            close()
        }
        drawPath(tri, color)
    }
}

/** 播放三角。 */
@Composable
fun PlayIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val p = Path().apply {
            moveTo(8f * s, 5f * s)
            lineTo(19f * s, 12f * s)
            lineTo(8f * s, 19f * s)
            close()
        }
        drawPath(p, color)
    }
}

/** 暂停双杠。 */
@Composable
fun PauseIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val w = 4f * s
        val h = 14f * s
        val r = CornerRadius(1.2f * s, 1.2f * s)
        drawRoundRect(color, Offset(6f * s, 5f * s), Size(w, h), r)
        drawRoundRect(color, Offset(14f * s, 5f * s), Size(w, h), r)
    }
}

/** 下一首：三角＋右杠（条上小键与队列跳播共用画法）。 */
@Composable
fun NextIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val p = Path().apply {
            moveTo(6f * s, 6f * s)
            lineTo(14.5f * s, 12f * s)
            lineTo(6f * s, 18f * s)
            close()
        }
        drawPath(p, color)
        drawRoundRect(
            color,
            Offset(16f * s, 6f * s),
            Size(2f * s, 12f * s),
            CornerRadius(1f * s, 1f * s)
        )
    }
}

/** 爱心：24 视窗通行心形路径；filled=false 时只描边。 */
@Composable
fun HeartIcon(size: Dp, color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val p = Path().apply {
            moveTo(12f * s, 21.35f * s)
            lineTo(10.55f * s, 20.03f * s)
            cubicTo(5.4f * s, 15.36f * s, 2f * s, 12.28f * s, 2f * s, 8.5f * s)
            cubicTo(2f * s, 5.42f * s, 4.42f * s, 3f * s, 7.5f * s, 3f * s)
            cubicTo(9.24f * s, 3f * s, 10.91f * s, 3.81f * s, 12f * s, 5.09f * s)
            cubicTo(13.09f * s, 3.81f * s, 14.76f * s, 3f * s, 16.5f * s, 3f * s)
            cubicTo(19.58f * s, 3f * s, 22f * s, 5.42f * s, 22f * s, 8.5f * s)
            cubicTo(22f * s, 12.28f * s, 18.6f * s, 15.36f * s, 13.45f * s, 20.03f * s)
            lineTo(12f * s, 21.35f * s)
            close()
        }
        if (filled) drawPath(p, color)
        else drawPath(p, color, style = Stroke(width = 1.8f * s))
    }
}

/** 全屏歌词页第一键（队列）glyph：照定案图 113209 为三横线（与 MyMenu 同画法，键位功能以定案口径为准）。 */
@Composable
fun QueueGlyphIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val sw = 2.5f * s
        for (y in listOf(5f, 12f, 19f)) {
            drawLine(
                color = color,
                start = Offset(3f * s, y * s),
                end = Offset(21f * s, y * s),
                strokeWidth = sw,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * 全屏歌词页第二键（歌词样式）glyph：三条递短横条＋右侧音符——路径取数值档存档的瓜子矢量原文
 * （gz-smali-missing-dig 存档 PlaylistIcon 路径，与定案图 113209 第二键样子逐点一致）。
 */
@Composable
fun LyricStyleIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val path = remember {
        PathParser().parsePathString(
            "M2,18 L12,18 L12,20 L2,20 L2,18 Z " +
                "M2,11 L16,11 L16,13 L2,13 L2,11 Z " +
                "M2,4 L22,4 L22,6 L2,6 L2,4 Z " +
                "M19,15.1707 L19,9 L24,9 L24,11 L21,11 L21,18 " +
                "C21,19.6569 19.6569,21 18,21 C16.3431,21 15,19.6569 15,18 " +
                "C15,16.3431 16.3431,15 18,15 C18.3506,15 18.6872,15.0602 19,15.1707 Z " +
                "M18,19 C18.5523,19 19,18.5523 19,18 C19,17.4477 18.5523,17 18,17 " +
                "C17.4477,17 17,17.4477 17,18 C17,18.5523 17.4477,19 18,19 Z"
        ).toPath()
    }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) {
            drawPath(path, color)
        }
    }
}

/** 瓜子贝塞尔曲线族 bs4（数值表）：a=(0.2,0,0,1) 通用、e=(0.05,0.7,0.1,1) 切歌、f=(0.3,0,0.8,0.15) 拖放。 */
val EaseBs4A = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
val EaseBs4E = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EaseBs4F = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
