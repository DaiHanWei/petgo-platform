package com.tailtopia.consult.repository;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.RatingPromptState;
import com.tailtopia.consult.domain.SessionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 咨询会话持久层（Story 5.3）。模块边界：仅 {@code consult.service} 直接访问。
 * Story 5.2：加 {@link JpaSpecificationExecutor} 供后台会话元数据多维动态查询（经 consult 只读 service）。
 */
public interface ConsultSessionRepository extends JpaRepository<ConsultSession, Long>,
        JpaSpecificationExecutor<ConsultSession> {

    /** 某用户处于占用态（WAITING/IN_PROGRESS/PENDING_CLOSE）的会话（「同时仅 1 个」约束用）。 */
    Optional<ConsultSession> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
            long userId, Collection<SessionStatus> statuses);

    /** 30min 评分门超时扫描（Story 5.6）：PENDING_CLOSE 且计时早于阈值。 */
    List<ConsultSession> findByStatusAndPendingCloseStartedAtBefore(SessionStatus status, Instant threshold);

    /** 某用户待补弹评分的已关闭会话（Story 5.6，进问诊页补弹一次）。 */
    Optional<ConsultSession> findFirstByUserIdAndStatusAndRatingPromptState(
            long userId, SessionStatus status, RatingPromptState ratingPromptState);

    /** 某兽医处于活跃态（IN_PROGRESS/PENDING_CLOSE）的会话（Story 5.7 封禁批量中断；工作台「进行中」Tab）。 */
    List<ConsultSession> findByVetIdAndStatusIn(long vetId, Collection<SessionStatus> statuses);

    /** 某兽医终态（CLOSED/INTERRUPTED）会话按时间倒序（工作台「历史」Tab）。 */
    List<ConsultSession> findByVetIdAndStatusInOrderByCreatedAtDesc(
            long vetId, Collection<SessionStatus> statuses);

    /** 用户兽医问诊历史（Story 5.8）：终态（CLOSED/INTERRUPTED）会话按时间倒序（CANCELLED 不入历史）。 */
    List<ConsultSession> findByUserIdAndStatusInOrderByCreatedAtDesc(
            long userId, Collection<SessionStatus> statuses);

    /** Story 7.3：注销匿名化——某用户全部会话（剥 user_id，保留症状/评级供运营，决策 D1）。 */
    List<ConsultSession> findByUserId(long userId);
}
