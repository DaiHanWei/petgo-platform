package com.tailtopia.admin.account.domain;

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
 * 后台账号主体（Story 1.1，AG-1）。**与 App {@code users} / {@code vet_accounts} 完全隔离**（PRD 术语表，决策 F-A）。
 *
 * <p>身份标识 = {@code larkEmail}（兼 Lark 邮箱白名单，Story 1.2）。{@code passwordHash} 仅超管紧急入口用
 * （BCrypt，明文绝不落库/日志，env 注入）；STAFF 走 Lark OAuth、无密码。命名映射链：列 snake_case ↔ 字段 camelCase；
 * 枚举落库 varchar + UPPER；时间戳一律 UTC。
 */
@Entity
@Table(name = "admin_accounts")
public class AdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lark_email", nullable = false, length = 255)
    private String larkEmail;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AdminAccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdminAccountStatus status = AdminAccountStatus.ACTIVE;

    /** 仅超管紧急账密入口用（BCrypt）；Lark OAuth 账号（STAFF）为 null。 */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    /** 创建者后台账号 id（首个超管由 bootstrap 预置时为 null）。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminAccount() {
    }

    /** 超级管理员（bootstrap 预置）：带紧急账密，无 createdBy。 */
    public static AdminAccount newSuperAdmin(String larkEmail, String displayName, String passwordHash) {
        AdminAccount a = new AdminAccount();
        a.larkEmail = larkEmail;
        a.displayName = displayName;
        a.accountType = AdminAccountType.SUPER_ADMIN;
        a.status = AdminAccountStatus.ACTIVE;
        a.passwordHash = passwordHash;
        return a;
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

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setStatus(AdminAccountStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getLarkEmail() {
        return larkEmail;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AdminAccountType getAccountType() {
        return accountType;
    }

    public AdminAccountStatus getStatus() {
        return status;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
