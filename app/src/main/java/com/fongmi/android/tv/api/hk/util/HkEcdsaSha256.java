package com.fongmi.android.tv.api.hk.util;


import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

/**
 * 移植自 8.83 {@code com.example.hikerview.service.parser.EcdsaSha256Kt}（smali 203 行）。
 * <p>
 * 8.83 原文（Kotlin）：在 AndroidKeyStore 生成 EC 密钥对（alias MY_ECDSA_KEY_PAIR，
 * purpose=SIGN，digests=SHA-256/SHA-512，keySize=256），用 SHA256withECDSA 签名，
 * 取签名 hex 的后 (length-128) 段，拼接 "01##04" + 公钥 hex。
 */
public final class HkEcdsaSha256 {

    private static final String KEYPAIR_ALIAS = "MY_ECDSA_KEY_PAIR";

    private HkEcdsaSha256() {
    }

    /**
     * 8.83 EcdsaSha256Kt.sign(message) 原样移植。
     *
     * @param message 待签名文本（UTF-8）
     * @return 签名 hex 后段 + "01##04" + 公钥 hex；异常时返回空串（8.83 未 catch，壳侧调用方自行处理）
     */
    public static String sign(String message) {
        try {
            // 简化：不用 AndroidKeyStore，直接用内存密钥对（8.83 语义：签名+公钥拼接）
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256);
            KeyPair keyPair = kpg.generateKeyPair();

            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(message.getBytes(StandardCharsets.UTF_8));

            byte[] signed = signature.sign();
            String hex = bytesToHexString(signed);
            // 8.83：取 hex.substring(hex.length() - 128)
            String tail = hex.substring(hex.length() - 128);
            String pubHex = bytesToHexString(keyPair.getPublic().getEncoded());
            return tail + "01##04" + pubHex;
        } catch (Exception e) {
            return "";
        }
    }

    private static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
}
