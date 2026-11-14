package com.tailtopia.starterpack.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 礼包购买的不可枚举对外标识（V1.5.0 gift-pack Story 4-2）：32 位 base62 + SecureRandom，
 * 形态照 {@code purchase.service.KeepsakeTokenGenerator}（不跨包复用）。
 */
@Component
public class StarterPackTokenGenerator {

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 32;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
