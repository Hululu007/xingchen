package com.fongmi.android.tv.ui.custom;

/**
 * 音乐律动信号源（A4，瓜子 FluidCoverAmbient 的 level 输入口）。
 * level() 返回 0..1 的律动强度，驱动流体三色块的缩放／alpha。
 * 首选音频会话响度（系统 Visualizer／播放器响度回调）；备选节拍估算（能量包络峰值检测）。
 * 未接通时返回 0：流体仍按时间正弦漂移，不静止。
 * 现状（X-15 已接通）：实现为 ui.music.VisualizerRhythmSource——会话 id 经
 * MusicPlaybackService.getAudioSessionId 只读口＋MusicController.audioSessionId 转发取得，
 * 挂系统 Visualizer 取 FFT 低频包络；无权限/无会话时 level 恒 0 静默退化。
 */
public interface MusicRhythmSource {

    /** 律动强度 0..1；未接通返回 0 */
    float level();

    /** 节拍 a 路包络（dg0 对称 τ0.25）；默认与 level() 同值，源有双路时各自实现。 */
    default float beatA() {
        return level();
    }

    /** 节拍 b 路包络（dg0 升 0.09/降 0.32）；默认与 level() 同值，源有双路时各自实现。 */
    default float beatB() {
        return level();
    }
}
