package com.tailtopia.passport.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 护照快照不可枚举对外标识（32 位 base62 + {@link SecureRandom}，形态同 {@code PlaceTokenGenerator}）。
 * 类名刻意不同，避免撞 bean 名。
 */
@Component
public class PassportSnapshotTokenGenerator {

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
