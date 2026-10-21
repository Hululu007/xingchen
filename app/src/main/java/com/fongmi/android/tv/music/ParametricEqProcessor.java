package com.fongmi.android.tv.music;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 参数均衡器处理链（B6，Media3 AudioProcessor）：把 ParametricEq 串进音乐 ExoPlayer。
 * 只处理 PCM_16BIT 与 PCM_FLOAT，其余编码抛 UnhandledAudioFormatException（链自动绕过）；
 * 禁用或旁路时原样透传，不动音频；配置变更（面板改频段／开关）下一 buffer 起生效，无需重建播放器。
 */
public class ParametricEqProcessor implements AudioProcessor {

    private AudioFormat inputFormat = AudioFormat.NOT_SET;
    private ByteBuffer outputBuffer = EMPTY_BUFFER;

    private volatile boolean enabled;
    private volatile List<ParametricEq.Band> bands = new ArrayList<>();
    private volatile long configVersion;
    private long appliedVersion = -1;
    private final ParametricEq eq = new ParametricEq();

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        configVersion++;
    }

    public void setBands(List<ParametricEq.Band> bands) {
        List<ParametricEq.Band> copy = new ArrayList<>();
        if (bands != null) {
            for (ParametricEq.Band b : bands) {
                if (b != null) copy.add(b.copy());
            }
        }
        this.bands = copy;
        configVersion++;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<ParametricEq.Band> copyBands() {
        List<ParametricEq.Band> copy = new ArrayList<>();
        for (ParametricEq.Band b : bands) copy.add(b.copy());
        return copy;
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        int encoding = inputAudioFormat.encoding;
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        inputFormat = inputAudioFormat;
        appliedVersion = -1;
        return inputAudioFormat;
    }

    @Override
    public boolean isActive() {
        return inputFormat != AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) return;
        int remaining = inputBuffer.remaining();
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
        } else {
            outputBuffer.clear();
        }
        outputBuffer.put(inputBuffer);
        outputBuffer.flip();
        if (!enabled || inputFormat == AudioFormat.NOT_SET) return;
        if (appliedVersion != configVersion) {
            eq.setBands(bands, inputFormat.sampleRate, inputFormat.channelCount);
            appliedVersion = configVersion;
        }
        if (eq.isBypass()) return;
        int channels = inputFormat.channelCount;
        int sampleRate = inputFormat.sampleRate;
        if (channels <= 0 || sampleRate <= 0) return;
        try {
            if (inputFormat.encoding == C.ENCODING_PCM_16BIT) {
                int frames = remaining / (2 * channels);
                if (frames <= 0) return;
                float[] pcm = new float[frames * channels];
                for (int i = 0; i < frames * channels; i++) {
                    pcm[i] = outputBuffer.getShort(i * 2) / 32768.0f;
                }
                eq.process(pcm, frames, channels);
                for (int i = 0; i < frames * channels; i++) {
                    float s = Math.max(-1f, Math.min(1f, pcm[i]));
                    outputBuffer.putShort(i * 2, (short) (s * 32767));
                }
            } else {
                int frames = remaining / (4 * channels);
                if (frames <= 0) return;
                float[] pcm = new float[frames * channels];
                for (int i = 0; i < frames * channels; i++) {
                    pcm[i] = outputBuffer.getFloat(i * 4);
                }
                eq.process(pcm, frames, channels);
                for (int i = 0; i < frames * channels; i++) {
                    outputBuffer.putFloat(i * 4, Math.max(-1f, Math.min(1f, pcm[i])));
                }
            }
        } catch (Throwable ignored) {
            // 处理失败不影响播放：本 buffer 已是原样透传
        }
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
    public void flush() {
        outputBuffer = EMPTY_BUFFER;
        appliedVersion = -1;
    }

    @Override
    public void queueEndOfStream() {
    }

    @Override
    public void reset() {
        flush();
        inputFormat = AudioFormat.NOT_SET;
    }
}
