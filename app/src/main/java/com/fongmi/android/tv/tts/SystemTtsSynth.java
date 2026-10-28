package com.fongmi.android.tv.tts;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统 TTS 合成器（第二家供应商，他 2026-10-27 令「不许只押一家」）：
 * Edge 合成失败时由 TtsEngine 调它把一段文本合成 wav 落盘，走与 Edge
 * 段完全相同的播放链（PlaybackService＋PlayerManager），听书永不哑。
 * 初始化在主线程做（部分厂商引擎要求），合成在引擎 worker 线程阻塞等。
 */
public class SystemTtsSynth {

    /** 系统语音不可用的分型（供引擎拼人话错误）：无问题／没引擎／没中文嗓。 */
    public static final int PROBLEM_NONE = 0;
    public static final int PROBLEM_NO_ENGINE = 1;
    public static final int PROBLEM_NO_CHINESE = 2;

    private final Context appContext;
    private volatile TextToSpeech tts;
    private volatile boolean initDone;
    private volatile boolean initOk;
    private volatile boolean released;
    private volatile int problem = PROBLEM_NONE;

    public SystemTtsSynth(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 当前不可用分型；引擎/初始化未跑完时为 PROBLEM_NONE。 */
    public int problem() {
        return problem;
    }

    /** 确保已初始化（阻塞，至多等约 6 秒）。无引擎/初始化失败返回 false。 */
    private boolean ensureInit() {
        if (initDone) return initOk;
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable create = () -> {
            try {
                tts = new TextToSpeech(appContext, status -> {
                    initOk = status == TextToSpeech.SUCCESS;
                    if (initOk) {
                        // 中文嗓定版：有中文才算能读；没有就标 PROBLEM_NO_CHINESE，
                        // 不许拿外语嗓把中文读成乱音还报成功。
                        boolean zh = false;
                        try {
                            TextToSpeech t = tts;
                            if (t != null && t.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)
                                    >= TextToSpeech.LANG_AVAILABLE) {
                                t.setLanguage(Locale.SIMPLIFIED_CHINESE);
                                zh = true;
                            } else if (t != null && t.isLanguageAvailable(Locale.CHINESE)
                                    >= TextToSpeech.LANG_AVAILABLE) {
                                t.setLanguage(Locale.CHINESE);
                                zh = true;
                            }
                        } catch (Throwable ignored) {
                        }
                        problem = zh ? PROBLEM_NONE : PROBLEM_NO_CHINESE;
                    } else {
                        problem = PROBLEM_NO_ENGINE;
                    }
                    initDone = true;
                    latch.countDown();
                });
            } catch (Throwable t) {
                initOk = false;
                problem = PROBLEM_NO_ENGINE;
                initDone = true;
                latch.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            create.run();
        } else {
            new Handler(Looper.getMainLooper()).post(create);
        }
        try {
            latch.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return initDone && initOk && tts != null;
    }

    /**
     * 合成一段 → wav 文件（阻塞，引擎 worker 线程调）。
     * 成功且文件非空返回 true；任何失败返回 false（调方按双家全败处理）。
     */
    public boolean synthesize(String text, float rate, File outFile) {
        if (released || text == null || text.trim().isEmpty()) return false;
        if (!ensureInit()) {
            if (problem == PROBLEM_NONE) problem = PROBLEM_NO_ENGINE;
            return false;
        }
        if (problem == PROBLEM_NO_CHINESE) return false;
        TextToSpeech t = tts;
        if (t == null) return false;
        try {
            t.setSpeechRate(rate > 0 ? rate : 1.0f);
        } catch (Throwable ignored) {
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final String uid = "sys_" + System.nanoTime();
        try {
            t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                }

                @Override public void onDone(String utteranceId) {
                    if (uid.equals(utteranceId)) {
                        ok.set(true);
                        done.countDown();
                    }
                }

                @Override public void onError(String utteranceId) {
                    if (uid.equals(utteranceId)) done.countDown();
                }
            });
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();
            if (outFile.exists()) outFile.delete();
            int rc = t.synthesizeToFile(text, new Bundle(), outFile, uid);
            if (rc != TextToSpeech.SUCCESS) return false;
            // 超时按文本量给：约每秒 8 字打底 15 秒、封顶 60 秒
            long timeoutSec = Math.min(60, Math.max(15, text.length() / 8 + 10));
            if (!done.await(timeoutSec, TimeUnit.SECONDS)) {
                try {
                    t.stop();
                } catch (Throwable ignored) {
                }
                return false;
            }
            return ok.get() && outFile.exists() && outFile.length() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    public void shutdown() {
        released = true;
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {
        }
        tts = null;
    }
}
