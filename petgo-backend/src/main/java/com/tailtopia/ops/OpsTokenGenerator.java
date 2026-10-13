package com.tailtopia.ops;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 运营模块（弹窗 / 通知）对外标识生成器（V1.3.4 Story 5.1，D-24）。{@code SecureRandom} + Base62 22 位。
 *
 * <p>🔴 对外暴露标识一律不可枚举（CLAUDE.md 护栏）；App 接口 token 与埋点 {@code popup_id} /
 * {@code campaign_id} 都取它，自增 id 不外露。
 * 🔴 <b>字符表与长度必须与 {@code ShopTokenGenerator} / {@code CardTokenGenerator} 完全一致</b>（Base62 / 22 位）。
 *
 * <p>提供静态 {@link #next()}：实体静态工厂（{@code OpsPopup.create}）在无注入环境下也要生成 token。
 */
@Component
public class OpsTokenGenerator {

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    static final int LENGTH = 22;

    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        return next();
    }

    public static String next() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
