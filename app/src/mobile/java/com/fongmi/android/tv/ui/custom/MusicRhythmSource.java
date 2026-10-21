package com.fongmi.android.tv.ui.custom;

/**
 * 音乐律动信号源（A4，瓜子 FluidCoverAmbient 的 level 输入口）。
 * level() 返回 0..1 的律动强度，驱动流体三色块的缩放／alpha。
 * 首选音频会话响度（系统 Visualizer／播放器响度回调）；备选节拍估算（能量包络峰值检测）。
 * 未接通时返回 0：流体仍按时间正弦漂移，不静止。
 * 现状：音频会话 id 只在 MusicPlaybackService 内部（均衡器用 player.getAudioSessionId），
 * 未暴露给播放页层，故本批不接 Visualizer，level 恒 0；接通需服务侧先暴露会话 id（另批）。
 */
public interface MusicRhythmSource {

    /** 律动强度 0..1；未接通返回 0 */
    float level();
}
