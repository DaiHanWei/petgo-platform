package com.tailtopia.admin.comment.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.comment.dto.AdminCommentRow;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台评论内容管理（Story 9.9，后台§7.5）。评论纳入全量内容管理——运营主动下架/恢复评论 + 审计。
 * 下架复用既有软删（{@code Comment.deletedAt}，公开口径经 {@code findByPostIdAndDeletedAtIsNull} 过滤）。
 * 帖子拦截（F10）/ 封禁挂起（3-8）已在前序 story，不在此重复。
 */
@Service
public class AdminCommentModerationService {

    private final CommentRepository comments;
    private final AdminAuditService audit;

    public AdminCommentModerationService(CommentRepository comments, AdminAuditService audit) {
        this.comments = comments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AdminCommentRow> recent() {
        return comments.findTop200ByOrderByIdDesc().stream()
                .map(AdminCommentModerationService::toRow).toList();
    }

    /** 主动下架评论（软删 + 审计）。已删幂等。 */
    @Transactional
    public void takedown(long commentId, long adminId) {
        Comment c = comments.findById(commentId)
                .orElseThrow(() -> AppException.notFound("评论不存在"));
        if (c.isDeleted()) {
            return;
        }
        c.softDelete();
        comments.save(c);
        audit.record(adminId, "COMMENT_TAKEN_DOWN", "comment", String.valueOf(commentId),
                "post=" + c.getPostId());
    }

    /** 恢复评论（清软删 + 审计）。未删幂等。 */
    @Transactional
    public void restore(long commentId, long adminId) {
        Comment c = comments.findById(commentId)
                .orElseThrow(() -> AppException.notFound("评论不存在"));
        if (!c.isDeleted()) {
            return;
        }
        c.restore();
        comments.save(c);
        audit.record(adminId, "COMMENT_RESTORED", "comment", String.valueOf(commentId),
                "post=" + c.getPostId());
    }

    private static AdminCommentRow toRow(Comment c) {
        return new AdminCommentRow(c.getId(), c.getPostId(), c.getAuthorId(), c.getBody(),
                c.isDeleted(), c.getCreatedAt());
    }
}
