package com.petgo.notify.repository;

import com.petgo.notify.domain.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 通知持久层（Story 6.1）。6.6 通知中心按收件人倒序拉取；按 token 回查深链目标。
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(long recipientUserId, Pageable pageable);

    Optional<Notification> findByDeepLinkTokenAndRecipientUserId(String deepLinkToken, long recipientUserId);

    long countByRecipientUserIdAndReadIsFalse(long recipientUserId);
}
