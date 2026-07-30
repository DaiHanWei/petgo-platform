package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.domain.LedgerDirection;
import com.tailtopia.pay.domain.LedgerEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 总账仓储（Story 1.2）。<b>append-only</b>：故意 {@code extends Repository}（非 {@code JpaRepository}）
 * 只暴露 {@code save}(insert) + 查询/对账，<b>绝不暴露 delete / 批量改</b>——总账不可篡改，更正走反向分录。
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry entry);

    Optional<LedgerEntry> findById(Long id);

    /** 幂等/重放：按幂等键取任一既有分录（同键必同 entry_group）。 */
    Optional<LedgerEntry> findFirstByIdempotencyKey(String idempotencyKey);

    List<LedgerEntry> findByEntryGroup(String entryGroup);

    List<LedgerEntry> findByUserIdAndAccount(long userId, LedgerAccount account);

    /** 对账：某用户某科目某方向的金额合计（无则 0）。 */
    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e "
            + "where e.userId = :userId and e.account = :account and e.direction = :direction")
    long sumAmount(@Param("userId") long userId, @Param("account") LedgerAccount account,
            @Param("direction") LedgerDirection direction);
}
