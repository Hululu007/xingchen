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
    private float b0, b1, b2, a1, a2, lx1, lx2, ly1, ly2;
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
        // 低频回补用的低通（170Hz）：贝斯鼓点居中，全砍声音发空；中高频走立体声差值
        double w = 2 * Math.PI * 170.0 / Math.max(8000, format.sampleRate);
        double alpha = Math.sin(w) / (2 * 0.7071);
        double cw = Math.cos(w);
        double a0 = 1 + alpha;
        b0 = (float) (((1 - cw) / 2) / a0);
        b1 = (float) ((1 - cw) / a0);
        b2 = b0;
        a1 = (float) ((-2 * cw) / a0);
        a2 = (float) ((1 - alpha) / a0);
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
                        // 分频相减：低频把居中单声道回补回来（保住贝斯鼓点），
                        // 中高频只留左右差值——差值本来就是立体声，别再压成单声道糊一团；
                        // 全程浮点，整数截断的台阶声就是电流声的来源
                        // 差值天生比原声小一截，不补增益消完就剩蚊子声（他真机听到的"伴奏很小"）
                        float side = (l - r) * 0.8f;
                        float mono = (l + r) * 0.5f;
                        float y = b0 * mono + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2;
                        lx2 = lx1;
                        lx1 = mono;
                        ly2 = ly1;
                        ly1 = y;
                        float keep = 1 - cancelMix;
                        pl = clamp(Math.round((side + y) * cancelMix + l * keep));
                        pr = clamp(Math.round((-side + y) * cancelMix + r * keep));
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
    private static int clamp(int v) {
        return v > 32767 ? 32767 : Math.max(v, -32768);
    }

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        lx1 = lx2 = ly1 = ly2 = 0;
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
    }
}
