package com.petgo.triage.repository;

import com.petgo.triage.domain.TriageStatus;
import com.petgo.triage.domain.TriageTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 分诊任务仓储（Story 4.1）。{@code findByStatusIn} 供启动重扫扫残留（PENDING/PROCESSING）；
 * {@code findByIdempotencyKey} 供幂等去重。
 */
public interface TriageTaskRepository extends JpaRepository<TriageTask, Long> {

    /** 启动重扫：取未完成（PENDING/PROCESSING）残留任务续跑。 */
    List<TriageTask> findByStatusIn(List<TriageStatus> statuses);

    /** 幂等去重：同 Idempotency-Key 命中既有任务。 */
    Optional<TriageTask> findByIdempotencyKey(String idempotencyKey);
}
