package com.tailtopia.admin.anomaly.repository;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.domain.ConsultAnomaly;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 异常工单仓储（Story 5.1）。无删除方法（AC6 工单不可删）。 */
public interface ConsultAnomalyRepository extends JpaRepository<ConsultAnomaly, Long> {

    boolean existsBySessionId(long sessionId);

    Optional<ConsultAnomaly> findBySessionId(long sessionId);

    /** 按状态筛选（待处理 OPEN / 已归档 RESOLVED），创建时间倒序。 */
    List<ConsultAnomaly> findByStatusOrderByCreatedAtDesc(AnomalyStatus status);

    /** 全部工单（含归档），创建时间倒序。 */
    List<ConsultAnomaly> findAllByOrderByCreatedAtDesc();
}
