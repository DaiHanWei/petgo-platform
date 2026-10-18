package com.tailtopia.collect.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 场所收藏（V1.4.0 ④ Story 2.1 · AD-7）。用户×场所唯一（{@code uq_place_collections_user_place}）。
 *
 * <p>🔴 写入不走 {@code save()}：一律 {@code insertIfAbsent}（{@code INSERT … ON CONFLICT DO NOTHING}，按影响行数判断是否真正新增）。
 * 实体只供列表读取（Story 2.2）与 {@code ddl-auto=validate}；{@code created_at} 由库默认值填。
 */
@Entity
@Table(name = "place_collections")
public class PlaceCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PlaceCollection() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
