package com.tailtopia.growth.repository;

import com.tailtopia.growth.domain.GrowthLedgerEntry;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 成长值流水仓储（V1.4.0 ① Story 1.1）。只读查询；写入经 {@code GrowthLedgerService} 的原生 INSERT。
 */
public interface GrowthLedgerRepository extends JpaRepository<GrowthLedgerEntry, Long> {

    Optional<GrowthLedgerEntry> findByIdemKey(String idemKey);

    boolean existsByIdemKey(String idemKey);

    /** 某条原流水已被扣回的总额（正数）。 */
    @Query("select coalesce(sum(-e.delta), 0) from GrowthLedgerEntry e where e.reversesId = :originalId")
    long sumReversed(@Param("originalId") long originalId);

    /** 同账号同一里程碑 code 的正向入账条数（PRD AC15a：最多 5 次）。 */
    @Query("select count(e) from GrowthLedgerEntry e where e.userId = :userId and e.ruleCode in :ruleCodes "
            + "and e.refId = :code and e.delta > 0")
    long countPositiveByRef(@Param("userId") long userId, @Param("ruleCodes") Collection<String> ruleCodes,
            @Param("code") String code);
}
