package com.tailtopia.admin.virtual.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 运营发布身份池里的**真实账号授权**（V1.1.6 Story 12.1 · AB-3I）。
 *
 * <p>🛡 <b>本表只存"授权关系"，不动 {@code users.account_type}</b>（AC2）。虚拟账号没有宠物档案
 * （发不了成长日历）、没有粉丝、主页是空的 —— 替代不了人格化 IP 号；但把真人号改成别的账号类型，
 * 会让它在 App 内的一切行为（登录、发帖、被查看）都走进未被验证的分支。所以是"加一张表"，
 * 不是"加一种账号"。
 *
 * <p><b>授权是有历史的</b>：纳入 → 移出 → 再纳入，每次纳入是一行新记录，移出把该行标成
 * {@link Status#REMOVED} 而不是删行 —— 否则"谁在什么时候把谁移出去的"就查不到了。
 * 同一账号同时只能有一条 {@code ACTIVE}（DB 侧 partial unique index 兜底）。
 *
 * <p>🛡 <b>移出 ≠ 封号</b>：只收回"后台可代其发布"这一项，该账号在 App 内的一切行为不受影响。
 */
@Entity
@Table(name = "seed_real_account_grants")
public class SeedRealAccountGrant {

    /** 授权状态。 */
    public enum Status {
        ACTIVE,
        REMOVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Column(name = "authorization_note", nullable = false, length = 500)
    private String authorizationNote;

    @Column(name = "granted_by", nullable = false, updatable = false)
    private long grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(name = "removed_by")
    private Long removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected SeedRealAccountGrant() {
    }

    public static SeedRealAccountGrant grant(long userId, String authorizationNote, long adminId) {
        SeedRealAccountGrant g = new SeedRealAccountGrant();
        g.userId = userId;
        g.authorizationNote = authorizationNote;
        g.grantedBy = adminId;
        g.grantedAt = Instant.now();
        g.status = Status.ACTIVE;
        return g;
    }

    /** 移出：只改状态与移出痕迹，**不删行**。 */
    public void remove(long adminId) {
        this.status = Status.REMOVED;
        this.removedBy = adminId;
        this.removedAt = Instant.now();
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getAuthorizationNote() {
        return authorizationNote;
    }

    public long getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Status getStatus() {
        return status;
    }

    public Long getRemovedBy() {
        return removedBy;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
