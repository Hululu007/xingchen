package com.fongmi.android.tv.music;

/**
 * 正在播放音频的真实参数（服务从 ExoPlayer 当前 Format 采集，位深靠本地文件头确认）。
 * 播放页音质格照它显示：无损只显编码名（如「FLAC」），有损显编码＋真实码率
 * （如「MP3 · 320kbps」）；采样率永不进显示，所选档永不进显示。
 * {@link #master}（lossless 且位深≥24）只作内部判定保留，不进展示串。
 */
public class MusicAudioInfo {

    /** 显示用编码名：FLAC/MP3/AAC/OGG/OPUS/PCM 等 */
    public String codec = "";
    /** 码率 kbps，-1 未知 */
    public int bitrateKbps = -1;
    /** 采样率 Hz，-1 未知 */
    public int sampleRateHz = -1;
    /** 位深，-1 未知（只对本地 FLAC/WAV 文件头解析，取不到保持 -1） */
    public int bitDepth = -1;
    /** 编码确认无损（FLAC/WAV/PCM/ALAC/APE） */
    public boolean lossless;
    /** 确认母带：lossless 且 bitDepth>=24 */
    public boolean master;

    /**
     * 展示串（2026-10-20 最终口径）：无损编码只显编码名一个词（如「FLAC」，
     * 不带码率/赫兹/母带）；有损显 编码＋真实码率（如「MP3 · 320kbps」），
     * 码率取不到就返回 null（调用方不显示音质格）。采样率在任何情况下
     * 都不进显示。所选档永不进显示。master 字段保留（位深探针/判重用），
     * 不再进展示串。
     */
    public String describe() {
        if (codec == null || codec.isEmpty()) return null;
        if (lossless) return codec;
        if (bitrateKbps <= 0) return null;
        return codec + " · " + bitrateKbps + "kbps";
    }

    /** 变化判重用：全字段拼串 */
    public String signature() {
        return codec + "|" + bitrateKbps + "|" + sampleRateHz + "|" + bitDepth + "|" + lossless + "|" + master;
    }
}
