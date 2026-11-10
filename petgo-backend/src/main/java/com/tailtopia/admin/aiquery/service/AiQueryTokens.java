package com.tailtopia.admin.aiquery.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** 不可枚举标识（NFR5）：24 字节随机数 → 32 位 base64url。与 agent 的 token 格式 {@code [A-Za-z0-9_-]{8,128}} 兼容。 */
public final class AiQueryTokens {

    private static final SecureRandom RNG = new SecureRandom();
    public static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private AiQueryTokens() {
    }

    public static String newToken() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static boolean isValid(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }
}
