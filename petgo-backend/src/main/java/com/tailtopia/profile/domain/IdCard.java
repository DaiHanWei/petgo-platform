package com.tailtopia.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 身份证卡快照（Story 6-7，bug 20260721-344 返工）。每张卡=一次锁定的信息快照 + 独立 serial。
 *
 * <p>决策①卡快照 ②每卡新号 ③旧卡保留可看可下载 ④独立建卡器（卡信息与档案解耦）。
 * 建卡时定格 name/petType/breed/birthday/avatarUrl/intro（之后改档案不影响本卡）；{@code hdUnlocked}
 * 为本卡 HD 解锁态（购买到账置 true）。{@code serialId} 仅展示编号，绝不作分享/深链/资源定位符。
 */
@Entity
@Table(name = "id_cards")
public class IdCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "serial_id", nullable = false)
    private Long serialId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /** 宠物类型枚举名（UPPER_SNAKE，如 DOG/CAT），快照存字符串，可空。 */
    @Column(name = "pet_type", length = 16)
    private String petType;

    @Column(name = "breed", length = 80)
    private String breed;

    @Column(name = "birthday")
    private LocalDate birthday;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Column(name = "intro", length = 30)
    private String intro;

    @Column(name = "hd_unlocked", nullable = false)
    private boolean hdUnlocked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdCard() {
    }

    /** 建卡快照（独立建卡器传入的字段 + 分配好的 serial）。初始未解锁。 */
    public static IdCard snapshot(long userId, long serialId, String name, String petType,
            String breed, LocalDate birthday, String avatarUrl, String intro) {
        IdCard c = new IdCard();
        c.userId = userId;
        c.serialId = serialId;
        c.name = name;
        c.petType = petType;
        c.breed = breed;
        c.birthday = birthday;
        c.avatarUrl = avatarUrl;
        c.intro = intro;
        c.hdUnlocked = false;
        return c;
    }

    /** 标记本卡 HD 已解锁（购买到账）。幂等：已解锁再调无副作用。 */
    public void markHdUnlocked() {
        this.hdUnlocked = true;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSerialId() {
        return serialId;
    }

    public String getName() {
        return name;
    }

    public String getPetType() {
        return petType;
    }

    public String getBreed() {
        return breed;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getIntro() {
        return intro;
    }

    public boolean isHdUnlocked() {
        return hdUnlocked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
