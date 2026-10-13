package com.fongmi.android.tv.ai.asr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 本地语音识别引擎（高层封装）。
 *
 * 职责：
 * - 协调插件加载（AiAsrPluginManager）与模型就绪（AiAsrModelManager）
 * - 管理 OnlineRecognizer 的流生命周期
 * - 音频喂入 → 解码 → 结果回调（主线程）
 *
 * 使用流程：
 *   AiAsrEngine.get().prepare(context, callback)  // 确保插件+模型就绪
 *   AiAsrEngine.get().start()                      // 开始识别
 *   AiAsrEngine.get().feedPcm(float[])             // 喂 16kHz PCM（AudioProcessor 调用）
 *   AiAsrEngine.get().stop()                       // 停止
 */
public class AiAsrEngine {

    private static volatile AiAsrEngine instance;

    public static AiAsrEngine get() {
        if (instance == null) {
            synchronized (AiAsrEngine.class) {
                if (instance == null) instance = new AiAsrEngine();
            }
        }
        return instance;
    }

    /** 识别结果回调（主线程） */
    public interface Listener {
        /** 中间结果（实时更新） */
        void onPartial(String text);
        /** 一句话结束（端点检测） */
        void onFinal(String text);
        /** 错误 */
        void onError(String msg);
    }

    /** 准备状态回调 */
    public interface PrepareCallback {
        void onProgress(String stage, int percent);
        void onReady();
        void onError(String msg);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();

    private IAiAsrBridge bridge;
    private long streamPtr = 0;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Listener listener;
    private String lastText = "";

    private AiAsrEngine() {}

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 准备引擎：确保插件已加载且模型已下载，然后初始化识别器。
     */
    public void prepare(Context context, PrepareCallback callback) {
        if (bridge != null && bridge.isReady()) {
            callback.onReady();
            return;
        }
        Context appContext = context.getApplicationContext();

        // 1. 确保模型已下载
        if (!AiAsrModelManager.isModelReady(appContext)) {
            callback.onProgress("下载语音模型", 0);
            AiAsrModelManager.downloadModel(appContext, new AiAsrModelManager.Callback() {
                @Override
                public void onProgress(String stage, int percent) {
                    callback.onProgress(stage, percent / 2); // 模型占 0-50%
                }

                @Override
                public void onSuccess(File modelDir) {
                    loadPluginAndInit(appContext, callback);
                }

                @Override
                public void onError(String msg) {
                    callback.onError(msg);
                }
            });
        } else {
            loadPluginAndInit(appContext, callback);
        }
    }

    private void loadPluginAndInit(Context context, PrepareCallback callback) {
        // 2. 确保插件已加载
        callback.onProgress("加载识别引擎", 55);
        AiAsrPluginManager.ensurePlugin(context, new AiAsrPluginManager.Callback() {
            @Override
            public void onProgress(String stage, int percent) {
                callback.onProgress(stage, 50 + percent / 2); // 插件占 50-100%
            }

            @Override
            public void onSuccess(IAiAsrBridge b) {
                // 3. 初始化识别器（后台线程，模型加载较慢）
                new Thread(() -> {
                    try {
                        File modelDir = AiAsrModelManager.modelDir(context);
                        int threads = Math.min(4, Math.max(2,
                                Runtime.getRuntime().availableProcessors() - 1));
                        boolean ok = b.init(modelDir.getAbsolutePath(), threads);
                        if (!ok) {
                            postError(callback, "识别器初始化失败");
                            return;
                        }
                        bridge = b;
                        mainHandler.post(callback::onReady);
                    } catch (Exception e) {
                        postError(callback, "初始化失败：" + e.getMessage());
                    }
                }).start();
            }

            @Override
            public void onError(String msg) {
                postError(callback, msg);
            }
        });
    }

    private void postError(PrepareCallback callback, String msg) {
        mainHandler.post(() -> callback.onError(msg));
    }

    /**
     * 开始识别（创建新流）。
     * 必须在 prepare 成功后调用。
     */
    public boolean start() {
        if (bridge == null || !bridge.isReady()) return false;
        if (!running.compareAndSet(false, true)) return true; // 已在运行
        try {
            streamPtr = bridge.createStream();
            if (streamPtr == 0) {
                running.set(false);
                return false;
            }
            lastText = "";
            return true;
        } catch (Exception e) {
            running.set(false);
            return false;
        }
    }

    /**
     * 喂入 PCM 音频（16kHz 单声道 float，范围 [-1, 1]）。
     * 由 AiAsrAudioProcessor 在音频线程调用。
     */
    public void feedPcm(float[] samples) {
        if (!running.get() || bridge == null || streamPtr == 0) return;
        if (samples == null || samples.length == 0) return;
        final long ptr = streamPtr;
        final float[] copy = samples.clone(); // 避免调用方复用 buffer
        decodeExecutor.execute(() -> {
            if (!running.get() || ptr != streamPtr) return;
            try {
                bridge.acceptWaveform(ptr, copy);
                bridge.decode(ptr);
                String text = bridge.getResult(ptr);
                if (text != null && !text.equals(lastText)) {
                    lastText = text;
                    final String t = text;
                    // 端点检测：一句话说完
                    if (bridge.isEndpoint(ptr)) {
                        mainHandler.post(() -> {
                            if (listener != null) listener.onFinal(t);
                        });
                        // 端点后重置流，开始下一句
                        resetStream();
                    } else if (!t.isEmpty()) {
                        mainHandler.post(() -> {
                            if (listener != null) listener.onPartial(t);
                        });
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 端点后重置流 */
    private void resetStream() {
        decodeExecutor.execute(() -> {
            if (!running.get()) return;
            try {
                long oldPtr = streamPtr;
                long newPtr = bridge.createStream();
                if (newPtr != 0) {
                    streamPtr = newPtr;
                    bridge.releaseStream(oldPtr);
                    lastText = "";
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 停止识别 */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        final long ptr = streamPtr;
        streamPtr = 0;
        decodeExecutor.execute(() -> {
            try {
                if (bridge != null && ptr != 0) {
                    bridge.inputFinished(ptr);
                    bridge.releaseStream(ptr);
                }
            } catch (Exception ignored) {}
        });
    }

    /** 释放引擎（App 退出时） */
    public void release() {
        stop();
        decodeExecutor.execute(() -> {
            try {
                if (bridge != null) bridge.release();
            } catch (Exception ignored) {}
            bridge = null;
        });
    }
}
