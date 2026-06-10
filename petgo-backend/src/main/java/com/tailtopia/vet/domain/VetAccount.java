package com.tailtopia.vet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 兽医账号（Story 5.1 创建 {@code vet_accounts} 表）。Epic 5 在线兽医咨询的账号主体。
 *
 * <p>命名映射链：列 snake_case ↔ 字段 camelCase（JPA 桥接）。时间戳一律 UTC。
 *
 * <p>护栏：{@code passwordHash} 仅存 BCrypt 哈希，<b>明文绝不落库/落日志</b>；
 * 无「忘记密码」字段（重置由运营在 Admin 直接改 hash）。{@code status=BANNED} 不可登录（5.7 封禁同源）。
 */
@Entity
@Table(name = "vet_accounts")
public class VetAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VetStatus status = VetStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VetAccount() {
    }

    /** 运营开户：BCrypt hash 由 service 预先 encode 后传入（明文绝不进 entity）。 */
    public static VetAccount create(String username, String passwordHash, String displayName) {
        VetAccount v = new VetAccount();
        v.username = username;
        v.passwordHash = passwordHash;
        v.displayName = displayName;
        v.status = VetStatus.ACTIVE;
        return v;
    }

    /** 运营重置密码：替换 BCrypt hash（旧凭证随即失效）。 */
    public void resetPassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    /** 封禁/解封（5.7 复用；本故事落 ACTIVE↔BANNED 切换 + BANNED 不可登录）。 */
    public void setStatus(VetStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == VetStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public VetStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
