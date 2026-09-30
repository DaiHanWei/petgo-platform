package com.tailtopia.passport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 护照快照（V1.3.2 Story 3.4 · 表 {@code passport_snapshots}）：发起购买时冻结的一版护照。
 *
 * <p>{@link #paidAt} 只由发放口 {@code PassportSnapshotGranter} 置位（JDBC）。版本判定按 {@link #frozenStamps()} 里的
 * placeId 现算 hash；{@link #placeSetHash} 只作记录。
 */
@Entity(name = "PassportSnapshot")
@Table(name = "passport_snapshots")
public class PassportSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stamps", nullable = false, updatable = false)
    private List<Map<String, Object>> stamps = new ArrayList<>();

    @Column(name = "stamp_count", nullable = false, updatable = false)
    private int stampCount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "place_set_hash", nullable = false, updatable = false, length = 64)
    private String placeSetHash;

    @Column(name = "paid_at", insertable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PassportSnapshot() {
    }

    public static PassportSnapshot freeze(String publicToken, long petProfileId, List<FrozenStamp> stamps,
            String placeSetHash, Instant now) {
        PassportSnapshot s = new PassportSnapshot();
        s.publicToken = publicToken;
        s.petProfileId = petProfileId;
        s.stamps = new ArrayList<>(stamps.stream().map(FrozenStamp::toJson).toList());
        s.stampCount = stamps.size();
        s.placeSetHash = placeSetHash;
        s.createdAt = now;
        return s;
    }

    public List<FrozenStamp> frozenStamps() {
        return stamps.stream().map(FrozenStamp::fromJson).toList();
    }

    public List<Long> placeIds() {
        return frozenStamps().stream().map(FrozenStamp::placeId).toList();
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public int getStampCount() {
        return stampCount;
    }

    public String getPlaceSetHash() {
        return placeSetHash;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
