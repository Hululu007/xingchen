package com.fongmi.android.tv.hongguo.model;

import java.util.Collections;
import java.util.List;

/**
 * 一集的播放模型（不可变）：multi_video_model 回包里一集的 video_model，
 * 或 fplay 回包的 video_info.data，两者归一到本类。
 */
public final class HgVideoModel {

    private final String requestedId;
    private final String videoId;
    private final int status;
    private final String message;
    private final double durationSec;
    private final String mediaType;
    private final long urlExpire;
    private final String posterUrl;
    private final String keySeed;
    private final String fallbackApi;
    private final int videoWidth;
    private final int videoHeight;
    private final List<HgVideoEntry> entries;

    HgVideoModel(String requestedId, String videoId, int status, String message,
                 double durationSec, String mediaType, long urlExpire, String posterUrl,
                 String keySeed, String fallbackApi, int videoWidth, int videoHeight,
                 List<HgVideoEntry> entries) {
        this.requestedId = requestedId;
        this.videoId = videoId;
        this.status = status;
        this.message = message;
        this.durationSec = durationSec;
        this.mediaType = mediaType;
        this.urlExpire = urlExpire;
        this.posterUrl = posterUrl;
        this.keySeed = keySeed;
        this.fallbackApi = fallbackApi;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.entries = Collections.unmodifiableList(entries);
    }

    /** 请求时的集 id（multi 回包 data 的键）；fplay 形态为 null。 */
    public String getRequestedId() { return requestedId; }
    /** 服务端 video_id（v 开头的内部 id）。 */
    public String getVideoId() { return videoId; }
    public int getStatus() { return status; }
    public String getMessage() { return message; }
    public double getDurationSec() { return durationSec; }
    public String getMediaType() { return mediaType; }
    /** 直链过期时间戳（秒）；0 表示回包未给。 */
    public long getUrlExpire() { return urlExpire; }
    public String getPosterUrl() { return posterUrl; }
    /** fplay 形态才有：URL 解密用的 key_seed（Base64，32 字节）；multi 明文链形态为 null。 */
    public String getKeySeed() { return keySeed; }
    /** multi 形态才有：fplay 兜底取址地址；无则 null。 */
    public String getFallbackApi() { return fallbackApi; }
    public int getVideoWidth() { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }
    public List<HgVideoEntry> getEntries() { return entries; }

    public HgVideoEntry entryByDefinition(String definition) {
        if (definition == null) return null;
        for (HgVideoEntry e : entries) if (definition.equals(e.getDefinition())) return e;
        return null;
    }

    /** 按官方档位序取第一个通用播放器可解的档位；都没有时返回第一档（可能为 null）。 */
    public HgVideoEntry firstPlayableEntry() {
        String[] order = {"1080p", "720p", "540p", "480p", "360p"};
        for (String def : order) {
            HgVideoEntry e = entryByDefinition(def);
            if (e != null && e.isPlayableCodec()) return e;
        }
        for (HgVideoEntry e : entries) if (e.isPlayableCodec()) return e;
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** 直链是否已过期（urlExpire 为 0 时按未过期处理，交给上层重试口径）。 */
    public boolean isExpired(long nowSec) {
        return urlExpire > 0 && nowSec >= urlExpire;
    }

    /**
     * 模型级保护判定（定罪账借条 3，openhongguo-dig-20261028.md §5）：
     * 任一档带加密标记即整模型受保护。钥匙全模型共用一份，见 {@link #deriveDekHex()}。
     */
    public boolean isProtected() {
        for (HgVideoEntry e : entries) if (e.hasEncryptMarkers()) return true;
        return false;
    }

    /** 全模型扫 spade_a 推 DEK：第一个推得出的即模型钥匙；全推不出返回 null。 */
    public String deriveDekHex() {
        for (HgVideoEntry e : entries) {
            String dek = e.deriveDekHex();
            if (dek != null) return dek;
        }
        return null;
    }
}
