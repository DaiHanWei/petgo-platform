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

    /** 性别快照 MALE/FEMALE/UNKNOWN（与 pet_type 同为字符串快照）。旧卡（新规则前建）为 null。 */
    @Column(name = "gender", length = 8)
    private String gender;

    /** 身份码 TT+DDMMYY+SP+XXXX（《宠物身份码护照编码规则》）。旧卡 null=保留旧号，前端旧拼号展示。 */
    @Column(name = "card_no", length = 14)
    private String cardNo;

    /** 护照号 TT+SP+P+YY+XXXXX。旧卡 null 同上。仅展示，不作分享/深链/资源定位键。 */
    @Column(name = "passport_no", length = 12)
    private String passportNo;

    // ---- 卡面趣味字段快照（bug 20260729-409：Edit Info 与卡面字段对齐）----
    // 可空：null = 前端渲染趣味默认（BANDUNG / JL. MELATI... / CHIEF HAPPINESS OFFICER / LAJANG），
    // 旧卡与未填写的新卡展示零变化。

    @Column(name = "birth_city", length = 40)
    private String birthCity;

    @Column(name = "address", length = 80)
    private String address;

    @Column(name = "occupation", length = 40)
    private String occupation;

    @Column(name = "marital_status", length = 24)
    private String maritalStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdCard() {
    }

    /**
     * 建卡快照（独立建卡器传入的字段 + 分配好的 legacy serial + 新规则两号）。初始未解锁。
     * gender/cardNo/passportNo 为 spec-ktp-pet-idcode-numbering 新增：新卡三者皆非空，旧卡保持 null。
     * birthCity/address/occupation/maritalStatus（bug 20260729-409）可空：null → 前端趣味默认。
     */
    public static IdCard snapshot(long userId, long serialId, String name, String petType,
            String breed, LocalDate birthday, String avatarUrl, String intro,
            String gender, String cardNo, String passportNo,
            String birthCity, String address, String occupation, String maritalStatus) {
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
        c.gender = gender;
        c.cardNo = cardNo;
        c.passportNo = passportNo;
        c.birthCity = birthCity;
        c.address = address;
        c.occupation = occupation;
        c.maritalStatus = maritalStatus;
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

    public String getGender() {
        return gender;
    }

    public String getCardNo() {
        return cardNo;
    }

    public String getPassportNo() {
        return passportNo;
    }

    public String getBirthCity() {
        return birthCity;
    }

    public String getAddress() {
        return address;
    }

    public String getOccupation() {
        return occupation;
    }

    public String getMaritalStatus() {
        return maritalStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
