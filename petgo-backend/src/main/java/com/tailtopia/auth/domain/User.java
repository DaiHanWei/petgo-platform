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

    /**
     * 昵称当前是否为违规重置生成的系统默认编码名（{@code user_<hex>}，内容审核 story 4，D-CM4）。
     * 违规重置 ≠ 注销匿名化：此标记只随违规重置写入的真实昵称，与 7.3「已注销用户」展示层无关。
     * 用户主动改新昵称时清 false。
     */
    @Column(name = "is_system_default_name", nullable = false)
    private boolean systemDefaultName = false;

    /** 语言偏好（bug 20260625-105）：由 App 请求 Accept-Language 捕获，'id'/'en'；空=默认 id。供系统推送文案本地化。 */
    @Column(name = "locale", length = 8)
    private String locale;

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

    /** 注销前 email 快照，仅运营后台展示（原 {@code email} 注销后置空）。业务/公开/vet 侧不读。 */
    @Column(name = "deleted_email", length = 320)
    private String deletedEmail;

    /** 注销前 displayName 快照，仅运营后台展示（原 {@code displayName} 注销后置空）。业务/公开/vet 侧不读。 */
    @Column(name = "deleted_display_name", length = 255)
    private String deletedDisplayName;

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

    public String getLocale() {
        return locale;
    }

    /** 语言偏好（bug 20260625-105）：'id'/'en'，由 Accept-Language 捕获。 */
    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 昵称是否为违规重置的系统默认编码名（内容审核 story 4）。 */
    public boolean isSystemDefaultName() {
        return systemDefaultName;
    }

    /** 违规重置置 true；用户主动改新昵称时清 false。 */
    public void setSystemDefaultName(boolean systemDefaultName) {
        this.systemDefaultName = systemDefaultName;
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

    /** 注销前 email 快照（仅后台展示）。 */
    public String getDeletedEmail() {
        return deletedEmail;
    }

    /** 注销前 displayName 快照（仅后台展示）。 */
    public String getDeletedDisplayName() {
        return deletedDisplayName;
    }

    /**
     * 注销「就地匿名化」（Story 7.3，决策 D1/A）：软删标记 + 擦除 PII，<b>不物理删行</b>——避免
     * content_posts/comments/likes/reports 的 NOT NULL + RESTRICT 外键阻断（DataIntegrityViolationException）。
     * UGC 保留、由 {@code AccountQueryService} 解析为「已注销用户」。{@code googleSub}/{@code appleSub} 置墓碑：
     * 满足非空+唯一约束、且同账号再次登录只会新建账号（不复活旧号）。幂等（重跑安全）。健康/个人数据在其它服务另删。
     */
    public void anonymizeForDeletion(Instant now) {
        // 首次注销才快照展示用 PII 到专用列（幂等：重跑时 deletedAt 已非空，不覆盖快照）。
        if (this.deletedAt == null) {
            this.deletedEmail = this.email;
            this.deletedDisplayName = this.displayName;
        }
        this.deletedAt = now;
        this.email = null;
        this.displayName = null;
        this.avatarUrl = null;
        this.nickname = null;
        this.googleSub = "deleted:" + this.id; // 非空 + 唯一（每行 id 唯一），防复登 + 防唯一冲突
        this.appleSub = null;
    }
}
