package com.tailtopia.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 里程碑庆祝对外分享行（P-35 分享链接，{@code milestone_shares}）。复用名片分享范式（决策 F16）：
 * 对外用不可枚举 {@link #shareToken}（绝不外露顺序 id），公开 H5 {@code GET /m/{shareToken}} 据此直出。
 *
 * <p>存「渲染好」的庆祝文案（{@link #title}/{@link #body}/{@link #locale}）——沿用本模块「显示文案归客户端
 * 按 locale 出、后端只存稳定数据」约定。{@code (petProfileId, code)} 唯一 → 同一里程碑分享幂等（重复分享复用
 * 同一 token，仅刷新文案）。归 profile 域，随档案 / 账号注销级联清理（见 {@code ProfileDeletionService}）。
 */
@Entity
@Table(name = "milestone_shares")
public class MilestoneShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_token", nullable = false, length = 64, updatable = false)
    private String shareToken;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @Column(name = "code", nullable = false, length = 16, updatable = false)
    private String code;

    @Column(name = "level", nullable = false, length = 8)
    private String level;

    @Column(name = "pet_name", nullable = false, length = 80)
    private String petName;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "locale", nullable = false, length = 8)
    private String locale;

    /** 「已解锁合集」快照：分享时已完成里程碑的级别串（按合集顺序，每字符 S/M/L）。 */
    @Column(name = "collection_levels", nullable = false, length = 64)
    private String collectionLevels;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MilestoneShare() {
    }

    public static MilestoneShare create(String shareToken, long petProfileId, String code,
            String level, String petName, String title, String body, String locale,
            String collectionLevels, Instant completedAt) {
        MilestoneShare s = new MilestoneShare();
        s.shareToken = shareToken;
        s.petProfileId = petProfileId;
        s.code = code;
        s.level = level;
        s.petName = petName;
        s.title = title;
        s.body = body;
        s.locale = locale;
        s.collectionLevels = collectionLevels;
        s.completedAt = completedAt;
        return s;
    }

    /** 重复分享：复用 token，仅刷新本地化文案 / petName / locale / level / 合集快照（completedAt、token 不变）。 */
    public void refresh(String level, String petName, String title, String body, String locale,
            String collectionLevels) {
        this.level = level;
        this.petName = petName;
        this.title = title;
        this.body = body;
        this.locale = locale;
        this.collectionLevels = collectionLevels;
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

    public String getShareToken() {
        return shareToken;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public String getCode() {
        return code;
    }

    public String getLevel() {
        return level;
    }

    public String getPetName() {
        return petName;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLocale() {
        return locale;
    }

    public String getCollectionLevels() {
        return collectionLevels;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
