package com.fongmi.android.tv.hongguo.model;

import com.fongmi.android.tv.hongguo.crypto.HgUrlCrypto;
import com.fongmi.android.tv.hongguo.crypto.SpadeKey;

/**
 * 一集的一个清晰度档位（不可变）。
 *
 * <p>字段来源有两种回包形态，解析时已归一：multi_video_model 的 video_list 数组项
 * （清晰度等在 video_meta 子对象、加密信息在 encrypt_info 子对象）与 fplay 的
 * video_list 对象表（字段扁平在项上）。main_url 在 multi 形态是明文链、在 fplay
 * 形态是加密串（需配合该回包的 key_seed 用 {@link HgUrlCrypto} 解）。</p>
 */
public final class HgVideoEntry {

    private final String definition;
    private final String quality;
    private final String vtype;
    private final int width;
    private final int height;
    private final long bitrate;
    private final long realBitrate;
    private final double fps;
    private final String codecType;
    private final long size;
    private final String fileId;
    private final String fileHash;
    private final String mainUrl;
    private final String backupUrl;
    private final String spadeA;
    private final String kid;
    private final String encryptionMethod;
    private final String gearDesKey;

    HgVideoEntry(String definition, String quality, String vtype, int width, int height,
                 long bitrate, long realBitrate, double fps, String codecType, long size,
                 String fileId, String fileHash, String mainUrl, String backupUrl,
                 String spadeA, String kid, String encryptionMethod, String gearDesKey) {
        this.definition = definition;
        this.quality = quality;
        this.vtype = vtype;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.realBitrate = realBitrate;
        this.fps = fps;
        this.codecType = codecType;
        this.size = size;
        this.fileId = fileId;
        this.fileHash = fileHash;
        this.mainUrl = mainUrl;
        this.backupUrl = backupUrl;
        this.spadeA = spadeA;
        this.kid = kid;
        this.encryptionMethod = encryptionMethod;
        this.gearDesKey = gearDesKey;
    }

    public String getDefinition() { return definition; }
    public String getQuality() { return quality; }
    public String getVtype() { return vtype; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getBitrate() { return bitrate; }
    public long getRealBitrate() { return realBitrate; }
    public double getFps() { return fps; }
    public String getCodecType() { return codecType; }
    public long getSize() { return size; }
    public String getFileId() { return fileId; }
    public String getFileHash() { return fileHash; }
    /** 原始 main_url：multi 形态为明文链，fplay 形态为加密串（见 {@link #isUrlEncrypted()}）。 */
    public String getMainUrl() { return mainUrl; }
    public String getBackupUrl() { return backupUrl; }
    public String getSpadeA() { return spadeA; }
    public String getKid() { return kid; }
    public String getEncryptionMethod() { return encryptionMethod; }
    public String getGearDesKey() { return gearDesKey; }

    public boolean isUrlEncrypted() {
        return HgUrlCrypto.isEncryptedUrl(mainUrl);
    }

    /** 通用播放器可解的编码（bytevc2 是字节自有编码，通用播放器无支持，照海阔线口径跳过）。 */
    public boolean isPlayableCodec() {
        return codecType == null || codecType.isEmpty() || "bytevc1".equals(codecType);
    }

    /** 由 spade_a 推导 CENC 解密钥匙（DEK 十六进制串）；无 spade_a 或推导失败返回 null。 */
    public String deriveDekHex() {
        if (spadeA == null || spadeA.isEmpty()) return null;
        SpadeKey.Result r = SpadeKey.derive(spadeA);
        return r.isOk() ? r.key : null;
    }
}
