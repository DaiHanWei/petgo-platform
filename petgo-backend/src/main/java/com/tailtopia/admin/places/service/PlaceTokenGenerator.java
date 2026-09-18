package com.tailtopia.admin.places.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 场所对外标识生成器（V1.3.0 Story 5.1，AD-5）。{@code SecureRandom} + Base62 22 位，复制 {@code shop.service.ShopTokenGenerator}。
 *
 * <p>🔴 <b>CLAUDE.md 强制护栏：对外暴露标识一律不可枚举 token，不用自增 id 直接外露。</b>
 * 🔴 <b>字符表与长度须与 {@code profile.service.CardTokenGenerator} / {@code ShopTokenGenerator} 完全一致</b>（Base62 / 22 位），
 * 否则各套 token 的碰撞概率与外观不一致。不跨模块复用是为了不让 {@code admin/places} 依赖 {@code shop/} 或 {@code profile/}。
 */
@Component
public class PlaceTokenGenerator {

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    public static final int LENGTH = 22;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
