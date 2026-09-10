package com.tailtopia.admin.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.comment.dto.CommentInspectRow;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（Story 9.9 · 两线合并后 → V1.3.0 Story 7.2）：本 service 只承担列表读取。
 * 行映射含审核线可见性态 {@code moderationStatus} 与软删标记，且**整页一次**带出所属帖子摘要与作者昵称。
 * 下架/恢复动作在审核线 {@code AdminCommentManageService}（其语义在该 service 自己的测试覆盖）。
 */
class AdminCommentModerationServiceTest {

    private CommentRepository comments;
    private ContentService contentService;
    private AccountQueryService accountQuery;
    private AdminCommentModerationService svc;

    @BeforeEach
    void setUp() {
        comments = Mockito.mock(CommentRepository.class);
        contentService = mock(ContentService.class);
        accountQuery = mock(AccountQueryService.class);
        svc = new AdminCommentModerationService(comments, contentService, accountQuery,
                mock(NamedParameterJdbcTemplate.class));
    }

    private Comment comment(long id, boolean deleted) {
        Comment c = Comment.create(9L, null, 3L, "内容"); // postId, parentId, authorId, body
        ReflectionTestUtils.setField(c, "id", id);
        if (deleted) {
            c.softDelete();
        }
        return c;
    }

    private void stubEnrichment(String nickname, boolean authorDeleted) {
        when(contentService.adminRowsByIds(ArgumentMatchers.any()))
                .thenReturn(List.of(new AdminContentRow(9L, ContentType.DAILY, 3L, "帖子正文",
                        false, Instant.now(), List.of("https://cdn/x.jpg"), null)));
        when(accountQuery.findAuthorViews(ArgumentMatchers.any()))
                .thenReturn(Map.of(3L, new AuthorView(3L, nickname, null, authorDeleted, null)));
    }

    @Test
    void detailMapsModerationStatusDeletedAndEnrichesPostAndAuthor() {
        Comment down = comment(6L, false);
        down.takedown(); // 审核线可见性态：VISIBLE → TAKEN_DOWN
        when(comments.findById(6L)).thenReturn(Optional.of(down));
        stubEnrichment("阿猫", false);

        CommentInspectRow row = svc.detail(6L);

        assertThat(row.id()).isEqualTo(6L);
        assertThat(row.moderationStatus()).isEqualTo("TAKEN_DOWN");
        assertThat(row.deleted()).isFalse();
        assertThat(row.postPreview()).isEqualTo("帖子正文");
        assertThat(row.postImage()).isEqualTo("https://cdn/x.jpg");
        assertThat(row.authorName()).isEqualTo("阿猫");
        // 🔴 下架 ≠ 删除：可恢复、不可再下架
        assertThat(row.canRestore()).isTrue();
        assertThat(row.canTakedown()).isFalse();
    }

    /** 🔴 用户自删与可见性态正交：删除态下两个处置都不给（Dev Notes）。 */
    @Test
    void deletedCommentOffersNoDisposition() {
        when(comments.findById(7L)).thenReturn(Optional.of(comment(7L, true)));
        stubEnrichment("阿狗", true);

        CommentInspectRow row = svc.detail(7L);

        assertThat(row.deleted()).isTrue();
        assertThat(row.canTakedown()).isFalse();
        assertThat(row.canRestore()).isFalse();
        assertThat(row.authorDeleted()).isTrue();
    }
}
