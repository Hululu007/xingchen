package com.fongmi.android.tv.api.node;

import androidx.annotation.Nullable;

/**
 * 实验室 Node 环境信息登记口（模块页 NODEJS 行的版本显示用，桥接设计
 * 映射表 15）：实验室在 mobile 源集，主源集不能直引 ui.lab 类（leanback
 * 变体没有实验室），照第 1/3 步登记口先例——mobile 侧实现类靠类名触发
 * 加载并在静态块自登记（proguard 单独 keep 该类名）。未登记（leanback／
 * 实验室不在）一律按没装处理，任何路径不许把没装吞成可用假装已装。
 */
public final class NodeLabInfo {

    public interface Provider {

        boolean installed();

        @Nullable
        String installedVersion();

        @Nullable
        String availableVersion();

        /** 有没有真更新：判据归实验室（LabRuntimeManager.hasUpdate 已定口径），消费侧不许自造 */
        boolean hasUpdate();
    }

    private static final String IMPL_CLASS = "com.fongmi.android.tv.ui.lab.LabNodeInfo";

    private static volatile Provider provider;
    private static volatile boolean loadAttempted;

    private NodeLabInfo() {
    }

    public static void register(Provider impl) {
        provider = impl;
    }

    public static boolean installed() {
        Provider impl = provider();
        if (impl == null) return false;
        try {
            return impl.installed();
        } catch (Throwable e) {
            return false;
        }
    }

    @Nullable
    public static String installedVersion() {
        Provider impl = provider();
        if (impl == null) return null;
        try {
            return impl.installedVersion();
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    public static String availableVersion() {
        Provider impl = provider();
        if (impl == null) return null;
        try {
            return impl.availableVersion();
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean hasUpdate() {
        Provider impl = provider();
        if (impl == null) return false;
        try {
            return impl.hasUpdate();
        } catch (Throwable e) {
            return false;
        }
    }

    private static Provider provider() {
        if (provider == null && !loadAttempted) {
            synchronized (NodeLabInfo.class) {
                if (provider == null && !loadAttempted) {
                    loadAttempted = true;
                    try {
                        Class.forName(IMPL_CLASS);
                    } catch (Throwable ignored) {
                        // leanback 没有实验室实现类：保持未登记，按没装处理。
                    }
                }
            }
        }
        return provider;
    }
}
