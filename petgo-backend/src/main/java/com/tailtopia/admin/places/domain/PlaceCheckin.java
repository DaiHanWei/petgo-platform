package com.tailtopia.admin.places.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 场所打卡的后台侧映射（Story 5.1；表 {@code place_checkins}）。
 *
 * <p>V1.3.2 起<b>唯一插入方是 App 侧 {@code com.tailtopia.place.domain.PlaceCheckin}</b>（打卡接口）；
 * 后台只读（统计）与合并改挂（{@code PlaceCheckinRepository.reassignPlace} 在合并事务内改 {@code place_id}）。
 * 本实体不映射 V1.3.2 新增的 {@code public_token / origin_place_id / visit_date}（均 NOT NULL）——
 * 后台生产代码<b>从不插入</b>打卡行，所以这不影响任何现有行为；「章」是聚合（AD-5），合并后自动并章。
 */
// JPA 实体名与 App 侧 com.tailtopia.place.domain.PlaceCheckin 区分（同表两套映射，2026-09-18 场所表对齐）；
// 🔴 JPQL 里要写 AdminPlaceCheckin，不是类名。
@Entity(name = "AdminPlaceCheckin")
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

    /**
     * @deprecated V1.3.2 起 {@code place_checkins} 有三列 NOT NULL 本实体未映射，用它插入会被库拒。
     *     插入一律走 App 侧 {@code com.tailtopia.place.domain.PlaceCheckin.create}。保留仅为不破坏二进制兼容。
     */
    @Deprecated
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
