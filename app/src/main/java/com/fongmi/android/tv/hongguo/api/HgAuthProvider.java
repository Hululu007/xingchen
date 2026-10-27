package com.fongmi.android.tv.hongguo.api;

import java.util.Map;

/**
 * 登录态注入点（预留）：第四阶段接壳子统一登录托管时实现本接口即可，
 * 客户端每次请求前取一次，注入 header / Cookie。本阶段无实现，默认不带登录态
 * （正片取模型链已实证不要求登录；追剧架等账号维度接口届时靠它带态）。
 */
public interface HgAuthProvider {

    /** 附加请求头（如 authorization）；无则返回 null 或空表。 */
    Map<String, String> headers();

    /** Cookie 头的值；无则返回 null。 */
    String cookie();
}
