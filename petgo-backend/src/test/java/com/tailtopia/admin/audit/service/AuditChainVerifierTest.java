package com.tailtopia.admin.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.domain.AuditHashing;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：链完整性校验（AC4）——完整链通过；改内容 / 断链接 / 改 rowHash 均被检出。 */
class AuditChainVerifierTest {

    private static final Instant T = Instant.parse("2026-06-29T10:00:00.000001Z");

    /** 用真实哈希算法造一条 n 行的合法链（id=1..n）。 */
    private List<AdminAuditLog> validChain(int n) {
        List<AdminAuditLog> rows = new ArrayList<>();
        String prev = AuditHashing.GENESIS_HASH;
        for (int i = 1; i <= n; i++) {
            Instant ts = T.plusSeconds(i);
            String rowHash = AuditHashing.rowHash(prev, (long) i, "ACTION_" + i, "T", "id" + i, "s" + i, ts);
            AdminAuditLog row = AdminAuditLog.create((long) i, "ACTION_" + i, "T", "id" + i, "s" + i,
                    ts, prev, rowHash);
            setId(row, (long) i);
            rows.add(row);
            prev = rowHash;
        }
        return rows;
    }

    private static void setId(AdminAuditLog row, Long id) {
        try {
            Field f = AdminAuditLog.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** 改某行的一个字段但不重算 rowHash（模拟篡改）。 */
    private static void tamperSummary(AdminAuditLog row, String newSummary) {
        try {
            Field f = AdminAuditLog.class.getDeclaredField("summary");
            f.setAccessible(true);
            f.set(row, newSummary);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void tamperRowHash(AdminAuditLog row, String newHash) {
        try {
            Field f = AdminAuditLog.class.getDeclaredField("rowHash");
            f.setAccessible(true);
            f.set(row, newHash);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void intactChainVerifies() {
        AuditChainVerifier.Result r = AuditChainVerifier.verify(validChain(5));
        assertThat(r.intact()).isTrue();
        assertThat(r.verifiedCount()).isEqualTo(5);
        assertThat(r.firstBrokenId()).isNull();
    }

    @Test
    void emptyChainVerifies() {
        assertThat(AuditChainVerifier.verify(List.of()).intact()).isTrue();
    }

    @Test
    void tamperedContentDetected() {
        List<AdminAuditLog> chain = validChain(5);
        tamperSummary(chain.get(2), "被偷偷改过");
        AuditChainVerifier.Result r = AuditChainVerifier.verify(chain);
        assertThat(r.intact()).isFalse();
        assertThat(r.firstBrokenId()).isEqualTo(3L);
    }

    @Test
    void tamperedRowHashDetected() {
        List<AdminAuditLog> chain = validChain(3);
        tamperRowHash(chain.get(1), "deadbeef".repeat(8));
        AuditChainVerifier.Result r = AuditChainVerifier.verify(chain);
        assertThat(r.intact()).isFalse();
        // 第2行 rowHash 被改 → 它自身重算不符即检出（id=2）。
        assertThat(r.firstBrokenId()).isEqualTo(2L);
    }
}
