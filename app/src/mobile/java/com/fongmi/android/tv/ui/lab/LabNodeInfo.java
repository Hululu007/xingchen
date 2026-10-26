package com.fongmi.android.tv.ui.lab;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.node.NodeLabInfo;

/**
 * NodeLabInfo 的实验室实现：类加载即自登记（靠 NodeLabInfo 按类名触发，
 * proguard 单独 keep 本类名）。判据与第 1 步 LabNodeHost 同源，只读实验室
 * 自己的账——在场走 LabRuntimeManager.isInstalled、已装版本走 LabRegistry
 * 登记、可用版本走 LabRuntimeManager.catalogVersion。
 */
public final class LabNodeInfo {

    static {
        NodeLabInfo.register(new NodeLabInfo.Provider() {
            @Override
            public boolean installed() {
                return LabRuntimeManager.isInstalled(App.get(), "nodejs");
            }

            @Override
            public String installedVersion() {
                if (!installed()) return null;
                LabRegistry.Runtime runtime = LabRegistry.get(App.get()).runtime("nodejs");
                return runtime == null ? null : runtime.version;
            }

            @Override
            public String availableVersion() {
                return LabRuntimeManager.catalogVersion(App.get(), "nodejs");
            }

            @Override
            public boolean hasUpdate() {
                // 与实验室三家同一口径（扫描 O12）：已装版本为空＝未知不提示、
                // 目录无条目不提示、版本比较大于才算更新——模块页此前自造
                // 「两串不等即更新」，空串与降级都被报成可更新。
                return LabRuntimeManager.hasUpdate(App.get(), "nodejs");
            }
        });
    }

    private LabNodeInfo() {
    }
}
