package com.tailtopia.admin.service;

import com.tailtopia.vet.domain.VetAccount;
import java.time.Instant;

/**
 * Admin 后台兽医列表行视图（Story 5.1）。绝不含 {@code passwordHash}。
 */
public record VetAdminView(long id, String username, String displayName, String status, Instant createdAt) {

    public static VetAdminView from(VetAccount v) {
        return new VetAdminView(v.getId(), v.getUsername(), v.getDisplayName(), v.getStatus().name(), v.getCreatedAt());
    }
}
