package com.tailtopia.shop.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 电商对外标识生成器（Story 1.1，AD-7）。{@code SecureRandom} + Base62 22 位。
 *
 * <p>🔴 <b>CLAUDE.md 强制护栏：对外暴露标识一律不可枚举 token，不用自增 id 直接外露。</b>
 * 日期 + 自增序列（如 {@code TT20260817000001}）同样可枚举——任何用户从自己的标识即可推断
 * 平台当日单量与累计单量，对一个还在验证 A-15 的产品，这是直接泄露最敏感的经营数据。
 *
 * <p><b>为何不复用 {@code profile.service.CardTokenGenerator}：</b>AD-7 给了「自建同范式」与
 * 「提升到 shared/」两个选项，本版本取自建——跨模块 import 会让 {@code shop/} 依赖
 * {@code profile/}；挪进 {@code shared/} 又要改 {@code profile/} 的既有 import，在三人并行下
 * 为 20 行代码去碰共享文件不划算。
 * 🔴 <b>但字符表与长度必须与 CardTokenGenerator 完全一致</b>（Base62 / 22 位），
 * 否则两套 token 的碰撞概率与外观不一致。
 */
@Component
public class ShopTokenGenerator {

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 22;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
