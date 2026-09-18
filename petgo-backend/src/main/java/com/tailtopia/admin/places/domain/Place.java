package com.tailtopia.admin.places.domain;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 场所（V1.3.0 Story 5.1，AB-17A / AD-5；表 {@code places}，契约 X-1 / X-3：schema 由本分支定，App 分支 FR-112 读写数据）。
 * <ul>
 * <li>对外只暴露不可枚举 {@link #publicToken}（{@code PlaceTokenGenerator} 生成，{@code updatable=false}）。</li>
 * <li>状态机：{@code ACTIVE ⇄ DELISTED}（下架 / 恢复）；{@code → MERGED}（合并，须带 {@link #mergedIntoId}，CHECK {@code ck_places_merged_ref}）。
 * {@code status != ACTIVE} 或 {@code deleted_at IS NOT NULL} 对用户端即「场所不存在」。</li>
 * <li>{@link #placeType} / {@link #tags} 值域由 App 端定义，这里只存字符串（{@link PlaceType} 软校验）。</li>
 * <li>五个计数列是反规范化缓存：服务层同事务 {@link #recount} / 增减维护，本实体不含业务逻辑（Story 5.3）。</li>
 * <li>{@link #markedByUserId} 指 {@code users}（App 用户或运营发布身份池账号），不是后台账号，不可改。</li>
 * </ul>
 */
@Entity
@Table(name = "places")
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, updatable = false)
    private String publicToken;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** 场所类型（UPPER_SNAKE），值域由 App 端 FR-112 定；后台 {@link PlaceType#isKnown} 软校验。 */
    @Column(name = "place_type", nullable = false, length = 32)
    private String placeType;

    /** 宠物友好标签码列表（JSONB）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false)
    private List<String> tags = new ArrayList<>();

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** D-39：城市名。 */
    @Column(name = "city", nullable = false, length = 60)
    private String city;

    @Column(name = "address_text", nullable = false, length = 255)
    private String addressText;

    @Column(name = "lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "marked_by_user_id", nullable = false, updatable = false)
    private Long markedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlaceStatus status = PlaceStatus.ACTIVE;

    @Column(name = "merged_into_id")
    private Long mergedIntoId;

    @Column(name = "photo_count", nullable = false)
    private int photoCount;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Column(name = "checkin_count", nullable = false)
    private int checkinCount;

    @Column(name = "recommend_count", nullable = false)
    private int recommendCount;

    @Column(name = "not_recommend_count", nullable = false)
    private int notRecommendCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Place() {
    }

    /** 新建场所（ACTIVE、计数全 0）。{@code publicToken} 由 {@code PlaceTokenGenerator} 生成后传入。 */
    public static Place create(String publicToken, String name, String placeType, List<String> tags, String description,
            String city, String addressText, BigDecimal lat, BigDecimal lng, long markedByUserId) {
        Place p = new Place();
        p.publicToken = Objects.requireNonNull(publicToken, "publicToken");
        p.name = Objects.requireNonNull(name, "name");
        p.placeType = Objects.requireNonNull(placeType, "placeType");
        p.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        p.description = description;
        p.city = Objects.requireNonNull(city, "city");
        p.addressText = Objects.requireNonNull(addressText, "addressText");
        p.lat = Objects.requireNonNull(lat, "lat");
        p.lng = Objects.requireNonNull(lng, "lng");
        p.markedByUserId = markedByUserId;
        p.status = PlaceStatus.ACTIVE;
        return p;
    }

    /** 编辑基本信息（Story 5.3）；标记人 / token 不可改。 */
    public void edit(String name, String placeType, List<String> tags, String description, String city, String addressText,
            BigDecimal lat, BigDecimal lng) {
        this.name = Objects.requireNonNull(name, "name");
        this.placeType = Objects.requireNonNull(placeType, "placeType");
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        this.description = description;
        this.city = Objects.requireNonNull(city, "city");
        this.addressText = Objects.requireNonNull(addressText, "addressText");
        this.lat = Objects.requireNonNull(lat, "lat");
        this.lng = Objects.requireNonNull(lng, "lng");
    }

    /** 下架：ACTIVE → DELISTED；已下架幂等；MERGED 不可下架。返回是否发生变化。 */
    public boolean delist() {
        if (status == PlaceStatus.MERGED) {
            throw new IllegalStateException("MERGED place cannot be delisted");
        }
        if (status == PlaceStatus.DELISTED) {
            return false;
        }
        status = PlaceStatus.DELISTED;
        return true;
    }

    /** 恢复：DELISTED → ACTIVE；已上架幂等；MERGED 不可恢复。返回是否发生变化。 */
    public boolean restore() {
        if (status == PlaceStatus.MERGED) {
            throw new IllegalStateException("MERGED place cannot be restored");
        }
        if (status == PlaceStatus.ACTIVE) {
            return false;
        }
        status = PlaceStatus.ACTIVE;
        return true;
    }

    /**
     * 合并进保留场所（Story 5.3）：status=MERGED + merged_into_id（CHECK {@code ck_places_merged_ref} 要求成对，
     * {@code ck_places_no_self_merge} 禁自合并）。已 MERGED（不静默改指向）/ 已软删的不可再合并；保留方不得自身为 MERGED——服务层保证。
     */
    public void markMerged(long keepId) {
        if (id != null && id == keepId) {
            throw new IllegalArgumentException("place cannot be merged into itself");
        }
        if (status == PlaceStatus.MERGED) {
            throw new IllegalStateException("place already merged");
        }
        if (deletedAt != null) {
            throw new IllegalStateException("deleted place cannot be merged");
        }
        this.status = PlaceStatus.MERGED;
        this.mergedIntoId = keepId;
    }

    /** 软删（deleted_at）；列表查询默认 {@code deleted_at IS NULL}。 */
    public void softDelete() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
        }
    }

    /** 全量重算五个缓存计数（合并 / 删除时由服务层在同一事务里调用；负数视为 0）。 */
    public void recount(int photoCount, int commentCount, int checkinCount, int recommendCount, int notRecommendCount) {
        this.photoCount = Math.max(0, photoCount);
        this.commentCount = Math.max(0, commentCount);
        this.checkinCount = Math.max(0, checkinCount);
        this.recommendCount = Math.max(0, recommendCount);
        this.notRecommendCount = Math.max(0, notRecommendCount);
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

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 用户端可见 = ACTIVE 且未软删（契约文档语义 ①）。 */
    public boolean isVisibleToUsers() {
        return status == PlaceStatus.ACTIVE && deletedAt == null;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public String getName() {
        return name;
    }

    public String getPlaceType() {
        return placeType;
    }

    /** 只读视图；App 分支写入的数组可能含 null 元素，不用 {@code List.copyOf}（对 null 抛 NPE）。 */
    public List<String> getTags() {
        return tags == null ? List.of() : java.util.Collections.unmodifiableList(tags);
    }

    public String getDescription() {
        return description;
    }

    public String getCity() {
        return city;
    }

    public String getAddressText() {
        return addressText;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public Long getMarkedByUserId() {
        return markedByUserId;
    }

    public PlaceStatus getStatus() {
        return status;
    }

    public Long getMergedIntoId() {
        return mergedIntoId;
    }

    public int getPhotoCount() {
        return photoCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public int getCheckinCount() {
        return checkinCount;
    }

    public int getRecommendCount() {
        return recommendCount;
    }

    public int getNotRecommendCount() {
        return notRecommendCount;
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
