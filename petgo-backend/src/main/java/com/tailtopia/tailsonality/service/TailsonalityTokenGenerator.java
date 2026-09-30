package com.tailtopia.tailsonality.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 结果行不可枚举对外标识（32 位 base62 + {@link SecureRandom}，形态同 {@code place.service.PlaceTokenGenerator}）。
 *
 * <p>类名刻意不同，避免与 {@code PlaceTokenGenerator} 撞 bean 名；不跨模块复用，理由同该类 javadoc。
 */
@Component
public class TailsonalityTokenGenerator {

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
