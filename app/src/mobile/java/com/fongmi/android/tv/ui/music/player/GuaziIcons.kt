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

/**
 * 全屏页头歌词样式键 glyph（2026-10-27 源码对死）：瓜子页头玻璃键图标在源码里是矢量
 * Rounded.TextFields（单例 lambda 内容描述即「歌词样式」）——三条圆头文本线、中线短。
 * 此处按该矢量的条位结构绘制（上/下线 y≈6.25/18.75、x 4→20；中线 y＝12、x 4→14；条高 1.5），
 * 路径级逐点未誊录，待真机对照。此前按定案图装的 QueueGlyphIcon／LyricStyleIcon（实为队列矢量）
 * 随全屏页装配订正一并撤除。
 */
@Composable
fun LyricStyleGlyph(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val sw = 1.5f * s
        drawLine(
            color = color,
            start = Offset(4f * s, 6.25f * s),
            end = Offset(20f * s, 6.25f * s),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(4f * s, 12f * s),
            end = Offset(14f * s, 12f * s),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(4f * s, 18.75f * s),
            end = Offset(20f * s, 18.75f * s),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
    }
}

/** 瓜子贝塞尔曲线族 bs4（数值表）：a=(0.2,0,0,1) 通用、e=(0.05,0.7,0.1,1) 切歌、f=(0.3,0,0.8,0.15) 拖放。 */
val EaseBs4A = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
val EaseBs4E = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EaseBs4F = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
