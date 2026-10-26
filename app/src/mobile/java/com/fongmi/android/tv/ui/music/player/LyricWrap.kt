package com.fongmi.android.tv.ui.music.player

/**
 * 歌词引擎的纯函数件（照瓜子 LyricsViewKt 亲读原文移植，数值见 gz-player-values.md
 * 第十六节断行与第八/十五节淡出；本项目旧舞台件已有同口径 Java 移植，此处逐项对齐）：
 * calcBalancedBreaks 均衡断行＋bottomFadeKeep 锚下淡出曲线。
 */

/** 断行段（照瓜子 WrapSegment：sentenceStart＝段首在整行文本中的字符偏移）。 */
internal class WrapSegment(
    val sentenceStart: Int,
    val width: Float,
    val text: String,
) {
    /** 瓜子 dw7.S 口径：整段皆空白（空串亦真，与旧移植同一判定）。 */
    fun isSpace(): Boolean = text.all { it.isWhitespace() }
}

private const val WRAP_PUNCTUATION = ",.;:!?，。；：！？、）】》」』’”)] }>~…"
private const val WRAP_OVERFLOW_PENALTY = 1000.0

/** 瓜子 isCJK 码点区间（十六进制）：[3000,3040) [3040,3100) [3400,4DC0) [4E00,A000) [F900,FB00)。 */
internal fun isCjkChar(c: Char): Boolean {
    val v = c.code
    return (v >= 0x3000 && v < 0x3040) || (v >= 0x3040 && v < 0x3100) ||
        (v >= 0x3400 && v < 0x4DC0) || (v >= 0x4E00 && v < 0xA000) ||
        (v >= 0xF900 && v < 0xFB00)
}

/** 断点附加奖惩（按断点前一段判定，优先级照瓜子原序：标点→空格→CJK→其余）。 */
private fun wrapBreakAdjustment(prev: WrapSegment, w: Double): Double {
    val t = prev.text
    if (t.isNotEmpty() && WRAP_PUNCTUATION.indexOf(t[t.length - 1]) >= 0) {
        val b = 0.6 * w
        return -(b * b)
    }
    if (prev.isSpace() || t.endsWith(" ")) {
        val b = 0.4 * w
        return -(b * b)
    }
    for (ch in t) {
        if (isCjkChar(ch)) {
            val p = 0.15 * w
            return p * p
        }
    }
    val p = 0.5 * w
    return p * p
}

/**
 * 瓜子 calcBalancedBreaks：对段列求均衡断点。
 * 一行代价＝(W−行宽)²；单段独占且超宽＝(超宽)²×1000；断点附加见上。
 * 返回各断点处段的 sentenceStart（升序）；总宽 ≤W 返回空。
 */
internal fun calcBalancedBreaks(segs: List<WrapSegment>, maxW: Float): List<Int> {
    val out = mutableListOf<Int>()
    val n = segs.size
    if (n == 0 || maxW <= 0f) return out
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + segs[i].width
    if (prefix[n] <= maxW) return out
    val w = maxW.toDouble()
    val dpCost = DoubleArray(n + 1)
    val from = IntArray(n + 1)
    for (i in 1..n) {
        dpCost[i] = Double.POSITIVE_INFINITY
        from[i] = -1
    }
    for (i in 1..n) {
        var j = i - 1
        while (j >= 0) {
            val lineW = prefix[i] - prefix[j]
            if (lineW > w && j < i - 1) break
            val cost = if (lineW > w) {
                val over = lineW - w
                over * over * WRAP_OVERFLOW_PENALTY
            } else {
                val slack = w - lineW
                slack * slack
            }
            var total = dpCost[j] + cost
            if (j > 0) total += wrapBreakAdjustment(segs[j - 1], w)
            if (total < dpCost[i]) {
                dpCost[i] = total
                from[i] = j
            }
            j--
        }
    }
    var cur = n
    while (cur > 0 && from[cur] > 0) {
        out.add(0, segs[from[cur]].sentenceStart)
        cur = from[cur]
    }
    return out
}

private val FADE_STOPS = floatArrayOf(0f, 0.3f, 0.55f, 0.78f, 1f)

/**
 * 瓜子 bottomFadeKeep：输入停点 [0, .3, .55, .78, 1]→输出 [1, .85, .62, .48, keep]，
 * 段间 smoothstep((3−2t)t²) 插值；keep 默认 0.45（引擎 pos40 定谳）。
 */
internal fun bottomFadeKeep(f: Float, keep: Float = 0.45f): Float {
    val outs = floatArrayOf(1f, 0.85f, 0.62f, 0.48f, keep)
    val v = f.coerceIn(0f, 1f)
    var i = 0
    while (i < 4) {
        val hi = FADE_STOPS[i + 1]
        if (v <= hi) {
            val lo = FADE_STOPS[i]
            val t = (v - lo) / (hi - lo)
            val s = (3f - 2f * t) * t * t
            return outs[i] + (outs[i + 1] - outs[i]) * s
        }
        i++
    }
    return outs[4]
}

/** smoothstep 本形（(3−2t)t²），顶部淡入与多处共用。 */
internal fun smoothstep01(t: Float): Float {
    val v = t.coerceIn(0f, 1f)
    return (3f - 2f * v) * v * v
}
