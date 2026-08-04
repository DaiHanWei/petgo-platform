package com.tailtopia.shared.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 分析用户标识（V1.1.2 Story 6.1）。{@code sha256("tailtopia-user-" + 内部用户id)}，小写十六进制。
 *
 * <p><b>必须与客户端逐字一致</b>：前端是
 * {@code sha256(utf8('tailtopia-user-$userId'))}（{@code analytics.dart#distinctIdFor}）。
 * 差一个字节，同一个人在看板上就会被算成两个人，「谁点了按钮 → 谁最终达成里程碑」这条漏斗断掉。
 * 本类的单测里钉了一条已知向量防漂移。
 *
 * <p>为什么不用自增 id：护栏「对外暴露标识一律不可枚举」。哈希也不是 PII，可安全送第三方。
 * 已知取舍（与前端同）：无盐 sha256 可被持 PostHog 读权限者暴力反推回内部 id；V1 接受
 * （该 id 既非 PII 也非健康数据，且 distinctId 不是对外 API 面）。
 */
public final class AnalyticsDistinctId {

    private static final String PREFIX = "tailtopia-user-";

    private AnalyticsDistinctId() {
    }

    public static String of(long userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((PREFIX + userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里。
            throw new IllegalStateException(e);
        }
    }
}
