package com.fongmi.android.tv.music;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地音频内嵌歌词读取（播放时现读，不入库；GZ EmbeddedLyricsReader 同款分工）。
 * 优先级（GZ y65.w0 口径）：ID3v2.3+：USLT > SYLT > TXXX（描述含 LYRIC）；ID3v2.2：ULT > SLT；
 * FLAC：Vorbis comment 的 LYRIC 键（LYRICS／UNSYNCEDLYRICS 为星辰侧超集，GZ 原文只确认 LYRIC）；
 * MP4：moov > udta > meta > ilst > ©lyr > data。Ogg 本轮不做（GZ y0 细节未抠清）。
 * SYLT（同步词）转 [mm:ss.xx] LRC 文本；USLT 等非同步文本原样返回，由解析层走无时间轴静态显示，
 * 不许在这里编时间。任何解析失败返回 null，不许抛到调用链（调用方 DeviceMusicSource.lyric 兜底）。
 * 全部读取走 RandomAccessFile 定界读，不整文件载入；扩展头按帧头校验滑动跳过（不依赖扩展头尺寸字段的两种口径）。
 */
public final class EmbeddedLyricsReader {

    private EmbeddedLyricsReader() {
    }

    public static String read(File audio) {
        try {
            if (audio == null || !audio.isFile()) return null;
            String name = audio.getName();
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
            if (ext.equals("flac")) return readFlac(audio);
            if (ext.equals("mp4") || ext.equals("m4a") || ext.equals("m4b") || ext.equals("m4p")) return readMp4(audio);
            String id3 = readId3(audio);
            if (id3 != null) return id3;
            if (ext.equals("flac")) return null;
            return readMp4(audio);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================= ID3v2 =================

    private static String readId3(File audio) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(audio, "r")) {
            if (raf.length() < 10) return null;
            byte[] head = new byte[10];
            raf.readFully(head);
            if (head[0] != 'I' || head[1] != 'D' || head[2] != '3') return null;
            int major = head[3] & 0xFF;
            if (major < 2 || major > 4) return null;
            boolean unsync = (head[5] & 0x80) != 0;
            int tagSize = synchsafe(head, 6);
            if (tagSize <= 0 || tagSize > 32 * 1024 * 1024) return null;
            long tagEnd = Math.min(raf.length(), 10L + tagSize);
            int len = (int) (tagEnd - 10);
            byte[] tag = new byte[len];
            raf.seek(10);
            raf.readFully(tag);
            if (unsync) tag = deunsync(tag);
            if (major == 2) return scanId3v2(tag, true, false);
            return scanId3v2(tag, false, major == 4);
        }
    }

    private static int synchsafe(byte[] b, int off) {
        return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
                | ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }

    private static byte[] deunsync(byte[] tag) {
        byte[] out = new byte[tag.length];
        int w = 0;
        for (int i = 0; i < tag.length; i++) {
            out[w++] = tag[i];
            if (tag[i] == (byte) 0xFF && i + 1 < tag.length && tag[i + 1] == 0x00) i++;
        }
        byte[] r = new byte[w];
        System.arraycopy(out, 0, r, 0, w);
        return r;
    }

    private static boolean isFrameId(byte[] tag, int off, int idLen) {
        if (off + idLen > tag.length) return false;
        for (int i = 0; i < idLen; i++) {
            byte c = tag[off + i];
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** 帧头校验滑动扫描：扩展头／填充／垃圾字节都滑过去，只认合法帧头；目标帧按优先级收齐再定。 */
    private static String scanId3v2(byte[] tag, boolean v22, boolean v24) throws Exception {
        String uslt = null;
        String sylt = null;
        String txxx = null;
        int off = 0;
        while (off < tag.length) {
            if (v22) {
                if (!isFrameId(tag, off, 3)) {
                    off++;
                    continue;
                }
                if (off + 6 > tag.length) break;
                int size = ((tag[off + 3] & 0xFF) << 16) | ((tag[off + 4] & 0xFF) << 8) | (tag[off + 5] & 0xFF);
                if (size <= 0 || off + 6 + size > tag.length) break;
                String id = new String(tag, off, 3, StandardCharsets.ISO_8859_1);
                int body = off + 6;
                if (id.equals("ULT") && uslt == null) uslt = parseUslt(tag, body, size);
                else if (id.equals("SLT") && sylt == null) sylt = parseSylt(tag, body, size);
                off += 6 + size;
            } else {
                if (!isFrameId(tag, off, 4)) {
                    off++;
                    continue;
                }
                if (off + 10 > tag.length) break;
                // 扫描 M12：v2.4 帧长是 synchsafe 整数（每字节 7 位），与 v2.3 的普通
                // 大端整数不同——旧实现 3/4 共用大端解析，v2.4 下歌词帧（≥128 字节）
                // 长度被放大数十倍、整表扫描直接 break，内嵌词基本读不到。
                int size = v24 ? synchsafe(tag, off + 4)
                        : ((tag[off + 4] & 0xFF) << 24) | ((tag[off + 5] & 0xFF) << 16)
                        | ((tag[off + 6] & 0xFF) << 8) | (tag[off + 7] & 0xFF);
                if (size <= 0 || off + 10 + size > tag.length) break;
                String id = new String(tag, off, 4, StandardCharsets.ISO_8859_1);
                int body = off + 10;
                if (id.equals("USLT") && uslt == null) uslt = parseUslt(tag, body, size);
                else if (id.equals("SYLT") && sylt == null) sylt = parseSylt(tag, body, size);
                else if (id.equals("TXXX") && txxx == null) txxx = parseTxxx(tag, body, size);
                off += 10 + size;
            }
        }
        if (uslt != null && !uslt.trim().isEmpty()) return uslt;
        if (sylt != null && !sylt.trim().isEmpty()) return sylt;
        if (txxx != null && !txxx.trim().isEmpty()) return txxx;
        return null;
    }

    /** USLT/ULT：编码(1)＋语言(3)＋描述(按编码结尾)＋正文 */
    private static String parseUslt(byte[] tag, int body, int size) {
        if (size < 5) return null;
        int enc = tag[body] & 0xFF;
        if (enc > 3) return null;
        int p = body + 4;
        int end = body + size;
        int descEnd = findTerminator(tag, p, end, enc);
        if (descEnd < 0) return null;
        int textStart = descEnd + (enc == 0 || enc == 3 ? 1 : 2);
        if (textStart > end) return null;
        return decode(tag, textStart, end - textStart, enc);
    }

    /** SYLT/SLT：编码＋语言(3)＋时间戳格式(1)＋内容类型(1)＋描述＋（正文结尾＋4 字节时间戳）循环 → LRC */
    private static String parseSylt(byte[] tag, int body, int size) {
        if (size < 7) return null;
        int enc = tag[body] & 0xFF;
        if (enc > 3) return null;
        int tsFormat = tag[body + 4] & 0xFF;
        if (tsFormat != 1 && tsFormat != 2) return null;
        int p = body + 6;
        int end = body + size;
        int descEnd = findTerminator(tag, p, end, enc);
        if (descEnd < 0) return null;
        p = descEnd + (enc == 0 || enc == 3 ? 1 : 2);
        List<long[]> entries = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (p < end) {
            int tEnd = findTerminator(tag, p, end, enc);
            if (tEnd < 0 || tEnd + (enc == 0 || enc == 3 ? 1 : 2) + 4 > end) break;
            String text = decode(tag, p, tEnd - p, enc);
            int tsPos = tEnd + (enc == 0 || enc == 3 ? 1 : 2);
            long ts = ((tag[tsPos] & 0xFFL) << 24) | ((tag[tsPos + 1] & 0xFFL) << 16)
                    | ((tag[tsPos + 2] & 0xFFL) << 8) | (tag[tsPos + 3] & 0xFFL);
            // 扫描 M11：SYLT 格式字节 2＝毫秒（本就是毫秒，旧实现却按采样数换算、
            // 时间被压到约 1/22）；1＝MPEG 帧为单位，帧长取决于音频流参数、SYLT
            // 本身不带，只能按最常见的 MPEG1 Layer3（1152 采样/帧、44.1kHz）近似
            // 换算——原样当毫秒用会差约 26 倍，近似换算后至少量级正确。
            if (tsFormat == 1) ts = ts * 1152 * 1000 / 44100;
            if (!text.trim().isEmpty()) {
                entries.add(new long[]{ts});
                texts.add(text.trim());
            }
            p = tsPos + 4;
        }
        if (texts.isEmpty()) return null;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Long.compare(entries.get(a)[0], entries.get(b)[0]));
        StringBuilder sb = new StringBuilder();
        for (int idx : order) {
            long ms = Math.max(0, entries.get(idx)[0]);
            sb.append(String.format("[%02d:%02d.%02d]", ms / 60000, (ms / 1000) % 60, (ms / 10) % 100));
            sb.append(texts.get(idx)).append('\n');
        }
        return sb.toString();
    }

    /** TXXX：编码＋描述＋值；描述大写后含 LYRIC 才认 */
    private static String parseTxxx(byte[] tag, int body, int size) {
        if (size < 2) return null;
        int enc = tag[body] & 0xFF;
        if (enc > 3) return null;
        int end = body + size;
        int descEnd = findTerminator(tag, body + 1, end, enc);
        if (descEnd < 0) return null;
        String desc = decode(tag, body + 1, descEnd - (body + 1), enc);
        if (desc == null || !desc.toUpperCase().contains("LYRIC")) return null;
        int valStart = descEnd + (enc == 0 || enc == 3 ? 1 : 2);
        if (valStart > end) return null;
        return decode(tag, valStart, end - valStart, enc);
    }

    private static int findTerminator(byte[] tag, int from, int end, int enc) {
        if (enc == 0 || enc == 3) {
            for (int i = from; i < end; i++) if (tag[i] == 0) return i;
            return -1;
        }
        for (int i = from; i + 1 < end; i += 2) {
            if (tag[i] == 0 && tag[i + 1] == 0) return i;
        }
        return -1;
    }

    private static String decode(byte[] tag, int off, int len, int enc) {
        try {
            if (len <= 0) return "";
            switch (enc) {
                case 0:
                    return new String(tag, off, len, StandardCharsets.ISO_8859_1);
                case 2:
                    return new String(tag, off, len, StandardCharsets.UTF_16BE);
                case 3:
                    return new String(tag, off, len, StandardCharsets.UTF_8);
                default: {
                    if (len >= 2 && tag[off] == (byte) 0xFF && tag[off + 1] == (byte) 0xFE) {
                        return new String(tag, off + 2, len - 2, StandardCharsets.UTF_16LE);
                    }
                    if (len >= 2 && tag[off] == (byte) 0xFE && tag[off + 1] == (byte) 0xFF) {
                        return new String(tag, off + 2, len - 2, StandardCharsets.UTF_16BE);
                    }
                    return new String(tag, off, len, StandardCharsets.UTF_16LE);
                }
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ================= FLAC =================

    private static String readFlac(File audio) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(audio, "r")) {
            if (raf.length() < 8) return null;
            byte[] head = new byte[4];
            raf.readFully(head);
            if (head[0] != 'f' || head[1] != 'L' || head[2] != 'a' || head[3] != 'C') return null;
            long pos = 4;
            while (pos + 4 <= raf.length()) {
                raf.seek(pos);
                int b0 = raf.read();
                int type = b0 & 0x7F;
                boolean last = (b0 & 0x80) != 0;
                int len = (raf.read() << 16) | (raf.read() << 8) | raf.read();
                if (len < 0 || pos + 4 + len > raf.length()) return null;
                if (type == 4) {
                    byte[] block = new byte[len];
                    raf.readFully(block);
                    return parseVorbisComment(block);
                }
                pos += 4L + len;
                if (last) break;
            }
            return null;
        }
    }

    private static String parseVorbisComment(byte[] block) {
        try {
            int p = 0;
            if (block.length < 8) return null;
            int vendorLen = le32(block, p);
            p += 4;
            if (vendorLen < 0 || p + vendorLen + 4 > block.length) return null;
            p += vendorLen;
            int count = le32(block, p);
            p += 4;
            if (count < 0 || count > 100000) return null;
            for (int i = 0; i < count; i++) {
                if (p + 4 > block.length) return null;
                int elen = le32(block, p);
                p += 4;
                if (elen < 0 || p + elen > block.length) return null;
                String entry = new String(block, p, elen, StandardCharsets.UTF_8);
                p += elen;
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String key = entry.substring(0, eq).trim().toUpperCase();
                if (key.equals("LYRIC") || key.equals("LYRICS") || key.equals("UNSYNCEDLYRICS")) {
                    String v = entry.substring(eq + 1);
                    if (!v.trim().isEmpty()) return v;
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    // ================= MP4 =================

    private static String readMp4(File audio) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(audio, "r")) {
            long fileLen = raf.length();
            String[] path = {"moov", "udta", "meta", "ilst", "\u00a9lyr"};
            long[] range = {0, fileLen};
            for (int depth = 0; depth < path.length; depth++) {
                long[] found = findBox(raf, range[0], range[1], path[depth]);
                if (found == null) return null;
                range = found;
                // meta 盒子内容区前 4 字节是版本／标志，下一层（ilst）在其后找
                if (depth == 2) range[0] += 4;
            }
            long[] data = findBox(raf, range[0], range[1], "data");
            if (data == null) return null;
            long textStart = data[0] + 8;
            long textEnd = data[1];
            if (textEnd <= textStart || textEnd - textStart > 4 * 1024 * 1024) return null;
            byte[] text = new byte[(int) (textEnd - textStart)];
            raf.seek(textStart);
            raf.readFully(text);
            String s = new String(text, StandardCharsets.UTF_8);
            return s.trim().isEmpty() ? null : s;
        }
    }

    /** 在 [start, end) 内找 type 盒子，返回其内容区 [contentStart, contentEnd) */
    private static long[] findBox(RandomAccessFile raf, long start, long end, String type) throws Exception {
        byte[] want = type.getBytes(StandardCharsets.ISO_8859_1);
        long pos = start;
        int guard = 0;
        while (pos + 8 <= end && guard++ < 10000) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            byte[] t = new byte[4];
            raf.readFully(t);
            long header = 8;
            if (size == 1) {
                if (pos + 16 > end) return null;
                size = raf.readLong();
                header = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < header || pos + size > end) return null;
            if (t[0] == want[0] && t[1] == want[1] && t[2] == want[2] && t[3] == want[3]) {
                return new long[]{pos + header, pos + size};
            }
            pos += size;
        }
        return null;
    }
}
