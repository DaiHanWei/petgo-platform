package com.tailtopia.admin.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.comment.dto.AdminCommentRow;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.repository.CommentRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（Story 9.9 · 两线合并后）：本 service 只承担列表读取——行映射含审核线可见性态
 * {@code moderationStatus} 与软删标记。下架/恢复动作已切审核线 {@code AdminCommentManageService}
 * （其语义在该 service 自己的测试覆盖），此处不再测软删下架。
 */
class AdminCommentModerationServiceTest {

    private CommentRepository comments;
    private AdminCommentModerationService svc;

    @BeforeEach
    void setUp() {
        comments = Mockito.mock(CommentRepository.class);
        svc = new AdminCommentModerationService(comments);
    }

    private Comment comment(long id, boolean deleted) {
        Comment c = Comment.create(9L, null, 3L, "内容"); // postId, parentId, authorId, body
        ReflectionTestUtils.setField(c, "id", id);
        if (deleted) {
            c.softDelete();
        }
        return c;
    }

    @Test
    void recentMapsModerationStatusAndDeleted() {
        Comment visible = comment(5L, false);
        Comment down = comment(6L, false);
        down.takedown(); // 审核线可见性态：VISIBLE → TAKEN_DOWN
        Comment removed = comment(7L, true);
        when(comments.findTop200ByOrderByIdDesc()).thenReturn(List.of(visible, down, removed));

        List<AdminCommentRow> rows = svc.recent();

        assertThat(rows).extracting(AdminCommentRow::id).containsExactly(5L, 6L, 7L);
        assertThat(rows.get(0).moderationStatus()).isEqualTo("VISIBLE");
        assertThat(rows.get(0).deleted()).isFalse();
        assertThat(rows.get(1).moderationStatus()).isEqualTo("TAKEN_DOWN");
        assertThat(rows.get(2).deleted()).isTrue();
    }
}
