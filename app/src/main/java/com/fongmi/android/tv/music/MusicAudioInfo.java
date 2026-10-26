package com.fongmi.android.tv.music;

/**
 * 正在播放音频的真实参数（服务从 ExoPlayer 当前 Format 采集，位深靠本地文件头确认）。
 * 播放页音质格照它显示：无损只显编码名（如「FLAC」），有损显编码＋真实码率
 * （如「MP3 · 320kbps」）；采样率永不进显示，所选档永不进显示。
 * {@link #master}（lossless 且位深≥24）只作内部判定保留，不进展示串。
 * 格子短码（GZ immersiveShortLabel 口径，shortCode）：
 * 实测 → SQ／HQ／Std／Hi-Res／24bit；describe() 保留为实测明细串，不再进格子。
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

    /** 声道数（ExoPlayer Format.channelCount；信息页技术规格「声道」格用，<=0 未知） */
    public int channelCount = -1;
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

    /**
     * 格子短码（最新令，照 GZ immersiveShortLabel：LOSSLESS→SQ、EXHIGH→HQ、STANDARD→Std、
     * HIRES→Hi-Res、FLAC_24BIT→24bit；PlayQuality.java＋PlayerScreenKt.java:27260 前后）。
     * 数据源仍是实测（不是所选档）：无损按位深／采样率分 SQ／Hi-Res／24bit，有损按码率分 HQ／Std，
     * 取不到返回 null（调用方隐藏格子）。阈值取 GZ 码内数：HIRES 采样率 floor 88200Hz
     * （LibrarySongRowKt.java:648：采样率 ≥88200 或位深 ≥24 → HIRES）、HQ 码率 floor 192000bps
     * （同文件 :646：码率 <192000 → STANDARD，否则 HQ；该处反编译分支语义局部不清，已在对照表标推断）。
     * 24bit 分流是星辰侧判定（GZ 码内无实测分流公式）：24bit＋192kHz → 24bit（GZ FLAC_24BIT 规格
     * “24bit 192kHz FLAC”）、24bit＋96kHz 档（≥88200）→ Hi-Res（GZ HIRES 规格“24bit 96kHz”）、
     * 24bit＋低／未知采样率 → 24bit；但 P-2c 例外：编码是 FLAC 的 24bit 一律照瓜子名
     * “Hi-Res Lossless”（GZ FLAC_24BIT 的 label），不再出 24bit／Hi-Res。
     * 2026-10-26 收紧（他的口径盖过 GZ 徽标口径）：Hi-Res 家族标签（Hi-Res／Hi-Res Lossless）
     * 只给实测位深 ≥24 且采样率高于 CD 的 44100 的真无损——44.1kHz 的文件就是无损档，
     * 24bit 容器只标事实位深“24bit”，不许冒 Hi-Res 名（《来不及爱你》案：44.1k FLAC
     * 被标 Hi-Res）；位深没实测到时不许凭采样率单独贴 Hi-Res（旧“采样率 ≥88200 即
     * HIRES”的 GZ 本地徽标口径作废）。注：播放页音质格现行走 describe()，本短码暂无
     * 界面调用方，收紧是为口径一致、防日后复用再冒领。
     */
    public String shortCode() {
        if (codec == null || codec.isEmpty()) return null;
        if (lossless) {
            if (bitDepth >= 24) {
                if (sampleRateHz > 44100) {
                    // P-2c：实测位深≥24 且采样率高于 CD 的 FLAC 照瓜子名“Hi-Res Lossless”
                    //（GZ FLAC_24BIT 的 label，规格“24bit 192kHz FLAC”）。
                    if ("FLAC".equals(codec)) return "Hi-Res Lossless";
                    if (sampleRateHz >= 192000) return "24bit";
                    if (sampleRateHz >= 88200) return "Hi-Res";
                }
                return "24bit";
            }
            return "SQ";
        }
        if (bitrateKbps >= 192) return "HQ";
        if (bitrateKbps > 0) return "Std";
        return null;
    }

    /** 变化判重用：全字段拼串 */
    public String signature() {
        return codec + "|" + bitrateKbps + "|" + sampleRateHz + "|" + bitDepth + "|" + lossless + "|" + master + "|" + channelCount;
    }
}
