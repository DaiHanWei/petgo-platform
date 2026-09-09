package com.tailtopia.admin.roles.domain;

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
 * 后台岗位角色表实体（V1.3.0 Story 1.4，AD-4 方案 A）。类名带 {@code Entity} 后缀以区别于
 * {@code admin_accounts.role} 列仍在用的枚举 {@link com.tailtopia.admin.account.domain.AdminRole}。
 *
 * <p>SYSTEM 行：code 为四个预置岗位（OPERATIONS / FULFILLMENT / SUPPORT / FINANCE），name_key = {@code role.<CODE>}
 * 三语；CUSTOM 行由运营在角色配置页自建（Story 1.5），name 单语、name_key 为 NULL。权限码在 {@link AdminRolePermission}。
 */
@Entity
@Table(name = "admin_roles")
public class AdminRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "name_key", length = 80)
    private String nameKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, length = 8)
    private RoleType roleType;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminRoleEntity() {
    }

    /** 运营自建角色（Story 1.5）：{@code code} 由服务层生成（{@code role-<id>} 需先持久化取 id，故允许先占位再改）。 */
    public static AdminRoleEntity newCustom(String code, String name, Long createdBy) {
        AdminRoleEntity r = new AdminRoleEntity();
        r.code = code;
        r.name = name;
        r.roleType = RoleType.CUSTOM;
        r.createdBy = createdBy;
        return r;
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

    public boolean isSystem() {
        return roleType == RoleType.SYSTEM;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public RoleType getRoleType() {
        return roleType;
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
