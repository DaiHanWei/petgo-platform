package com.tailtopia.admin.comment.service;

import com.tailtopia.admin.comment.dto.AdminCommentRow;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.repository.CommentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台评论内容管理（Story 9.9，后台§7.5）——两线合并后本 service 只承担【列表读取】。
 *
 * <p>下架/恢复动作在合并时切到内容审核线 {@code AdminCommentManageService}（FR-55A 语义：
 * 可见性态迁移 VISIBLE→TAKEN_DOWN 作者仍可见 + CONTENT_REMOVED 通知 + 违规计数 + 必填原因审计），
 * 取代本线原「软删即下架」实现——软删会连作者一起隐藏、且绕过违规计数/通知，与审核模型冲突。
 */
@Service
public class AdminCommentModerationService {

    private final CommentRepository comments;

    public AdminCommentModerationService(CommentRepository comments) {
        this.comments = comments;
    }

    @Transactional(readOnly = true)
    public List<AdminCommentRow> recent() {
        return comments.findTop200ByOrderByIdDesc().stream()
                .map(AdminCommentModerationService::toRow).toList();
    }

    private static AdminCommentRow toRow(Comment c) {
        return new AdminCommentRow(c.getId(), c.getPostId(), c.getAuthorId(), c.getBody(),
                c.isDeleted(), c.getModerationStatus().name(), c.getCreatedAt());
    }
}
