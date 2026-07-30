package com.tailtopia.content.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.event.CommentSubmittedEvent;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论写入/删除（Story 3.5，FR-24）+ 评论审核（内容审核补充规范 story 3）。
 *
 * <p><b>创建同步过滤（§5.2）</b>：{@code createTopLevel/createReply} 在 {@code requireVisible} 后走
 * {@link ContentModerationService#moderateComment(String)}：L1/HIGH_RISK → 即时 422、从未落库/不发事件/不入队；
 * DEGRADED → 落 {@code UNDER_REVIEW} + 入人工队列 + 不发事件（G4）；PASS → 落 {@code VISIBLE} + 发事件（现网行为）。
 *
 * <p><b>队列处置门面（§5.3/§5.4）</b>：admin.moderation 经本类 {@code approveComment/rejectComment/
 * takedownComment/restoreComment/findModerationSummary} 变更评论审核态（admin 不直读 comments repo）。
 */
@Service
public class CommentService {

    /** L1 命中文案（仅后端日志/排查；前端按 error type 映射单一 toast，不展示原文）。 */
    static final String L1_BLOCKED_MESSAGE = "内容包含不当词汇，请修改后重试";
    /** 风险 ≥0.8 文案（同上，不外泄给前端展示）。 */
    static final String HIGH_RISK_MESSAGE = "评论不符合友好社区规定，请修改后重试";

    private final CommentRepository comments;
    private final ContentPostRepository posts;
    private final AccountQueryService accountQueryService;
    private final ApplicationEventPublisher events;
    private final ContentModerationService moderation;
    private final ManualReviewGate reviewGate;

    public CommentService(CommentRepository comments, ContentPostRepository posts,
            AccountQueryService accountQueryService, ApplicationEventPublisher events,
            ContentModerationService moderation, ManualReviewGate reviewGate) {
        this.comments = comments;
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.events = events;
        this.moderation = moderation;
        this.reviewGate = reviewGate;
    }

    /**
     * 发表一级评论（异步无感知审核）。L1 硬黑名单同步即时 422（明显违规秒拒、给反馈、不落库）；否则落
     * {@code UNDER_REVIEW} 秒回（评论人自己即可见），阿里云评分交异步——通过才转 VISIBLE + 通知楼主。
     */
    @Transactional
    public CommentResponse createTopLevel(long postId, long authorId, String body) {
        requireVisible(postId);
        if (moderation.isL1Blocked(body)) {
            throw AppException.commentBlocked(L1_BLOCKED_MESSAGE);
        }
        Comment saved = comments.save(Comment.createUnderReview(postId, null, authorId, body));
        events.publishEvent(new CommentSubmittedEvent(saved.getId(), body, saved.getContentVersion()));
        return CommentResponse.topLevel(saved, authorView(authorId), 0, List.of());
    }

    /** 回复（二级）。回复二级评论时归并到其一级父（两级约束，绝不三级）。含同步审核过滤。 */
    @Transactional
    public CommentResponse createReply(long parentId, long authorId, String body) {
        Comment parent = comments.findById(parentId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("评论不存在"));
        ContentPost post = requireVisible(parent.getPostId());

        if (moderation.isL1Blocked(body)) {
            throw AppException.commentBlocked(L1_BLOCKED_MESSAGE);
        }

        // 两级约束：若被回复者本身是二级，则归并到它的一级父。
        long topLevelParentId = parent.isTopLevel() ? parent.getId() : parent.getParentId();

        Comment saved = comments.save(
                Comment.createUnderReview(post.getId(), topLevelParentId, authorId, body));
        events.publishEvent(new CommentSubmittedEvent(saved.getId(), body, saved.getContentVersion()));
        return CommentResponse.reply(saved, authorView(authorId));
    }

    /**
     * 异步审核路由入队（内容审核 story 3 · 异步化）：高危 / 三方降级 / 审核异常 → 入人工队列
     * （fail-closed，绝不自动放行）。{@code @Transactional} 供 {@code CommentModerationListener}（@Async）
     * 跨 bean 调用，AFTER_COMMIT 后起新事务。
     */
    @Transactional
    public void queueForReview(long commentId, int contentVersion) {
        reviewGate.enqueueComment(commentId, contentVersion);
    }

    /**
     * 删除评论。权限 = 评论作者本人 OR 该评论所属内容作者；否则 403。
     * 删一级 → 级联软删其全部二级；删二级 → 仅该条。
     */
    @Transactional
    public void delete(long commentId, long userId) {
        Comment c = comments.findById(commentId)
                .filter(cm -> cm.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("评论不存在"));

        long contentAuthorId = posts.findById(c.getPostId())
                .map(ContentPost::getAuthorId)
                .orElse(-1L);
        boolean allowed = userId == c.getAuthorId() || userId == contentAuthorId;
        if (!allowed) {
            throw AppException.forbidden("无权删除该评论");
        }

        c.softDelete();
        comments.save(c);
        if (c.isTopLevel()) {
            // 级联软删全部二级（事务内）。
            for (Comment reply : comments.findByParentIdAndDeletedAtIsNull(c.getId())) {
                reply.softDelete();
                comments.save(reply);
            }
        }
    }

    // ===== 队列处置门面（admin.moderation 经此变更评论审核态，禁直读 comments repo）=====

    /**
     * 人工审核通过（§5.3，G4）：{@code UNDER_REVIEW → VISIBLE} 并<b>此刻发 {@code ContentCommentedEvent}</b>
     * （评论转可见 → 触发「新评论」通知）。幂等：非挂起态 no-op、不重发事件。不给评论作者发「通过」通知（D-CM6）。
     */
    @Transactional
    public void approveComment(long commentId) {
        comments.findById(commentId)
                .filter(c -> c.getDeletedAt() == null)
                .filter(c -> c.getModerationStatus() == CommentModerationStatus.UNDER_REVIEW)
                .ifPresent(c -> {
                    c.approveModeration();
                    comments.save(c);
                    long contentAuthorId = posts.findById(c.getPostId())
                            .map(ContentPost::getAuthorId).orElse(-1L);
                    publishCommented(c, contentAuthorId);
                });
    }

    /**
     * 人工审核拒绝 / 超时（§5.3）：{@code UNDER_REVIEW → REJECTED}（终态、仍仅作者可见、永不发新评论事件）。
     * 幂等：非挂起态 no-op。移除通知由 admin 侧发（复用 CONTENT_REMOVED）。
     */
    @Transactional
    public void rejectComment(long commentId) {
        comments.findById(commentId)
                .filter(c -> c.getModerationStatus() == CommentModerationStatus.UNDER_REVIEW)
                .ifPresent(c -> {
                    c.rejectModeration();
                    comments.save(c);
                });
    }

    /**
     * FR-55A 巡查下架（§5.4）：仅 {@code VISIBLE} 可下架 → {@code TAKEN_DOWN}。返回迁移后的摘要（供 admin 通知/审计）；
     * 幂等 no-op（已下架）→ 返回空，admin 不重复通知。非 VISIBLE 且非 TAKEN_DOWN（挂起/已拒）→ 422。
     */
    @Transactional
    public Optional<CommentModerationSummary> takedownComment(long commentId) {
        Comment c = comments.findById(commentId)
                .filter(cm -> cm.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("评论不存在"));
        if (c.getModerationStatus() == CommentModerationStatus.TAKEN_DOWN) {
            return Optional.empty(); // 幂等：已下架，不重复通知/审计
        }
        if (!c.takedown()) {
            throw AppException.validation("仅可下架正常展示的评论");
        }
        comments.save(c);
        return Optional.of(summaryOf(c));
    }

    /**
     * FR-55A 恢复（§5.4）：{@code TAKEN_DOWN}/{@code REJECTED → VISIBLE}。<b>不通知、不重发事件</b>（恢复非「新评论」）。
     * 幂等：已 VISIBLE → no-op。
     */
    @Transactional
    public void restoreComment(long commentId) {
        Comment c = comments.findById(commentId)
                .filter(cm -> cm.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("评论不存在"));
        if (c.restoreModeration()) {
            comments.save(c);
        }
    }

    /** 队列行预览 / 版本守卫用摘要（§5.3/§5.6）。admin 经此取评论快照，不直读 comments repo。 */
    @Transactional(readOnly = true)
    public Optional<CommentModerationSummary> findModerationSummary(long commentId) {
        return comments.findById(commentId).map(this::summaryOf);
    }

    private CommentModerationSummary summaryOf(Comment c) {
        String body = c.getBody();
        String preview = body == null ? "" : (body.length() > 60 ? body.substring(0, 60) : body);
        return new CommentModerationSummary(c.getId(), c.getPostId(), c.getAuthorId(), preview,
                c.getContentVersion(), c.getModerationStatus());
    }

    /** 评论审核队列快照投影（§5.3）。preview 已裁剪，避免日志/展示外泄全文。 */
    public record CommentModerationSummary(long commentId, long postId, long authorId, String textPreview,
            int contentVersion, CommentModerationStatus moderationStatus) {
    }

    /** 发「新评论」事件（携 parentAuthorId：二级回复时为其一级父作者，一级评论为 null）。 */
    private void publishCommented(Comment c, long contentAuthorId) {
        Long parentAuthorId = c.isTopLevel() ? null
                : comments.findById(c.getParentId()).map(Comment::getAuthorId).orElse(null);
        events.publishEvent(new ContentCommentedEvent(
                c.getPostId(), c.getId(), c.getAuthorId(), contentAuthorId, parentAuthorId, Instant.now()));
    }

    private ContentPost requireVisible(long postId) {
        return posts.findById(postId)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .orElseThrow(() -> AppException.notFound(ContentDetailService.GONE_DETAIL));
    }

    private AuthorView authorView(long authorId) {
        return accountQueryService.findAuthorViews(List.of(authorId)).get(authorId);
    }
}
