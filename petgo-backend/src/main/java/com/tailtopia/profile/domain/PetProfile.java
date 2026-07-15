package com.tailtopia.profile.domain;

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
import java.time.LocalDate;

/**
 * 宠物档案（Story 2.2 创建 {@code pet_profiles} 表）。承载成长记忆 + 对外名片。
 *
 * <p>命名映射链：列 snake_case ↔ 字段 camelCase。时间戳 UTC。V1 单账号单宠物
 * （{@code owner_id} 唯一）。对外用不可枚举 {@code cardToken}，绝不外露顺序 id。
 */
@Entity
@Table(name = "pet_profiles")
public class PetProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    /** 宠物类型（F6）：必选，**创建后不可改**（无 setter，update 路径不含此字段）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "pet_type", nullable = false, length = 16)
    private PetType petType;

    @Column(name = "name", nullable = false, length = 20)
    private String name;

    /**
     * 宠物名当前是否为违规重置生成的系统默认编码名（{@code Pet_<hex>}，内容审核 story 4，D-CM4）。
     * 用户主动改新名时清 false。与注销匿名化正交。
     */
    @Column(name = "is_system_default_name", nullable = false)
    private boolean systemDefaultName = false;

    @Column(name = "breed", length = 60)
    private String breed;

    @Column(name = "birthday")
    private LocalDate birthday;

    @Column(name = "intro", length = 30)
    private String intro;

    @Column(name = "card_token", nullable = false, length = 64)
    private String cardToken;

    /** 社交预览预渲染 OG 静态图 URL（公开桶+CDN，Story 2.6；档案/头像变更时重渲染）。 */
    @Column(name = "og_image_url", length = 1024)
    private String ogImageUrl;

    /**
     * 宠物身份证全平台自增流水号（Story 6.1，FR-49A）。惰性分配：未生成为 null（老用户「尚未生成」引导态）。
     * 仅作展示编号，绝不作对外资源标识（分享/深链/快照用 cardToken / 内部 id）。删除档案时经号池回收复用。
     */
    @Column(name = "serial_id")
    private Long serialId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PetProfile() {
    }

    public static PetProfile create(long ownerId, PetType petType, String name, String avatarUrl,
            String breed, LocalDate birthday, String intro, String cardToken) {
        PetProfile p = new PetProfile();
        p.ownerId = ownerId;
        p.petType = petType;
        p.name = name;
        p.avatarUrl = avatarUrl;
        p.breed = breed;
        p.birthday = birthday;
        p.intro = intro;
        p.cardToken = cardToken;
        return p;
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

    public Long getOwnerId() {
        return ownerId;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    /** 宠物类型（只读：创建后不可改，无 setter）。 */
    public PetType getPetType() {
        return petType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** 宠物名是否为违规重置的系统默认编码名（内容审核 story 4）。 */
    public boolean isSystemDefaultName() {
        return systemDefaultName;
    }

    /** 违规重置置 true；用户主动改新名时清 false。 */
    public void setSystemDefaultName(boolean systemDefaultName) {
        this.systemDefaultName = systemDefaultName;
    }

    public String getBreed() {
        return breed;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getCardToken() {
        return cardToken;
    }

    public String getOgImageUrl() {
        return ogImageUrl;
    }

    public void setOgImageUrl(String ogImageUrl) {
        this.ogImageUrl = ogImageUrl;
    }

    /** 流水号（Story 6.1；未生成为 null → 老用户「尚未生成」引导态）。 */
    public Long getSerialId() {
        return serialId;
    }

    /**
     * 分配流水号（Story 6.1）：仅当尚未分配时赋值。已有则拒绝覆盖（幂等由 service 层判定，实体层防误改换号）。
     */
    public void assignSerial(long serial) {
        if (this.serialId != null) {
            throw new IllegalStateException("流水号已分配，不可覆盖：" + this.serialId);
        }
        this.serialId = serial;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
