package com.fongmi.android.tv.ui.music

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fongmi.android.tv.music.MusicController
import com.fongmi.android.tv.ui.music.player.PlayerOpenContract
import com.fongmi.android.tv.ui.music.player.PlayerRoot
import com.fongmi.android.tv.ui.music.player.PlayerStateHolder
import com.fongmi.android.tv.utils.CoverColor

/**
 * 播放页（瓜子渲染组织法，见 ~/workspace/gz-render-tree-map.md）：
 * 本类只管窗口与装配——关系统转场防闪、窗口底先铺外壳回落色、读开页契约的条矩形、
 * 把根交给 PlayerRoot。不在本类堆业务状态：状态一份归 PlayerStateHolder 中枢，
 * 条（ListenMiniBar 里的 PlayerBar）与本页 collect 同一份快照，条是页的收起态。
 * 开页路不变：迷你条/通知按类名反射 Intent 找本类（FQCN 不动）。
 */
class MusicPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.setBackgroundDrawable(ColorDrawable(CoverColor.FALLBACK_COLOR))
        PlayerStateHolder.attach(this)
        MusicController.get().bind(this)
        val barRect = PlayerOpenContract.readBarRect(intent)
        setContent {
            PlayerRoot(barRect = barRect, onClose = { closePage() })
        }
    }

    /** 收回动画走完才走这里：置回落免淡入旗再 finish，落点真条直接满显不闪。 */
    private fun closePage() {
        PlayerOpenContract.suppressNextBarFade()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        try {
            MusicController.get().unbind()
        } catch (t: Throwable) {
            // 控制器未就绪时解绑失败不许带崩页面销毁
        }
        super.onDestroy()
    }
}
