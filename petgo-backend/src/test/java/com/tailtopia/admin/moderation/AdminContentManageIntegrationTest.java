package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.service.AdminContentManageService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：全量内容管理（Story 4.2，需 Docker postgres+redis）。跨作者浏览/类型筛选/正文搜索；
 * 主动下架→软删 + 审计；恢复→清 deletedAt + 审计。经 {@link AdminContentManageService}（不直读 content repo）。
 */
class AdminContentManageIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminContentManageService contentManage;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentService contentService;
    @Autowired
    private AdminAuditService auditService;

    private long newPost(ContentType type, String text) {
        long author = newUser().getId(); // content_posts.author_id 有 FK→users
        return posts.save(ContentPost.publish(author, type, null, text, List.of())).getId();
    }

    @Test
    void browseSearchesBodyCaseInsensitiveAcrossAuthors() {
        String token = "ZxqMarker" + SEQ.incrementAndGet();
        long p1 = newPost(ContentType.DAILY, "前缀 " + token + " 后缀");
        newPost(ContentType.KNOWLEDGE, "无关内容"); // 另一作者，不应命中

        // 关键词小写也命中（ILIKE），跨作者
        List<AdminContentRow> hits = contentManage.browse(null, null, null, null, null,
                token.toLowerCase(), 0);

        assertThat(hits).extracting(AdminContentRow::id).contains(p1);
        assertThat(hits).allMatch(r -> r.textPreview().toLowerCase().contains(token.toLowerCase()));
    }

    @Test
    void takedownSoftDeletesAndAudits() {
        long actor = 421000L + SEQ.incrementAndGet();
        long postId = newPost(ContentType.DAILY, "待下架内容");

        contentManage.takedown(postId, "违反社区规范", actor);

        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isTrue();
        assertThat(auditService.search(null, null, actor, AuditActions.CONTENT_TAKEN_DOWN,
                PageRequest.of(0, 5)).getContent()).isNotEmpty();
    }

    @Test
    void restoreClearsDeletedAtAndAudits() {
        long actor = 422000L + SEQ.incrementAndGet();
        long postId = newPost(ContentType.DAILY, "先下架再恢复");
        contentManage.takedown(postId, "误判", actor);
        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isTrue();

        contentManage.restore(postId, actor);

        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isFalse();
        assertThat(auditService.search(null, null, actor, AuditActions.CONTENT_RESTORED,
                PageRequest.of(0, 5)).getContent()).isNotEmpty();
        // 恢复后可被 status=ONLINE 浏览到（重回公开口径）
        List<AdminContentRow> online = contentManage.browse(null, null, null, null, "ONLINE", null, 0);
        assertThat(online).extracting(AdminContentRow::id).contains(postId);
    }
}
