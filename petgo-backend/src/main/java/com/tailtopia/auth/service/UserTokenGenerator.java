package com.tailtopia.auth.service;

import java.security.SecureRandom;

/**
 * 用户对外不可枚举标识生成器（V1.3.4 follow · Story 1.1 · D-2 / AD-2）。
 *
 * <p>形态照 {@code place.service.PlaceTokenGenerator}：<b>BASE62 × 32</b> + {@link SecureRandom}。
 * 与之不同的是本类只提供<b>静态</b> {@link #generate()} —— 调用点是 JPA 回调
 * {@code User.onCreate()}（{@code @PrePersist}），实体里拿不到 Spring Bean；
 * 放在 {@code @PrePersist} 能让全部建号工厂（Google / Apple / ADMIN / 虚拟号）与测试夹具一处覆盖。
 *
 * <p>为何不复用 {@code PlaceTokenGenerator}：跨模块 import 会让 {@code auth} 依赖 {@code place}
 * （同 {@code ShopTokenGenerator} 的既定取舍）。
 */
public final class UserTokenGenerator {

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /** token 长度（与 {@code users.public_token VARCHAR(32)} 一致）。 */
    public static final int LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private UserTokenGenerator() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
