package com.fongmi.android.tv.ui.music

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.ui.music.player.PlayerOpenContract
import com.fongmi.android.tv.ui.music.player.PlayerRoot
import com.fongmi.android.tv.ui.music.player.PlayerStateHolder

/**
 * 播放页新一套的树根宿主（瓜子渲染组织法，见 ~/workspace/gz-render-tree-map.md）。
 * 本类只负责：挂接状态中枢、读开页契约的条矩形、setContent PlayerRoot。
 * 状态一份归 PlayerStateHolder，页面组织归 PlayerRoot，本类不堆业务。
 *
 * 窗口纪律（条⇄页开合不闪的根）：进出都关系统转场、窗口底透明——
 * 形变全程由 PlayerRoot 自己画，系统不许插一帧自己的动画或底色。
 * FQCN 与旧 Java 件同名，迷你条与通知的反射 Intent 零改动直达。
 */
class MusicPlayerActivity : ComponentActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // 不熄屏初值（存档在 MusicStore；运行中变更由 PlayerRoot 订中枢修订号落旗）
        if (MusicStore.isPlayerKeepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        PlayerStateHolder.attach(this)
        val startRect = PlayerOpenContract.readBarRect(intent)
        setContent {
            PlayerRoot(
                startRect = startRect,
                onFinish = { finish() },
            )
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
