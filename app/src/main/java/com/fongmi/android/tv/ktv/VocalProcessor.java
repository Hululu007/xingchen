package com.fongmi.android.tv.ktv;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 老式消音 + 声道处理（唱歌页专用，串在 Exo 音频链里，实时、不动文件）：
 * CANCEL = 左右声道相减抵消居中人声（旧点歌机法）；
 * LEFT/RIGHT = 只取一个声道铺满两边（VCD 文件一边原唱一边伴奏时用）。
 * 实现方式照本应用 AiAsrAudioProcessor 的直实现路子。
 */
public class VocalProcessor implements AudioProcessor {

    public static final int OFF = 0;
    public static final int CANCEL = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;
    private volatile int mode = OFF;
    private int lpState;
    private long rampPos = Long.MAX_VALUE;
    private volatile float cancelMix = 0.85f;

    /** 消音强度：0 轻（留一点原声）1 标准 2 重（纯相减） */
    public void setCancelStrength(int idx) {
        cancelMix = idx <= 0 ? 0.65f : idx >= 2 ? 1f : 0.85f;
    }

    public void setMode(int mode) {
        if (this.mode != mode) rampPos = 0; // 切换时渐入，别一刀切出爆声
        this.mode = mode;
    }

    public int getMode() {
        return mode;
    }

    @Override
    public AudioFormat configure(AudioFormat format) throws UnhandledAudioFormatException {
        if (format.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(format);
        }
        inputFormat = format;
        return format; // 输出格式与输入一致
    }

    @Override
    public boolean isActive() {
        return inputFormat != AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        } else {
            outputBuffer.clear();
        }
        int m = mode;
        if (m == OFF || inputFormat.channelCount < 2) {
            outputBuffer.put(inputBuffer);
        } else {
            ByteBuffer in = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer out = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            out.position(outputBuffer.position());
            while (in.remaining() >= 4) {
                short l = in.getShort();
                short r = in.getShort();
                int pl, pr;
                switch (m) {
                    case CANCEL: {
                        // 差值信号先过一遍柔和：压缩音频在差值里全是金属味杂音，不滤就是电流声
                        int d = l - r;
                        lpState += (d - lpState) * 45 / 100;
                        int v = lpState / 2;
                        int mono = (l + r) / 2;
                        int mixed = (int) (v * cancelMix + mono * (1 - cancelMix));
                        pl = mixed;
                        pr = mixed;
                        break;
                    }
                    case LEFT:
                        pl = l;
                        pr = l;
                        break;
                    case RIGHT:
                        pl = r;
                        pr = r;
                        break;
                    default:
                        pl = l;
                        pr = r;
                        break;
                }
                if (rampPos < rampTotal()) {
                    double f = rampPos / (double) rampTotal();
                    pl = (int) (l * (1 - f) + pl * f);
                    pr = (int) (r * (1 - f) + pr * f);
                    rampPos++;
                }
                out.putShort((short) pl);
                out.putShort((short) pr);
            }
            while (in.hasRemaining()) out.put(in.get());
            outputBuffer.position(out.position());
            inputBuffer.position(inputBuffer.limit());
        }
        outputBuffer.flip();
    }

    @Override
    public ByteBuffer getOutput() {
        ByteBuffer out = outputBuffer;
        outputBuffer = EMPTY_BUFFER;
        return out;
    }

    @Override
    public boolean isEnded() {
        return false;
    }

    @Override
    public void queueEndOfStream() {
    }

    private long rampTotal() {
        int rate = inputFormat == AudioFormat.NOT_SET ? 44100 : inputFormat.sampleRate;
        return Math.max(1, rate * 15 / 100);
    }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        lpState = 0;
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
    }
}
