package com.tailtopia.admin.service;

import com.tailtopia.vet.domain.VetAccount;
import java.time.Instant;

/**
 * Admin 后台兽医列表行视图（Story 5.1；2.2 扩资质/在线/均分列）。绝不含 {@code passwordHash}。
 *
 * <p>{@code username} 即登录邮箱/账号；{@code qualStatus} 为 2.1 资质 6 态名（null=简单视图未取）；
 * {@code presence} 为 ONLINE/BUSY/OFFLINE；{@code ratingAvg} 均分（无评分为 null，显示「—」）。
 */
public record VetAdminView(long id, String username, String displayName, String status,
        Instant createdAt, String qualStatus, String presence, Double ratingAvg, String avatarUrl) {

    /** 简单视图（仅账号字段，资质/在线/均分留空）——供单兽医详情/评分页导航等无需附加列处。 */
    public static VetAdminView from(VetAccount v) {
        return new VetAdminView(v.getId(), v.getUsername(), v.getDisplayName(),
                v.getStatus().name(), v.getCreatedAt(), null, null, null, v.getAvatarUrl());
    }

    /** 列表完整视图（2.2）：由 service 组装资质 + 在线 + 均分。 */
    public static VetAdminView of(VetAccount v, String qualStatus, String presence, Double ratingAvg) {
        return new VetAdminView(v.getId(), v.getUsername(), v.getDisplayName(),
                v.getStatus().name(), v.getCreatedAt(), qualStatus, presence, ratingAvg, v.getAvatarUrl());
    }
}
