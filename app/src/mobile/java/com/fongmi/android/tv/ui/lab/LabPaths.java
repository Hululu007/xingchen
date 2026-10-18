package com.fongmi.android.tv.ui.lab;

import android.content.Context;

import java.io.File;

/**
 * 实验室目录布局（应用私有目录，照参照物）：
 * files/lab/
 *   packages/<id>/      已装 .lab 包（files/ 内容解开于此，lab.json 在根）
 *   runtimes/<runtime>/ 运行环境（python/nodejs/php），bin/ 在其下
 *   pids/               进程跟踪
 *   logs/               每包一份日志
 *   registry.json       安装注册表
 */
public final class LabPaths {

    private LabPaths() {
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "lab");
    }

    public static File packages(Context c) {
        return new File(root(c), "packages");
    }

    public static File packageDir(Context c, String id) {
        return new File(packages(c), safe(id));
    }

    public static File runtimes(Context c) {
        return new File(root(c), "runtimes");
    }

    public static File runtimeDir(Context c, String runtime) {
        return new File(runtimes(c), safe(runtime));
    }

    public static File pids(Context c) {
        return new File(root(c), "pids");
    }

    public static File logs(Context c) {
        return new File(root(c), "logs");
    }

    public static File registry(Context c) {
        return new File(root(c), "registry.json");
    }

    public static File manifestOf(Context c, String id) {
        return new File(packageDir(c, id), "lab.json");
    }

    private static String safe(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
