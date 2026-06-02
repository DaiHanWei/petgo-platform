package com.petgo.notify.repository;

import com.petgo.notify.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 通知持久层（Story 6.1）。6.6 通知中心按收件人倒序拉取（游标分页）；按 token 回查/标记已读；未读回算。
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 游标分页：某用户 created_at < cursor 的通知倒序（首页传 cursor=now/MAX）。 */
    List<Notification> findByRecipientUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            long recipientUserId, Instant before, Pageable pageable);

    Optional<Notification> findByDeepLinkTokenAndRecipientUserId(String deepLinkToken, long recipientUserId);

    long countByRecipientUserIdAndReadIsFalse(long recipientUserId);

    List<Notification> findByRecipientUserIdAndReadIsFalse(long recipientUserId);
}
