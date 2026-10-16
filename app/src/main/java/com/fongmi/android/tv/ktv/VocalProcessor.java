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

    public void setMode(int mode) {
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
                switch (m) {
                    case CANCEL: {
                        short v = (short) ((l - r) / 2);
                        out.putShort(v);
                        out.putShort(v);
                        break;
                    }
                    case LEFT:
                        out.putShort(l);
                        out.putShort(l);
                        break;
                    case RIGHT:
                        out.putShort(r);
                        out.putShort(r);
                        break;
                    default:
                        out.putShort(l);
                        out.putShort(r);
                        break;
                }
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

    @Override
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
    }
}
