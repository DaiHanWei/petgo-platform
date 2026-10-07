package com.tailtopia.place.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 打卡 ↔ 宠物（V1.3.2 batch-a Story 1.1 · AD-4）。表 {@code place_checkin_pets}。
 *
 * <p>多对多结构，界面按单宠（本版本 {@code pet_profiles} 仍一账号一宠）。
 * {@code originPlaceId} / {@code visitDate} 冗余自打卡行，<b>只为</b>
 * {@code UNIQUE(pet_profile_id, origin_place_id, visit_date)} 做并发兜底 ——
 * 业务上的「今日已打卡」按当前 {@code place_id} 判（合并进来的原场所也算），见 {@code PlaceCheckinService}。
 */
@Entity(name = "PlaceCheckinPet")
@Table(name = "place_checkin_pets")
public class PlaceCheckinPet {

    @EmbeddedId
    private Key id;

    @Column(name = "origin_place_id", nullable = false, updatable = false)
    private Long originPlaceId;

    @Column(name = "visit_date", nullable = false, updatable = false)
    private LocalDate visitDate;

    protected PlaceCheckinPet() {
    }

    public static PlaceCheckinPet of(PlaceCheckin checkin, long petProfileId) {
        PlaceCheckinPet p = new PlaceCheckinPet();
        p.id = new Key(checkin.getId(), petProfileId);
        p.originPlaceId = checkin.getOriginPlaceId();
        p.visitDate = checkin.getVisitDate();
        return p;
    }

    public Long getCheckinId() {
        return id.checkinId;
    }

    public Long getPetProfileId() {
        return id.petProfileId;
    }

    public Long getOriginPlaceId() {
        return originPlaceId;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    /** 复合主键 (checkin_id, pet_profile_id)。 */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "checkin_id", nullable = false, updatable = false)
        private Long checkinId;

        @Column(name = "pet_profile_id", nullable = false, updatable = false)
        private Long petProfileId;

        protected Key() {
        }

        public Key(Long checkinId, Long petProfileId) {
            this.checkinId = checkinId;
            this.petProfileId = petProfileId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(checkinId, k.checkinId)
                    && Objects.equals(petProfileId, k.petProfileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(checkinId, petProfileId);
        }
    }
}
