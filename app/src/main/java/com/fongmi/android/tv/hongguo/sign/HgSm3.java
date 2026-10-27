package com.fongmi.android.tv.hongguo.sign;

/**
 * SM3 摘要：逐行移植 openhongguo internal/protocol/sign/sm3.go（GM/T 0004-2012）。
 * "abc" 的摘要须为 66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0
 * （Go 侧 TestSM3ABC 同向量，离线单测有断言）。
 */
public final class HgSm3 {

    private HgSm3() {
    }

    public static byte[] sum(byte[] msg) {
        int[] h = {
                0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e,
        };
        byte[] padded = pad(msg);
        int[] w = new int[68];
        int[] wp = new int[64];
        for (int off = 0; off < padded.length; off += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = be32(padded, off + i * 4);
            }
            for (int j = 16; j < 68; j++) {
                int x = w[j - 16] ^ w[j - 9] ^ rol(w[j - 3], 15);
                w[j] = p1(x) ^ rol(w[j - 13], 7) ^ w[j - 6];
            }
            for (int j = 0; j < 64; j++) {
                wp[j] = w[j] ^ w[j + 4];
            }
            int a = h[0], b = h[1], c = h[2], d = h[3];
            int e = h[4], f = h[5], g = h[6], hh = h[7];
            for (int j = 0; j < 64; j++) {
                int tj = j < 16 ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = rol(rol(a, 12) + e + rol(tj, j), 7);
                int ss2 = ss1 ^ rol(a, 12);
                int tt1 = ff(j, a, b, c) + d + ss2 + wp[j];
                int tt2 = gg(j, e, f, g) + hh + ss1 + w[j];
                d = c;
                c = rol(b, 9);
                b = a;
                a = tt1;
                hh = g;
                g = rol(f, 19);
                f = e;
                e = p0(tt2);
            }
            h[0] ^= a;
            h[1] ^= b;
            h[2] ^= c;
            h[3] ^= d;
            h[4] ^= e;
            h[5] ^= f;
            h[6] ^= g;
            h[7] ^= hh;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (h[i] >>> 24);
            out[i * 4 + 1] = (byte) (h[i] >>> 16);
            out[i * 4 + 2] = (byte) (h[i] >>> 8);
            out[i * 4 + 3] = (byte) h[i];
        }
        return out;
    }

    private static byte[] pad(byte[] msg) {
        long bitLen = (long) msg.length * 8L;
        int total = msg.length + 1;
        while ((total + 8) % 64 != 0) total++;
        total += 8;
        byte[] out = new byte[total];
        System.arraycopy(msg, 0, out, 0, msg.length);
        out[msg.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            out[total - 1 - i] = (byte) (bitLen >>> (8 * i));
        }
        return out;
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int rol(int v, int n) {
        return Integer.rotateLeft(v, n);
    }

    private static int ff(int j, int x, int y, int z) {
        if (j < 16) return x ^ y ^ z;
        return (x & y) | (x & z) | (y & z);
    }

    private static int gg(int j, int x, int y, int z) {
        if (j < 16) return x ^ y ^ z;
        return (x & y) | (~x & z);
    }

    private static int p0(int x) {
        return x ^ rol(x, 9) ^ rol(x, 17);
    }

    private static int p1(int x) {
        return x ^ rol(x, 15) ^ rol(x, 23);
    }
}
