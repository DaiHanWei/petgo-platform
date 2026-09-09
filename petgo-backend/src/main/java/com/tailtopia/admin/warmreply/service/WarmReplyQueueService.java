package com.tailtopia.admin.warmreply.service;

import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.HandledAction;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import com.tailtopia.admin.warmreply.repository.WarmReplyFollowupRepository;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.event.ContentCommentedEvent;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 暖贴回复跟进队列（V1.3.0 Story 4.3，AD-6 / D-19 / D-35）。
 * <ul>
 * <li><b>入队</b>：{@code ContentCommentedEvent}（评论转可见时发）且 被回复的一级评论作者 = VIRTUAL、回复者 = REAL →
 * {@code INSERT … ON CONFLICT (virtual_comment_id) WHERE status='PENDING' DO UPDATE}（部分唯一索引作冲突目标，并发安全，合并累加）。</li>
 * <li><b>出队</b>：{@code CommentRemovedEvent}（作者自删 / 运营下架，只对删前可见的评论发）→ 真实用户的回复：对应 PENDING 项
 * {@code pending_reply_count - 1}，归零删行；虚拟一级评论本身被删：PENDING 项整条删除；HANDLED 不受影响。</li>
 * <li>两者都由 AFTER_COMMIT 监听器调入，本类方法 {@code REQUIRES_NEW}（监听阶段无环境事务，不开新事务写入会静默丢——07 月通知事故的修法）。</li>
 * <li>D-35：虚拟账号发的<b>二级</b>被回复不入队（两级结构下该回复的 parentAuthorId 是一级作者），X-2 启用后再扩展。</li>
 * <li>日志只记 id，不记评论正文。</li>
 * </ul>
 */
@Service
public class WarmReplyQueueService {

    private static final Logger log = LoggerFactory.getLogger(WarmReplyQueueService.class);
    public static final Duration HANDLED_RETENTION = Duration.ofDays(30);

    public static final String UPSERT_SQL = """
            INSERT INTO warm_reply_followups (virtual_comment_id, post_id, virtual_user_id, pending_reply_count, last_reply_id, last_reply_at)
            VALUES (?, ?, ?, 1, ?, ?)
            ON CONFLICT (virtual_comment_id) WHERE status = 'PENDING'
            DO UPDATE SET pending_reply_count = warm_reply_followups.pending_reply_count + 1,
                          last_reply_id = EXCLUDED.last_reply_id,
                          last_reply_at = EXCLUDED.last_reply_at,
                          updated_at = now()
            """;

    private final WarmReplyFollowupRepository followups;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public WarmReplyQueueService(WarmReplyFollowupRepository followups, UserRepository users, JdbcTemplate jdbc) {
        this.followups = followups;
        this.users = users;
        this.jdbc = jdbc;
    }

    /**
     * 入队判定 + upsert（AC2）。返回是否入队（供测试 / 日志）。
     * 不入队：一级评论（parentAuthorId / parentCommentId 为空）、回复者非真实用户（{@code isSyntheticAccount}）、被回复者非 VIRTUAL。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueueIfWarmReply(ContentCommentedEvent e) {
        if (e.parentAuthorId() == null || e.parentCommentId() == null) {
            return false;
        }
        Optional<User> parent = users.findById(e.parentAuthorId());
        if (parent.isEmpty() || parent.get().getAccountType() != AccountType.VIRTUAL || !isRealUser(e.commenterId())) {
            return false;
        }
        jdbc.update(UPSERT_SQL, e.parentCommentId(), e.postId(), e.parentAuthorId(), e.commentId(),
                Timestamp.from(e.createdAt() == null ? Instant.now() : e.createdAt()));
        log.info("warm-reply followup enqueued virtualCommentId={} replyId={}", e.parentCommentId(), e.commentId());
        return true;
    }

    /**
     * 出队（AC3）：只处理「真实用户的可见回复」被删 / 下架且其一级存在 PENDING 项的情况；减一，归零删行。返回是否有变更。
     * 回复者非真实用户（虚拟号回虚拟号）当初没入队，这里也不减（复审 #1）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean onReplyRemoved(long commentId, Long parentId, long authorId) {
        if (parentId == null || !isRealUser(authorId)) {
            return false;
        }
        Optional<WarmReplyFollowup> pending = followups.findByVirtualCommentIdAndStatus(parentId, FollowupStatus.PENDING);
        if (pending.isEmpty()) {
            return false;
        }
        int updated = jdbc.update("UPDATE warm_reply_followups SET pending_reply_count = pending_reply_count - 1, updated_at = now()"
                + " WHERE id = ? AND status = 'PENDING' AND pending_reply_count > 0", pending.get().getId());
        if (updated == 0) {
            return false;
        }
        int deleted = jdbc.update("DELETE FROM warm_reply_followups WHERE id = ? AND status = 'PENDING' AND pending_reply_count = 0",
                pending.get().getId());
        log.info("warm-reply followup dequeued virtualCommentId={} replyId={} removed={}", parentId, commentId, deleted > 0);
        return true;
    }

    /**
     * 一级评论本身被删 / 下架（复审 #3）：它若是有 PENDING 项的虚拟暖评，项随之删除——否则成孤儿（角标一直计数、
     * 4-4 以它为 parent 回复会 404）。HANDLED 历史项保留。返回是否删了行。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean onTopLevelRemoved(long commentId) {
        int deleted = jdbc.update("DELETE FROM warm_reply_followups WHERE virtual_comment_id = ? AND status = 'PENDING'", commentId);
        if (deleted > 0) {
            log.info("warm-reply followup dropped: virtual comment removed virtualCommentId={}", commentId);
        }
        return deleted > 0;
    }

    /** 「真实用户」口径走 Story 3.1 的唯一出口 {@link User#isSyntheticAccount()}（VIRTUAL 或 role=ADMIN 都不算真实用户，AD-8）。 */
    private boolean isRealUser(long userId) {
        return users.findById(userId).map(u -> !u.isSyntheticAccount()).orElse(false);
    }

    /** 待跟进数（AC5，供待办中心角标聚合）。 */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return followups.countByStatus(FollowupStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Page<WarmReplyFollowup> listPending(int page, int size) {
        return followups.findByStatusOrderByLastReplyAtDescIdDesc(FollowupStatus.PENDING, PageRequest.of(Math.max(page, 0), size));
    }

    /** 已处理（近 30 天）。 */
    @Transactional(readOnly = true)
    public Page<WarmReplyFollowup> listHandled(int page, int size) {
        return followups.findByStatusAndHandledAtAfterOrderByHandledAtDescIdDesc(FollowupStatus.HANDLED,
                Instant.now().minus(HANDLED_RETENTION), PageRequest.of(Math.max(page, 0), size));
    }

    /** 只读不回（4-4 用）：PENDING → HANDLED/READ；已处理幂等返回 false。 */
    @Transactional
    public boolean markRead(long followupId, long adminAccountId) {
        return followups.findById(followupId).map(f -> f.markHandled(adminAccountId, HandledAction.READ, null)).orElse(false);
    }

    /** 已回复（4-4 用）：PENDING → HANDLED/REPLIED，带运营以虚拟身份发出的回复 id。 */
    @Transactional
    public boolean markReplied(long followupId, long adminAccountId, long handledCommentId) {
        return followups.findById(followupId).map(f -> f.markHandled(adminAccountId, HandledAction.REPLIED, handledCommentId))
                .orElse(false);
    }
}
