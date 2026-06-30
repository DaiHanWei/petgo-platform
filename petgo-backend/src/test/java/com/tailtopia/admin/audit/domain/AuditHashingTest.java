package com.tailtopia.admin.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0：哈希链算法（AC2）——确定性、创世值、字段不可碰撞分隔、null 哨兵。 */
class AuditHashingTest {

    private static final Instant T = Instant.parse("2026-06-29T10:00:00.000001Z");

    @Test
    void genesisHashIs64Zeros() {
        assertThat(AuditHashing.GENESIS_HASH).isEqualTo("0".repeat(64));
    }

    @Test
    void rowHashIsDeterministicAnd64HexChars() {
        String h1 = AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 7L, "X", "T", "id1", "做了X", T);
        String h2 = AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 7L, "X", "T", "id1", "做了X", T);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void anyFieldChangeChangesHash() {
        String base = AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 7L, "X", "T", "id1", "s", T);
        assertThat(AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 8L, "X", "T", "id1", "s", T))
                .isNotEqualTo(base);
        assertThat(AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 7L, "Y", "T", "id1", "s", T))
                .isNotEqualTo(base);
        assertThat(AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 7L, "X", "T", "id1", "s2", T))
                .isNotEqualTo(base);
        assertThat(AuditHashing.rowHash("prev_other", 7L, "X", "T", "id1", "s", T))
                .isNotEqualTo(base);
        assertThat(AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 7L, "X", "T", "id1", "s",
                T.plusSeconds(1))).isNotEqualTo(base);
    }

    @Test
    void fieldSeparatorPreventsCollision() {
        // ("AB","C") 与 ("A","BC") 不得碰撞——拼接分隔符的意义所在。
        String ab = AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 1L, "AB", "C", "x", "s", T);
        String a = AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 1L, "A", "BC", "x", "s", T);
        assertThat(ab).isNotEqualTo(a);
    }

    @Test
    void nullFieldsDistinctFromEmptyString() {
        String withNull = AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 1L, "X", null, null, "s", T);
        String withEmpty = AuditHashing.rowHash(AuditHashing.GENESIS_HASH, 1L, "X", "", "", "s", T);
        assertThat(withNull).isNotEqualTo(withEmpty);
    }
}
