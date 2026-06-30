package com.tailtopia.auth.domain;

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

    @Column(name = "google_sub", length = 255)
    private String googleSub;

    /** Apple Sign-In 身份（FR-44，1.1.0）。Apple-only 用户的 googleSub 为 null，二者至少其一非空。 */
    @Column(name = "apple_sub", length = 255)
    private String appleSub;

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

    /** 账号状态（Story 3.2，停用/激活；与物理删除/注销正交）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

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
     * Apple 首授权建号（FR-44，1.1.0）。Apple identity token 不含姓名（仅 sub/email），
     * 故 displayName/nickname 留空，由新用户引导（Story 1.6）补昵称；Apple 不提供头像。
     */
    public static User newAppleUser(String appleSub, String email) {
        User u = new User();
        u.appleSub = appleSub;
        u.email = email;
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

    /** Story 3.2：运营停用（即时不可登录，可逆）。 */
    public void deactivate() {
        this.status = UserStatus.DEACTIVATED;
    }

    /** Story 3.2：重新激活，恢复登录权。 */
    public void reactivate() {
        this.status = UserStatus.ACTIVE;
    }

    public UserStatus getStatus() {
        return status;
    }

    /** 账号是否处于激活状态（Story 3.2 登录/刷新门控用）。 */
    public boolean isActiveStatus() {
        return status == UserStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public String getAppleSub() {
        return appleSub;
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
