package com.fongmi.android.tv.api;

/**
 * 第三方公开服务常量集中管理（轮换只改这里一处，不用改多处）。
 * 注意：以下均不是用户自己的凭证——Pastebin key 自海阔 8.83 反编译提取，
 * 网易 EAPI 常量是网易歌词 EAPI 算法的公开常量；服务方吊销／轮换时对应功能
 * 整路失败，换值后需发版（无远端下发源可接）。
 */
public final class ThirdPartyKeys {

    private ThirdPartyKeys() {
    }

    /** Pastebin API key（自 8.83 反编译提取；云剪贴板 1 上传用）。 */
    public static final String PASTEBIN_DEV_KEY = "Qs2VjmfmU1Qz-SGdXpUV6fRPquPzR0js";

    /** 网易 EAPI AES 密钥（网易歌词 EAPI 加密用）。 */
    public static final String NETEASE_EAPI_KEY = "e82ckenh8dichen8";
}
