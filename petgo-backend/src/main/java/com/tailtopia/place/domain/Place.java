package com.tailtopia.place.domain;

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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 宠物友好场所（V1.3.0 batch-b1 Story 1.1 建 {@code places} 表 · FR-112.1 · AD-1）。
 *
 * <p><b>只存经纬度 + 文字地址</b>，不存任何地图厂商的对象 id / POI id —— 换厂商时数据一行不动。
 * 对外一律走不可枚举 {@link #publicToken}，自增 {@link #id} 只在站内 API 与后台使用。
 *
 * <p>弹性字段：{@code tags} 映射 JSONB；{@code type} / {@code status}
 * 落 varchar + UPPER_SNAKE；时间戳 {@code timestamptz} UTC。
 *
 * <h2>🔴 不要往这里加的东西</h2>
 * <ul>
 *   <li><b>⑧ 打卡相关的任何字段</b> —— 打卡在批次 B2，预留即越界（Story 1.1 Dev Notes 第 1 条）；</li>
 *   <li><b>编辑入口</b> —— 本版用户不可修改场所（2026-09-15 拍板），服务端不提供编辑接口，
 *       所以这里也没有任何 setter；纠错走后台 AB-17A（下架 / 合并重复）。</li>
 * </ul>
 */
@Entity
@Table(name = "places")
// 2026-09-18 场所表对齐：本表 schema 归后台（V20260909_1749 + V20260918_2110），App 侧只映射自己读写的列。
// 同表另有后台实体 com.tailtopia.admin.places.domain.Place（JPA 实体名 AdminPlace）。
// 🔴 后台负责的列（status / merged_into_id / city / deleted_at）在这里一律 updatable=false —— App 只插入、从不改已有行，
//    这样即便将来有人在 App 侧 save 一个旧实例，也覆盖不掉运营刚做的下架 / 合并。
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 不可枚举对外标识（BASE62×32）。分享链接与 App 寻址只用它。 */
    @Column(name = "public_token", nullable = false, length = 32, updatable = false)
    private String publicToken;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_type", nullable = false, length = 32)
    private PlaceType type;

    /** 宠物友好标签（≥1）。JSONB 数组，取值见 {@link PlaceTag}。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false)
    private List<PlaceTag> tags;

    /**
     * 纬度（-90~90）。
     *
     * <p>🔴 与 {@link #longitude} 是<b>两个独立的数值列</b>（AD-2 Rule 2/3）：Story 1.2 要对它们
     * 各建索引做矩形范围粗筛。拼成字符串或换成 point 类型都吃不到索引。
     */
    @Column(name = "lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    /** 经度（-180~180）。见 {@link #latitude} 的说明。 */
    @Column(name = "lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    /**
     * 文字地址（必填的位置备注）。
     *
     * <p>🔴 <b>纯展示 + 一键复制的字符串</b>：平台不做地理编码、不校验它与坐标是否一致
     * （AD-1 Rule 5）。任何把它当作可解析结构化地址的逻辑都是越界。
     */
    @Column(name = "address_text", nullable = false, length = 255)
    private String addressText;

    /** 列是 TEXT（后台 schema）；App 接口仍校验 ≤200 字。 */
    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * 城市（后台 D-39 必填）。App 标记时由 {@code PlaceCityResolver} 填（对齐决策 D2，本版为默认城市）；
     * 运营可在后台改。App 侧不改。
     */
    @Column(name = "city", nullable = false, length = 60, updatable = false)
    private String city;

    /**
     * 🔴 <b>照片不在这张表上</b>（Story 1.9 起）：它们搬到了 {@code place_photos}，
     * 因为每张要带**上传者**与**自己的审核态**（他人可补充照片，先发后审）。
     * 迁移 {@code V20260915_0927__init_place_photos.sql} 已把存量搬走并删掉了 {@code photo_urls} 列 ——
     * **不要再往这里加一个照片字段**，那会立刻变成第二份真相。
     */

    /** 标记人 {@code users.id}；冷启动数据为运营官方账号。 */
    @Column(name = "marked_by_user_id", nullable = false, updatable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private PlaceStatus status = PlaceStatus.ACTIVE;

    /** 运营合并后指向保留场所（status=MERGED 时非空）。App 据此把直链 / 分享页转到保留场所（对齐决策 D4）。 */
    @Column(name = "merged_into_id", insertable = false, updatable = false)
    private Long mergedIntoId;

    /** 后台软删时间。非空 = 对用户「不存在」（与 status != ACTIVE 同等对待）。 */
    @Column(name = "deleted_at", insertable = false, updatable = false)
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Place() {
    }

    /**
     * 标记一个场所（Story 1.3 的落库入口；本 story 只建模，写端点在 1.3）。
     *
     * <p>字段级校验（长度 / 标签 ≥1 / 照片 1–9）归服务层，这里只负责装配。
     */
    public static Place mark(String publicToken, String name, PlaceType type, List<PlaceTag> tags,
            double latitude, double longitude, String addressText, String description,
            long createdBy, String city) {
        Place p = new Place();
        p.publicToken = publicToken;
        p.name = name;
        p.type = type;
        p.tags = tags;
        p.latitude = coord(latitude);
        p.longitude = coord(longitude);
        p.city = city;
        p.addressText = addressText;
        p.description = description;
        p.createdBy = createdBy;
        p.status = PlaceStatus.ACTIVE;
        return p;
    }

    /** 坐标落库精度：NUMERIC(9,6)（约 0.11 米），与后台录入同一口径。 */
    static BigDecimal coord(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
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

    public String getPublicToken() {
        return publicToken;
    }

    public String getName() {
        return name;
    }

    public PlaceType getType() {
        return type;
    }

    public List<PlaceTag> getTags() {
        return tags;
    }

    /** 距离计算用 double；库里是 NUMERIC(9,6)。 */
    public double getLatitude() {
        return latitude.doubleValue();
    }

    public double getLongitude() {
        return longitude.doubleValue();
    }

    public String getCity() {
        return city;
    }

    public Long getMergedIntoId() {
        return mergedIntoId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** 对用户可见 = ACTIVE 且未软删（与后台 {@code isVisibleToUsers} 同一口径）。 */
    public boolean isVisibleToUsers() {
        return status == PlaceStatus.ACTIVE && deletedAt == null;
    }

    public String getAddressText() {
        return addressText;
    }

    public String getDescription() {
        return description;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public PlaceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
