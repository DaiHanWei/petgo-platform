package com.tailtopia.tailsonality.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 主人四字母类型（V1.3.2 Story 2.5 · 表 {@code tailsonality_owner_types}）。账号级、一人一行。
 *
 * <p>只经 {@code TailsonalityOwnerTypeRepository.upsert}（原生 insert-on-conflict）写入，本实体只读映射。
 */
@Entity(name = "TailsonalityOwnerType")
@Table(name = "tailsonality_owner_types")
public class TailsonalityOwnerType {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_code", nullable = false, length = 4)
    private String typeCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TailsonalityOwnerType() {
    }

    public Long getUserId() {
        return userId;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
