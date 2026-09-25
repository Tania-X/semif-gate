package com.semif.gate.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 哈希工具。
 *
 * <p>整个网关只用这一种哈希：缓存键、state 指纹、prompt 哈希、选项集哈希、注册表哈希。
 * 统一算法让「比对两个哈希」这件事不需要关心上下文。
 */
public final class StateHasher {

    private StateHasher() {
    }

    /**
     * 计算文本的 SHA-256 十六进制摘要（小写）。
     *
     * @param text 待哈希文本，按 UTF-8 编码
     * @return 64 位十六进制字符串
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算字节的 SHA-256 十六进制摘要（小写）。
     *
     * @param bytes 待哈希字节
     * @return 64 位十六进制字符串
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然提供 SHA-256；走到这里说明运行环境损坏
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }
}
