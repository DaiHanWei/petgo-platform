package com.tailtopia.admin.warmreply.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.HandledAction;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import com.tailtopia.admin.warmreply.repository.WarmReplyFollowupRepository;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.ratelimit.IdempotencyService;
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
 * <li>Story 4.4（A9 工作台）：{@link #reply} 以被回复的虚拟账号身份发二级评论（{@code CommentService.createReply}，与 App 同链路 D-4，
 * 身份从跟进行取、不接收参数）→ 项置 HANDLED/REPLIED + 审计 {@code COMMENT_VIRTUAL_POST}；{@link #markRead} → HANDLED/READ + 审计 {@code WARM_REPLY_READ}。</li>
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

    /** 回复正文上限（与 App 端评论一致）。 */
    public static final int BODY_MAX = 200;
    /** 审计摘要只记正文前 50 字（同 4-2 规则）。 */
    static final int AUDIT_PREVIEW = 50;

    private final WarmReplyFollowupRepository followups;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final CommentRepository comments;
    private final ContentPostRepository posts;
    private final CommentService commentService;
    private final IdempotencyService idempotency;
    private final AdminAuditService audit;

    public WarmReplyQueueService(WarmReplyFollowupRepository followups, UserRepository users, JdbcTemplate jdbc,
            CommentRepository comments, ContentPostRepository posts, CommentService commentService,
            IdempotencyService idempotency, AdminAuditService audit) {
        this.followups = followups;
        this.users = users;
        this.jdbc = jdbc;
        this.comments = comments;
        this.posts = posts;
        this.commentService = commentService;
        this.idempotency = idempotency;
        this.audit = audit;
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

    /** 已跟进（近 30 天）计数：页签计数与列表同口径。 */
    @Transactional(readOnly = true)
    public long handledCount() {
        return followups.countByStatusAndHandledAtAfter(FollowupStatus.HANDLED, Instant.now().minus(HANDLED_RETENTION));
    }

    /** 处置后自动选中的下一条（待跟进队列顶部；没有则 null）。 */
    @Transactional(readOnly = true)
    public Long nextPendingId() {
        return followups.findFirstByStatusOrderByLastReplyAtDescIdDesc(FollowupStatus.PENDING).map(WarmReplyFollowup::getId).orElse(null);
    }

    /**
     * 标记已读（不回复）（Story 4.4 AC5）：PENDING → HANDLED/READ + 审计 {@code WARM_REPLY_READ}；不存在 404，已处理 → 422
     * （{@code admin.err.warmReply.notPending}）。无二次确认。
     */
    @Transactional
    public void markRead(long followupId, long adminAccountId) {
        WarmReplyFollowup f = requirePending(followupId);
        f.markHandled(adminAccountId, HandledAction.READ, null);
        audit.record(adminAccountId, AuditActions.WARM_REPLY_READ, "WARM_REPLY_FOLLOWUP", String.valueOf(f.getId()),
                "virtualCommentId=" + f.getVirtualCommentId() + ", postId=" + f.getPostId() + ", pendingReplies=" + f.getPendingReplyCount());
        log.info("warm-reply followup read followupId={} by admin={}", f.getId(), adminAccountId);
    }

    /** 以虚拟身份回复的结果：评论 id；{@code replayed} = 幂等重放（同 key 已发过）。 */
    public record ReplyResult(long commentId, boolean replayed) {
    }

    /**
     * 以被回复的虚拟账号身份回复（Story 4.4 AC4，D-4）：身份锁定为 {@code followup.virtual_user_id}，父评论 = {@code virtual_comment_id}
     * （二级回复，App 端 {@code POST /api/v1/comments/{parentId}/replies} 同一方法 {@code createReply}：L1 黑名单 / UNDER_REVIEW / 审核事件全部继承）。
     * 顺序：幂等短路（key 按项加作用域）→ 项须 PENDING（行锁；404 / 422）→ 正文 1～200 → 帖子与虚拟评论未删（422
     * {@code admin.err.warmReply.contentDeleted}，此时只允许「标记已读」）→ 帖子 PUBLISHED（422 {@code admin.err.virtualComment.postHidden}）→ 发布 → 项置 HANDLED/REPLIED → 审计 {@code COMMENT_VIRTUAL_POST}（正文只记前 50 字）→ 幂等记录（提交后写）。
     */
    @Transactional
    public ReplyResult reply(long followupId, String body, String idempotencyKey, long adminAccountId) {
        // 幂等键加作用域：全局 idem: 命名空间里别处的 key 不能拿来短路本项（复审 #7）
        String scopedKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : "wr:" + followupId + ":" + idempotencyKey;
        Optional<Long> replay = idempotency.findResourceId(scopedKey);
        if (replay.isPresent()) {
            return new ReplyResult(replay.get(), true);
        }
        WarmReplyFollowup f = requirePending(followupId);
        String text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > BODY_MAX) {
            throw AppException.validation("回复内容需为 1～200 字").code("admin.err.warmReply.bodyInvalid");
        }
        if (isContentDeleted(f)) {
            throw AppException.validation("帖子或暖评已删除，只能标记已读").code("admin.err.warmReply.contentDeleted");
        }
        if (!posts.findById(f.getPostId()).map(p -> p.getStatus() == PostStatus.PUBLISHED).orElse(false)) {
            // 帖子未删但不可见（下架 / 挂审）：createReply 会 404 中文原文，这里先按后台码 422（复审 #5）
            throw AppException.validation("帖子不可见，不能评论").code("admin.err.virtualComment.postHidden");
        }
        CommentResponse created;
        try {
            created = commentService.createReply(f.getVirtualCommentId(), f.getVirtualUserId(), text);
        } catch (AppException e) {
            if (ErrorTypes.COMMENT_BLOCKED.equals(e.getType())) {
                throw e.code("admin.v130.comments.virtual.err.blocked"); // L1 命中：后台三语按码渲染
            }
            throw e;
        }
        long commentId = created.id();
        f.markHandled(adminAccountId, HandledAction.REPLIED, commentId);
        audit.record(adminAccountId, AuditActions.COMMENT_VIRTUAL_POST, "COMMENT", String.valueOf(commentId),
                "followupId=" + f.getId() + ", postId=" + f.getPostId() + ", virtualUserId=" + f.getVirtualUserId()
                        + ", parentCommentId=" + f.getVirtualCommentId() + ", body=" + VirtualCommentService.summarize(text, AUDIT_PREVIEW));
        idempotency.store(scopedKey, commentId);
        log.info("warm-reply replied followupId={} commentId={} by admin={}", f.getId(), commentId, adminAccountId);
        return new ReplyResult(commentId, false);
    }

    /** 帖子或那条虚拟一级评论已软删（AC6 边界）：右栏「内容已删除」占位，只允许标记已读。 */
    @Transactional(readOnly = true)
    public boolean isContentDeleted(WarmReplyFollowup f) {
        boolean postGone = posts.findById(f.getPostId()).map(p -> p.getDeletedAt() != null).orElse(true);
        boolean commentGone = comments.findById(f.getVirtualCommentId()).map(Comment::isDeleted).orElse(true);
        return postGone || commentGone;
    }

    /** 行锁取 PENDING 项（复审 #6：两名运营同时回复同一项只能成功一个；后到的在锁释放后看到 HANDLED → 422）。 */
    private WarmReplyFollowup requirePending(long followupId) {
        WarmReplyFollowup f = followups.findForUpdateById(followupId)
                .orElseThrow(() -> AppException.notFound("跟进项不存在").code("admin.err.warmReply.notFound"));
        if (f.getStatus() != FollowupStatus.PENDING) {
            throw AppException.validation("该项已跟进，不能再操作").code("admin.err.warmReply.notPending");
        }
        return f;
    }
}
