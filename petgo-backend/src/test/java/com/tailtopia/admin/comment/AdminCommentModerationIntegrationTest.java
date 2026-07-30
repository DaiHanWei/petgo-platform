package com.tailtopia.admin.comment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.comment.service.AdminCommentModerationService;
import com.tailtopia.admin.moderation.service.AdminCommentManageService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 9.9 · 两线合并后）：真 pg——评论后台下架走审核线语义
 * （可见性态 TAKEN_DOWN、<b>不软删</b>、作者视角保留）+ 恢复回 VISIBLE + 列表读取。
 */
class AdminCommentModerationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminCommentModerationService service;
    @Autowired
    private AdminCommentManageService manage;
    @Autowired
    private ContentService contentService;
    @Autowired
    private CommentRepository comments;

    private long seedComment() {
        User author = newUser();
        long postId = contentService.publish(author.getId(),
                new ContentPostCreateRequest(ContentType.DAILY, null, "帖子正文", null),
                UUID.randomUUID().toString()).id();
        Comment c = comments.save(Comment.create(postId, null, author.getId(), "一条评论"));
        return c.getId();
    }

    @Test
    void takedownThenRestore() {
        long cid = seedComment();

        manage.takedownComment(cid, "违规测试", 1L);
        Comment down = comments.findById(cid).orElseThrow();
        // 审核线语义：可见性态迁移（作者仍可见自己的评论），不软删。
        assertThat(down.getModerationStatus()).isEqualTo(CommentModerationStatus.TAKEN_DOWN);
        assertThat(down.isDeleted()).isFalse();

        manage.restoreComment(cid, 1L);
        assertThat(comments.findById(cid).orElseThrow().getModerationStatus())
                .isEqualTo(CommentModerationStatus.VISIBLE);
    }

    @Test
    void recentListsComment() {
        long cid = seedComment();
        assertThat(service.recent()).extracting("id").contains(cid);
    }
}
