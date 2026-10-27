package com.fongmi.android.tv.hongguo.api;

/**
 * 红果数据层异步回调。
 *
 * <p>线程口径：onSuccess / onError 一律在 OkHttp 分发线程（后台线程）上调用，
 * 不在主线程；回包解析也在该线程完成。上层要碰 UI 必须自己切主线程。</p>
 */
public interface HgCallback<T> {

    void onSuccess(T result);

    /** httpCode 为 HTTP 状态码；网络层失败时为 -1，message 带原因。 */
    void onError(int httpCode, String message);
}
