package com.tailtopia.admin.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.throttle.domain.RankThrottle;
import com.tailtopia.moderation.throttle.domain.ThrottleDuration;
import com.tailtopia.moderation.throttle.domain.ThrottleScope;
import com.tailtopia.moderation.throttle.service.RankThrottleService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：后台限流处置动作与状态可见（Story 17.2）。
 *
 * <p>🛡 所有 POST 都带 {@code .with(csrf())} —— {@code /admin/**} 那条过滤链**保留 CSRF**（AC6）。
 * 少了它拿到 403，会被误读成「权限门没放行」，而实际权限完全正确。
 */
class AdminThrottleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private RankThrottleService throttleService;

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "throttle-" + n + "@tailtopia.test", "限流超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null,
                new java.util.ArrayList<>(p.getAuthorities()));
    }

    /**
     * 带指定权限码的 STAFF（非超管）—— 用来验两个码真的分开。
     *
     * <p>⚠️ authorities 必须走 {@link AdminUserDetails#getAuthorities()}，不能只塞权限码：
     * {@code /admin/**} 那条过滤链在 URL 层就要 {@code ROLE_ADMIN}。
     * 只塞码的话每一次请求都 403 —— 而 403 正是「权限门拦住了」想要的结果，
     * <b>于是所有 403 断言都会因为错误的原因通过</b>。第一版就是这么写的，
     * 靠一条本该 200 的用例红了才暴露出来。
     */
    private Authentication operatorWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "throttle-op-" + n + "@tailtopia.test", "限流运营", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, java.util.Set.of(codes));
        return new TestingAuthenticationToken(p, null,
                new java.util.ArrayList<>(p.getAuthorities()));
    }

    private ContentPost publish(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null,
                "admin-throttle-" + SEQ.incrementAndGet(), List.of()));
    }

    private double factor(ContentPost p) {
        return throttleService.factorsFor(
                        List.of(new RankThrottleService.Target(p.getId(), p.getAuthorId())),
                        Instant.now())
                .getOrDefault(p.getId(), 1.0);
    }

    // ── AC1：落库的粒度与期限 ─────────────────────────────────────

    @Test
    void applyingPostThrottleThroughTheAdminPathStoresScopeAndDuration() throws Exception {
        User author = newUser();
        ContentPost p = publish(author.getId());

        mvc.perform(post("/admin/throttles").with(authentication(superAdmin())).with(csrf())
                        .param("scope", "POST")
                        .param("targetId", String.valueOf(p.getId()))
                        .param("duration", "DAYS_7")
                        .param("back", "content"))
                .andExpect(status().is3xxRedirection());

        List<RankThrottle> rows = throttleService.history(ThrottleScope.POST, p.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDuration()).isEqualTo(ThrottleDuration.DAYS_7);
        assertThat(rows.get(0).getExpiresAt()).isNotNull();
        assertThat(factor(p)).isLessThan(1.0);
    }

    @Test
    void applyingAccountThrottleThroughTheAdminPathStoresScopeAndDuration() throws Exception {
        User author = newUser();
        ContentPost p = publish(author.getId());

        mvc.perform(post("/admin/throttles").with(authentication(superAdmin())).with(csrf())
                        .param("scope", "ACCOUNT")
                        .param("targetId", String.valueOf(author.getId()))
                        .param("duration", "PERMANENT")
                        .param("back", "tickets"))
                .andExpect(status().is3xxRedirection());

        List<RankThrottle> rows = throttleService.history(ThrottleScope.ACCOUNT, author.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDuration()).isEqualTo(ThrottleDuration.PERMANENT);
        assertThat(rows.get(0).getExpiresAt()).as("永久不该有到期时刻").isNull();
        assertThat(factor(p)).isLessThan(1.0);
    }

    /**
     * ⚠️ 内容举报弹窗里粒度可选，两种粒度指向<b>不同的 id</b>（帖 id / 作者 id）。
     *
     * <p>钉住的是「选了账号级却把帖 id 当账号 id 限流」这类降错对象的错 ——
     * 它不会报错，也没人会发现。
     */
    @Test
    void scopeDecidesWhichTargetIdIsUsed() throws Exception {
        User author = newUser();
        ContentPost p = publish(author.getId());

        mvc.perform(post("/admin/throttles").with(authentication(superAdmin())).with(csrf())
                        .param("scope", "ACCOUNT")
                        .param("postTargetId", String.valueOf(p.getId()))
                        .param("accountTargetId", String.valueOf(author.getId()))
                        .param("duration", "DAYS_30"))
                .andExpect(status().is3xxRedirection());

        assertThat(throttleService.history(ThrottleScope.ACCOUNT, author.getId())).hasSize(1);
        assertThat(throttleService.history(ThrottleScope.POST, p.getId())).isEmpty();
    }

    // ── AC3：手动解除与批量 ──────────────────────────────────────

    @Test
    void manualLiftThroughTheAdminPathReturnsFactorToOne() throws Exception {
        User author = newUser();
        ContentPost p = publish(author.getId());
        RankThrottle t = throttleService.throttlePost(p.getId(), ThrottleDuration.PERMANENT,
                Instant.now(), 1L, null, "试");
        assertThat(factor(p)).isLessThan(1.0);

        mvc.perform(post("/admin/throttles/lift").with(authentication(superAdmin())).with(csrf())
                        .param("throttleIds", String.valueOf(t.getId()))
                        .param("back", "content"))
                .andExpect(status().is3xxRedirection());

        assertThat(factor(p)).isEqualTo(1.0);
    }

    @Test
    void batchLiftClearsEveryThrottleInOneRequest() throws Exception {
        Instant now = Instant.now();
        User a = newUser();
        User b = newUser();
        ContentPost pa = publish(a.getId());
        ContentPost pb = publish(b.getId());
        RankThrottle ta = throttleService.throttlePost(pa.getId(), ThrottleDuration.DAYS_7, now,
                1L, null, "试");
        RankThrottle tb = throttleService.throttleAccount(b.getId(), ThrottleDuration.DAYS_30, now,
                1L, null, "试");

        mvc.perform(post("/admin/throttles/lift").with(authentication(superAdmin())).with(csrf())
                        .param("throttleIds", String.valueOf(ta.getId()),
                                String.valueOf(tb.getId())))
                .andExpect(status().is3xxRedirection());

        assertThat(factor(pa)).isEqualTo(1.0);
        assertThat(factor(pb)).isEqualTo(1.0);
    }

    /** 批量里混入一条已解除的：不报错，其余照样解除（幂等收尾动作）。 */
    @Test
    void batchLiftToleratesAlreadyLiftedRows() throws Exception {
        Instant now = Instant.now();
        ContentPost p = publish(newUser().getId());
        RankThrottle stale = throttleService.throttlePost(p.getId(), ThrottleDuration.DAYS_7, now,
                1L, null, "试");
        throttleService.lift(stale.getId(), now, 1L);
        ContentPost other = publish(newUser().getId());
        RankThrottle live = throttleService.throttlePost(other.getId(), ThrottleDuration.DAYS_7,
                now, 1L, null, "试");

        mvc.perform(post("/admin/throttles/lift").with(authentication(superAdmin())).with(csrf())
                        .param("throttleIds", String.valueOf(stale.getId()),
                                String.valueOf(live.getId())))
                .andExpect(status().is3xxRedirection());

        assertThat(factor(other)).isEqualTo(1.0);
    }

    // ── 🔴 AC2 / AC4：两条明示都在（用标记锚点，不断言散文）────────

    /**
     * 🔴 ⚠️ 断言用 {@code data-notice} 标记，<b>不断言文案</b>。
     *
     * <p>Story 13-3 踩过两次：{@code contains("...")} 会命中我自己写的 HTML 注释或占位文案，
     * 把横幅整条删掉测试照样绿。
     */
    @Test
    void contentPageCarriesBothMandatoryNotices() throws Exception {
        String html = mvc.perform(get("/admin/content").with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("🔴 AC2：「不是下架」明示不见了")
                .contains("data-notice=\"throttle-not-takedown\"");
        assertThat(html).as("⚠️ AC4：非 ALL Tab 边界提示不见了")
                .contains("data-notice=\"throttle-all-tab-only\"");
    }

    // ── AC3：状态与到期时间（WIB）可见 ────────────────────────────

    /** 🛡 到期时间按 WIB 展示且带可见的「WIB」字样（平台既定口径，本处是第六处）。 */
    @Test
    void contentPageShowsThrottleStatusWithWibExpiry() throws Exception {
        User author = newUser();
        ContentPost p = publish(author.getId());
        throttleService.throttlePost(p.getId(), ThrottleDuration.DAYS_7, Instant.now(), 1L, null,
                "试");

        String html = mvc.perform(get("/admin/content").with(authentication(superAdmin()))
                        .param("authorId", String.valueOf(author.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/admin/throttles/lift"); // 就地解除入口
        assertThat(html).as("到期时间没带 WIB 字样").containsPattern("\\d{2}:\\d{2}:\\d{2} WIB");
    }

    // ── 🛡 AC5：两个权限码真的分开 ────────────────────────────────

    /** 🛡 只有查看权 → 能看状态，但点不了处置（403）。 */
    @Test
    void viewOnlyOperatorCannotApplyOrLift() throws Exception {
        ContentPost p = publish(newUser().getId());
        Authentication viewOnly = operatorWith(AdminPermissions.CONTENT_THROTTLE_VIEW,
                AdminPermissions.CONTENT_VIEW);

        mvc.perform(post("/admin/throttles").with(authentication(viewOnly)).with(csrf())
                        .param("scope", "POST")
                        .param("targetId", String.valueOf(p.getId()))
                        .param("duration", "DAYS_7"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/throttles/lift").with(authentication(viewOnly)).with(csrf())
                        .param("throttleIds", "1"))
                .andExpect(status().isForbidden());
    }

    /** 🛡 无任何限流权 → 列表页不渲染限流列，也就没有「点了必 403」的活按钮。 */
    @Test
    void operatorWithoutThrottleViewSeesNoThrottleColumn() throws Exception {
        ContentPost p = publish(newUser().getId());
        throttleService.throttlePost(p.getId(), ThrottleDuration.DAYS_7, Instant.now(), 1L, null,
                "试");
        Authentication noThrottle = operatorWith(AdminPermissions.CONTENT_VIEW);

        String html = mvc.perform(get("/admin/content").with(authentication(noThrottle)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("/admin/throttles/lift");
    }

    /** 🛡 AC6：漏了 CSRF 就该被拦 —— 这条钉住「/admin/** 保留 CSRF」这件事本身。 */
    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        ContentPost p = publish(newUser().getId());
        mvc.perform(post("/admin/throttles").with(authentication(superAdmin()))
                        .param("scope", "POST")
                        .param("targetId", String.valueOf(p.getId()))
                        .param("duration", "DAYS_7"))
                .andExpect(status().isForbidden());
    }

    /** 🛡 回跳目标是白名单，外站 URL 不会被拼进 redirect（开放重定向）。 */
    @Test
    void backParameterCannotRedirectOffSite() throws Exception {
        ContentPost p = publish(newUser().getId());
        mvc.perform(post("/admin/throttles").with(authentication(superAdmin())).with(csrf())
                        .param("scope", "POST")
                        .param("targetId", String.valueOf(p.getId()))
                        .param("duration", "DAYS_7")
                        .param("back", "https://evil.example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/content"));
    }

    /** 期限漏选 → 400（枚举转换失败），而不是静默落成永久。 */
    @Test
    void missingDurationIsRejectedRatherThanDefaultingToPermanent() throws Exception {
        ContentPost p = publish(newUser().getId());
        mvc.perform(post("/admin/throttles").with(authentication(superAdmin())).with(csrf())
                        .param("scope", "POST")
                        .param("targetId", String.valueOf(p.getId())))
                .andExpect(status().is4xxClientError());
        assertThat(throttleService.history(ThrottleScope.POST, p.getId())).isEmpty();
    }

    @Test
    void expiredThrottleDoesNotShowAsActiveOnThePage() throws Exception {
        User author = newUser();
        ContentPost p = publish(author.getId());
        // 造一条「7 天前开始的 7 天限流」= 刚好已到期。
        throttleService.throttlePost(p.getId(), ThrottleDuration.DAYS_7,
                Instant.now().minus(Duration.ofDays(8)), 1L, null, "试");

        String html = mvc.perform(get("/admin/content").with(authentication(superAdmin()))
                        .param("authorId", String.valueOf(author.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("已到期的限流不该还显示解除入口")
                .doesNotContain("/admin/throttles/lift");
        assertThat(factor(p)).isEqualTo(1.0);
    }
}
