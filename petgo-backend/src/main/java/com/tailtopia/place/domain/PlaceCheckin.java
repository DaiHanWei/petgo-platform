package com.tailtopia.place.domain;

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
 * 场所打卡（V1.3.2 batch-a Story 1.1 · FR-112 §8 · 架构 delta AD-4）。表 {@code place_checkins}。
 *
 * <p>🔴 <b>同表两套映射</b>：后台侧 {@code com.tailtopia.admin.places.domain.PlaceCheckin}
 * （JPA 实体名 {@code AdminPlaceCheckin}）只读 / 只做合并改挂；<b>唯一插入方是本实体</b>
 * （{@code PlaceCheckinService}）。两者列定义须一致（AD-4 最后一条）。
 *
 * <p>🛡 <b>没有经纬度列</b>：坐标只用于本次到场判定，不落库（AD-4）。
 *
 * <p>{@code originPlaceId} = 打卡当时的场所，<b>永不改</b>；合并场所只改 {@code placeId}
 * （后台 {@code reassignPlace}）。「章」是 (宠物, 当前 place_id) 的聚合（AD-5），不另建表。
 */
@Entity(name = "PlaceCheckin")
@Table(name = "place_checkins")
public class PlaceCheckin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    /** 当前归属场所（合并后被后台改成保留方）。 */
    @Column(name = "place_id", nullable = false)
    private Long placeId;

    @Column(name = "origin_place_id", nullable = false, updatable = false)
    private Long originPlaceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    /** WIB 自然日（D-7），只用于「每天限一次」。 */
    @Column(name = "visit_date", nullable = false, updatable = false)
    private LocalDate visitDate;

    protected PlaceCheckin() {
    }

    public static PlaceCheckin create(String publicToken, long placeId, long userId,
            Instant checkedAt, LocalDate visitDate) {
        PlaceCheckin c = new PlaceCheckin();
        c.publicToken = publicToken;
        c.placeId = placeId;
        c.originPlaceId = placeId;
        c.userId = userId;
        c.checkedAt = checkedAt;
        c.visitDate = visitDate;
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

    public String getPublicToken() {
        return publicToken;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Long getOriginPlaceId() {
        return originPlaceId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }
}
