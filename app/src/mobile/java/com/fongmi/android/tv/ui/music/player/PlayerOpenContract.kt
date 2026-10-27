package com.fongmi.android.tv.ui.music.player

import android.content.Intent
import android.graphics.Rect

/**
 * 条→页开合契约（星辰自有形态：条是页的收起态，原地长开收回）：
 * 点条时把条在窗口中的矩形经 Intent 带给播放页，PlayerRoot 以此为形变起点，
 * 封面/歌名/进度在同一棵树里连续长开，不跳不闪；收回落点与真条重合后 finish。
 * 矩形缺失（通知/别处直开页）时播放页整页直显，不做形变。
 */
object PlayerOpenContract {

    private const val EXTRA_LEFT = "player.bar.left"
    private const val EXTRA_TOP = "player.bar.top"
    private const val EXTRA_RIGHT = "player.bar.right"
    private const val EXTRA_BOTTOM = "player.bar.bottom"

    @JvmStatic
    fun writeBarRect(intent: Intent, rect: Rect) {
        intent.putExtra(EXTRA_LEFT, rect.left)
        intent.putExtra(EXTRA_TOP, rect.top)
        intent.putExtra(EXTRA_RIGHT, rect.right)
        intent.putExtra(EXTRA_BOTTOM, rect.bottom)
    }

    @JvmStatic
    fun readBarRect(intent: Intent?): Rect? {
        if (intent == null || !intent.hasExtra(EXTRA_LEFT)) return null
        val rect = Rect(
            intent.getIntExtra(EXTRA_LEFT, 0),
            intent.getIntExtra(EXTRA_TOP, 0),
            intent.getIntExtra(EXTRA_RIGHT, 0),
            intent.getIntExtra(EXTRA_BOTTOM, 0)
        )
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }

    /**
     * 回落免淡入：播放页收回 finish 后，首页真条由 ListenMiniBar 重新挂载——
     * 默认 200ms 淡入会让落点闪一下，故播放页关闭前置此旗，下一次挂条免淡入直接满显，
     * 旗只消费一次。普通切页挂条不受影响。
     */
    @Volatile
    private var suppressBarFade = false

    @JvmStatic
    fun suppressNextBarFade() {
        suppressBarFade = true
    }

    @JvmStatic
    fun consumeSuppressBarFade(): Boolean {
        val value = suppressBarFade
        suppressBarFade = false
        return value
    }
}
