package com.tailtopia.passport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 宠物护照（V1.3.2 batch-a Story 1.2 · FR-120 · 架构 delta AD-6）。表 {@code pet_passports}。
 *
 * <p>🔴 <b>类名禁用 {@code PassportCard}</b>：那是 KTP 体系里的「护照卡种」，与本实体无关（架构 delta 术语段）。
 *
 * <p>只经 {@code PetPassportRepository.insertIfAbsent}（原生 insert-on-conflict）写入，本实体只读映射。
 */
@Entity(name = "PetPassport")
@Table(name = "pet_passports")
public class PetPassport {

    @Id
    private Long id;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @Column(name = "passport_no", nullable = false, updatable = false, length = 12)
    private String passportNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 8)
    private PassportSource source;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    protected PetPassport() {
    }

    public Long getId() {
        return id;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public String getPassportNo() {
        return passportNo;
    }

    public PassportSource getSource() {
        return source;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
