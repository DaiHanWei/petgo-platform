package com.tailtopia.passport.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 登机牌 SEAT（V1.3.2 Story 3.5 · AC4.3 · D-5）：<b>纯装饰</b>、确定性、不存储、无业务含义。
 *
 * <p>{@code SHA-256("seat:" + petId + ":" + placeId)} 取前 4 字节无符号整数 n：行号 {@code n % 40 + 1}（两位补零）
 * + 座位字母 {@code "ABCDEF".charAt((n / 40) % 6)}，如 {@code 02A}。
 */
public final class BoardingPassSeat {

    private static final String LETTERS = "ABCDEF";

    private BoardingPassSeat() {
    }

    public static String of(long petProfileId, long placeId) {
        byte[] h;
        try {
            h = MessageDigest.getInstance("SHA-256")
                    .digest(("seat:" + petProfileId + ":" + placeId).getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        long n = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
        return String.format("%02d%c", n % 40 + 1, LETTERS.charAt((int) ((n / 40) % 6)));
    }
}
