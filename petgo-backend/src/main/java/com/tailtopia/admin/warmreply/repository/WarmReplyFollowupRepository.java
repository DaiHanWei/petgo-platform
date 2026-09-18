package com.tailtopia.admin.warmreply.repository;

import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code warm_reply_followups} 仓储（V1.3.0 Story 4.3）。入队 upsert 走 {@code WarmReplyQueueService} 的原生 SQL。 */
public interface WarmReplyFollowupRepository extends JpaRepository<WarmReplyFollowup, Long> {

    long countByStatus(FollowupStatus status);

    Optional<WarmReplyFollowup> findByVirtualCommentIdAndStatus(long virtualCommentId, FollowupStatus status);

    Page<WarmReplyFollowup> findByStatusOrderByLastReplyAtDescIdDesc(FollowupStatus status, Pageable pageable);

    Page<WarmReplyFollowup> findByStatusAndHandledAtAfterOrderByHandledAtDescIdDesc(FollowupStatus status, Instant since,
            Pageable pageable);

    /** 已跟进页签计数（近 30 天，Story 4.4 AC1）。 */
    long countByStatusAndHandledAtAfter(FollowupStatus status, Instant since);

    /** 处置用行锁读取（Story 4.4：并发回复 / 已读只成功一个）。须在事务内调用。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from WarmReplyFollowup f where f.id = :id")
    Optional<WarmReplyFollowup> findForUpdateById(@Param("id") Long id);

    /** 处置后「自动选中下一条」：待跟进队列顶部一条（Story 4.4 AC4 / AC5）。 */
    Optional<WarmReplyFollowup> findFirstByStatusOrderByLastReplyAtDescIdDesc(FollowupStatus status);
}
