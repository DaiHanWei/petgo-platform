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

    /**
     * 岗位角色（V165）。{@code CUSTOM} 表示权限按 {@code admin_account_permissions} 勾选行走
     * （Story 1.5 原有形态），其余按 {@link AdminRole} 模板在登录时解析。
     * 与 {@link #accountType} 由 {@link AdminRole#accountType()} 单向推导保持自洽（库级另有 CHECK 兜底）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private AdminRole role = AdminRole.CUSTOM;

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
        a.role = AdminRole.SUPER_ADMIN;
        a.status = AdminAccountStatus.ACTIVE;
        a.passwordHash = passwordHash;
        return a;
    }

    /**
     * 超管在后台创建账号（Story 1.5；V165 改为按<b>岗位角色</b>建号）：ACTIVE、无密码
     * （STAFF/SUPER_ADMIN 均走 Lark OAuth 登录；紧急账密仅 bootstrap 预置的超管有）。
     * {@code createdBy} = 操作的超管账号 id。
     *
     * <p>{@code accountType} 由 {@link AdminRole#accountType()} 推导而非单独传入——单一入参定型，
     * 不给「角色=发货、类型=超管」这类矛盾组合留出口。
     */
    public static AdminAccount create(String larkEmail, String displayName,
            AdminRole role, Long createdBy) {
        AdminAccount a = new AdminAccount();
        a.larkEmail = larkEmail;
        a.displayName = displayName;
        a.role = role;
        a.accountType = role.accountType();
        a.status = AdminAccountStatus.ACTIVE;
        a.createdBy = createdBy;
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

    /**
     * 改岗位角色（同步推导 {@code accountType}，二者永不脱钩）。
     * 超管名额上限与「不降级最后一个超管」的护栏在 {@code AdminAccountService} 侧校验。
     */
    public void setRole(AdminRole role) {
        this.role = role;
        this.accountType = role.accountType();
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

    public AdminRole getRole() {
        return role;
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
