package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.moderation.domain.AccountReportReason;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.moderation.service.AccountReportService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1：工单展开面板要能看出**谁**举报了、**什么理由**（bug 20260820）。
 *
 * <p>原先这个面板只给原因 + 时间，看不出是谁在报。而「三个不同的人各报一次」与
 * 「一个人反复报三次」处置结论可能完全相反 —— 优先级分把这件事压成了一个数字，
 * 展开面板是运营唯一能还原它的地方。
 *
 * <p>⚠️ 举报人身份只在运营后台展示：**绝不下发给被举报人、也绝不进日志**。
 * 一旦外泄，被举报者就能定点报复，举报功能等于废掉。
 */
class TicketDetailReporterIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private AccountReportService accountReports;

    @Autowired
    private AccountReportRepository reports;

    @Autowired
    private JdbcTemplate jdbc;

    /** 超管身份 + ROLE_ADMIN。⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它。 */
    private Authentication adminAuth() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "detail-" + n + "@tailtopia.test", "明细面板超管", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null, java.util.List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }

    private User renamed(User u, String nickname) {
        u.setNickname(nickname);
        return users.save(u);
    }

    @Test
    void detailPanelShowsEachReporterAndReason() throws Exception {
        String tag = "rp" + Long.toString(SEQ.incrementAndGet(), 36);
        User target = newUser();
        User first = renamed(newUser(), tag + "-a");
        User second = renamed(newUser(), tag + "-b");

        // ⚠️ 补充说明**只有理由为 OTHER 时才落库**（normalizeDetail：其余理由一律丢弃，
        //    且 OTHER 必填）。用 SPAM + 补充说明造数会静默丢掉那段文本，测试会红在
        //    「补充说明」上，误以为是展示没做。
        accountReports.submit(first.getId(), target.getId(), AccountReportReason.OTHER, "私信刷广告");
        long reportId = reports.findByTargetUserId(target.getId()).orElseThrow().getId();
        // 5 秒去重窗口（Story 2.1 AC11）：把已有明细拨走，模拟两次发生在不同时刻。
        jdbc.update("UPDATE account_report_entries SET created_at = created_at - interval '1 minute' "
                + "WHERE report_id = ?", reportId);
        accountReports.submit(second.getId(), target.getId(),
                AccountReportReason.IMPERSONATION, null);

        String html = mvc.perform(get("/admin/tickets/detail")
                        .param("type", "ACCOUNT_REPORT")
                        .param("sourceId", String.valueOf(reportId))
                        .param("userId", String.valueOf(target.getId()))
                        .param("lang", "zh_CN")
                        .with(authentication(adminAuth())))
                .andReturn().getResponse().getContentAsString();

        // 多于 1 条 → 原因区是聚合「原因 × 次数」
        assertThat(html).as("原因聚合").contains("×1");
        assertThat(html).as("两个原因都在").contains("OTHER").contains("IMPERSONATION");
        // 明细折叠起来，但内容仍在页面里（<details> 默认收起，点开即见）
        assertThat(html).as("折叠入口").contains("查看逐条");
        assertThat(html).as("举报人昵称").contains(tag + "-a").contains(tag + "-b");
        assertThat(html).as("举报人 id").contains("#" + first.getId()).contains("#" + second.getId());
        assertThat(html).as("补充说明（仅 OTHER 会落库）").contains("私信刷广告");
    }

    /**
     * 只有 1 条举报 → **不折叠、不聚合**，那一条直接摊开。
     *
     * <p>聚合摘要（「原因 ×1」）与「查看逐条」的折叠入口对单条毫无意义：多点一次才看到唯一的一条，
     * 纯属添乱。这条把「单条不折叠」钉住 —— 否则很容易为了少写一个分支而让两种情况共用折叠形态。
     */
    @Test
    void singleReportIsShownExpandedWithoutFolding() throws Exception {
        String tag = "sg" + Long.toString(SEQ.incrementAndGet(), 36);
        User target = newUser();
        User only = renamed(newUser(), tag + "-solo");

        accountReports.submit(only.getId(), target.getId(), AccountReportReason.SPAM, null);
        long reportId = reports.findByTargetUserId(target.getId()).orElseThrow().getId();

        String html = mvc.perform(get("/admin/tickets/detail")
                        .param("type", "ACCOUNT_REPORT")
                        .param("sourceId", String.valueOf(reportId))
                        .param("userId", String.valueOf(target.getId()))
                        .param("lang", "zh_CN")
                        .with(authentication(adminAuth())))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("单条也要看得到是谁报的").contains(tag + "-solo").contains("SPAM");
        assertThat(html).as("单条不该有折叠入口").doesNotContain("查看逐条");
        assertThat(html).as("单条不该显示「×次数」聚合").doesNotContain("×1");
    }
}
