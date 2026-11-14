package com.tailtopia.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 宠物类型修改记录（V1.5.0 pet-species Story 1.2 · D-5，{@code pet_species_changes}）。
 *
 * <p>{@code from/to} 存 {@link PetSpecies} 名字的 String：历史记录不随枚举改名失效。
 * 写入只经 {@code profile.service.PetSpeciesChangeLog#append}。{@code reason} 可能含工单号，🔒 不进日志 / 审计 summary。
 */
@Entity
@Table(name = "pet_species_changes")
public class PetSpeciesChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Column(name = "from_species", nullable = false, length = 32)
    private String fromSpecies;

    @Column(name = "to_species", nullable = false, length = 32)
    private String toSpecies;

    @Column(name = "type_changed", nullable = false)
    private boolean typeChanged;

    @Column(name = "group_changed", nullable = false)
    private boolean groupChanged;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private PetSpeciesChangeSource source;

    @Column(name = "operator_admin_id")
    private Long operatorAdminId;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PetSpeciesChange() {
    }

    public static PetSpeciesChange of(long petProfileId, String fromSpecies, String toSpecies, boolean typeChanged,
            boolean groupChanged, PetSpeciesChangeSource source, Long operatorAdminId, String reason) {
        PetSpeciesChange c = new PetSpeciesChange();
        c.petProfileId = petProfileId;
        c.fromSpecies = fromSpecies;
        c.toSpecies = toSpecies;
        c.typeChanged = typeChanged;
        c.groupChanged = groupChanged;
        c.source = source;
        c.operatorAdminId = operatorAdminId;
        c.reason = reason;
        return c;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public String getFromSpecies() {
        return fromSpecies;
    }

    public String getToSpecies() {
        return toSpecies;
    }

    public boolean isTypeChanged() {
        return typeChanged;
    }

    public boolean isGroupChanged() {
        return groupChanged;
    }

    public PetSpeciesChangeSource getSource() {
        return source;
    }

    public Long getOperatorAdminId() {
        return operatorAdminId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
