package com.fongmi.android.tv.ui.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 播放页新一套的树根（瓜子渲染组织法，见 ~/workspace/gz-render-tree-map.md）。
 * 地基阶段：空壳——只证明 Compose 通路与开页路（迷你条/通知反射 Intent 按类名找本类）。
 * 本体（状态中枢 PlayerStateHolder、一份快照分发五主题、歌词引擎共用）后续单子在此长出，
 * 不许在本类里堆业务状态：状态一份归中枢，本类只负责 setContent 与根装配。
 */
class MusicPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicPlayerShell() }
    }
}

@Composable
fun MusicPlayerShell() {
    Box(Modifier.fillMaxSize()) {
        // 空壳：PlayerRoot（氛围层＋主题分发＋同树面板）后续在此装配。
    }
}
