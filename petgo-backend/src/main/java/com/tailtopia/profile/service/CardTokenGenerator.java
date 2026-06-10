package com.tailtopia.profile.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 不可枚举对外名片 token 生成（Story 2.2 · B3）。
 *
 * <p>{@link SecureRandom} 取 ≥128bit 熵，base62 编码。**绝不由顺序 id 派生**。供 2.6 {@code /p/{cardToken}} 使用。
 */
@Component
public class CardTokenGenerator {

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    /** 22 个 base62 字符 ≈ 131 bit 熵（> 128bit）。 */
    private static final int LENGTH = 22;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
