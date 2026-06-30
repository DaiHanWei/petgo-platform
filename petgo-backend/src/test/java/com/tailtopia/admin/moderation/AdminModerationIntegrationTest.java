package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminModerationService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：举报处理（Story 4.1，需 Docker postgres+redis）。单条下架→软删 + RESOLVED + 审计；批量逐条独立。
 */
class AdminModerationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminModerationService moderation;
    @Autowired
    private ReportService reportService;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentService contentService;
    @Autowired
    private AdminAuditService auditService;

    private AdminUserDetails admin(long acctId) {
        return new AdminUserDetails(acctId, null, "ops@x", null, AdminAccountType.SUPER_ADMIN);
    }

    private long reportOnNewPost(long reporterId) {
        long author = newUser().getId(); // content_posts.author_id 有 FK→users
        ContentPost p = posts.save(ContentPost.publish(author, ContentType.DAILY, null, "被举报", List.of()));
        reportService.submit(p.getId(), reporterId, ReportReason.INAPPROPRIATE);
        return reportService.byStatus(ReportStatus.PENDING, 50).stream()
                .filter(r -> r.getPostId().equals(p.getId())).findFirst().orElseThrow().getId();
    }

    @Test
    void takedownSoftDeletesMarksResolvedAndAudits() {
        long actor = 410000L + SEQ.incrementAndGet();
        long reportId = reportOnNewPost(newUser().getId());

        moderation.takedown(reportId, admin(actor));

        var resolved = reportService.byStatus(ReportStatus.RESOLVED, 50);
        assertThat(resolved).anyMatch(r -> r.getId().equals(reportId));
        long postId = reportService.find(reportId).orElseThrow().getPostId();
        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isTrue();
        assertThat(auditService.search(null, null, actor, AuditActions.CONTENT_TAKEN_DOWN, PageRequest.of(0, 5))
                .getContent()).isNotEmpty();
    }

    @Test
    void batchDismissProcessesEachIndependently() {
        long actor = 420000L + SEQ.incrementAndGet();
        long r1 = reportOnNewPost(newUser().getId());
        long r2 = reportOnNewPost(newUser().getId());

        AdminModerationService.BatchResult res = moderation.batch(List.of(r1, r2), false, admin(actor));

        assertThat(res.ok()).isEqualTo(2);
        assertThat(res.failedCount()).isZero();
        var dismissed = reportService.byStatus(ReportStatus.DISMISSED, 50);
        assertThat(dismissed).anyMatch(r -> r.getId().equals(r1));
        assertThat(dismissed).anyMatch(r -> r.getId().equals(r2));
    }
}
