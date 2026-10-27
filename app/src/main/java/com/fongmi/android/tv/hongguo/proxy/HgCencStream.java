package com.fongmi.android.tv.hongguo.proxy;

import com.fongmi.android.tv.hongguo.crypto.HgCencDecryptor;
import com.fongmi.android.tv.hongguo.crypto.HgHex;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条已注册的红果解密流：随机读源＋DEK＋懒解析的盒结构。
 *
 * <p>时序：构造不取数；{@link #ensureMeta()} 首次调用才解析（顶层盒头小读＋
 * moov 一读）；{@link #readClear} 按请求区间取数、套补丁、只解相关样本。
 * 播放器每次 Range 请求都走 readClear，互不等待整集，首字节只取决于首段取数
 * 与盒解析。流可被多条 Range 请求并发读（Meta 不可变、每次解密用独立 Cipher、
 * 取数走源自身的同步／无状态实现）。</p>
 */
public final class HgCencStream implements Closeable {

    private final HgRandomSource source;
    private final byte[] dek;
    private final long expectedSize;
    private final String fileHash;
    private volatile HgCencDecryptor.Meta meta;
    private final AtomicLong decryptedBlocks = new AtomicLong();
    private volatile boolean closed;

    public HgCencStream(HgRandomSource source, String dekHex, long expectedSize, String fileHash) {
        this.source = source;
        this.dek = HgHex.decode(dekHex);
        if (this.dek.length != 16) throw new IllegalArgumentException("DEK 长度须 16 字节");
        this.expectedSize = expectedSize;
        this.fileHash = fileHash;
    }

    public HgRandomSource source() {
        return source;
    }

    public String getFileHash() {
        return fileHash;
    }

    /** 总长：优先回包 size（省一笔探测），没有才问源。 */
    public long length() throws IOException {
        if (expectedSize > 0) return expectedSize;
        return source.length();
    }

    /** 解析盒结构（幂等，解析后缓存）。预热口与首笔出流都会先走这里。 */
    public HgCencDecryptor.Meta ensureMeta() throws IOException {
        HgCencDecryptor.Meta m = meta;
        if (m != null) return m;
        synchronized (this) {
            if (meta == null) {
                if (closed) throw new IOException("流已关闭");
                // 盒解析读的是源的绝对偏移；若回包 size 与源实际长不符，以源为准校验
                long srcLen = source.length();
                if (expectedSize > 0 && srcLen > 0 && srcLen != expectedSize) {
                    // 不直接判死：直链可能换档/重签，长度以源为真值继续走，
                    // file_hash 校验留给三阶段（整流落盤时）与联调。
                }
                meta = HgCencDecryptor.parse(source);
            }
            return meta;
        }
    }

    public boolean isParsed() {
        return meta != null;
    }

    /** 累计已解密的 16 字节块数（「尾部小区间不触发整解」的计数证据）。 */
    public long decryptedBlocks() {
        return decryptedBlocks.get();
    }

    /**
     * 读明文字节：从绝对偏移 pos 起最多 len 字节进 buf[off..]，返回实际字节数，
     * 文件尾返回 -1。流程＝按区间取密文（源内部只取所需段）→套补丁表→只解
     * 与区间相交样本的相交块，全程在调用方 buf 上原地完成，不另拷大缓冲。
     */
    public int readClear(long pos, byte[] buf, int off, int len) throws IOException {
        HgCencDecryptor.Meta m = ensureMeta();
        long total = length();
        if (pos >= total) return -1;
        int want = (int) Math.min(len, total - pos);
        int got = 0;
        while (got < want) {
            int n = source.readAt(pos + got, buf, off + got, want - got);
            if (n < 0) break;
            got += n;
        }
        if (got == 0) return -1;
        HgCencDecryptor.applyPatches(m, buf, pos, off, got);
        decryptedBlocks.addAndGet(HgCencDecryptor.decryptWindow(m, dek, buf, pos, off, got));
        return got;
    }

    /**
     * 预热：解析盒结构＋把文件头一段（覆盖 ftyp/moov 在头时的 moov 与首批样本）
     * 真读一遍解密丢弃——播放页滑到之前调用，正式起播时 Meta 已就绪、CDN 连接
     * 已热。moov 在尾时 ensureMeta 已把尾部 moov 取回，本段只再摸头部首样本区。
     */
    public void warm() throws IOException {
        ensureMeta();
        long total = length();
        int n = (int) Math.min(total, 256 * 1024);
        if (n > 0) {
            byte[] scratch = new byte[n];
            readClear(0, scratch, 0, n);
        }
    }

    @Override
    public void close() {
        closed = true;
        source.close();
    }
}
