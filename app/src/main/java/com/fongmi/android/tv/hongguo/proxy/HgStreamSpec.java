package com.fongmi.android.tv.hongguo.proxy;

import com.fongmi.android.tv.hongguo.model.HgVideoEntry;

/**
 * 注册一条解密流所需的全部播放信息（一集一档）：明文直链＋DEK＋size/file_hash。
 * 三阶段从 {@link HgVideoEntry} 造：先用 HgUrlCrypto 把加密 main_url 解成明文链
 * （fplay 形态）或直接用明文链（multi 形态），再 {@link #fromEntry}。
 */
public final class HgStreamSpec {

    public final String upstreamUrl;
    public final String dekHex;
    public final long size;
    public final String fileHash;
    /** 诊断标签（如 vid/档位），只进日志不参与取数。 */
    public final String label;

    public HgStreamSpec(String upstreamUrl, String dekHex, long size, String fileHash, String label) {
        this.upstreamUrl = upstreamUrl;
        this.dekHex = dekHex;
        this.size = size;
        this.fileHash = fileHash;
        this.label = label;
    }

    /** 由一档播放信息造规格：DEK 由 spade_a 即时推导；推不出返回 null。 */
    public static HgStreamSpec fromEntry(String plainUrl, HgVideoEntry entry, String label) {
        if (plainUrl == null || entry == null) return null;
        String dek = entry.deriveDekHex();
        if (dek == null) return null;
        return new HgStreamSpec(plainUrl, dek, entry.getSize(), entry.getFileHash(), label);
    }
}
