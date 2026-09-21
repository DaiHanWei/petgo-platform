package com.tailtopia.place.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 场所不可枚举对外标识生成器（V1.3.0 batch-b1 Story 1.1 · AD-1 Rule 3）。
 *
 * <p>🔴 <b>CLAUDE.md 强制护栏：对外暴露标识一律不可枚举 token，不用自增 id 直接外露。</b>
 * 场所尤其不能用名字拼 URL —— 那等于让任何人按名字或按序号把全站场所爬一遍。
 *
 * <p>形态沿用 {@code NotificationService.generateToken()}：<b>BASE62 × 32</b> + {@link SecureRandom}
 * （Story 1.1 Dev Notes 指定）。{@code profile.CardTokenGenerator} / {@code shop.ShopTokenGenerator}
 * 是同族的 22 位版本，两者都 ≥128bit 熵；本模块取 32 位与 notify 侧一致。
 *
 * <p><b>为何不复用另两个生成器</b>：跨模块 import 会让 {@code place/} 依赖 {@code profile/} 或
 * {@code shop/}（§2 依赖图里没有这两条边）；挪进 {@code shared/} 又要改既有模块的 import。
 * 同 {@code ShopTokenGenerator} 的既定取舍。
 */
@Component
public class PlaceTokenGenerator {

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
