package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.service.CommentService.CommentModerationSummary;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台评论巡查下架 / 恢复（内容审核 story 3，FR-55A，AB-3B 评论扩展）。经 {@link CommentService} 门面变更
 * 评论审核态（禁 admin 直读 comments repo）。下架/恢复同事务写审计；下架必填原因（进审计 summary，不进作者通知）。
 * 安全攸关（AB-3B）：勿埋绕过点。
 */
@Service
public class AdminCommentManageService {

    /** §8.5 评论移除通知（临时中文 literal；i18n 归 story 7）。复用 CONTENT_REMOVED 类型，深链指向帖子。 */
    private static final String REMOVED_TITLE = "内容已被移除";
    private static final String REMOVED_BODY = "你发布的评论因违反社区规范已被移除";

    private final CommentService commentService;
    private final NotificationService notifications;
    private final AdminAuditService auditService;
    private final ViolationCountService violationCountService;

    public AdminCommentManageService(CommentService commentService, NotificationService notifications,
            AdminAuditService auditService, ViolationCountService violationCountService) {
        this.commentService = commentService;
        this.notifications = notifications;
        this.auditService = auditService;
        this.violationCountService = violationCountService;
    }

    /**
     * 主动下架评论（必填原因）：VISIBLE → TAKEN_DOWN + 通知作者（CONTENT_REMOVED，深链 postId）+ 审计（含原因）。
     * 幂等：已下架 → no-op（不重复通知/审计）。挂起/已拒态 → 422（仅可下架正常展示的评论）。
     */
    @Transactional
    public void takedownComment(long commentId, String reason, long actorAccountId) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("下架原因不能为空");
        }
        Optional<CommentModerationSummary> transitioned = commentService.takedownComment(commentId);
        transitioned.ifPresent(s -> {
            // 深链指向帖子详情（作者仍可见自己被下架的评论 + 标签）。
            notifications.send(s.authorId(), NotificationType.CONTENT_REMOVED, REMOVED_TITLE, REMOVED_BODY,
                    NotificationType.CONTENT_REMOVED.name(), String.valueOf(s.postId()));
            // 原因进审计（不进作者通知，与帖子下架一致）。
            auditService.record(actorAccountId, AuditActions.COMMENT_TAKEN_DOWN, "COMMENT",
                    String.valueOf(commentId), "主动下架评论（原因：" + reason.trim() + "）");
            // story 9 §5.1：评论 FR-55A 巡查下架 = 人工判定违规 → 同事务累加 COMMENT 计数（仅真实迁移时，幂等）。
            violationCountService.record(s.authorId(), ViolationType.COMMENT);
        });
    }

    /**
     * 恢复已下架/已拒评论：TAKEN_DOWN/REJECTED → VISIBLE + 审计。<b>不通知、不重发新评论事件</b>（恢复=正向，D-CM6）。
     * 幂等：已 VISIBLE → no-op。
     */
    @Transactional
    public void restoreComment(long commentId, long actorAccountId) {
        commentService.restoreComment(commentId);
        auditService.record(actorAccountId, AuditActions.COMMENT_RESTORED, "COMMENT",
                String.valueOf(commentId), "恢复已下架评论");
    }
}
