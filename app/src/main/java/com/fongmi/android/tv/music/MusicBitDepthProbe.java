package com.fongmi.android.tv.music;

import java.io.File;
import java.io.FileInputStream;

/**
 * 本地音频位深探针：只认文件头能实锤的——FLAC 看 STREAMINFO 里的 bps 字段，
 * WAV 看 fmt 块的 bitsPerSample。其它格式/读不到一律返回 -1（未知），
 * 调用方拿 -1 就不许显示「母带」。纯文件头解析，不解码、不联网。
 */
public class MusicBitDepthProbe {

    /**
     * 按字节探头：只认文件头能实锤的——FLAC 看 STREAMINFO 里的 bps 字段，
     * WAV 看 fmt 块的 bitsPerSample。其它格式/读不到一律返回 -1（未知）。
     * 与 probe(File) 同一套解析（在线 Range 探头复用，不改本地逻辑）。
     *
     * @return 位深 8/16/24/32；未知/不支持返回 -1
     */
    public static int probeHeader(byte[] head, int n) {
        if (head == null || n < 4) return -1;
        try {
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                return flacBitsPerSample(head, n);
            }
            if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
                return wavBitsPerSample(head, n);
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }
    public static int probe(File file) {
        if (file == null || !file.isFile()) return -1;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[128];
            int n = 0;
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            if (n >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                return flacBitsPerSample(head, n);
            }
            if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
                return wavBitsPerSample(head, n);
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * FLAC：fLaC(4) + 第一个块头(4) + STREAMINFO(34)。
     * STREAMINFO 第 10..17 字节打包：采样率 20 位｜声道-1 3 位｜bps-1 5 位｜总样本 36 位。
     */
    static int flacBitsPerSample(byte[] head, int n) {
        if (n < 8 + 18) return -1;
        // 第一个块必须是 STREAMINFO（块类型低 7 位 == 0）
        if ((head[4] & 0x7F) != 0) return -1;
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (head[8 + 10 + i] & 0xFFL);
        int bps = (int) ((v >>> 36) & 0x1F) + 1;
        if (bps < 4 || bps > 32) return -1;
        return bps;
    }

    /** WAV：定位 fmt 块，块数据第 14..15 字节（小端）= bitsPerSample。 */
    static int wavBitsPerSample(byte[] head, int n) {
        for (int i = 12; i + 8 <= n; i++) {
            if (head[i] == 'f' && head[i + 1] == 'm' && head[i + 2] == 't' && head[i + 3] == ' ') {
                int p = i + 8 + 14;
                if (p + 2 > n) return -1;
                int bps = (head[p] & 0xFF) | ((head[p + 1] & 0xFF) << 8);
                if (bps < 4 || bps > 64) return -1;
                return bps;
            }
        }
        return -1;
    }
}
