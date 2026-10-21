package com.fongmi.android.tv.bean.music;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件运行时状态（C10，字段照瓜子 LxRuntimeStatus 抄）。
 * 内存态，不落盘，随进程走（风格同 MusicSourceManager 的 bridges／lxBridges 缓存）。
 */
public class MusicRuntimeStatus {

    /** 存活桥数 */
    public int liveRunners;
    /** 隔离数（连续超时被自动停用的桥） */
    public int quarantinedRunners;
    /** 隔离名表 */
    public List<String> quarantinedNames = new ArrayList<>();
    /** 启动失败数（桥创建失败） */
    public int failedPlugins;
    /** 失败名表 */
    public List<String> failedNames = new ArrayList<>();
    /** 已启用插件数 */
    public int enabledLocalPlugins;
    /** 启动中（有桥正在创建） */
    public boolean initializing;
    /** 重新加载中 */
    public boolean reloading;
}
