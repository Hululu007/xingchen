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
 *   <li>QMC 旧格式（.qmc0/.qmc3/.qmcflac）：整文件与 128 字节静态掩码异或。
 *       掩码由 56 字节种子矩阵按 [SuperA(0xC3) + 左行 7 字节 + SuperB(0xD8) +
 *       镜像行 7 字节] × 8 行展开，取掩码时按 unlock-music 的 Decrypt 规则在
 *       0x8000 边界跳一位。种子字节双源核对一致：unlock-music 原版源码
 *       qmcMask.js（从已发布构建的 sourcemap 提取，第 27–36 行）与
 *       presburger/qmc-decoder 的 src/seed.hpp（第 20–27 行）逐字节相同，
 *       展开与跳位规则两边也一致。</li>
 *   <li>酷狗 KGM/KGMA（.kgm/.kgma，含 VPR 头的 .vpr）：16 字节魔数 + 0x10 处
 *       小端头部长度 + 0x1C 处 16 字节文件密钥（补 0 成 17 字节）；每字节
 *       out = G(key[i%17] ⊕ data) ⊕ G(PreDef[i%272] ⊕ 动态掩码[i>>4])，
 *       其中 G(v)=v⊕((v&0xF)&lt;&lt;4)；动态掩码由 table1/table2 逐层生成；
 *       VPR 魔数再多异或 VprMaskDiff[i%17]。三张 272 字节表为三源互证：
 *       MaskV2PreDef 在 unlock-music 的 Node 移植（jiangyalin/ncm-or-kgma-to-flac
 *       的 decrypt/kgm.js 第 93–110 行）与 0x77fe/AudioDecrypt 的 KGM.h
 *       （第 53–70 行）逐字节相同；table1/table2 取自 KGM.h（第 15–32、34–51 行），
 *       其生成动态掩码又与 zqy857/music-unlock 自带真实口令文件 kugou_key.xz
 *       解压后的 73,155,904 字节逐字节相同（0 差异）。</li>
 * </ul>
 *
 * <h3>未实现（如实声明，不假装支持）</h3>
 * <ul>
 *   <li>QMC2 短密钥（派生密钥 ≤ 300 字节）的映射表（Map）密码：掩码是按文件
 *       密钥现场生成的那一张，生成公式未能核实，暂不支持。注意这与 QMC 旧
 *       格式的固定默认掩码不是一回事。</li>
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
     * 按文件名判断是否为本类能真正解密的格式：
     * QMC2 家族、QMC 旧格式、酷狗 KGM 家族返回 true（见类注释）。
     */
    public static boolean isSupportedExt(String fileName) {
        String ext = extOf(fileName);
        switch (ext) {
            case "mflac":
            case "mflac0":
            case "mgg":
            case "mgg1":
            case "qmc0":
            case "qmc3":
            case "qmcflac":
            case "kgm":
            case "kgma":
            case "vpr":
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
                return decryptQmcOld(data);
            case "kgm":
            case "kgma":
            case "vpr":
                return decryptKgm(data);
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
    // ------------------------------------------------------------------
    // QMC 旧格式：.qmc0 / .qmc3 / .qmcflac
    // 整文件与 128 字节静态掩码异或；掩码由 56 字节种子按
    // [SuperA + 左 7 + SuperB + 右 7（镜像）] × 8 行展开；
    // 取用时按 unlock-music 的 Decrypt 规则在 0x8000 边界跳一位。
    // ------------------------------------------------------------------

    private static final byte[] QMC_OLD_MATRIX_58 = {
            0x4A, (byte) 0xD6, (byte) 0xCA, (byte) 0x90, 0x67, (byte) 0xF7, 0x52, 0x5E,
            (byte) 0x95, 0x23, (byte) 0x9F, 0x13, 0x11, 0x7E, 0x47, 0x74,
            0x3D, (byte) 0x90, (byte) 0xAA, 0x3F, 0x51, (byte) 0xC6, 0x09, (byte) 0xD5,
            (byte) 0x9F, (byte) 0xFA, 0x66, (byte) 0xF9, (byte) 0xF3, (byte) 0xD6, (byte) 0xA1, (byte) 0x90,
            (byte) 0xA0, (byte) 0xF7, (byte) 0xF0, 0x1D, (byte) 0x95, (byte) 0xDE, (byte) 0x9F, (byte) 0x84,
            0x11, (byte) 0xF4, 0x0E, 0x74, (byte) 0xBB, (byte) 0x90, (byte) 0xBC, 0x3F,
            (byte) 0x92, 0x00, 0x09, 0x5B, (byte) 0x9F, 0x62, 0x66, (byte) 0xA1
    };

    private static final byte[] QMC_OLD_MASK_128 = buildQmcOldMask128();

    private static byte[] buildQmcOldMask128() {
        byte[] m128 = new byte[128];
        for (int row = 0; row < 8; row++) {
            int base = row * 16;
            m128[base] = (byte) 0xC3; // SuperA
            System.arraycopy(QMC_OLD_MATRIX_58, 7 * row, m128, base + 1, 7);
            m128[base + 8] = (byte) 0xD8; // SuperB
            for (int i = 0; i < 7; i++) {
                m128[base + 9 + i] = QMC_OLD_MATRIX_58[55 - 7 * row - i];
            }
        }
        return m128;
    }

    private static Result decryptQmcOld(byte[] data) throws DecryptException {
        byte[] out = new byte[data.length];
        int index = -1, maskIdx = -1;
        for (int p = 0; p < data.length; p++) {
            index++;
            maskIdx++;
            if (index == 0x8000 || (index > 0x8000 && (index + 1) % 0x8000 == 0)) {
                index++;
                maskIdx++;
            }
            if (maskIdx >= 128) {
                maskIdx -= 128;
            }
            out[p] = (byte) (data[p] ^ QMC_OLD_MASK_128[maskIdx]);
        }
        return new Result(out, detectExt(out));
    }

    // ------------------------------------------------------------------
    // 酷狗 KGM / KGMA / VPR（.kgm / .kgma / .vpr）
    // 头部：16 字节魔数（KGM / VPR）+ 0x10 处小端头部长度（常见 1024）
    // + 0x1C 处 16 字节文件密钥（补 1 个 0 成 17 字节循环）。
    // out[i] = G(key[i%17] ^ data) ^ G(PreDef[i%272] ^ dyn(i>>4))，
    // G(v) = v ^ ((v & 0xF) << 4)；VPR 魔数再异或 VprMaskDiff[i%17]。
    // 动态掩码由 table1/table2 逐层生成（见 kgmDynMask）。
    // ------------------------------------------------------------------

    private static final byte[] KGM_MAGIC = {
            0x7C, (byte) 0xD5, 0x32, (byte) 0xEB, (byte) 0x86, 0x02, 0x7F, 0x4B,
            (byte) 0xA8, (byte) 0xAF, (byte) 0xA6, (byte) 0x8E, 0x0F, (byte) 0xFF, (byte) 0x99, 0x14
    };

    private static final byte[] KGM_VPR_MAGIC = {
            0x05, 0x28, (byte) 0xBC, (byte) 0x96, (byte) 0xE9, (byte) 0xE4, 0x5A, 0x43,
            (byte) 0x91, (byte) 0xAA, (byte) 0xBD, (byte) 0xD0, 0x7A, (byte) 0xF5, 0x36, 0x31
    };

    private static final byte[] KGM_MASK_PRE_DEF = {
            (byte) 0xB8, (byte) 0xD5, 0x3D, (byte) 0xB2, (byte) 0xE9, (byte) 0xAF, 0x78, (byte) 0x8C,
            (byte) 0x83, 0x33, 0x71, 0x51, 0x76, (byte) 0xA0, (byte) 0xCD, 0x37,
            0x2F, 0x3E, 0x35, (byte) 0x8D, (byte) 0xA9, (byte) 0xBE, (byte) 0x98, (byte) 0xB7,
            (byte) 0xE7, (byte) 0x8C, 0x22, (byte) 0xCE, 0x5A, 0x61, (byte) 0xDF, 0x68,
            0x69, (byte) 0x89, (byte) 0xFE, (byte) 0xA5, (byte) 0xB6, (byte) 0xDE, (byte) 0xA9, 0x77,
            (byte) 0xFC, (byte) 0xC8, (byte) 0xBD, (byte) 0xBD, (byte) 0xE5, 0x6D, 0x3E, 0x5A,
            0x36, (byte) 0xEF, 0x69, 0x4E, (byte) 0xBE, (byte) 0xE1, (byte) 0xE9, 0x66,
            0x1C, (byte) 0xF3, (byte) 0xD9, 0x02, (byte) 0xB6, (byte) 0xF2, 0x12, (byte) 0x9B,
            0x44, (byte) 0xD0, 0x6F, (byte) 0xB9, 0x35, (byte) 0x89, (byte) 0xB6, 0x46,
            0x6D, 0x73, (byte) 0x82, 0x06, 0x69, (byte) 0xC1, (byte) 0xED, (byte) 0xD7,
            (byte) 0x85, (byte) 0xC2, 0x30, (byte) 0xDF, (byte) 0xA2, 0x62, (byte) 0xBE, 0x79,
            0x2D, 0x62, 0x62, 0x3D, 0x0D, 0x7E, (byte) 0xBE, 0x48,
            (byte) 0x89, 0x23, 0x02, (byte) 0xA0, (byte) 0xE4, (byte) 0xD5, 0x75, 0x51,
            0x32, 0x02, 0x53, (byte) 0xFD, 0x16, 0x3A, 0x21, 0x3B,
            0x16, 0x0F, (byte) 0xC3, (byte) 0xB2, (byte) 0xBB, (byte) 0xB3, (byte) 0xE2, (byte) 0xBA,
            0x3A, 0x3D, 0x13, (byte) 0xEC, (byte) 0xF6, 0x01, 0x45, (byte) 0x84,
            (byte) 0xA5, 0x70, 0x0F, (byte) 0x93, 0x49, 0x0C, 0x64, (byte) 0xCD,
            0x31, (byte) 0xD5, (byte) 0xCC, 0x4C, 0x07, 0x01, (byte) 0x9E, 0x00,
            0x1A, 0x23, (byte) 0x90, (byte) 0xBF, (byte) 0x88, 0x1E, 0x3B, (byte) 0xAB,
            (byte) 0xA6, 0x3E, (byte) 0xC4, 0x73, 0x47, 0x10, 0x7E, 0x3B,
            0x5E, (byte) 0xBC, (byte) 0xE3, 0x00, (byte) 0x84, (byte) 0xFF, 0x09, (byte) 0xD4,
            (byte) 0xE0, (byte) 0x89, 0x0F, 0x5B, 0x58, 0x70, 0x4F, (byte) 0xFB,
            0x65, (byte) 0xD8, 0x5C, 0x53, 0x1B, (byte) 0xD3, (byte) 0xC8, (byte) 0xC6,
            (byte) 0xBF, (byte) 0xEF, (byte) 0x98, (byte) 0xB0, 0x50, 0x4F, 0x0F, (byte) 0xEA,
            (byte) 0xE5, (byte) 0x83, 0x58, (byte) 0x8C, 0x28, 0x2C, (byte) 0x84, 0x67,
            (byte) 0xCD, (byte) 0xD0, (byte) 0x9E, 0x47, (byte) 0xDB, 0x27, 0x50, (byte) 0xCA,
            (byte) 0xF4, 0x63, 0x63, (byte) 0xE8, (byte) 0x97, 0x7F, 0x1B, 0x4B,
            0x0C, (byte) 0xC2, (byte) 0xC1, 0x21, 0x4C, (byte) 0xCC, 0x58, (byte) 0xF5,
            (byte) 0x94, 0x52, (byte) 0xA3, (byte) 0xF3, (byte) 0xD3, (byte) 0xE0, 0x68, (byte) 0xF4,
            0x00, 0x23, (byte) 0xF3, 0x5E, 0x0A, 0x7B, (byte) 0x93, (byte) 0xDD,
            (byte) 0xAB, 0x12, (byte) 0xB2, 0x13, (byte) 0xE8, (byte) 0x84, (byte) 0xD7, (byte) 0xA7,
            (byte) 0x9F, 0x0F, 0x32, 0x4C, 0x55, 0x1D, 0x04, 0x36,
            0x52, (byte) 0xDC, 0x03, (byte) 0xF3, (byte) 0xF9, 0x4E, 0x42, (byte) 0xE9,
            0x3D, 0x61, (byte) 0xEF, 0x7C, (byte) 0xB6, (byte) 0xB3, (byte) 0x93, 0x50
    };

    private static final byte[] KGM_TABLE_1 = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x21, 0x01, 0x61, 0x01, 0x21, 0x01,
            (byte) 0xE1, 0x01, 0x21, 0x01, 0x61, 0x01, 0x21, 0x01,
            (byte) 0xD2, 0x23, 0x02, 0x02, 0x42, 0x42, 0x02, 0x02,
            (byte) 0xC2, (byte) 0xC2, 0x02, 0x02, 0x42, 0x42, 0x02, 0x02,
            (byte) 0xD3, (byte) 0xD3, 0x02, 0x03, 0x63, 0x43, 0x63, 0x03,
            (byte) 0xE3, (byte) 0xC3, (byte) 0xE3, 0x03, 0x63, 0x43, 0x63, 0x03,
            (byte) 0x94, (byte) 0xB4, (byte) 0x94, 0x65, 0x04, 0x04, 0x04, 0x04,
            (byte) 0x84, (byte) 0x84, (byte) 0x84, (byte) 0x84, 0x04, 0x04, 0x04, 0x04,
            (byte) 0x95, (byte) 0x95, (byte) 0x95, (byte) 0x95, 0x04, 0x05, 0x25, 0x05,
            (byte) 0xE5, (byte) 0x85, (byte) 0xA5, (byte) 0x85, (byte) 0xE5, 0x05, 0x25, 0x05,
            (byte) 0xD6, (byte) 0xB6, (byte) 0x96, (byte) 0xB6, (byte) 0xD6, 0x27, 0x06, 0x06,
            (byte) 0xC6, (byte) 0xC6, (byte) 0x86, (byte) 0x86, (byte) 0xC6, (byte) 0xC6, 0x06, 0x06,
            (byte) 0xD7, (byte) 0xD7, (byte) 0x97, (byte) 0x97, (byte) 0xD7, (byte) 0xD7, 0x06, 0x07,
            (byte) 0xE7, (byte) 0xC7, (byte) 0xE7, (byte) 0x87, (byte) 0xE7, (byte) 0xC7, (byte) 0xE7, 0x07,
            0x18, 0x38, 0x18, 0x78, 0x18, 0x38, 0x18, (byte) 0xE9,
            0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08,
            0x19, 0x19, 0x19, 0x19, 0x19, 0x19, 0x19, 0x19,
            0x08, 0x09, 0x29, 0x09, 0x69, 0x09, 0x29, 0x09,
            (byte) 0xDA, 0x3A, 0x1A, 0x3A, 0x5A, 0x3A, 0x1A, 0x3A,
            (byte) 0xDA, 0x2B, 0x0A, 0x0A, 0x4A, 0x4A, 0x0A, 0x0A,
            (byte) 0xDB, (byte) 0xDB, 0x1B, 0x1B, 0x5B, 0x5B, 0x1B, 0x1B,
            (byte) 0xDB, (byte) 0xDB, 0x0A, 0x0B, 0x6B, 0x4B, 0x6B, 0x0B,
            (byte) 0x9C, (byte) 0xBC, (byte) 0x9C, 0x7C, 0x1C, 0x3C, 0x1C, 0x7C,
            (byte) 0x9C, (byte) 0xBC, (byte) 0x9C, 0x6D, 0x0C, 0x0C, 0x0C, 0x0C,
            (byte) 0x9D, (byte) 0x9D, (byte) 0x9D, (byte) 0x9D, 0x1D, 0x1D, 0x1D, 0x1D,
            (byte) 0x9D, (byte) 0x9D, (byte) 0x9D, (byte) 0x9D, 0x0C, 0x0D, 0x2D, 0x0D,
            (byte) 0xDE, (byte) 0xBE, (byte) 0x9E, (byte) 0xBE, (byte) 0xDE, 0x3E, 0x1E, 0x3E,
            (byte) 0xDE, (byte) 0xBE, (byte) 0x9E, (byte) 0xBE, (byte) 0xDE, 0x2F, 0x0E, 0x0E,
            (byte) 0xDF, (byte) 0xDF, (byte) 0x9F, (byte) 0x9F, (byte) 0xDF, (byte) 0xDF, 0x1F, 0x1F,
            (byte) 0xDF, (byte) 0xDF, (byte) 0x9F, (byte) 0x9F, (byte) 0xDF, (byte) 0xDF, 0x0E, 0x0F,
            0x00, 0x20, 0x00, 0x60, 0x00, 0x20, 0x00, (byte) 0xE0,
            0x00, 0x20, 0x00, 0x60, 0x00, 0x20, 0x00, (byte) 0xF1
    };

    private static final byte[] KGM_TABLE_2 = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x23, 0x01, 0x67, 0x01, 0x23, 0x01,
            (byte) 0xEF, 0x01, 0x23, 0x01, 0x67, 0x01, 0x23, 0x01,
            (byte) 0xDF, 0x21, 0x02, 0x02, 0x46, 0x46, 0x02, 0x02,
            (byte) 0xCE, (byte) 0xCE, 0x02, 0x02, 0x46, 0x46, 0x02, 0x02,
            (byte) 0xDE, (byte) 0xDE, 0x02, 0x03, 0x65, 0x47, 0x65, 0x03,
            (byte) 0xED, (byte) 0xCF, (byte) 0xED, 0x03, 0x65, 0x47, 0x65, 0x03,
            (byte) 0x9D, (byte) 0xBF, (byte) 0x9D, 0x63, 0x04, 0x04, 0x04, 0x04,
            (byte) 0x8C, (byte) 0x8C, (byte) 0x8C, (byte) 0x8C, 0x04, 0x04, 0x04, 0x04,
            (byte) 0x9C, (byte) 0x9C, (byte) 0x9C, (byte) 0x9C, 0x04, 0x05, 0x27, 0x05,
            (byte) 0xEB, (byte) 0x8D, (byte) 0xAF, (byte) 0x8D, (byte) 0xEB, 0x05, 0x27, 0x05,
            (byte) 0xDB, (byte) 0xBD, (byte) 0x9F, (byte) 0xBD, (byte) 0xDB, 0x25, 0x06, 0x06,
            (byte) 0xCA, (byte) 0xCA, (byte) 0x8E, (byte) 0x8E, (byte) 0xCA, (byte) 0xCA, 0x06, 0x06,
            (byte) 0xDA, (byte) 0xDA, (byte) 0x9E, (byte) 0x9E, (byte) 0xDA, (byte) 0xDA, 0x06, 0x07,
            (byte) 0xE9, (byte) 0xCB, (byte) 0xE9, (byte) 0x8F, (byte) 0xE9, (byte) 0xCB, (byte) 0xE9, 0x07,
            0x19, 0x3B, 0x19, 0x7F, 0x19, 0x3B, 0x19, (byte) 0xE7,
            0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08,
            0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18,
            0x08, 0x09, 0x2B, 0x09, 0x6F, 0x09, 0x2B, 0x09,
            (byte) 0xD7, 0x39, 0x1B, 0x39, 0x5F, 0x39, 0x1B, 0x39,
            (byte) 0xD7, 0x29, 0x0A, 0x0A, 0x4E, 0x4E, 0x0A, 0x0A,
            (byte) 0xD6, (byte) 0xD6, 0x1A, 0x1A, 0x5E, 0x5E, 0x1A, 0x1A,
            (byte) 0xD6, (byte) 0xD6, 0x0A, 0x0B, 0x6D, 0x4F, 0x6D, 0x0B,
            (byte) 0x95, (byte) 0xB7, (byte) 0x95, 0x7B, 0x1D, 0x3F, 0x1D, 0x7B,
            (byte) 0x95, (byte) 0xB7, (byte) 0x95, 0x6B, 0x0C, 0x0C, 0x0C, 0x0C,
            (byte) 0x94, (byte) 0x94, (byte) 0x94, (byte) 0x94, 0x1C, 0x1C, 0x1C, 0x1C,
            (byte) 0x94, (byte) 0x94, (byte) 0x94, (byte) 0x94, 0x0C, 0x0D, 0x2F, 0x0D,
            (byte) 0xD3, (byte) 0xB5, (byte) 0x97, (byte) 0xB5, (byte) 0xD3, 0x3D, 0x1F, 0x3D,
            (byte) 0xD3, (byte) 0xB5, (byte) 0x97, (byte) 0xB5, (byte) 0xD3, 0x2D, 0x0E, 0x0E,
            (byte) 0xD2, (byte) 0xD2, (byte) 0x96, (byte) 0x96, (byte) 0xD2, (byte) 0xD2, 0x1E, 0x1E,
            (byte) 0xD2, (byte) 0xD2, (byte) 0x96, (byte) 0x96, (byte) 0xD2, (byte) 0xD2, 0x0E, 0x0F,
            0x00, 0x22, 0x00, 0x66, 0x00, 0x22, 0x00, (byte) 0xEE,
            0x00, 0x22, 0x00, 0x66, 0x00, 0x22, 0x00, (byte) 0xFE
    };

    private static final byte[] KGM_VPR_DIFF = {
            0x25, (byte) 0xDF, (byte) 0xE8, (byte) 0xA6, 0x75, 0x1E, 0x75, 0x0E, 0x2F, (byte) 0x80, (byte) 0xF3, 0x2D, (byte) 0xB8, (byte) 0xB6, (byte) 0xE3, 0x11, 0x00
    };

    /** 按 0x77fe/AudioDecrypt 的 KGM.cpp 解密循环逐值生成动态掩码。 */
    private static int kgmDynMask(int n) {
        int msk = 0;
        int offset = n;
        while (offset >= 0x11) {
            msk ^= (KGM_TABLE_1[offset % 272] & (byte) 0xFF);
            offset >>= 4;
            msk ^= (KGM_TABLE_2[offset % 272] & (byte) 0xFF);
            offset >>= 4;
        }
        return msk;
    }

    private static Result decryptKgm(byte[] data) throws DecryptException {
        if (data.length < 0x2C) {
            throw new DecryptException("文件太短：不像酷狗加密文件");
        }
        boolean vpr = startsWithBytes(data, KGM_VPR_MAGIC);
        if (!vpr && !startsWithBytes(data, KGM_MAGIC)) {
            throw new DecryptException("文件头不对：不是酷狗 KGM/KGMA 文件");
        }
        long headerLen = readLe32u(data, 0x10);
        if (headerLen < 0x2C || headerLen >= data.length) {
            throw new DecryptException("文件头不对：头部长度异常");
        }
        byte[] key = new byte[17]; // 最后一个字节为 0
        System.arraycopy(data, 0x1C, key, 0, 16);
        int audioOff = (int) headerLen;
        int audioLen = data.length - audioOff;
        int blocks = (audioLen + 15) / 16;
        byte[] dyn = new byte[blocks];
        for (int n = 0; n < blocks; n++) {
            dyn[n] = (byte) kgmDynMask(n);
        }
        byte[] out = new byte[audioLen];
        for (int i = 0; i < audioLen; i++) {
            int med = (key[i % 17] ^ data[audioOff + i]) & (byte) 0xFF;
            med ^= (med & 0xF) << 4;
            int msk = ((KGM_MASK_PRE_DEF[i % 272] & (byte) 0xFF) ^ (dyn[i >> 4] & (byte) 0xFF)) & (byte) 0xFF;
            msk ^= (msk & 0xF) << 4;
            int b = med ^ msk;
            if (vpr) {
                b ^= (KGM_VPR_DIFF[i % 17] & (byte) 0xFF);
            }
            out[i] = (byte) b;
        }
        return new Result(out, detectExt(out));
    }

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

    private static boolean startsWithBytes(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
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
