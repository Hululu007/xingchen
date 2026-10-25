package com.fongmi.android.tv.service;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 节拍分析器：瓜子 cg0 的逐行移植（media3 AudioProcessor，透传不改声）。
 * 逐样本喂原始 PCM（16 位先 /32768、FLOAT 原值，多声道逐个样本），无低通滤波，
 * 低频靠对原始样本的指数滑动平均（α=0.014）统计得到。每 2048 个样本结算一帧：
 * energy = min(1, RMS×2.4)；慢电平 j 对 min(1, RMS(EMA)×5) 做 0.15 跟踪、
 * 基线 k 对 j 做 0.02 跟踪；bass = clamp(((j/max(k,0.005))−0.6)/0.9, 0, 1)；
 * 脉冲 m：energy 基线 l 做 0.06 跟踪（初值 0.03），energy>0.03 且 >1.3·l 时
 * m = max(m, clamp((energy/l −1)/1.8, 0.15, 1))，每帧 m ×= 0.93 衰减。
 * 双门（开关 requestEnabled && 播放态 playing）齐开才分析并发布到 MusicBeatState。
 */
@UnstableApi
public final class MusicBeatAnalyzer implements AudioProcessor {

    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

    private boolean formatOk;
    private boolean floatEncoding;
    private boolean started;
    private ByteBuffer buffer = EMPTY;
    private ByteBuffer output = EMPTY;
    private boolean inputEnded;

    private double sumSq;
    private long count;
    private double ema;
    private double emaSq;
    private double level;
    private double baseline;
    private double energyBaseline;
    private float pulse;

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        int encoding = inputAudioFormat.encoding;
        if (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) {
            formatOk = true;
            floatEncoding = encoding == C.ENCODING_PCM_FLOAT;
            resetStats();
            return inputAudioFormat;
        }
        formatOk = false;
        return AudioFormat.NOT_SET;
    }

    @Override
    public boolean isActive() {
        return formatOk && MusicBeatState.requestEnabled;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        } else {
            buffer.clear();
        }
        if (!MusicBeatState.requestEnabled || !MusicBeatState.playing) {
            started = false;
            buffer.put(inputBuffer);
            buffer.flip();
            output = buffer;
            return;
        }
        if (!started) {
            resetStats();
            started = true;
        }
        if (floatEncoding) {
            int n = remaining / 4;
            for (int i = 0; i < n; i++) {
                float f = inputBuffer.getFloat();
                feed(f);
                buffer.putFloat(f);
            }
        } else {
            int n = remaining / 2;
            for (int i = 0; i < n; i++) {
                short s = inputBuffer.getShort();
                feed(s / 32768.0);
                buffer.putShort(s);
            }
        }
        buffer.flip();
        output = buffer;
    }

    private void feed(double d) {
        double sq = d * d + sumSq;
        sumSq = sq;
        double e = ema + (d - ema) * 0.014;
        ema = e;
        emaSq += e * e;
        long n = ++count;
        if (n >= 2048) {
            double rms = Math.sqrt(sq / n);
            double rmsEma = Math.sqrt(emaSq / n);
            count = 0;
            sumSq = 0;
            emaSq = 0;
            float energy = (float) Math.min(1.0, rms * 2.4);
            double levelNow = Math.min(1.0, rmsEma * 5.0);
            level += (levelNow - level) * 0.15;
            if (level > 0.005) baseline += (level - baseline) * 0.02;
            double denom = baseline >= 0.005 ? baseline : 0.005;
            float bass = clampF((float) (((level / denom) - 0.6) / 0.9), 0f, 1f);
            energyBaseline += (energy - energyBaseline) * 0.06;
            if (energy > 0.03f) {
                float eb = (float) energyBaseline;
                if (energy > 1.3f * eb) {
                    pulse = Math.max(pulse, clampF((energy / eb - 1f) / 1.8f, 0.15f, 1f));
                }
            }
            pulse *= 0.93f;
            if (MusicBeatState.requestEnabled && MusicBeatState.playing) {
                MusicBeatState.publish(bass, energy, pulse);
            }
        }
    }

    private void resetStats() {
        sumSq = 0;
        count = 0;
        ema = 0;
        emaSq = 0;
        level = 0;
        baseline = 0;
        energyBaseline = 0.03;
        pulse = 0f;
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public ByteBuffer getOutput() {
        return output;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && output.remaining() == 0;
    }

    @Override
    public void queueEndOfStream() {
        inputEnded = true;
    }

    @Override
    public void flush() {
        output = EMPTY;
        buffer.clear();
        inputEnded = false;
        started = false;
        resetStats();
    }

    @Override
    public void reset() {
        flush();
        formatOk = false;
        floatEncoding = false;
    }
}
