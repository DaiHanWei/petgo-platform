package com.tailtopia.admin.usermgmt.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 视频免审名单的一行（专题线 video · Story 1.1 · FR-22，决策 V-25）。一个用户至多一行（user_id 即主键）。
 *
 * <p>写入只走仓库的原子 native 语句（{@code insertIfAbsent} / {@code deleteByUserId}），本实体只用于读取。
 * 移出 = 删行；「谁在什么时候加入 / 移出了谁」由审计日志 {@code VIDEO_TRUSTED_ADD / VIDEO_TRUSTED_REMOVE} 留痕。
 */
@Entity
@Table(name = "video_trusted_publishers")
public class VideoTrustedPublisher {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "created_by_admin_id", nullable = false, updatable = false)
    private long createdByAdminId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VideoTrustedPublisher() {
    }

    public Long getUserId() {
        return userId;
    }

    public long getCreatedByAdminId() {
        return createdByAdminId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
