package com.fongmi.android.tv.hongguo.proxy;

/**
 * 注册一条流所需的全部播放信息（一集一档）：明文直链＋DEK＋size/file_hash。
 * 明文链先由 HgUrlCrypto 把加密 main_url 解出（fplay 形态）或直接用明文链，
 * 保护判定（模型级钥匙）由调用方在造规格前完成（新短剧解链层口径）。
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
}
