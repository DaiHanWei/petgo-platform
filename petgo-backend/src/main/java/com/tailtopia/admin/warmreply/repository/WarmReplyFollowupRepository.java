package com.tailtopia.admin.warmreply.repository;

import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code warm_reply_followups} 仓储（V1.3.0 Story 4.3）。入队 upsert 走 {@code WarmReplyQueueService} 的原生 SQL。 */
public interface WarmReplyFollowupRepository extends JpaRepository<WarmReplyFollowup, Long> {

    long countByStatus(FollowupStatus status);

    Optional<WarmReplyFollowup> findByVirtualCommentIdAndStatus(long virtualCommentId, FollowupStatus status);

    Page<WarmReplyFollowup> findByStatusOrderByLastReplyAtDescIdDesc(FollowupStatus status, Pageable pageable);

    Page<WarmReplyFollowup> findByStatusAndHandledAtAfterOrderByHandledAtDescIdDesc(FollowupStatus status, Instant since,
            Pageable pageable);
}
