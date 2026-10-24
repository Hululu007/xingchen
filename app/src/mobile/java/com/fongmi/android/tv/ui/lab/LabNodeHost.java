package com.fongmi.android.tv.ui.lab;

import android.content.Context;

import com.github.catvod.utils.RuntimeModule;

import java.io.File;

/**
 * Node 内置桥的宿主判据登记（桥接设计映射表 13/14）：把实验室 Node 环境
 * 接到 RuntimeModule 的 NODEJS 内置分支——
 * 在场＝LabRuntimeManager.isInstalled（runtimes/nodejs/bin 是目录且非空，
 * 与实验室自己的判据同一条）；
 * 本体＝runtimes/nodejs/bin/node（桥子进程 exec 的就是它，recordLoaded 只认它）；
 * 登记戳＝注册表里的版本＋安装时间（同版本重打包靠安装时间区分，设计未定项③定法）。
 * 由 LabBoot.schedule 在启动时登记一次；进程内单例，重复登记同义覆盖。
 */
public final class LabNodeHost {

    private LabNodeHost() {
    }

    public static void register() {
        RuntimeModule.registerNodeHost(new RuntimeModule.NodeHost() {
            @Override
            public boolean installed(Context context) {
                return LabRuntimeManager.isInstalled(context, "nodejs");
            }

            @Override
            public File binary(Context context) {
                return new File(new File(LabPaths.runtimeDir(context, "nodejs"), "bin"), "node");
            }

            @Override
            public String stamp(Context context) {
                LabRegistry.Runtime runtime = LabRegistry.get(context).runtime("nodejs");
                return runtime == null ? "" : runtime.version + "@" + runtime.installTime;
            }
        });
    }
}
