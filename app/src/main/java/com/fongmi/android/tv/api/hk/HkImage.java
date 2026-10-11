package com.fongmi.android.tv.api.hk;

/**
 * 海阔图片 @js= 求值时的"当前规则" JS 运行时持有者。
 *
 * <p>8.83 用 {@code JSEngine.getInstance()} 单例承载当前规则的 allFunctions；
 * 宿主是多规则 HkRouter 缓存，故由 HkPageActivity 在每次取 router 时
 * 把当前规则的 HkJsRuntime 登记到这里，供 {@link HkOkHttpStreamFetcher}
 * 求值 {@code @js=} 代码（{@code $.require("aes?rule="+MY_TITLE)} 等依赖规则 pages）。</p>
 */
public class HkImage {

    private static volatile HkJsRuntime currentRt;

    public static void setCurrentRuntime(HkJsRuntime rt) {
        currentRt = rt;
    }

    /** 当前规则的 JS 运行时；未登记时返回 null（调用方退化为原图直出）。 */
    public static HkJsRuntime currentRuntime() {
        return currentRt;
    }
}
