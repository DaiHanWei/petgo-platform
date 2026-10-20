package com.tailtopia.gift.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 送礼订单不可枚举对外标识（32 位 base62 + SecureRandom，形态同 {@code KeepsakeTokenGenerator}）。 */
@Component
public class GiftTokenGenerator {

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
