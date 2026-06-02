package com.petgo.consult.repository;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.SessionStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 咨询会话持久层（Story 5.3）。模块边界：仅 {@code consult.service} 直接访问。
 */
public interface ConsultSessionRepository extends JpaRepository<ConsultSession, Long> {

    /** 某用户处于占用态（WAITING/IN_PROGRESS/PENDING_CLOSE）的会话（「同时仅 1 个」约束用）。 */
    Optional<ConsultSession> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
            long userId, Collection<SessionStatus> statuses);
}
