package com.tailtopia.passport.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * passport 包的不可枚举对外标识（护照快照 Story 3.4 / 登机牌解锁行 Story 3.5）：32 位 base62 + {@link SecureRandom}，
 * 形态同 {@code PlaceTokenGenerator}。类名刻意不同，避免撞 bean 名。
 */
@Component
public class PassportTokenGenerator {

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
