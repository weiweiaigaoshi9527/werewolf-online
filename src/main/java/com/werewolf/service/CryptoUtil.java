package com.werewolf.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * api_key 的 AES-GCM 加密存储。
 * 密钥来源优先级：环境变量 WW_AES_KEY → 系统属性 ww.aes.key → 项目目录 config/aes.key（首次自动生成并持久化）。
 * 不再使用任何硬编码默认密钥。
 */
public final class CryptoUtil {

    private static final String ALG = "AES/GCM/NoPadding";
    private static final Path KEY_FILE = Paths.get("config", "aes.key");
    private static final byte[] KEY;
    static {
        try {
            String secret = System.getenv("WW_AES_KEY");
            if (secret == null || secret.isBlank()) {
                // 其次读系统属性
                secret = System.getProperty("ww.aes.key");
            }
            if (secret == null || secret.isBlank()) {
                // 最后从项目目录的密钥文件读取；不存在则生成随机密钥并持久化，保证重启后可解密
                secret = loadOrCreateKeyFile();
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            KEY = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("初始化 AES 密钥失败", e);
        }
    }

    /** 读取 config/aes.key；不存在时生成一个随机的 Base64 密钥写入该文件（首次生成，后续复用）。 */
    private static synchronized String loadOrCreateKeyFile() throws Exception {
        if (Files.exists(KEY_FILE)) {
            String existing = new String(Files.readAllBytes(KEY_FILE), StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) return existing;
        }
        // 生成 32 字节随机密钥并 Base64 编码
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String generated = Base64.getEncoder().encodeToString(raw);
        Files.createDirectories(KEY_FILE.getParent());
        Files.write(KEY_FILE, generated.getBytes(StandardCharsets.UTF_8));
        return generated;
    }

    private CryptoUtil() {}

    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    public static String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) return enc;
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            byte[] ct = new byte[all.length - 12];
            System.arraycopy(all, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败不再静默返回 null，打印告警日志便于排查（例如密钥变更导致的历史数据无法解密）
            System.err.println("[CryptoUtil] 解密失败：" + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }
}
