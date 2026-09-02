package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1：人工复核页渲染（bug 20260902-480 / 481）。复核员必须能**看到实物**、读得懂时间：
 * 内容类工单出「查看内容」链接（用帖 id，不是队列号）、头像审核直接渲染头像图、时间列 WIB。
 */
class ManualReviewPageRenderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Authentication superAdminAuth() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "mrr-" + n + "@tailtopia.test", "Review Render", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    /**
     * 按「被举报账号」筛选后渲染 —— 🔴 必须筛：共享库工单逐轮累积，同分工单按最早时间排，
     * 上一轮跑测试造的行会把本轮新行挤出第一屏（2026-09-02 复跑真的红过一次）。
     */
    private String renderPage(long targetUserId) throws Exception {
        return mvc.perform(get("/admin/manual-review").param("status", "PENDING")
                        .param("q", String.valueOf(targetUserId))
                        .with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();
    }

    /** 480：送审工单的「查看内容」用帖 id —— 队列号连过去是一条毫不相干的内容。 */
    @Test
    void submissionRowLinksToTheActualPostNotTheQueueId() throws Exception {
        User author = newUser();
        ContentPost post = posts.save(ContentPost.publish(
                author.getId(), ContentType.DAILY, null, "render-审核中-" + SEQ.incrementAndGet(),
                java.util.List.of()));
        jdbc.update("INSERT INTO manual_review_queue (content_id, content_type, submitted_at, "
                + "status, priority, created_at, updated_at) "
                + "VALUES (?, 'CONTENT_POST', now(), 'PENDING', 'P0', now(), now())", post.getId());

        String html = renderPage(author.getId());
        assertThat(html).contains("/admin/content/" + post.getId());
    }

    /** 480：头像审核渲染头像图本身，而不是一串 URL 文字。 */
    @Test
    void avatarReviewRendersTheImage() throws Exception {
        User target = newUser();
        String url = "https://cdn/avatar-" + SEQ.incrementAndGet() + ".jpg";
        // 分数给 HIGH：共享库工单多，NORMAL(2 分) 可能被挤下第一屏。
        jdbc.update("INSERT INTO avatar_reviews (subject_type, subject_id, avatar_url, status, "
                + "priority) VALUES ('USER_AVATAR', ?, ?, 'MANUAL_PENDING', 'HIGH')",
                target.getId(), url);

        String html = renderPage(target.getId());
        assertThat(html).contains("src=\"" + url + "\"");
        // 头像工单没有「查看内容」链接（contentRefId 为空）。
        assertThat(html).doesNotContain("/admin/content/" + url);
    }

    /** 481：时间列是 WIB 格式（UTC+7 换算 + 带 WIB 字样），不再是原始 ISO UTC。 */
    @Test
    void earliestTimeIsRenderedAsWib() throws Exception {
        User author = newUser();
        ContentPost post = posts.save(ContentPost.publish(
                author.getId(), ContentType.DAILY, null, "render-时间-" + SEQ.incrementAndGet(),
                java.util.List.of()));
        // 固定送审时刻 2026-01-15 03:00 UTC。P0(12 分) + 最早时刻 → 必进第一屏；
        // 断言它以 WIB（UTC+7 = 10:00）格式渲染，原始 ISO 串（2026-01-15T03:00:00Z）绝迹。
        jdbc.update("INSERT INTO manual_review_queue (content_id, content_type, submitted_at, "
                + "status, priority, created_at, updated_at) "
                + "VALUES (?, 'CONTENT_POST', '2026-01-15 03:00:00+00', 'PENDING', 'P0', "
                + "now(), now())", post.getId());

        String html = renderPage(author.getId());
        assertThat(html).contains("2026-01-15 10:00:00 WIB");
        assertThat(html).doesNotContain("2026-01-15T03:00");
    }
}
