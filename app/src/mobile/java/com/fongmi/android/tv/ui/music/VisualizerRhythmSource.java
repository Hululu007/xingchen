package com.fongmi.android.tv.ui.music;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.ui.custom.MusicRhythmSource;

/**
 * 律动包络源（X-15 接通）：挂当前播放会话的系统 Visualizer，取 FFT 低频（bass）能量
 * 作包络输入，两路指数趋近包络照瓜子 dg0 的时间常数形态：
 * a 对称时间常数 0.25s；b 升 0.09s／降 0.32s（k＝1−exp(−dt/τ)）。
 * FFT 幅值归一（/144 钳 0..1）是星辰侧标定（Visualizer 字节 FFT 满幅约 128√2），非瓜子字面值。
 * 兜底口径：无会话 id、无 RECORD_AUDIO 权限、Visualizer 建立/启用任一失败——全部静默，
 * 包络保持 0（消费侧退化为基础速率），不弹窗、不打扰用户、不抛异常出本类。
 */
public class VisualizerRhythmSource implements MusicRhythmSource {

    private static final float TAU_A = 0.25f;
    private static final float TAU_B_UP = 0.09f;
    private static final float TAU_B_DOWN = 0.32f;
    /** bass 频带上沿（Hz）：高于此频的 bin 不进包络输入。 */
    private static final float BASS_TOP_HZ = 250f;
    /** FFT 幅值归一分母（星辰标定）。 */
    private static final float MAG_NORM = 144f;

    private final Context appContext;
    private Visualizer visualizer;
    private int sessionId;
    /** 待裁8：捕获回调在 Visualizer 自己的线程上跑（不走 start/stop 的 synchronized），
     * running 与 lastCaptureMs 都必须 volatile，否则跨线程读写无可见性保证。 */
    private volatile boolean running;
    private volatile long lastCaptureMs;
    private volatile float envA;
    private volatile float envB;

    public VisualizerRhythmSource(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    /** 律动强度（平滑路 a）0..1；未接通 0。 */
    @Override
    public float level() {
        return envA;
    }

    /** 节拍 a 路（对称时间常数 0.25s 的平滑包络）0..1。 */
    public float beatA() {
        return envA;
    }

    /** 节拍 b 路（升 0.09s／降 0.32s 的快包络）0..1。 */
    public float beatB() {
        return envB;
    }

    /**
     * 按会话 id 启停：id 相同且在跑则无操作；id 变化先释放旧的再重挂；
     * id≤0 或权限/建立失败时保持静默（包络归 0）。
     */
    public synchronized void start(int audioSessionId) {
        if (audioSessionId <= 0) {
            stopLocked();
            return;
        }
        if (running && visualizer != null && sessionId == audioSessionId) return;
        releaseLocked();
        if (appContext == null) return;
        try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) return;
        } catch (Throwable t) {
            return;
        }
        try {
            Visualizer v = new Visualizer(audioSessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(range[1]);
            v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            visualizer = v;
            sessionId = audioSessionId;
            running = true;
            lastCaptureMs = 0;
        } catch (Throwable t) {
            visualizer = null;
            running = false;
        }
    }

    /** 停并释放；包络归 0（暂停态消费侧走基础速率）。 */
    public synchronized void stop() {
        stopLocked();
    }

    private void stopLocked() {
        releaseLocked();
        envA = 0f;
        envB = 0f;
    }

    private void releaseLocked() {
        running = false;
        sessionId = 0;
        // 释放即包络归 0：start 失败路（权限被拒/构造抛错）只走本方法，
        // 不清会把上一会话的包络残值冻结着继续喂消费方，与类头「失败包络保持 0」口径相悖
        envA = 0f;
        envB = 0f;
        Visualizer v = visualizer;
        visualizer = null;
        if (v != null) {
            try {
                v.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                v.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private final Visualizer.OnDataCaptureListener listener = new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            // 待裁8：停/释放后迟到的回调不许再写包络——releaseLocked 刚把 envA/envB 归 0，
            // 迟到一帧就会把旧包络复活、继续喂消费方（与类头「停后包络保持 0」相悖）。
            if (!running) return;
            try {
                float target = bassLevel(fft, samplingRate);
                long now = SystemClock.uptimeMillis();
                float dt = lastCaptureMs == 0 ? 0.05f
                        : Math.min(0.5f, Math.max(0.001f, (now - lastCaptureMs) / 1000f));
                lastCaptureMs = now;
                envA += (target - envA) * (1f - (float) Math.exp(-dt / TAU_A));
                float tauB = target > envB ? TAU_B_UP : TAU_B_DOWN;
                envB += (target - envB) * (1f - (float) Math.exp(-dt / tauB));
            } catch (Throwable ignored) {
            }
        }
    };

    /** FFT 字节数组低频段平均幅值 → 0..1（bin k 频率＝k×采样率/捕获长，取 ≤250Hz 段）。 */
    private static float bassLevel(byte[] fft, int samplingRateMilliHz) {
        if (fft == null || fft.length < 4 || samplingRateMilliHz <= 0) return 0f;
        int captureSize = fft.length;
        double hzPerBin = (samplingRateMilliHz / 1000.0) / captureSize;
        int kMax = (int) (BASS_TOP_HZ / hzPerBin);
        int bins = captureSize / 2 - 1;
        if (kMax > bins) kMax = bins;
        if (kMax < 1) kMax = 1;
        double sum = 0;
        for (int k = 1; k <= kMax; k++) {
            int re = fft[2 * k];
            int im = fft[2 * k + 1];
            sum += Math.sqrt(re * re + im * im);
        }
        float level = (float) (sum / kMax) / MAG_NORM;
        return level < 0f ? 0f : Math.min(1f, level);
    }
}
