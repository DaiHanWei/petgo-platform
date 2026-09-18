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
    @Column(name = "type", nullable = false, length = 24)
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
    @Column(name = "latitude", nullable = false)
    private double latitude;

    /** 经度（-180~180）。见 {@link #latitude} 的说明。 */
    @Column(name = "longitude", nullable = false)
    private double longitude;

    /**
     * 文字地址（必填的位置备注）。
     *
     * <p>🔴 <b>纯展示 + 一键复制的字符串</b>：平台不做地理编码、不校验它与坐标是否一致
     * （AD-1 Rule 5）。任何把它当作可解析结构化地址的逻辑都是越界。
     */
    @Column(name = "address_text", nullable = false, length = 255)
    private String addressText;

    @Column(name = "description", length = 200)
    private String description;

    /**
     * 🔴 <b>照片不在这张表上</b>（Story 1.9 起）：它们搬到了 {@code place_photos}，
     * 因为每张要带**上传者**与**自己的审核态**（他人可补充照片，先发后审）。
     * 迁移 {@code V20260915_0927__init_place_photos.sql} 已把存量搬走并删掉了 {@code photo_urls} 列 ——
     * **不要再往这里加一个照片字段**，那会立刻变成第二份真相。
     */

    /** 标记人 {@code users.id}；冷启动数据为运营官方账号。 */
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlaceStatus status = PlaceStatus.ACTIVE;

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
            long createdBy) {
        Place p = new Place();
        p.publicToken = publicToken;
        p.name = name;
        p.type = type;
        p.tags = tags;
        p.latitude = latitude;
        p.longitude = longitude;
        p.addressText = addressText;
        p.description = description;
        p.createdBy = createdBy;
        p.status = PlaceStatus.ACTIVE;
        return p;
    }

    /** 运营下架（AB-17A）。不物理删、不软删列，保留行结构供 H5 落统一空态。 */
    public void takeDown() {
        this.status = PlaceStatus.TAKEN_DOWN;
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

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
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
