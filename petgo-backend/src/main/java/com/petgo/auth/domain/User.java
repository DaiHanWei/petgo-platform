package com.petgo.auth.domain;

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
 * 平台用户主体（Story 1.3 创建 {@code users} 表）。
 *
 * <p>命名映射链：列 snake_case ↔ 字段 camelCase（JPA 桥接）。时间戳一律 UTC。
 * {@code role}/{@code petStatus} 枚举落库 varchar + UPPER。
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_sub", nullable = false, length = 255)
    private String googleSub;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Column(name = "nickname", length = 20)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "pet_status", length = 8)
    private PetStatus petStatus;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role = Role.USER;

    /** 仅 ADMIN 账密登录用（BCrypt 哈希）；OAuth 用户为 null。Story 3.1。 */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {
    }

    public static User newGoogleUser(String googleSub, String email, String displayName, String avatarUrl) {
        User u = new User();
        u.googleSub = googleSub;
        u.email = email;
        u.displayName = displayName;
        u.avatarUrl = avatarUrl;
        u.nickname = displayName; // 初始可同 display_name，Story 1.6 确认
        u.role = Role.USER;
        u.onboardingCompleted = false;
        return u;
    }

    /**
     * 运营 ADMIN 账号（Story 3.1）。无 Google 身份，走账密表单登录；
     * {@code googleSub} 占位为 {@code admin:<email>} 以满足非空+唯一约束。
     */
    public static User newAdmin(String email, String displayName, String passwordHash) {
        User u = new User();
        u.googleSub = "admin:" + email;
        u.email = email;
        u.displayName = displayName;
        u.nickname = displayName;
        u.role = Role.ADMIN;
        u.onboardingCompleted = true;
        u.passwordHash = passwordHash;
        return u;
    }

    /** 更新 ADMIN 密码哈希（bootstrap 幂等重置用）。 */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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

    public String getGoogleSub() {
        return googleSub;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    /** 头像替换（Story 7.1）：仅存应用自有 OSS URL。 */
    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public PetStatus getPetStatus() {
        return petStatus;
    }

    public void setPetStatus(PetStatus petStatus) {
        this.petStatus = petStatus;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public void setOnboardingCompleted(boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
    }

    public Role getRole() {
        return role;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
