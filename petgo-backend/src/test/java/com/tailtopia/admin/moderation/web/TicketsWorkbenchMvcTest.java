package com.tailtopia.admin.moderation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.moderation.domain.AccountReportReason;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.moderation.service.AccountReportService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：A2 被举报用户工作台（V1.3.0 Story 2.5 AC10）——
 * 整页 200（两态页签 + 队列行）/ {@code HX-Request} 返 fragment / {@code warn} 缺 {@code reason} → 422 行内 err /
 * {@code warn} 成功 fragment 带 {@code data-next-id} + oob + {@code HX-Trigger}，理由入审计而通知文案不变 /
 * 缺 {@code user.deactivate} 封号 → 403 禁用态且 chip 禁用 / 整页 POST 仍 PRG / 旧 {@code GET /admin/tickets/detail} 已退役。
 */
class TicketsWorkbenchMvcTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private AccountReportService accountReports;
    @Autowired
    private AccountReportRepository reports;
    @Autowired
    private AdminAuditLogRepository audits;
    @Autowired
    private NotificationRepository notifications;

    @Test
    void ticketsWorkbenchFourWays() throws Exception {
        long seq = SEQ.incrementAndGet();
        long actor = 960000L + seq;
        accountService.createAccount("tk-super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(), actor);
        AdminUserDetails superAdmin = userDetailsService.loadByEmail("tk-super-" + seq + "@tailtopia.test", false);
        // 只有处置权、没有停用权：能进页面、能警告，封号 403（SUSPEND_AUTH 是 AND 关系）
        accountService.createAccount("tk-staff-" + seq + "@tailtopia.test", "员工", AdminRole.CUSTOM,
                List.of(AdminPermissions.CONTENT_VIEW_TICKETS, AdminPermissions.CONTENT_DISPOSE_ACCOUNT), actor);
        AdminUserDetails staff = userDetailsService.loadByEmail("tk-staff-" + seq + "@tailtopia.test", false);

        User target1 = newUser();
        User target2 = newUser();
        long reporter = newUser().getId();
        accountReports.submit(reporter, target1.getId(), AccountReportReason.SPAM, null);
        accountReports.submit(reporter, target2.getId(), AccountReportReason.HARASSMENT, null);
        long t1 = reports.findByTargetUserId(target1.getId()).orElseThrow().getId();
        long t2 = reports.findByTargetUserId(target2.getId()).orElseThrow().getId();

        // ① 整页 200：两态页签 + 队列行（勾选框远程关联批量表单）；旧 detail 路由已退役
        String page = mvc.perform(get("/admin/tickets").param("q", String.valueOf(target1.getId())).with(user(superAdmin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page)
                .contains("id=\"tickets-tab-count-pending\"").contains("id=\"tickets-tab-count-handled\"")
                .contains("id=\"ticket-row-" + t1 + "\"")
                .contains("form=\"ticket-batch-form\"")
                .doesNotContain("/admin/tickets/detail?");
        mvc.perform(get("/admin/tickets/detail").param("type", "ACCOUNT_REPORT").param("sourceId", String.valueOf(t1))
                        .with(user(superAdmin)))
                .andExpect(status().isNotFound());

        // ② HX-Request：左栏行片段 / 右栏四区片段（两段式 chips + warn 面板带 reason）
        String rows = mvc.perform(get("/admin/tickets").param("q", String.valueOf(target1.getId()))
                        .with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(rows).contains("ticket-row-" + t1).doesNotContain("<html");
        String detail = mvc.perform(get("/admin/tickets/" + t1 + "/detail").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail)
                .contains("data-chip=\"warn\"").contains("data-chip=\"suspend\"").contains("data-chip=\"dismiss\"")
                .contains("name=\"reason\"").contains("/admin/tickets/warn")
                .contains("SPAM");
        // 缺 user.deactivate 的员工：封号 chip 禁用 + 所缺权限中文标签
        String staffDetail = mvc.perform(get("/admin/tickets/" + t1 + "/detail").with(user(staff)).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(staffDetail).contains("disabled").contains("停用用户");

        // ③ warn 缺 reason → 422 行内 err（不跳条）；带 reason → 200 done + data-next-id（= q 限定下的 t2）+ oob + HX-Trigger
        String err = mvc.perform(post("/admin/tickets/warn").param("targetUserId", String.valueOf(target1.getId()))
                        .param("reportId", String.valueOf(t1))
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true").header("HX-Target", "admin-inline-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#admin-inline-error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("理由");
        String done = mvc.perform(post("/admin/tickets/warn").param("targetUserId", String.valueOf(target1.getId()))
                        .param("reportId", String.valueOf(t1)).param("reason", "多次发布广告引流内容")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true")
                        .header("HX-Current-URL", "http://localhost/admin/tickets?state=pending&q=" + target2.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(done)
                .contains("data-next-id=\"").doesNotContain("data-next-id=\"" + t1 + "\"")
                .contains("id=\"ticket-row-" + t1 + "\"").contains("hx-swap-oob=\"delete\"")
                .contains("id=\"tickets-tab-count-pending\"");
        // 理由只进审计；用户通知标题 / 正文一字不改、不透出理由
        AdminAuditLog warned = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.ACCOUNT_WARNED.equals(a.getActionType())
                        && String.valueOf(target1.getId()).equals(a.getTargetId()))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(warned.getSummary()).contains("理由：多次发布广告引流内容");
        Notification n = notifications.findAll().stream()
                .filter(x -> Long.valueOf(target1.getId()).equals(x.getRecipientUserId())).findFirst().orElseThrow();
        assertThat(n.getTitle()).isEqualTo("账号警告");
        assertThat(n.getBody()).isEqualTo("你的账号因违反 TailTopia 社区规范收到一次警告，请遵守社区规范")
                .doesNotContain("广告");

        // ④ 整页 POST 仍 PRG 302（缺 reason 走红色 flash 回列表）
        mvc.perform(post("/admin/tickets/dismiss").param("reportId", String.valueOf(t2)).with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/tickets"));

        // ⑤ 缺 user.deactivate 的员工 htmx 封号 → 403 禁用态 fragment（带所缺权限名）
        String forbidden = mvc.perform(post("/admin/tickets/suspend").param("targetUserId", String.valueOf(target2.getId()))
                        .param("reportId", String.valueOf(t2))
                        .with(user(staff)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(forbidden).contains("forbidden");
    }
}
