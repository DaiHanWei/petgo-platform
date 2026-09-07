package com.tailtopia.admin.throttle.dto;

import com.tailtopia.moderation.throttle.domain.ThrottleDuration;
import com.tailtopia.moderation.throttle.domain.ThrottleScope;
import java.time.Instant;

/**
 * 一条内容/账号当前的限流状态，供后台列表展示（V1.1.6 Story 17.2 · AC3）。
 *
 * <p>{@code expiresAt} 为 {@code null} 有两种含义，靠 {@code duration} 区分：
 * {@code PERMANENT} = 永久；其余情况不会出现 null（建表 CHECK 保证）。
 * 模板里按 WIB 渲染并显式标「WIB」。
 */
public record ThrottleStatusRow(
        long throttleId,
        ThrottleScope scope,
        long targetId,
        ThrottleDuration duration,
        Instant expiresAt) {

    public boolean permanent() {
        return expiresAt == null;
    }
}
