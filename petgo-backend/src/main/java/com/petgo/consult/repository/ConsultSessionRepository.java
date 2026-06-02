package com.petgo.consult.repository;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.RatingPromptState;
import com.petgo.consult.domain.SessionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 咨询会话持久层（Story 5.3）。模块边界：仅 {@code consult.service} 直接访问。
 */
public interface ConsultSessionRepository extends JpaRepository<ConsultSession, Long> {

    /** 某用户处于占用态（WAITING/IN_PROGRESS/PENDING_CLOSE）的会话（「同时仅 1 个」约束用）。 */
    Optional<ConsultSession> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
            long userId, Collection<SessionStatus> statuses);

    /** 30min 评分门超时扫描（Story 5.6）：PENDING_CLOSE 且计时早于阈值。 */
    List<ConsultSession> findByStatusAndPendingCloseStartedAtBefore(SessionStatus status, Instant threshold);

    /** 某用户待补弹评分的已关闭会话（Story 5.6，进问诊页补弹一次）。 */
    Optional<ConsultSession> findFirstByUserIdAndStatusAndRatingPromptState(
            long userId, SessionStatus status, RatingPromptState ratingPromptState);

    /** 某兽医处于活跃态（IN_PROGRESS/PENDING_CLOSE）的会话（Story 5.7 封禁批量中断）。 */
    List<ConsultSession> findByVetIdAndStatusIn(long vetId, Collection<SessionStatus> statuses);
}
