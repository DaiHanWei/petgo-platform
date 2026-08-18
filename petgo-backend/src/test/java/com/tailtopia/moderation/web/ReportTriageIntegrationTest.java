package com.tailtopia.moderation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminModerationService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.FeedService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.repository.ContentReportRepository;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;

/**
 * 举报处置增强（内容审核 cm-6）集成测试（L1，真 Spring + PostgreSQL + Redis）。
 *
 * <p>覆盖 AC-B3（Feed 举报者过滤）/ AC-B4（详情 404）/ AC-B5（P1·P2 不改可见性）/
 * AC-B6（P0 达 10 预处置 + 幂等）/ AC-B7（单 ILLEGAL → P0）/ AC-B8（判违规下架 + 通知作者）/
 * AC-B9（判误报恢复 + 不通知，配合 {@code ContentServiceTest} L0 断言恢复不双 fire 事件）。
 * V52 迁移 + {@code ddl-auto=validate} 由本类上下文启动隐式验证（AC-B1）。
 */
class ReportTriageIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentReportRepository reports;
    @Autowired
    private ReportService reportService;
    @Autowired
    private AdminModerationService adminModerationService;
    @Autowired
    private FeedService feedService;
    @Autowired
    private NotificationRepository notifications;

    private ContentPost newPost(long authorId) {
        // 直接落 PUBLISHED（绕开 publish() 的 ContentPublishedEvent），使作者初始无任何通知，便于断言。
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "cm6 正文", List.of()));
    }

    private AdminUserDetails admin() {
        return new AdminUserDetails(SEQ.incrementAndGet(), null, "ops@x", "{bcrypt}x",
                AdminAccountType.SUPER_ADMIN);
    }

    private long firstPendingReportId(long postId) {
        return reports.findByPostIdAndStatus(postId, ReportStatus.PENDING).get(0).getId();
    }

    private boolean feedContains(long viewerId, String petStatus, long postId) {
        FeedPageResponse page = feedService.loadFeed(petStatus, "ALL", null, viewerId);
        return page.items().stream().anyMatch(i -> i.id() != null && i.id() == postId);
    }

    // ---- AC-B3：Feed 对举报者过滤（A 不见 P / B 见 P） ----
    @Test
    void feedExcludesReportedPostForReporterOnly() {
        User author = newUser();
        User reporterA = newUser();
        User viewerB = newUser();
        ContentPost p = newPost(author.getId());

        assertThat(feedContains(reporterA.getId(), "HAS_PET", p.getId())).isTrue(); // 举报前 A 可见

        reportService.submit(p.getId(), reporterA.getId(), ReportReason.INAPPROPRIATE);

        assertThat(feedContains(reporterA.getId(), "HAS_PET", p.getId())).isFalse(); // A 举报后不再见
        assertThat(feedContains(viewerB.getId(), "HAS_PET", p.getId())).isTrue();    // B 未举报仍见
        // P2（单次非 ILLEGAL）：不改可见性——帖仍 PUBLISHED、未预处置。
        ContentPost after = posts.findById(p.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(after.getReportHiddenAt()).isNull();
    }

    // ---- AC-B4：详情对举报者 404、对他人 200 ----
    @Test
    void detailReturns404ForReporterAnd200ForOthers() throws Exception {
        User author = newUser();
        User reporterA = newUser();
        User viewerB = newUser();
        ContentPost p = newPost(author.getId());
        reportService.submit(p.getId(), reporterA.getId(), ReportReason.HARASSMENT);

        mvc.perform(get("/api/v1/content-posts/{id}", p.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporterA.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/content-posts/{id}", p.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewerB.getId())))
                .andExpect(status().isOk());
    }

    // ---- AC-B5：P1（3–9）不自动下架 ----
    @Test
    void p1RangeDoesNotChangeVisibility() {
        User author = newUser();
        ContentPost p = newPost(author.getId());
        for (int i = 0; i < 5; i++) { // 5 个不同用户（P1 区间）
            reportService.submit(p.getId(), newUser().getId(), ReportReason.INAPPROPRIATE);
        }
        ContentPost after = posts.findById(p.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(after.getReportHiddenAt()).isNull();
        assertThat(reports.countByPostId(p.getId())).isEqualTo(5L);
    }

    // ---- AC-B6：P0 达 10 自动预处置 + 幂等 + 不通知 ----
    @Test
    void tenReportersTriggerP0HoldIdempotentNoNotify() {
        User author = newUser();
        ContentPost p = newPost(author.getId());
        for (int i = 0; i < 10; i++) {
            reportService.submit(p.getId(), newUser().getId(), ReportReason.INAPPROPRIATE);
        }
        ContentPost held = posts.findById(p.getId()).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(PostStatus.UNDER_REVIEW);
        assertThat(held.getReportHiddenAt()).isNotNull();
        assertThat(held.getReviewReason()).isEqualTo("REPORT_P0");
        assertThat(held.getDeletedAt()).isNull(); // 内容不删
        // 他人不可见（Feed 仅 PUBLISHED），作者本人仍可见（findMyPosts 放行 UNDER_REVIEW）。
        assertThat(feedContains(newUser().getId(), "HAS_PET", p.getId())).isFalse();
        long pid = p.getId();
        assertThat(feedService.myPosts(author.getId(), null).items().stream()
                .anyMatch(i -> i.id() != null && i.id() == pid)).isTrue();
        // 预处置不推送：作者无任何通知。
        assertThat(authorNotifications(author.getId())).isEmpty();

        // 幂等：第 11 个举报不重复入队/不改 report_hidden_at。
        Instant firstHiddenAt = held.getReportHiddenAt();
        reportService.submit(p.getId(), newUser().getId(), ReportReason.INAPPROPRIATE);
        ContentPost still = posts.findById(p.getId()).orElseThrow();
        assertThat(still.getStatus()).isEqualTo(PostStatus.UNDER_REVIEW);
        assertThat(still.getReportHiddenAt()).isEqualTo(firstHiddenAt);
    }

    // ---- AC-B7：单个 ILLEGAL 原因即 P0 ----
    @Test
    void singleIllegalReportTriggersP0Hold() {
        User author = newUser();
        ContentPost p = newPost(author.getId());
        reportService.submit(p.getId(), newUser().getId(), ReportReason.ILLEGAL);
        ContentPost held = posts.findById(p.getId()).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(PostStatus.UNDER_REVIEW);
        assertThat(held.getReportHiddenAt()).isNotNull();
        assertThat(held.getReviewReason()).isEqualTo("REPORT_P0");
        assertThat(authorNotifications(author.getId())).isEmpty();
    }

    // ---- AC-B8：判违规 → 永久下架 + 结单 + 通知作者 CONTENT_REMOVED ----
    @Test
    void takedownRemovesResolvesTicketsAndNotifiesAuthor() {
        User author = newUser();
        ContentPost p = newPost(author.getId());
        // 多个举报（P0），造多条 PENDING 单以验证全部结单。
        reportService.submit(p.getId(), newUser().getId(), ReportReason.ILLEGAL);
        reportService.submit(p.getId(), newUser().getId(), ReportReason.HARASSMENT);
        long reportId = firstPendingReportId(p.getId());

        adminModerationService.takedown(reportId, admin());

        assertThat(posts.findById(p.getId()).orElseThrow().getDeletedAt()).isNotNull(); // 永久下架
        assertThat(reports.findByPostIdAndStatus(p.getId(), ReportStatus.PENDING)).isEmpty(); // 全单结掉
        assertThat(authorNotifications(author.getId()))
                .anyMatch(n -> n.getType() == NotificationType.CONTENT_REMOVED); // 作者被通知
    }

    // ---- AC-B9：判误报 → 恢复 PUBLISHED + 清 report_hidden_at + 不通知作者（原举报者仍隐藏） ----
    @Test
    void dismissRestoresVisibilityClearsHoldNoNotify() {
        User author = newUser();
        User reporterA = newUser();
        ContentPost p = newPost(author.getId());
        reportService.submit(p.getId(), reporterA.getId(), ReportReason.ILLEGAL); // P0 挂起
        long reportId = firstPendingReportId(p.getId());

        adminModerationService.dismiss(reportId, admin());

        ContentPost restored = posts.findById(p.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(PostStatus.PUBLISHED); // 恢复对外可见
        assertThat(restored.getReportHiddenAt()).isNull();
        assertThat(restored.getReviewReason()).isNull();
        assertThat(restored.getDeletedAt()).isNull();
        // 作者无任何通知（既非下架、亦非「发布前审核通过」；D-CM6）。
        assertThat(authorNotifications(author.getId())).isEmpty();
        // 原举报者仍对其隐藏（举报记录未撤，R5）：他人可见、A 不可见。
        assertThat(feedContains(reporterA.getId(), "HAS_PET", p.getId())).isFalse();
        assertThat(feedContains(newUser().getId(), "HAS_PET", p.getId())).isTrue();
    }

    private List<Notification> authorNotifications(long authorId) {
        return notifications.findByRecipientUserIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                authorId, Instant.now().plusSeconds(60), PageRequest.of(0, 50));
    }
}
