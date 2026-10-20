package com.fongmi.android.tv.music;

import java.nio.charset.StandardCharsets;

/** Base64 用 android.util.Base64：java.util.Base64 要 API 26，星辰 minSdk 24。 */

/**
 * 加密歌曲解密（纯 Java，无 Android 依赖）。
 *
 * <p>算法依据开源项目 unlock-music（ix64/unlock-music）及其公开的同类实现
 * （jixunmoe/qmc2 的 Swift 移植 QMCDecode、zeroclear/unlock-mflac 的解密文档等）
 * 公布的格式说明独立实现，未复制其源码；文中出现的常量均为算法本身必需的公开数值。</p>
 *
 * <h3>已实现</h3>
 * <ul>
 *   <li>QMC2 移动版（.mflac/.mflac0/.mgg/.mgg1）：文件尾为
 *       [音频][ekey 文本][4 字节小端 ekey 长度]["QTag"]；
 *       ekey 经 Base64 解码后按 EKey V1 派生（simpleMakeKey(106) 交错组 TEA 密钥 +
 *       腾讯 TEA-CBC 解密），派生密钥大于 300 字节时用标准 RC4 解密音频。</li>
 *   <li>QMC2 桌面版 EncV2（.mflac 等，尾部无 QTag、末 4 字节为密钥长度）：
 *       Base64 解码后带 "QQMusic EncV2,Key:" 前缀的密钥走三段腾讯 TEA 派生
 *       （MixKey1 / MixKey2 / 动态 MixKey3），同样以 RC4 收尾。</li>
 * </ul>
 *
 * <h3>未实现（如实声明，不假装支持）</h3>
 * <ul>
 *   <li>QMC 旧格式（.qmc0/.qmc3/.qmcflac）静态掩码：掩码的位置映射规则已有公开说明，
 *       但默认 44 字节掩码矩阵的完整字节未能从公开页面核实（各文档均只给出截断摘要），
 *       为避免编造常量，暂不解密，调用会抛 {@link DecryptException}。</li>
 *   <li>QMC2 短密钥（派生密钥 ≤ 300 字节）的映射表（Map）密码：44/58→128 字节
 *       掩码展开公式未能核实，暂不支持。</li>
 *   <li>酷狗 KGM/KGMA（.kgm/.kgma）：头部结构、文件内 17 字节密钥与两段异或流程
 *       已有公开说明，但核心的三张 272 字节掩码表（MaskV2PreDef / table1 / table2）
 *       完整字节未能核实，暂不解密。</li>
 *   <li>QQ 音乐 2024 年后的 STag 新加密：密钥不在文件内，无法离线解密。</li>
 *   <li>桌面版尾部密钥长度 ≥ 0x300 的“固定密钥”变体：固定密钥未公开，暂不支持。</li>
 * </ul>
 */
public final class MusicDecrypt {

    private MusicDecrypt() {
    }

    /** 解密结果：明文字节 + 按文件头魔数判定的扩展名（不带点，如 "flac"）。 */
    public static final class Result {
        public final byte[] data;
        public final String ext;

        public Result(byte[] data, String ext) {
            this.data = data;
            this.ext = ext;
        }
    }

    /** 解密失败，message 为中文原因。 */
    public static class DecryptException extends Exception {
        public DecryptException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /**
     * 按文件名判断是否为本类能真正解密的格式。
     * 只对已实现的 QMC2 家族返回 true；.qmc0/.qmc3/.qmcflac/.kgm/.kgma
     * 因关键常量未核实返回 false（见类注释）。
     */
    public static boolean isSupportedExt(String fileName) {
        String ext = extOf(fileName);
        switch (ext) {
            case "mflac":
            case "mflac0":
            case "mgg":
            case "mgg1":
                return true;
            default:
                return false;
        }
    }

    /**
     * 解密一首加密歌曲。
     *
     * @param fileName 原文件名（用扩展名分发格式）
     * @param data     加密文件完整字节
     * @return 解密结果（明文 + 按魔数判定的扩展名）
     */
    public static Result decrypt(String fileName, byte[] data) throws DecryptException {
        if (data == null || data.length == 0) {
            throw new DecryptException("文件太短：没有数据");
        }
        String ext = extOf(fileName);
        switch (ext) {
            case "mflac":
            case "mflac0":
            case "mgg":
            case "mgg1":
                return decryptQmc2(data);
            case "qmc0":
            case "qmc3":
            case "qmcflac":
                throw new DecryptException("QMC 旧格式的默认掩码表未能从公开资料核实，暂不支持");
            case "kgm":
            case "kgma":
                throw new DecryptException("酷狗 KGM 的掩码表未能从公开资料核实，暂不支持");
            default:
                throw new DecryptException("不支持的文件类型：" + (ext.isEmpty() ? "(无扩展名)" : ext));
        }
    }

    // ------------------------------------------------------------------
    // QMC2：尾部密钥解析
    // ------------------------------------------------------------------

    private static Result decryptQmc2(byte[] data) throws DecryptException {
        if (data.length < 16) {
            throw new DecryptException("文件太短：不足以包含 QMC2 密钥尾");
        }
        // 2024 年后的新加密：尾巴是 "STag"，密钥不随文件分发，离线解不了。
        if (tailEquals(data, "STag")) {
            throw new DecryptException("这是新版 STag 加密，密钥不在文件里，暂不支持");
        }

        final byte[] rawKeyText;
        final int audioLen;
        if (tailEquals(data, "QTag")) {
            // 移动版：[音频][ekey][4 字节小端长度]["QTag"]
            long keyLen = readLe32u(data, data.length - 8);
            if (keyLen <= 0 || keyLen > data.length - 8L) {
                throw new DecryptException("QTag 尾不对：密钥长度异常");
            }
            audioLen = (int) (data.length - 8 - keyLen);
            rawKeyText = slice(data, audioLen, (int) keyLen);
        } else {
            // 桌面版：[音频][ekey][4 字节小端长度]
            long keyLen = readLe32u(data, data.length - 4);
            if (keyLen <= 0 || keyLen > data.length - 4L) {
                throw new DecryptException("文件尾不对：找不到密钥（可能不是 QMC2 文件）");
            }
            if (keyLen >= 0x300) {
                throw new DecryptException("这个版本的加密暂不支持（桌面版固定密钥型）");
            }
            audioLen = (int) (data.length - 4 - keyLen);
            rawKeyText = slice(data, audioLen, (int) keyLen);
        }
        if (audioLen <= 0) {
            throw new DecryptException("文件太短：没有音频数据");
        }

        byte[] key = deriveKey(rawKeyText);
        byte[] audio = slice(data, 0, audioLen);
        byte[] plain;
        if (key.length > 300) {
            plain = rc4(key, audio);
        } else {
            // 短密钥走映射表密码；44/58→128 掩码展开公式未核实，如实拒绝。
            throw new DecryptException("这个版本的加密暂不支持（短密钥映射表型）");
        }
        return new Result(plain, detectExt(plain));
    }

    /**
     * ekey 派生：入参是文件尾的 ekey 文本字节（Base64，可能带逗号后的附加字段）。
     * 解码后若以 "QQMusic EncV2,Key:" 开头走 V2 三段派生，否则走 V1 派生。
     */
    private static byte[] deriveKey(byte[] rawKeyText) throws DecryptException {
        String text = new String(rawKeyText, StandardCharsets.US_ASCII);
        int comma = text.indexOf(',');
        // 尾文本可能在逗号后带附加字段（如下载器写入的标记）；公开实现
        // （QMCDecode 的 searchKey）即按逗号截取密钥主体，标准 Base64 本身不含逗号。
        String b64 = (comma >= 0) ? text.substring(0, comma) : text;
        b64 = b64.trim();
        final byte[] decoded;
        try {
            decoded = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new DecryptException("密钥尾不对：Base64 解码失败");
        }
        if (decoded.length < 16) {
            throw new DecryptException("密钥尾不对：解码后密钥太短");
        }
        if (startsWithAscii(decoded, "QQMusic EncV2,Key:")) {
            return deriveKeyV2(decoded);
        }
        return deriveKeyV1(decoded);
    }

    /** EKey V1：simpleMakeKey(106) 与解码数据前 8 字节交错成 TEA 密钥，解密其余部分。 */
    private static byte[] deriveKeyV1(byte[] decoded) throws DecryptException {
        byte[] simpleKey = simpleMakeKey(106, 8);
        byte[] teaKey = new byte[16];
        for (int i = 0; i < 8; i++) {
            teaKey[2 * i] = simpleKey[i];
            teaKey[2 * i + 1] = decoded[i];
        }
        byte[] rest = slice(decoded, 8, decoded.length - 8);
        byte[] plain = tencentTeaDecrypt(rest, teaKey);
        byte[] key = new byte[8 + plain.length];
        System.arraycopy(decoded, 0, key, 0, 8);
        System.arraycopy(plain, 0, key, 8, plain.length);
        return key;
    }

    // EncV2 三段派生所用的公开常量（unlock-mflac 的 decrypt.cpp 公开文档）。
    private static final byte[] MIX_KEY_1 = "386ZJY!@#*$%^&)(".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MIX_KEY_2 = "**#!(#$%&^a1cZ,T".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HALF_MIX_KEY = {
            0x69, 0x56, 0x46, 0x38, 0x2B, 0x20, 0x15, 0x0B
    };

    /** EKey V2（EncV2）：两段固定 MixKey 的 TEA 解密 + 一段动态 MixKey 的 TEA 解密。 */
    private static byte[] deriveKeyV2(byte[] decoded) throws DecryptException {
        final int prefixLen = 18; // "QQMusic EncV2,Key:" 的长度
        byte[] stage1 = tencentTeaDecrypt(slice(decoded, prefixLen, decoded.length - prefixLen), MIX_KEY_1);
        byte[] stage2 = tencentTeaDecrypt(stage1, MIX_KEY_2);
        // stage2 是 ASCII 形式的 Base64，再解一层。
        String b64 = new String(stage2, StandardCharsets.US_ASCII).trim();
        final byte[] stage3;
        try {
            stage3 = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new DecryptException("密钥派生失败：EncV2 中层 Base64 解码失败");
        }
        if (stage3.length < 8 + 16) {
            throw new DecryptException("密钥派生失败：EncV2 内层密钥太短");
        }
        byte[] mixKey3 = new byte[16];
        for (int i = 0; i < 8; i++) {
            mixKey3[2 * i] = HALF_MIX_KEY[i];
            mixKey3[2 * i + 1] = stage3[i];
        }
        byte[] tail = tencentTeaDecrypt(slice(stage3, 8, stage3.length - 8), mixKey3);
        byte[] key = new byte[8 + tail.length];
        System.arraycopy(stage3, 0, key, 0, 8);
        System.arraycopy(tail, 0, key, 8, tail.length);
        return key;
    }

    /** 由 tan 函数生成的简单密钥：key[i] = (byte)(|tan(seed + i*0.1)| * 100)。 */
    private static byte[] simpleMakeKey(int seed, int size) {
        byte[] key = new byte[size];
        for (int i = 0; i < size; i++) {
            key[i] = (byte) (Math.abs(Math.tan(seed + i * 0.1)) * 100.0);
        }
        return key;
    }

    // ------------------------------------------------------------------
    // 腾讯 TEA（oi_symmetry 风格的 CBC + 自定义填充）
    // 明文布局：[1 字节: 低 3 位为填充长][填充][2 字节盐][原文][7 字节 0]
    // 链式规则：C_i = E(P_i XOR C_{i-1}) XOR P_{i-1}，P_{-1}、C_{-1} 均为全 0。
    // ------------------------------------------------------------------

    private static final int TEA_DELTA = 0x9E3779B9;
    private static final int TEA_SUM0 = 0xE3779B90; // delta * 16

    /** TEA 单块解密：8 字节大端进出，16 轮。 */
    private static void teaDecryptBlock(byte[] in, int inOff, byte[] key, byte[] out, int outOff) {
        int y = readBe32(in, inOff);
        int z = readBe32(in, inOff + 4);
        int k0 = readBe32(key, 0);
        int k1 = readBe32(key, 4);
        int k2 = readBe32(key, 8);
        int k3 = readBe32(key, 12);
        int sum = TEA_SUM0;
        for (int i = 0; i < 16; i++) {
            z -= ((y << 4) + k2) ^ (y + sum) ^ ((y >>> 5) + k3);
            y -= ((z << 4) + k0) ^ (z + sum) ^ ((z >>> 5) + k1);
            sum -= TEA_DELTA;
        }
        writeBe32(out, outOff, y);
        writeBe32(out, outOff + 4, z);
    }

    /** 腾讯 TEA-CBC 解密，校验填充与尾部 7 字节 0，返回原文。 */
    private static byte[] tencentTeaDecrypt(byte[] in, byte[] key) throws DecryptException {
        if (in == null || in.length < 16 || (in.length & 7) != 0) {
            throw new DecryptException("密钥派生失败：TEA 输入长度不对");
        }
        byte[] buf = new byte[in.length];
        byte[] prevCipher = new byte[8];
        byte[] prevPlain = new byte[8];
        byte[] block = new byte[8];
        for (int off = 0; off < in.length; off += 8) {
            byte[] curCipher = slice(in, off, 8);
            byte[] x = new byte[8];
            for (int i = 0; i < 8; i++) {
                x[i] = (byte) (curCipher[i] ^ prevPlain[i]);
            }
            teaDecryptBlock(x, 0, key, block, 0);
            for (int i = 0; i < 8; i++) {
                block[i] = (byte) (block[i] ^ prevCipher[i]);
            }
            System.arraycopy(block, 0, buf, off, 8);
            prevCipher = curCipher;
            prevPlain = block.clone();
        }
        int padLen = buf[0] & 0x07;
        // 结构：1(长度字节) + padLen(填充) + 2(盐) + 原文 + 7(零校验)
        int outLen = in.length - padLen - 10;
        if (outLen < 0) {
            throw new DecryptException("密钥派生失败：TEA 填充长度异常");
        }
        for (int i = in.length - 7; i < in.length; i++) {
            if (buf[i] != 0) {
                throw new DecryptException("密钥派生失败：TEA 尾部校验不过（密钥可能不对）");
            }
        }
        return slice(buf, 1 + padLen + 2, outLen);
    }

    // ------------------------------------------------------------------
    // 标准 RC4（KSA + PRGA，不丢弃）
    // ------------------------------------------------------------------

    private static byte[] rc4(byte[] key, byte[] data) {
        byte[] s = new byte[256];
        for (int i = 0; i < 256; i++) {
            s[i] = (byte) i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + (s[i] & 0xFF) + (key[i % key.length] & 0xFF)) & 0xFF;
            byte t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[data.length];
        int a = 0;
        int b = 0;
        for (int n = 0; n < data.length; n++) {
            a = (a + 1) & 0xFF;
            b = (b + (s[a] & 0xFF)) & 0xFF;
            byte t = s[a];
            s[a] = s[b];
            s[b] = t;
            out[n] = (byte) (data[n] ^ s[((s[a] & 0xFF) + (s[b] & 0xFF)) & 0xFF]);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 输出格式判定与小工具
    // ------------------------------------------------------------------

    /** 按解密后文件头魔数判定扩展名。 */
    private static String detectExt(byte[] d) throws DecryptException {
        if (d.length >= 4 && d[0] == 'f' && d[1] == 'L' && d[2] == 'a' && d[3] == 'C') {
            return "flac";
        }
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') {
            return "ogg";
        }
        if (d.length >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            return "m4a";
        }
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') {
            return "mp3";
        }
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) {
            return "mp3";
        }
        throw new DecryptException("解密后不是认识的音频格式（文件头魔数不匹配）");
    }

    private static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean tailEquals(byte[] data, String ascii) {
        byte[] t = ascii.getBytes(StandardCharsets.US_ASCII);
        if (data.length < t.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            if (data[data.length - t.length + i] != t[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] data, String ascii) {
        byte[] t = ascii.getBytes(StandardCharsets.US_ASCII);
        if (data.length < t.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            if (data[i] != t[i]) {
                return false;
            }
        }
        return true;
    }

    private static long readLe32u(byte[] d, int off) {
        return ((long) d[off] & 0xFF)
                | (((long) d[off + 1] & 0xFF) << 8)
                | (((long) d[off + 2] & 0xFF) << 16)
                | (((long) d[off + 3] & 0xFF) << 24);
    }

    private static int readBe32(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24)
                | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8)
                | (d[off + 3] & 0xFF);
    }

    private static void writeBe32(byte[] d, int off, int v) {
        d[off] = (byte) (v >>> 24);
        d[off + 1] = (byte) (v >>> 16);
        d[off + 2] = (byte) (v >>> 8);
        d[off + 3] = (byte) v;
    }

    private static byte[] slice(byte[] d, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(d, off, out, 0, len);
        return out;
    }
}
