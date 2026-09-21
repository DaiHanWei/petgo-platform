package com.tailtopia.triage.repository;

import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * 红色超额监控（Story 9.6 · AB-7A）：按用户聚合 RED 分诊计数，计数降序。纯观测（无自动拦截）。
     */
    @Query("select t.userId as userId, count(t) as redCount from TriageTask t "
            + "where t.dangerLevel = com.tailtopia.triage.domain.DangerLevel.RED "
            + "group by t.userId order by count(t) desc")
    java.util.List<RedCountProjection> redCountsByUser();

    /**
     * 某用户的 RED 分诊历史（V1.3.0 Story 8.5 · AC4：红色超额抽屉），近的在前。
     *
     * <p>⚠️ 上层只取 id / 状态 / 时间 —— 症状文本与解析结果是**健康数据**，不进后台展示
     * （见 {@code RedOverageRow} 与 {@code RedTaskRow} 的说明）。这里返回实体是因为
     * 投影再加一个接口不划算，但**取字段时必须克制**。
     */
    @Query("select t from TriageTask t "
            + "where t.userId = :userId "
            + "and t.dangerLevel = com.tailtopia.triage.domain.DangerLevel.RED "
            + "order by t.createdAt desc, t.id desc")
    java.util.List<com.tailtopia.triage.domain.TriageTask> findRedByUser(
            @org.springframework.data.repository.query.Param("userId") long userId);

}
