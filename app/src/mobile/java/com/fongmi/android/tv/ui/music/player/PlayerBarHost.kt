package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.compose.ui.platform.ComposeView
import kotlin.jvm.functions.Function1

/**
 * 条的 Java 接入点（ListenMiniBar 建音乐条时调）：给宿主 Activity 上下文与开页回调，
 * 产出一条 ComposeView 顶替旧 XML 条。挂接中枢在此一并完成（幂等）。
 * 条的显隐/挂载/淡入淡出仍归 ListenMiniBar 本来的机制管，本类只管条本体长什么样。
 */
object PlayerBarHost {

    @JvmStatic
    fun createBarView(context: Context, opener: Function1<Rect, Unit>): View {
        PlayerStateHolder.attach(context)
        return ComposeView(context).apply {
            setContent {
                PlayerBar(onOpen = { rect -> opener.invoke(rect) })
            }
        }
    }
}
