package com.fongmi.android.tv.music;

/**
 * 正在播放音频的真实参数（服务从 ExoPlayer 当前 Format 采集，位深靠本地文件头确认）。
 * 播放页音质格照它显示，如「FLAC · 1411kbps」「MP3 · 320kbps」。
 * 红线：{@link #master} 只许在确认无损编码且位深≥24 时为 true，否则绝不显示「母带」；
 * 位深取不到就是取不到，不猜、不冒充。
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

    /** 展示串；codec 为空时返回 null（调用方不显示音质格，所选档永不进显示）。 */
    public String describe() {
        if (codec == null || codec.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(codec);
        if (bitrateKbps > 0) {
            sb.append(" · ").append(bitrateKbps).append("kbps");
        } else if (sampleRateHz > 0) {
            sb.append(" · ").append(rateText());
        }
        if (master) sb.append(" · 母带");
        return sb.toString();
    }

    /** 采样率展示：96000→96kHz，44100→44.1kHz */
    private String rateText() {
        if (sampleRateHz % 1000 == 0) return (sampleRateHz / 1000) + "kHz";
        return (sampleRateHz / 1000f) + "kHz";
    }

    /** 变化判重用：全字段拼串 */
    public String signature() {
        return codec + "|" + bitrateKbps + "|" + sampleRateHz + "|" + bitDepth + "|" + lossless + "|" + master;
    }
}
