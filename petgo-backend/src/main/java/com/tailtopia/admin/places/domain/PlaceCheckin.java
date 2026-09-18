package com.tailtopia.admin.places.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 场所打卡（Story 5.1；表 {@code place_checkins}，护照章）。合并场所时的归并由 App 分支监听 {@code PlaceMergedEvent} 实现（契约 X-1）。 */
@Entity
@Table(name = "place_checkins")
public class PlaceCheckin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private Long placeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    protected PlaceCheckin() {
    }

    public static PlaceCheckin create(long placeId, long userId) {
        PlaceCheckin c = new PlaceCheckin();
        c.placeId = placeId;
        c.userId = userId;
        return c;
    }

    @PrePersist
    void onCreate() {
        if (checkedAt == null) {
            checkedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
