package com.fongmi.android.tv.music;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 本地音频内嵌标签写入（下载「封面与歌词」内嵌档的真写码位置；与 EmbeddedLyricsReader 读侧分工）。
 * 树内无音频标签写入库，此处按格式原文手写，仅支持两种可无损重写的容器：
 * MP3＝ID3v2.3（重建标签头：保留原有帧、替换 APIC／USLT 两帧，音频数据原样后接）；
 * FLAC＝元数据块重写（STREAMINFO 等块原样保留，Vorbis comment 内 LYRICS 键替换、
 * PICTURE 块替换，音频帧原样后接）。其余格式（m4a／ogg 等）返回 false，由调用方
 * 按写入失败策略分流，绝不假报已内嵌。写入先落同目录临时文件、成功才替换原文件，
 * 失败时原文件不动。
 */
public final class EmbeddedTagWriter {

    private EmbeddedTagWriter() {
    }

    /**
     * 把封面图与歌词文本内嵌进音频文件。coverBytes／lyricText 任一为 null 表示该项不写
     * （保留原文件里已有的同类帧／键）；两项都成功（或不要求）才返回 true。
     */
    public static boolean embed(File audio, byte[] coverBytes, String lyricText) {
        if (audio == null || !audio.isFile()) return false;
        if (coverBytes == null && lyricText == null) return true;
        try {
            byte[] head = new byte[4];
            try (FileInputStream in = new FileInputStream(audio)) {
                if (in.read(head) != 4) return false;
            }
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                return embedFlac(audio, coverBytes, lyricText);
            }
            boolean id3 = head[0] == 'I' && head[1] == 'D' && head[2] == '3';
            boolean mpegSync = (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
            if (id3 || mpegSync) return embedId3(audio, coverBytes, lyricText);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= ID3v2.3（MP3） =================

    private static boolean embedId3(File audio, byte[] coverBytes, String lyricText) throws Exception {
        long audioStart = 0;
        byte[] keptFrames = new byte[0];
        try (RandomAccessFile raf = new RandomAccessFile(audio, "r")) {
            if (raf.length() >= 10) {
                byte[] h = new byte[10];
                raf.readFully(h);
                if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
                    int major = h[3] & 0xFF;
                    int tagSize = synchsafe(h, 6);
                    if (tagSize > 0 && tagSize <= 32 * 1024 * 1024 && 10L + tagSize <= raf.length()) {
                        byte[] tag = new byte[tagSize];
                        raf.seek(10);
                        raf.readFully(tag);
                        keptFrames = filterFrames(tag, major == 4,
                                coverBytes != null, lyricText != null);
                        audioStart = 10L + tagSize;
                        // v2.4 带页脚标志时标签后还有 10 字节页脚，音频从其后起
                        if (major == 4 && (h[5] & 0x10) != 0) audioStart += 10;
                    }
                }
            }
        }
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(keptFrames);
        if (coverBytes != null) frames.write(frame("APIC", apicBody(coverBytes)));
        if (lyricText != null) frames.write(frame("USLT", usltBody(lyricText)));
        byte[] frameBytes = frames.toByteArray();
        File tmp = new File(audio.getParentFile(), audio.getName() + ".tagtmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new byte[]{'I', 'D', '3', 3, 0, 0,
                        (byte) ((frameBytes.length >> 21) & 0x7F), (byte) ((frameBytes.length >> 14) & 0x7F),
                        (byte) ((frameBytes.length >> 7) & 0x7F), (byte) (frameBytes.length & 0x7F)});
                out.write(frameBytes);
                try (FileInputStream in = new FileInputStream(audio)) {
                    skipFully(in, audioStart);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            return replaceFile(tmp, audio);
        } finally {
            if (tmp.isFile() && !audio.equals(tmp)) tmp.delete();
        }
    }

    /** 保留原标签帧（原样字节块），只剔除本次要替换的 APIC／USLT；帧序解析失败即整表丢弃重建（不许把音频数据误当帧留进新标签）。 */
    private static byte[] filterFrames(byte[] tag, boolean v24, boolean dropApic, boolean dropUslt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = 0;
        while (off + 10 <= tag.length) {
            if (!isFrameId(tag, off)) break;
            int size = v24 ? synchsafe(tag, off + 4)
                    : ((tag[off + 4] & 0xFF) << 24) | ((tag[off + 5] & 0xFF) << 16)
                    | ((tag[off + 6] & 0xFF) << 8) | (tag[off + 7] & 0xFF);
            if (size < 0 || off + 10 + size > tag.length) break;
            if (size > 0) {
                String id = new String(tag, off, 4, StandardCharsets.ISO_8859_1);
                boolean drop = (dropApic && id.equals("APIC")) || (dropUslt && id.equals("USLT"));
                if (!drop) out.write(tag, off, 10 + size);
            }
            off += 10 + size;
        }
        return out.toByteArray();
    }

    private static byte[] frame(String id, byte[] body) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        put(f, id.getBytes(StandardCharsets.ISO_8859_1));
        f.write((body.length >> 24) & 0xFF);
        f.write((body.length >> 16) & 0xFF);
        f.write((body.length >> 8) & 0xFF);
        f.write(body.length & 0xFF);
        f.write(0);
        f.write(0);
        put(f, body);
        return f.toByteArray();
    }

    /** APIC：文本编码 UTF-8(3)＋MIME（ASCII、0 结尾）＋图片类型 3（封面）＋描述（空、0 结尾）＋图数据 */
    private static byte[] apicBody(byte[] pic) {
        String mime = isPng(pic) ? "image/png" : "image/jpeg";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(3);
        put(b, mime.getBytes(StandardCharsets.ISO_8859_1));
        b.write(0);
        b.write(3);
        b.write(0);
        put(b, pic);
        return b.toByteArray();
    }

    /** USLT：文本编码 UTF-8(3)＋语言 eng＋描述（空、0 结尾）＋歌词正文（LRC 原文） */
    private static byte[] usltBody(String lyricText) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(3);
        put(b, new byte[]{'e', 'n', 'g'});
        b.write(0);
        put(b, lyricText.getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    // ================= FLAC =================

    private static boolean embedFlac(File audio, byte[] coverBytes, String lyricText) throws Exception {
        byte[] data;
        try (FileInputStream in = new FileInputStream(audio)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            data = bos.toByteArray();
        }
        if (data.length < 8) return false;
        // 解析元数据块表：(类型, 内容起, 内容长)；音频帧起点＝最后一块之后
        java.util.List<int[]> blocks = new java.util.ArrayList<>();
        int pos = 4;
        boolean last = false;
        while (!last && pos + 4 <= data.length) {
            int b0 = data[pos] & 0xFF;
            last = (b0 & 0x80) != 0;
            int type = b0 & 0x7F;
            int len = ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (len < 0 || pos + 4 + len > data.length) return false;
            blocks.add(new int[]{type, pos + 4, len});
            pos += 4 + len;
        }
        int audioStart = pos;
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        boolean vorbisDone = false;
        // 先数总块数定 last 标志：保留块＋（歌词需新建 comment 块时 +1）＋（封面时 +1 PICTURE）
        boolean needNewComment = lyricText != null && blocks.stream().noneMatch(b -> b[0] == 4);
        int total = blocks.size() + (needNewComment ? 1 : 0) + (coverBytes != null ? 1 : 0);
        int written = 0;
        for (int[] b : blocks) {
            int type = b[0];
            if (type == 6 && coverBytes != null) continue; // 旧 PICTURE 换新
            byte[] content;
            if (type == 4 && lyricText != null) {
                content = rebuildVorbisComment(data, b[1], b[2], lyricText);
                vorbisDone = true;
            } else {
                content = java.util.Arrays.copyOfRange(data, b[1], b[1] + b[2]);
            }
            written++;
            writeBlock(meta, type, content, written == total);
        }
        if (lyricText != null && !vorbisDone) {
            written++;
            writeBlock(meta, 4, rebuildVorbisComment(null, 0, 0, lyricText), written == total);
        }
        if (coverBytes != null) {
            written++;
            writeBlock(meta, 6, pictureBlock(coverBytes), written == total);
        }
        File tmp = new File(audio.getParentFile(), audio.getName() + ".tagtmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(new byte[]{'f', 'L', 'a', 'C'});
                out.write(meta.toByteArray());
                out.write(data, audioStart, data.length - audioStart);
            }
            return replaceFile(tmp, audio);
        } finally {
            if (tmp.isFile()) tmp.delete();
        }
    }

    /** Vorbis comment 重建：保留 vendor 与其它键，LYRICS／LYRIC／UNSYNCEDLYRICS 旧键剔除、末尾写入 LYRICS=正文 */
    private static byte[] rebuildVorbisComment(byte[] src, int off, int len, String lyricText) {
        java.util.List<String> entries = new java.util.ArrayList<>();
        String vendor = "";
        if (src != null && len >= 8) {
            int p = off;
            int end = off + len;
            int vendorLen = le32(src, p);
            p += 4;
            if (vendorLen >= 0 && p + vendorLen + 4 <= end) {
                vendor = new String(src, p, vendorLen, StandardCharsets.UTF_8);
                p += vendorLen;
                int count = le32(src, p);
                p += 4;
                for (int i = 0; i < count && p + 4 <= end; i++) {
                    int elen = le32(src, p);
                    p += 4;
                    if (elen < 0 || p + elen > end) break;
                    entries.add(new String(src, p, elen, StandardCharsets.UTF_8));
                    p += elen;
                }
            }
        }
        entries.removeIf(e -> {
            int eq = e.indexOf('=');
            if (eq <= 0) return false;
            String k = e.substring(0, eq).trim().toUpperCase(java.util.Locale.ROOT);
            return k.equals("LYRICS") || k.equals("LYRIC") || k.equals("UNSYNCEDLYRICS");
        });
        entries.add("LYRICS=" + lyricText);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] v = vendor.getBytes(StandardCharsets.UTF_8);
        writeLe32(b, v.length);
        put(b, v);
        writeLe32(b, entries.size());
        for (String e : entries) {
            byte[] eb = e.getBytes(StandardCharsets.UTF_8);
            writeLe32(b, eb.length);
            put(b, eb);
        }
        return b.toByteArray();
    }

    /** FLAC PICTURE 块：类型 3（封面）＋MIME＋空描述＋尺寸全 0＋图数据 */
    private static byte[] pictureBlock(byte[] pic) {
        String mime = isPng(pic) ? "image/png" : "image/jpeg";
        byte[] m = mime.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeBe32(b, 3);
        writeBe32(b, m.length);
        put(b, m);
        writeBe32(b, 0);
        writeBe32(b, 0);
        writeBe32(b, 0);
        writeBe32(b, 0);
        writeBe32(b, 0);
        writeBe32(b, pic.length);
        put(b, pic);
        return b.toByteArray();
    }

    private static void writeBlock(ByteArrayOutputStream out, int type, byte[] content, boolean last) {
        out.write((last ? 0x80 : 0) | type);
        out.write((content.length >> 16) & 0xFF);
        out.write((content.length >> 8) & 0xFF);
        out.write(content.length & 0xFF);
        put(out, content);
    }

    // ================= 共用 =================

    private static boolean replaceFile(File tmp, File target) {
        try {
            if (!tmp.isFile() || tmp.length() == 0) return false;
            if (target.exists() && !target.delete()) return false;
            return tmp.renameTo(target);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void put(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private static void skipFully(FileInputStream in, long n) throws Exception {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static boolean isPng(byte[] b) {
        return b != null && b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean isFrameId(byte[] tag, int off) {
        for (int i = 0; i < 4; i++) {
            byte c = tag[off + i];
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static int synchsafe(byte[] b, int off) {
        return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
                | ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void writeLe32(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF);
        b.write((v >> 8) & 0xFF);
        b.write((v >> 16) & 0xFF);
        b.write((v >> 24) & 0xFF);
    }

    private static void writeBe32(ByteArrayOutputStream b, int v) {
        b.write((v >> 24) & 0xFF);
        b.write((v >> 16) & 0xFF);
        b.write((v >> 8) & 0xFF);
        b.write(v & 0xFF);
    }
}
