package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.graphics.Rect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * 播放条的 View 侧宿主口（给 ListenMiniBar 的 Java 接管点用）：
 * 音乐条从这一版起由新树 Compose 件供画面，ListenMiniBar 只保留
 * 挂条/摘条/可见性白名单的老职责，条内一切（数据、点击、横划、开页）
 * 全走新树。听书条不经这里，一字不动。
 *
 * opener 由 Java 侧给（它握着宿主 Activity 的三级回落开页法），
 * 条点中时把自身窗口矩形交回去，播放页拿矩形作形变起点。
 */
object PlayerBarHost {

    @JvmStatic
    fun createBarView(context: Context, opener: (Rect?) -> Unit): ComposeView {
        PlayerStateHolder.attach(context)
        val view = ComposeView(context)
        view.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow
        )
        // 浮起层次与旧条布局的 elevation 12dp 对齐（条是悬浮件，不贴底）
        view.elevation = 12f * context.resources.displayMetrics.density
        view.setContent {
            val state by PlayerStateHolder.state.collectAsState()
            PlayerBar(
                state = state,
                onOpen = { rect -> opener(rect) },
            )
        }
        return view
    }
}
