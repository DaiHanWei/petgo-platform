package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminModerationService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Docker postgres+redis）。Story 4.8 举报处理结果回告举报人（FR-51/AB-3A）。
 *
 * <p>锁定既有 V1.0 admin epic 已实现的闭环——**举报处置（下架/驳回）→ 举报人 {@code REPORT_REVIEWED} 模糊通知真落库**
 * （经 event → {@code @TransactionalEventListener}(AFTER_COMMIT) → {@code send}(REQUIRES_NEW) 真实路径）。既有测试为 L0 mock，
 * 本测试补 L1 直查 {@code notifications} 表的端到端确认，且断言下架/驳回文案一致（不透露结果）+ 若退回默认 REQUIRED 会静默丢写即回归失败。
 */
class ReporterNotificationClosedLoopIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminModerationService moderation;
    @Autowired
    private ReportService reportService;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;

    private AdminUserDetails admin(long acctId) {
        return new AdminUserDetails(acctId, null, "ops@x", null, AdminAccountType.SUPER_ADMIN);
    }

    private long reportOnNewPost(long reporterId) {
        long author = newUser().getId();
        ContentPost p = posts.save(ContentPost.publish(author, ContentType.DAILY, null, "被举报", List.of()));
        reportService.submit(p.getId(), reporterId, ReportReason.INAPPROPRIATE);
        return reportService.byStatus(ReportStatus.PENDING, 50).stream()
                .filter(r -> r.getPostId().equals(p.getId())).findFirst().orElseThrow().getId();
    }

    private List<Map<String, Object>> reviewedNotifs(long reporterId) {
        return jdbc.queryForList(
                "SELECT title, body, target_ref FROM notifications "
                        + "WHERE recipient_user_id = ? AND type = 'REPORT_REVIEWED'",
                reporterId);
    }

    @Test
    void takedown_notifiesReporter_blurred_noTarget() {
        long reporter = newUser().getId();
        long reportId = reportOnNewPost(reporter);

        moderation.takedown(reportId, admin(480000L + SEQ.incrementAndGet()));

        List<Map<String, Object>> ns = reviewedNotifs(reporter);
        assertThat(ns).hasSize(1); // REQUIRES_NEW 真落库（若退回 REQUIRED 会 0 → 回归失败）
        assertThat(ns.get(0).get("target_ref")).isNull(); // deepLink 不导向内容（AB-3A）
    }

    @Test
    void dismiss_notifiesReporter() {
        long reporter = newUser().getId();
        long reportId = reportOnNewPost(reporter);

        moderation.dismiss(reportId, admin(481000L + SEQ.incrementAndGet()));

        assertThat(reviewedNotifs(reporter)).hasSize(1);
    }

    @Test
    void takedownAndDismiss_produceIdenticalBlurredText() {
        long reporterTakedown = newUser().getId();
        moderation.takedown(reportOnNewPost(reporterTakedown), admin(482000L + SEQ.incrementAndGet()));

        long reporterDismiss = newUser().getId();
        moderation.dismiss(reportOnNewPost(reporterDismiss), admin(483000L + SEQ.incrementAndGet()));

        Map<String, Object> takedownNotif = reviewedNotifs(reporterTakedown).get(0);
        Map<String, Object> dismissNotif = reviewedNotifs(reporterDismiss).get(0);
        // AB-3A：下架与驳回文案完全一致，不透露处置结果。
        assertThat(dismissNotif.get("title")).isEqualTo(takedownNotif.get("title"));
        assertThat(dismissNotif.get("body")).isEqualTo(takedownNotif.get("body"));
    }
}
