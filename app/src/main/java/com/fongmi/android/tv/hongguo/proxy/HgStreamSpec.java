package com.fongmi.android.tv.hongguo.proxy;

import com.fongmi.android.tv.hongguo.model.HgVideoEntry;

/**
 * 注册一条流所需的全部播放信息（一集一档）：明文直链＋DEK＋size/file_hash。
 * 明文链先由 HgUrlCrypto 把加密 main_url 解出（fplay 形态）或直接用明文链，
 * 再 {@link #fromEntry} 按模型级保护判定造规格（见该方法注）。
 */
public final class HgStreamSpec {

    public final String upstreamUrl;
    /** CENC 解密钥匙 hex；未保护条目为 null（代理直喂不解密）。 */
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

    /**
     * 由一档播放信息＋模型级钥匙造规格（定罪账借条 3，openhongguo-dig-20261028.md §5）：
     * 保护判定是模型级的——钥匙由调用方全模型扫 spade_a 推出后传入（modelDekHex）。
     * 选中档带加密标记而模型钥匙推不出 → 返回 null（起播侧判 E4）；
     * 选中档无加密标记 → 免钥匙直喂（dekHex=null），不再「没 spade_a 就判死」。
     */
    public static HgStreamSpec fromEntry(String plainUrl, HgVideoEntry entry, String modelDekHex, String label) {
        if (plainUrl == null || entry == null) return null;
        if (entry.hasEncryptMarkers()) {
            if (modelDekHex == null) return null;
            return new HgStreamSpec(plainUrl, modelDekHex, entry.getSize(), entry.getFileHash(), label);
        }
        return new HgStreamSpec(plainUrl, null, entry.getSize(), entry.getFileHash(), label);
    }
}
