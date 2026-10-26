package com.fongmi.android.tv.ui.music.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import com.fongmi.android.tv.ui.music.MusicPlayerActivity

/**
 * 播放条→播放页的开页契约：条把自己在窗口里的矩形随 Intent 带过去，
 * 播放页拿它作容器形变的起点（条⇄页同体开合的接缝就这一处）。
 * 无矩形（通知点开等）＝播放页直接展开态进场，不做形变。
 *
 * 开页标志口径与 ListenMiniBar.openMusicPlayer 的三级回落一致（那边是
 * 反射版、历史成因；新树这边用显式 Intent，但落点纪律一字不让）：
 * Activity 上下文零标志；非 Activity 上下文才补 NEW_TASK＋CLEAR_TOP＋SINGLE_TOP。
 */
object PlayerOpenContract {

    private const val EXTRA_LEFT = "player.bar.left"
    private const val EXTRA_TOP = "player.bar.top"
    private const val EXTRA_RIGHT = "player.bar.right"
    private const val EXTRA_BOTTOM = "player.bar.bottom"

    fun intent(context: Context, barRect: Rect?): Intent {
        val intent = Intent(context, MusicPlayerActivity::class.java)
        if (barRect != null) writeBarRect(intent, barRect)
        if (context !is Activity) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        return intent
    }

    fun open(context: Context, barRect: Rect?) {
        try {
            context.startActivity(intent(context, barRect))
        } catch (t: Throwable) {
            // 开页失败静默：条还在、歌还在播，不许崩首页
        }
    }

    /**
     * 把条矩形写进一个现成的 Intent（ListenMiniBar 的反射开页链自带 Intent
     * 构建与三级回落，只借这里写 extras，键名单一出处不许两边各写一份）。
     */
    @JvmStatic
    fun writeBarRect(intent: Intent, rect: Rect) {
        intent.putExtra(EXTRA_LEFT, rect.left)
        intent.putExtra(EXTRA_TOP, rect.top)
        intent.putExtra(EXTRA_RIGHT, rect.right)
        intent.putExtra(EXTRA_BOTTOM, rect.bottom)
    }

    fun readBarRect(intent: Intent?): Rect? {
        if (intent == null) return null
        if (!intent.hasExtra(EXTRA_LEFT)) return null
        return Rect(
            intent.getIntExtra(EXTRA_LEFT, 0),
            intent.getIntExtra(EXTRA_TOP, 0),
            intent.getIntExtra(EXTRA_RIGHT, 0),
            intent.getIntExtra(EXTRA_BOTTOM, 0),
        )
    }
}
