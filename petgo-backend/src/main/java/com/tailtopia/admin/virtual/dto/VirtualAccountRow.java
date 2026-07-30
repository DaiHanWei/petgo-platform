package com.tailtopia.admin.virtual.dto;

import java.time.Instant;

/** 虚拟账号列表行（Story 9.8，A-6）。 */
public record VirtualAccountRow(
        long id,
        String nickname,
        String avatarUrl,
        boolean enabled,
        int publishedCount,
        Long createdBy,
        Instant createdAt) {
}
