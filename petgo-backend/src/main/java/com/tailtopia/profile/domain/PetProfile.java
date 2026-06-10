package com.petgo.profile.domain;

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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
