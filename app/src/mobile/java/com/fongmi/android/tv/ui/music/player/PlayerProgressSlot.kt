package com.fongmi.android.tv.ui.music.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 播放页进度槽（星辰自有交互，他 2026-10-21 定案、重做时丢了、本单恢复）：
 * 平时只有一根短条（52dp 宽、视觉条 3dp 高、轨道白 20%、填充白 45%），
 * 点一下展开完整进度栈（PlaybackScrubber 本体），闲置 3 秒自动缩回；
 * 暂停时保持展开并停计时，播放重起 3 秒计时；拖动进度也重起计时。
 * 切换只在原地这一小块里淡入淡出——槽高恒定（64dp，与展开态同高），
 * 上面的歌词与封面一概不许跟着动（他此前点名过的毛病）。
 * 短条纵位对齐展开态的轨道中线（顶盒 28＋轨道 18/2＝37dp 处）。
 */
@Composable
fun PlayerProgressSlot(
    state: PlayerUiState,
    onSeek: (Long) -> Unit,
    onLyricChipClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showScrubber by remember { mutableStateOf(false) }
    var interactNonce by remember { mutableIntStateOf(0) }

    // 闲置收回：只在播放中计时；暂停保持展开（效果键含 isPlaying，重播自动重起 3 秒）
    LaunchedEffect(expanded, state.isPlaying, interactNonce) {
        if (expanded && state.isPlaying) {
            delay(3000)
            expanded = false
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) showScrubber = true
    }
    val scrubAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(200, easing = Bs4A),
        label = "scrubAlpha",
        finishedListener = { if (!expanded) showScrubber = false },
    )
    val miniAlpha by animateFloatAsState(
        targetValue = if (expanded) 0f else 1f,
        animationSpec = tween(200, easing = Bs4A),
        label = "miniAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        if (showScrubber) {
            PlaybackScrubber(
                state = state,
                onSeek = onSeek,
                onLyricChipClick = onLyricChipClick,
                onInteract = { interactNonce++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = scrubAlpha },
            )
        }
        if (miniAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 35.5.dp)
                    .graphicsLayer { alpha = miniAlpha }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !expanded,
                    ) { expanded = true },
                contentAlignment = Alignment.TopCenter,
            ) {
                MiniProgressBar(
                    fraction = if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                )
            }
        }
    }
}

/** 短条本体（52×3dp，轨道白 .20、填充白 .45，圆头）。 */
@Composable
private fun MiniProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.20f)),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.45f)),
            )
        }
    }
}
