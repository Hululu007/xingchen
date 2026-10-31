package com.bytedance.covode.number;

/**
 * 占位桩：官方包里每个类的静态初始化都会调一次 Covode.recordClassIndex
 * （字节的代码覆盖插桩），随 metasec SDK 类一同被运行时加载时必须存在，
 * 否则 SDK 类全部初始化失败。本类无任何行为。
 */
public final class Covode {

    private Covode() {
    }

    public static void recordClassIndex(int index) {
        // no-op
    }
}
