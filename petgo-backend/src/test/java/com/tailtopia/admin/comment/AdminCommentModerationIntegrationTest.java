package com.tailtopia.admin.comment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.comment.service.AdminCommentModerationService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 9.9）：真 pg——评论 admin 主动下架落 deletedAt + 公开过滤 + 恢复。
 */
class AdminCommentModerationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminCommentModerationService service;
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

        service.takedown(cid, 1L);
        Comment down = comments.findById(cid).orElseThrow();
        assertThat(down.isDeleted()).isTrue();
        // 公开口径过滤：软删评论不入 findByPostIdAndDeletedAtIsNull。
        assertThat(comments.findByPostIdAndDeletedAtIsNull(down.getPostId()))
                .extracting(Comment::getId).doesNotContain(cid);

        service.restore(cid, 1L);
        assertThat(comments.findById(cid).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void recentListsComment() {
        long cid = seedComment();
        assertThat(service.recent()).extracting("id").contains(cid);
    }
}
