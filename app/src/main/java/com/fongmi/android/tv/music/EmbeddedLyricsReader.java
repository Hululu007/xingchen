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
 * MP4：moov > udta > meta > ilst > ©lyr > data；
 * Ogg：Vorbis（\x03vorbis 注释包）／Opus（OpusTags 包）的 LYRICS 系键，注释包结构与 FLAC 同族。
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
            if (ext.equals("ogg") || ext.equals("oga") || ext.equals("opus")) return readOgg(audio);
            String id3 = readId3(audio);
            if (id3 != null) return id3;
            if (ext.equals("flac")) return null;
            // 无扩展名／错扩展名时按魔数兜底探 Ogg 容器（Vorbis/Opus 注释包）
            String ogg = readOgg(audio);
            if (ogg != null) return ogg;
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
            // SYLT 格式 1（MPEG 帧单位）换算要音频流的采样率与每帧采样数：
            // 在 ID3 标签之后找音频帧头／STREAMINFO 现读（见 audioParams），
            // 读不到才回落最常见近似（在 parseSylt 处注明）。
            int[] params = audioParams(audio, 10L + tagSize);
            if (major == 2) return scanId3v2(tag, true, false, params);
            return scanId3v2(tag, false, major == 4, params);
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
    private static String scanId3v2(byte[] tag, boolean v22, boolean v24, int[] syltParams) throws Exception {
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
                else if (id.equals("SLT") && sylt == null) sylt = parseSylt(tag, body, size, syltParams);
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
                else if (id.equals("SYLT") && sylt == null) sylt = parseSylt(tag, body, size, syltParams);
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

    /** SYLT/SLT：编码＋语言(3)＋时间戳格式(1)＋内容类型(1)＋描述＋（正文结尾＋4 字节时间戳）循环 → LRC。
     * syltParams＝audioParams 现读的 [采样率, 每帧采样数]，格式 1 按它真换算；null＝流参数
     * 读不到，回落 MPEG1 Layer3／44.1kHz 近似（仅量级正确，非 44.1k 文件仍会偏，属明示局限）。 */
    private static String parseSylt(byte[] tag, int body, int size, int[] syltParams) {
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
            // 时间被压到约 1/22）；1＝MPEG 帧为单位：帧长＝每帧采样数／采样率，
            // 由 audioParams 从音频流现读后真换算（MP3 帧头／FLAC STREAMINFO）；
            // 流参数读不到才回落 MPEG1 Layer3（1152 采样/帧、44.1kHz）近似。
            if (tsFormat == 1) {
                long spf = syltParams != null ? syltParams[1] : 1152;
                long rate = syltParams != null ? syltParams[0] : 44100;
                ts = ts * spf * 1000 / rate;
            }
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

    // ================= 音频流参数（SYLT 格式 1 真换算用） =================

    /**
     * 现读音频流参数 [采样率, 每帧采样数]，只供 SYLT 格式 1 换算；读不到返回 null。
     * 两路：FLAC STREAMINFO（采样率 20 位在块偏移 18，每帧采样数 FLAC 是变长帧、无
     * 固定值——FLAC 载 ID3/SYLT 本就罕见，此时每帧采样数按 4096 常规块长近似，属局限）；
     * MP3 帧头（在 from 之后 256KB 内找首个合法帧同步：版本/层/码率/采样率索引全合法才认）。
     */
    private static int[] audioParams(File audio, long from) {
        try (RandomAccessFile raf = new RandomAccessFile(audio, "r")) {
            long len = raf.length();
            if (len < 4) return null;
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a' && magic[3] == 'C') {
                // STREAMINFO 是第一个元数据块：块头 4 字节＋34 字节内容，采样率在内容偏移 10..12
                raf.seek(4);
                int b0 = raf.read();
                int type = b0 & 0x7F;
                int blockLen = (raf.read() << 16) | (raf.read() << 8) | raf.read();
                if (type != 0 || blockLen < 14) return null;
                raf.seek(4 + 4 + 10);
                int s0 = raf.read(), s1 = raf.read(), s2 = raf.read();
                int rate = (s0 << 12) | (s1 << 4) | (s2 >> 4);
                return rate > 0 ? new int[]{rate, 4096} : null;
            }
            long start = Math.max(0, from);
            long scanEnd = Math.min(len - 4, start + 256 * 1024);
            byte[] buf = new byte[(int) Math.max(0, scanEnd - start + 4)];
            if (buf.length < 4) return null;
            raf.seek(start);
            raf.readFully(buf);
            // 采样率表（索引 00/01/10 序）：MPEG1 44100/48000/32000、MPEG2 22050/24000/16000、MPEG2.5 11025/12000/8000
            int[][] rates = {
                    {44100, 48000, 32000}, // MPEG1
                    {22050, 24000, 16000}, // MPEG2
                    {11025, 12000, 8000},  // MPEG2.5
            };
            for (int i = 0; i + 4 <= buf.length; i++) {
                int h = ((buf[i] & 0xFF) << 24) | ((buf[i + 1] & 0xFF) << 16)
                        | ((buf[i + 2] & 0xFF) << 8) | (buf[i + 3] & 0xFF);
                if ((h & 0xFFE00000) != 0xFFE00000) continue;
                int version = (h >> 19) & 3; // 3=MPEG1, 2=MPEG2, 0=MPEG2.5, 1=保留
                int layer = (h >> 17) & 3;   // 3=I, 2=II, 1=III, 0=保留
                int bitrateIdx = (h >> 12) & 15;
                int rateIdx = (h >> 10) & 3;
                if (version == 1 || layer == 0 || bitrateIdx == 15 || bitrateIdx == 0 || rateIdx == 3) continue;
                int vrow = version == 3 ? 0 : version == 2 ? 1 : 2;
                int rate = rates[vrow][rateIdx];
                int spf;
                if (layer == 3) spf = 384;                        // Layer I 恒 384
                else if (layer == 2) spf = 1152;                   // Layer II 恒 1152
                else spf = version == 3 ? 1152 : 576;              // Layer III：MPEG1 1152／MPEG2+ 576
                return new int[]{rate, spf};
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================= Ogg（Vorbis／Opus 注释包） =================

    /** 注释包上限：歌词文本远小于此，超限按损坏处理不整包载入 */
    private static final int OGG_COMMENT_MAX = 1024 * 1024;

    /**
     * Ogg 容器注释：逐页重组数据包，找 Vorbis \x03vorbis／Opus OpusTags 注释包，
     * 包体结构与 FLAC Vorbis comment 同族（vendor＋条目数＋"键=值"），复用 parseVorbisComment
     * 但键集走本类口径（含 LYRICS 系）。魔数不是 OggS 直接回 null（供错扩展名兜底探）。
     */
    private static String readOgg(File audio) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(audio, "r")) {
            if (raf.length() < 27) return null;
            byte[] head = new byte[4];
            raf.readFully(head);
            if (head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') return null;
            java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
            long pos = 0;
            long fileLen = raf.length();
            int guard = 0;
            boolean seenHeader = false;
            while (pos + 27 <= fileLen && guard++ < 4096) {
                raf.seek(pos);
                byte[] ph = new byte[27];
                raf.readFully(ph);
                if (ph[0] != 'O' || ph[1] != 'g' || ph[2] != 'g' || ph[3] != 'S') return null;
                int nSeg = ph[26] & 0xFF;
                if (pos + 27 + nSeg > fileLen) return null;
                byte[] segTable = new byte[nSeg];
                raf.readFully(segTable);
                long payloadAt = pos + 27 + nSeg;
                long payloadLen = 0;
                for (byte s : segTable) payloadLen += s & 0xFF;
                if (payloadAt + payloadLen > fileLen) return null;
                raf.seek(payloadAt);
                // 逐段重组：段长 255＝包未完续接，<255＝本包在此结束；
                // 一页内可含多个完整包，逐包处理不并包。
                for (int i = 0; i < nSeg; i++) {
                    int segLen = segTable[i] & 0xFF;
                    if (segLen > 0) {
                        byte[] seg = new byte[segLen];
                        raf.readFully(seg);
                        packet.write(seg, 0, seg.length);
                        if (packet.size() > OGG_COMMENT_MAX) return null;
                    }
                    if (segLen == 255) continue;
                    byte[] p = packet.toByteArray();
                    packet.reset();
                    String hit = parseOggCommentPacket(p);
                    if (hit != null) return hit;
                    if (isOggHeaderPacket(p)) {
                        seenHeader = true;
                        continue;
                    }
                    // 注释包必紧跟识别头：识别头之后第一个非注释包出现就停，
                    // 不许整文件逐页扫到尾（音频包不是注释包、扫了也白扫）。
                    if (seenHeader) return null;
                }
                pos = payloadAt + payloadLen;
            }
            return null;
        }
    }

    private static boolean isOggHeaderPacket(byte[] p) {
        // Vorbis 识别头 \x01vorbis／Opus 识别头 OpusHead：其后紧跟的才是注释包；
        // 已扫过识别头又扫完一个完整包还没命中，说明注释包不在头部口径内。
        return p.length >= 7 && ((p[0] == 1 && p[1] == 'v' && p[2] == 'o' && p[3] == 'r' && p[4] == 'b' && p[5] == 'i' && p[6] == 's')
                || (p.length >= 8 && p[0] == 'O' && p[1] == 'p' && p[2] == 'u' && p[3] == 's' && p[4] == 'H' && p[5] == 'e' && p[6] == 'a' && p[7] == 'd'));
    }

    /** 注释包 → 歌词文本；不是注释包返回 null。包体从签名之后起与 Vorbis comment 同结构。 */
    private static String parseOggCommentPacket(byte[] p) {
        int off = -1;
        if (p.length >= 7 && p[0] == 3 && p[1] == 'v' && p[2] == 'o' && p[3] == 'r' && p[4] == 'b' && p[5] == 'i' && p[6] == 's') off = 7;
        else if (p.length >= 8 && p[0] == 'O' && p[1] == 'p' && p[2] == 'u' && p[3] == 's'
                && p[4] == 'T' && p[5] == 'a' && p[6] == 'g' && p[7] == 's') off = 8;
        if (off < 0) return null;
        byte[] body = new byte[p.length - off];
        System.arraycopy(p, off, body, 0, body.length);
        return parseVorbisComment(body);
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
