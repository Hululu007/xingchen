package com.fongmi.android.tv.api.node;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.github.catvod.Runtime;
import com.github.catvod.runtime.NodeRuntimeApi;
import com.github.catvod.utils.RuntimeModule;

public final class NodeService extends Service {

    private static final String TAG = "NodeService";

    private final IBinder binder = new Binder();
    private boolean started;

    @Override
    public void onCreate() {
        super.onCreate();
        Runtime.set(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        start(intent);
        return START_NOT_STICKY;
    }

    private void start(Intent intent) {
        if (intent == null || !NodeRuntimeApi.SERVICE_ACTION.equals(intent.getAction()) || started) return;
        started = true;
        try {
            ensureNodeHost();
            RuntimeModule.NODEJS.entry(NodeRuntimeApi.class).startProcess(this, intent);
        } catch (Throwable e) {
            // 止崩口径（体检类4）：entry() 的未装/加载失败信号不许再经
            // onStartCommand/onBind 上抛崩掉 :nodejs 进程；失败复位
            // started，下一次 start/bind 调用还能重试（实验室 Node
            // 后装好、或登记补上后不必重启进程）。
            started = false;
            Log.e(TAG, "Node 运行时启动失败，等待下次调用重试", e);
        }
    }

    /**
     * 本进程内确保 NodeHost 已登记（正路）：RuntimeModule 的 NodeHost
     * 静态登记原本只由主进程的 LabBoot 做，App.onCreate 对非主进程
     * 直接 return，本服务所在的 :nodejs 进程里登记恒为空，
     * entry()→require() 必抛 MissingException。登记实现 LabNodeHost
     * 在 mobile 源集（本类在 main 源集不能直引，leanback 也没有
     * 实验室），照 App 调 LabBoot 的反射口径在本进程补登记；
     * register() 无前置条件且幂等，类名靠 proguard-rules.pro 的
     * LabNodeHost 保名规则保住。登记不上（leanback 无此类）就维持
     * 未登记口径，失败在 start() 外层被接住记日志、不崩进程。
     */
    private static void ensureNodeHost() {
        try {
            Class.forName("com.fongmi.android.tv.ui.lab.LabNodeHost")
                    .getMethod("register")
                    .invoke(null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        start(intent);
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Process.killProcess(Process.myPid());
    }
}
