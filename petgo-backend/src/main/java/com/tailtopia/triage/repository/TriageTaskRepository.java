package com.tailtopia.triage.repository;

import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
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

    /** 用户 AI 问诊历史（Story 5.8）：本人已完成（DONE）任务，按时间倒序。 */
    List<TriageTask> findByUserIdAndStatusOrderByCreatedAtDesc(long userId, TriageStatus status);

    /** 幂等去重：同 Idempotency-Key 命中既有任务。 */
    Optional<TriageTask> findByIdempotencyKey(String idempotencyKey);

    /** Story 7.3：注销级联删除某用户全部分诊（先收集私密图 key 再删表；纯个人 AI 健康记录，物理删除）。 */
    List<TriageTask> findByUserId(long userId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByUserId(long userId);
}
