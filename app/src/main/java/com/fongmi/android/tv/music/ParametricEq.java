package com.fongmi.android.tv.music;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数均衡器 DSP 核（B6，纯 Java，不依赖安卓）：RBJ cookbook 双二阶滤波器。
 * 频段：滤波类型、频率、增益、Q 值；支持增删频段；采样率感知；逐通道独立状态。
 * 与系统 audiofx（预设＋固定频段＋低音＋环绕）是两套东西：本核不改称、不替代，只叠加。
 */
public class ParametricEq {

    public enum FilterType {
        PEAKING, LOWSHELF, HIGHSHELF, LOWPASS, HIGHPASS, BANDPASS, NOTCH
    }

    /** 频段（Gson 友好：公开字段＋无参构造，MusicStore 以 JSON 存取） */
    public static class Band {
        public FilterType type = FilterType.PEAKING;
        public float freq = 1000f;
        public float gainDb = 0f;
        public float q = 1f;

        public Band() {
        }

        public Band(FilterType type, float freq, float gainDb, float q) {
            this.type = type == null ? FilterType.PEAKING : type;
            this.freq = freq;
            this.gainDb = gainDb;
            this.q = q;
        }

        public Band copy() {
            return new Band(type, freq, gainDb, q);
        }
    }

    private static class Biquad {
        double b0, b1, b2, a1, a2;
        double z1, z2;

        void set(FilterType type, float freq, float gainDb, float q, int sampleRate) {
            double w0 = 2 * Math.PI * clamp(freq, 10, sampleRate / 2.2) / sampleRate;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2 * Math.max(0.05, q));
            double a = Math.pow(10, gainDb / 40.0);
            double sqrtA = Math.sqrt(a);
            double b0v, b1v, b2v, a0v, a1v, a2v;
            switch (type) {
                case LOWSHELF:
                    b0v = a * ((a + 1) - (a - 1) * cos + 2 * sqrtA * alpha);
                    b1v = 2 * a * ((a - 1) - (a + 1) * cos);
                    b2v = a * ((a + 1) - (a - 1) * cos - 2 * sqrtA * alpha);
                    a0v = (a + 1) + (a - 1) * cos + 2 * sqrtA * alpha;
                    a1v = -2 * ((a - 1) + (a + 1) * cos);
                    a2v = (a + 1) + (a - 1) * cos - 2 * sqrtA * alpha;
                    break;
                case HIGHSHELF:
                    b0v = a * ((a + 1) + (a - 1) * cos + 2 * sqrtA * alpha);
                    b1v = -2 * a * ((a - 1) + (a + 1) * cos);
                    b2v = a * ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha);
                    a0v = (a + 1) - (a - 1) * cos + 2 * sqrtA * alpha;
                    a1v = 2 * ((a - 1) - (a + 1) * cos);
                    a2v = (a + 1) - (a - 1) * cos - 2 * sqrtA * alpha;
                    break;
                case LOWPASS:
                    b0v = (1 - cos) / 2;
                    b1v = 1 - cos;
                    b2v = (1 - cos) / 2;
                    a0v = 1 + alpha;
                    a1v = -2 * cos;
                    a2v = 1 - alpha;
                    break;
                case HIGHPASS:
                    b0v = (1 + cos) / 2;
                    b1v = -(1 + cos);
                    b2v = (1 + cos) / 2;
                    a0v = 1 + alpha;
                    a1v = -2 * cos;
                    a2v = 1 - alpha;
                    break;
                case BANDPASS:
                    b0v = alpha;
                    b1v = 0;
                    b2v = -alpha;
                    a0v = 1 + alpha;
                    a1v = -2 * cos;
                    a2v = 1 - alpha;
                    break;
                case NOTCH:
                    b0v = 1;
                    b1v = -2 * cos;
                    b2v = 1;
                    a0v = 1 + alpha;
                    a1v = -2 * cos;
                    a2v = 1 - alpha;
                    break;
                case PEAKING:
                default:
                    b0v = 1 + alpha * a;
                    b1v = -2 * cos;
                    b2v = 1 - alpha * a;
                    a0v = 1 + alpha / a;
                    a1v = -2 * cos;
                    a2v = 1 - alpha / a;
                    break;
            }
            b0 = b0v / a0v;
            b1 = b1v / a0v;
            b2 = b2v / a0v;
            a1 = a1v / a0v;
            a2 = a2v / a0v;
            z1 = 0;
            z2 = 0;
        }

        float process(float in) {
            double out = b0 * in + z1;
            z1 = b1 * in - a1 * out + z2;
            z2 = b2 * in - a2 * out;
            return (float) out;
        }
    }

    private final List<Band> bands = new ArrayList<>();
    private Biquad[][] filters;
    private int channels;
    private int sampleRate;

    /** 换配置：深拷贝频段并按采样率／通道数重建滤波状态（下一 buffer 起生效） */
    public synchronized void setBands(List<Band> bands, int sampleRate, int channels) {
        this.bands.clear();
        if (bands != null) {
            for (Band b : bands) {
                if (b != null) this.bands.add(b.copy());
            }
        }
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, channels);
        rebuild();
    }

    public synchronized List<Band> copyBands() {
        List<Band> out = new ArrayList<>();
        for (Band b : bands) out.add(b.copy());
        return out;
    }

    /** 旁路口径：无频段，或全部频段增益为 0 且类型为 PEAKING／LOWSHELF／HIGHSHELF（此时数学上恒等） */
    public synchronized boolean isBypass() {
        if (bands.isEmpty()) return true;
        for (Band b : bands) {
            if (b.gainDb != 0) return false;
            if (b.type == FilterType.LOWPASS || b.type == FilterType.HIGHPASS
                    || b.type == FilterType.BANDPASS || b.type == FilterType.NOTCH) return false;
        }
        return true;
    }

    /** 交织 float 音频原地处理：frames 帧 × channels 通道 */
    public synchronized void process(float[] pcm, int frames, int channels) {
        if (filters == null || pcm == null || frames <= 0) return;
        int ch = Math.min(channels, this.channels);
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < ch; c++) {
                float s = pcm[i * channels + c];
                for (Biquad[] band : filters) {
                    s = band[c].process(s);
                }
                pcm[i * channels + c] = s;
            }
        }
    }

    private void rebuild() {
        if (bands.isEmpty() || sampleRate <= 0) {
            filters = null;
            return;
        }
        filters = new Biquad[bands.size()][channels];
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            for (int c = 0; c < channels; c++) {
                Biquad f = new Biquad();
                f.set(b.type, b.freq, b.gainDb, b.q, sampleRate);
                filters[i][c] = f;
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
