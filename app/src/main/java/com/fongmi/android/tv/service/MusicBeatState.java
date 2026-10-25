package com.fongmi.android.tv.service;

/**
 * 节拍帧全局槽（瓜子 hg0 的同位）：分析器（音频线程）写、氛围层（UI 线程）读。
 * 帧语义与瓜子 BeatFrame 一致：bass 低频比值、energy 总能量、beatPulse 脉冲。
 * requestEnabled 由播放页按流体/节拍开关置位；playing 由播放服务按播放态置位；
 * 两门齐开分析器才发布新帧（瓜子 cg0 的 a/b 双门同构）。
 */
public final class MusicBeatState {

    public static volatile float bass;
    public static volatile float energy;
    public static volatile float beatPulse;
    public static volatile boolean requestEnabled;
    public static volatile boolean playing;

    private MusicBeatState() {
    }

    public static void publish(float b, float e, float p) {
        bass = b;
        energy = e;
        beatPulse = p;
    }

    public static void silence() {
        bass = 0f;
        energy = 0f;
        beatPulse = 0f;
    }
}
