package com.tailtopia.admin.audit.service;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.domain.AuditHashing;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计链完整性校验器（Story 1.3，AC4）。逐行重算 {@code rowHash} 并核对链接（{@code prevHash} 须等于
 * 前一行 {@code rowHash}），任一不符即判定链被篡改，定位首个断裂行——供测试与后续运维核验。
 *
 * <p>纯算法 {@link #verify(List)} 不依赖 Spring/DB，便于单测；{@link #verifyAll()} 拉全量校验。
 */
@Service
public class AuditChainVerifier {

    private final AdminAuditLogRepository repository;

    public AuditChainVerifier(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    /** 校验整条链（按 id 升序拉全量）。 */
    @Transactional(readOnly = true)
    public Result verifyAll() {
        return verify(repository.findAllByOrderByIdAsc());
    }

    /**
     * 校验给定（按 id 升序）的审计行序列。
     *
     * @return {@link Result#intact()} 为 true 表示完整；否则 {@link Result#firstBrokenId()} 为首个断裂行 id。
     */
    public static Result verify(List<AdminAuditLog> rowsAscById) {
        String expectedPrev = AuditHashing.GENESIS_HASH;
        for (AdminAuditLog row : rowsAscById) {
            // ① 链接：本行 prevHash 必须等于前一行 rowHash（首行为创世值）。
            if (!expectedPrev.equals(row.getPrevHash())) {
                return Result.broken(row.getId());
            }
            // ② 内容：本行 rowHash 必须等于按其字段重算的结果（任一字段被改即检出）。
            if (!AuditHashing.rowHash(row).equals(row.getRowHash())) {
                return Result.broken(row.getId());
            }
            expectedPrev = row.getRowHash();
        }
        return Result.intact(rowsAscById.size());
    }

    /** 校验结果。 */
    public record Result(boolean intact, long verifiedCount, Long firstBrokenId) {
        static Result intact(long count) {
            return new Result(true, count, null);
        }

        static Result broken(Long brokenId) {
            return new Result(false, 0, brokenId);
        }
    }
}
