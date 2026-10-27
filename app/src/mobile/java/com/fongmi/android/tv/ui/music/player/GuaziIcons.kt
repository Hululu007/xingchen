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

/** bs4.b＝(0,0,0,1)、bs4.c＝(0.3,0,1,1)（数值表 §十五 defpackage 亲读）。 */
val EaseBs4B = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0f, 1f)
val EaseBs4C = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 1f, 1f)

/**
 * bs4.d＝PathEasing（数值表 §十五逐点亲读）：(0,0)→三次 (0.05,0)(0.133333,0.06)(0.166666,0.4)
 * →三次 (0.208333,0.82)(0.25,1)(1,1)，按原控制点构造、不近似。
 */
val EaseBs4D = androidx.compose.animation.core.PathEasing(
    androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    }
)

/** e92.a＝FastOutSlowIn (0.4,0,0.2,1)（数值表 §十五；工具面板筹色动画用）。 */
val EaseFastOutSlowIn = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** 通行矢量路径图标（24 视窗填充路径，路径数据为 Material 标准字形；尺寸/颜色由调用方给）。 */
@Composable
fun PathIcon(size: Dp, color: Color, pathData: String, modifier: Modifier = Modifier) {
    val path = remember(pathData) {
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(pathData).toPath()
    }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPath(path, color)
        }
    }
}

/** 非方形矢量图标（Apple 控制行上一首/下一首 48×42、播放 60×52.5 的盒内字形）。 */
@Composable
fun PathIconBox(width: Dp, height: Dp, color: Color, pathData: String, modifier: Modifier = Modifier) {
    val path = remember(pathData) {
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(pathData).toPath()
    }
    Canvas(modifier.size(width, height)) {
        val sx = this.size.width / 24f
        val sy = this.size.height / 24f
        withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
            drawPath(path, color)
        }
    }
}

// ===== 通行字形路径（Material 标准 24×24） =====
const val PATH_PREV = "M6 6h2v12H6zm3.5 6l8.5 6V6z"
const val PATH_NEXT = "M16 6h2v12h-2zM6 18l8.5-6L6 6v12z"
const val PATH_REPEAT = "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"
const val PATH_REPEAT_ONE =
    "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-4.5V15h-1.5v-3.6H10v-1.4h3v2.5z"
const val PATH_SHUFFLE =
    "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"
const val PATH_REORDER = "M3 15h18v-2H3v2zm0 4h18v-2H3v2zm0-8h18V9H3v2zm0-6v2h18V5H3z"
const val PATH_CHEVRON_DOWN = "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z"
const val PATH_CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"
const val PATH_ARROW_BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
const val PATH_CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
const val PATH_DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
const val PATH_TIMER =
    "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67V7z"
const val PATH_PLAYLIST_ADD = "M14 10H3v2h11v-2zm0-4H3v2h11V6zm4 8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM3 16h7v-2H3v2z"
const val PATH_TUNE =
    "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 13v2h2v-2H7zm14-2v2h-4v-2h4zM7 7h2v2H7V7zm14 2v2h-6V9h6zM3 13h2v2H3v-2z"
const val PATH_VOLUME_LOW = "M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z"
const val PATH_VOLUME_HIGH =
    "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"
const val PATH_MOON =
    "M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z"

/** 三个点（横排，iOS 式）：Apple 页头与 CD/唱片架顶行工具键字形（瓜子 wc2.C 库图标、点位未逐点抠，结构级）。 */
@Composable
fun MoreDotsIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        for (cx in listOf(5f, 12f, 19f)) {
            drawCircle(color, radius = 2f * s, center = Offset(cx * s, 12f * s))
        }
    }
}

/** 信息 i：圆环＋点＋竖线（CD 歌曲信息格/面内信息行字形，结构级）。 */
@Composable
fun InfoIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawCircle(color, radius = 9f * s, center = Offset(12f * s, 12f * s), style = Stroke(width = 1.7f * s))
        drawCircle(color, radius = 1.4f * s, center = Offset(12f * s, 7.6f * s))
        drawLine(
            color,
            Offset(12f * s, 11f * s),
            Offset(12f * s, 16.4f * s),
            strokeWidth = 2f * s,
            cap = StrokeCap.Round
        )
    }
}

/** 音质字形：四档升序柱（CD 音质格与功能行音质键，结构级）。 */
@Composable
fun QualityBarsIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val r = CornerRadius(1f * s, 1f * s)
        drawRoundRect(color, Offset(3.5f * s, 14f * s), Size(3.4f * s, 6f * s), r)
        drawRoundRect(color, Offset(8.6f * s, 11f * s), Size(3.4f * s, 9f * s), r)
        drawRoundRect(color, Offset(13.7f * s, 8f * s), Size(3.4f * s, 12f * s), r)
        drawRoundRect(color, Offset(18.8f * s, 5f * s), Size(3.4f * s, 15f * s), r)
    }
}

/** 太阳（CD 表面浅色档字形）：圆芯＋八向短线。 */
@Composable
fun SunIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val c = Offset(12f * s, 12f * s)
        drawCircle(color, radius = 4.2f * s, center = c, style = Stroke(width = 1.8f * s))
        for (deg in 0 until 360 step 45) {
            val rad = Math.toRadians(deg.toDouble())
            val x1 = c.x + (6.4f * s * kotlin.math.cos(rad)).toFloat()
            val y1 = c.y + (6.4f * s * kotlin.math.sin(rad)).toFloat()
            val x2 = c.x + (8.6f * s * kotlin.math.cos(rad)).toFloat()
            val y2 = c.y + (8.6f * s * kotlin.math.sin(rad)).toFloat()
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8f * s, cap = StrokeCap.Round)
        }
    }
}

/** 自动档字形：半实半虚圆（CD 表面自动档，结构级）。 */
@Composable
fun AutoSurfaceIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val c = Offset(12f * s, 12f * s)
        drawCircle(color, radius = 8.5f * s, center = c, style = Stroke(width = 1.8f * s))
        val half = Path().apply {
            moveTo(12f * s, 3.5f * s)
            arcTo(
                androidx.compose.ui.geometry.Rect(
                    Offset(3.5f * s, 3.5f * s),
                    Offset(20.5f * s, 20.5f * s)
                ),
                -90f, 180f, false
            )
            close()
        }
        drawPath(half, color)
    }
}

/** 歌词版本字形：文本线＋右下音符（CD 歌词版本格，结构级）。 */
@Composable
fun LyricVersionIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val sw = 1.8f * s
        drawLine(color, Offset(3f * s, 6f * s), Offset(17f * s, 6f * s), sw, StrokeCap.Round)
        drawLine(color, Offset(3f * s, 11f * s), Offset(13f * s, 11f * s), sw, StrokeCap.Round)
        drawLine(color, Offset(3f * s, 16f * s), Offset(10f * s, 16f * s), sw, StrokeCap.Round)
        // 音符（与 PlaylistIcon 同型小音符，右下）
        val note = Path().apply {
            moveTo(19f * s, 15.17f * s)
            lineTo(19f * s, 9f * s)
            lineTo(24f * s, 9f * s)
            lineTo(24f * s, 11f * s)
            lineTo(21f * s, 11f * s)
            lineTo(21f * s, 18f * s)
            cubicTo(21f * s, 19.66f * s, 19.66f * s, 21f * s, 18f * s, 21f * s)
            cubicTo(16.34f * s, 21f * s, 15f * s, 19.66f * s, 15f * s, 18f * s)
            cubicTo(15f * s, 16.34f * s, 16.34f * s, 15f * s, 18f * s, 15f * s)
            cubicTo(18.35f * s, 15f * s, 18.69f * s, 15.06f * s, 19f * s, 15.17f * s)
            close()
        }
        withTransform({ scale(0.82f, 0.82f, pivot = Offset(15f * s, 9f * s)) }) {
            drawPath(note, color)
        }
    }
}
