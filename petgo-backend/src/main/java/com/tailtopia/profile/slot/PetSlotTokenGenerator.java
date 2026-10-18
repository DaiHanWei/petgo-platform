package com.tailtopia.profile.slot;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 名额 / 购买行的不可枚举对外标识（V1.4.0 multi-pet Story 2.2）：32 位 base62 + SecureRandom，
 * 形态照 {@code purchase.service.KeepsakeTokenGenerator}（不跨包复用：purchase 包不得依赖 profile，反之亦不引其内部件）。
 */
@Component
public class PetSlotTokenGenerator {

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
