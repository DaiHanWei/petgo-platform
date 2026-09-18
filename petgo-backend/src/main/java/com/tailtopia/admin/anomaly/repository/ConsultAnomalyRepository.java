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

    /** 待办中心角标（V1.3.0 Story 2.2）：OPEN 工单数。 */
    long countByStatus(AnomalyStatus status);

    /** 全部工单（含归档），创建时间倒序。 */
    List<ConsultAnomaly> findAllByOrderByCreatedAtDesc();

    /** 工作台分页（V1.3.0 Story 2.6）：按状态、创建时间倒序，每页 20 滚动加载。 */
    org.springframework.data.domain.Page<ConsultAnomaly> findByStatusOrderByCreatedAtDesc(AnomalyStatus status,
            org.springframework.data.domain.Pageable pageable);

    /** 处置后「下一条」：最新一条 OPEN（队列时间倒序的第一条）。 */
    Optional<ConsultAnomaly> findFirstByStatusOrderByCreatedAtDesc(AnomalyStatus status);

    /**
     * 数据库端追加一行备注（V1.3.0 Story 2.6 复审 #1）：两名运营同时加备注不会互相覆盖（读-拼-写会丢更新）。
     * 返回 0 = 工单不存在。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE consult_anomalies SET internal_note = CASE"
            + " WHEN internal_note IS NULL OR internal_note = '' THEN :line ELSE internal_note || E'\\n' || :line END,"
            + " updated_at = now() WHERE id = :id", nativeQuery = true)
    int appendNoteLine(@org.springframework.data.repository.query.Param("id") long id,
            @org.springframework.data.repository.query.Param("line") String line);
}
