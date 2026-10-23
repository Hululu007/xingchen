package com.fongmi.android.tv.update;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.io.File;
import java.util.concurrent.Future;

import okhttp3.Call;

public final class OciUpdateTransfer implements UpdateTransfer, OciRegistryClient.Observer {

    private final UpdateTarget target;
    private final File file;
    private final String expectedIdentity;
    private volatile boolean canceled;
    private volatile Call call;
    private Future<?> future;
    private Callback callback;

    public OciUpdateTransfer(UpdateTarget target, File file, String expectedIdentity) {
        if (target == null || target.kind != UpdateTarget.Kind.OCI) throw new IllegalArgumentException("OCI target required");
        this.target = target;
        this.file = file;
        this.expectedIdentity = expectedIdentity;
    }

    @Override
    public void start(Callback callback) {
        this.callback = callback;
        this.canceled = false;
        this.future = Task.submit(this::pull);
    }

    @Override
    public void cancel() {
        canceled = true;
        if (call != null) call.cancel();
        if (future != null) future.cancel(true);
        // 不删档：已下拉取的部分留给下次续传（续传实现在 OciRegistryClient），
        // 好坏最终由 Updater.validate 把关，验不过由那边删档。
    }

    private void pull() {
        try {
            new OciRegistryClient(UpdateHttp.ociClient()).pull(target.endpoint, target.artifact, file, this, expectedIdentity);
            if (canceled) return;
            App.post(() -> {
                if (!canceled) callback.success(file);
            });
        } catch (Exception e) {
            // 不删档：失败留下的半截由 OciRegistryClient 下次带 Range 续拉；
            // 摘要对不上的整档会在客户端内当场删（那已是校验定案，不是半截）。
            if (canceled || "Canceled".equals(e.getMessage())) return;
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            App.post(() -> callback.error(message));
        } finally {
            call = null;
            future = null;
        }
    }

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void onCall(Call call) {
        this.call = call;
        if (canceled) call.cancel();
    }

    @Override
    public void onProgress(int progress, long bytes, long total, long speed, long elapsed) {
        App.post(() -> {
            if (!canceled) callback.progress(progress, bytes, total, speed, elapsed);
        });
    }
}
