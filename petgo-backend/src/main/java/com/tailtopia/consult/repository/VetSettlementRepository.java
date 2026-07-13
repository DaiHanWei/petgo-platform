package com.tailtopia.consult.repository;

import com.tailtopia.consult.domain.VetSettlement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 兽医月度结算仓储（Story 3.7）。唯一 {@code (vet_id, period)} → 月结生成幂等（存在即跳过）。 */
public interface VetSettlementRepository extends JpaRepository<VetSettlement, Long> {

    /** 幂等去重：该兽医该 period 是否已生成月结。 */
    boolean existsByVetIdAndPeriod(long vetId, String period);

    /** 收入页历史：某兽医全部月结按 period 倒序（近月在前）。 */
    List<VetSettlement> findByVetIdOrderByPeriodDesc(long vetId);
}
