package com.tailtopia.admin.refund.web;

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
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.domain.NeedDecision;
import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：A6 退款三段流工作台（V1.3.0 Story 2.8 AC8）——整页 200（四页签 + 行 + ?open= 深链）/ 旧详情路由 404 /
 * {@code HX-Request} 返 fragment / 三种权限各只渲染自己段 / 判定驳回缺 reason 422 / 判定成功 200 带 data-next-id + oob + HX-Trigger /
 * 🔴 超管兼任（提交人再审批）被服务层职责分离拒绝 → 403 fragment + 违规审计 / 非 htmx PRG。
 */
class RefundsWorkbenchMvcTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRequestRepository refunds;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private AdminAuditLogRepository audits;

    private ConsultOrder completedQrisOrder(long userId) {
        long n = SEQ.incrementAndGet();
        ConsultOrder o = ConsultOrder.inProgress("ord-rf-" + n, userId, 1L, 1L,
                50000, PayChannel.QRIS, null, 30000, 60, 50000, Instant.now());
        o.markCompleted(Instant.now());
        return orders.save(o);
    }

    @Test
    void refundsWorkbenchFourWays() throws Exception {
        long seq = SEQ.incrementAndGet();
        long actor = 990000L + seq;
        accountService.createAccount("rf-super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(), actor);
        AdminUserDetails superAdmin = userDetailsService.loadByEmail("rf-super-" + seq + "@tailtopia.test", false);
        accountService.createAccount("rf-cs-" + seq + "@tailtopia.test", "客服", AdminRole.CUSTOM, List.of(AdminPermissions.REFUND_SUBMIT), actor);
        AdminUserDetails cs = userDetailsService.loadByEmail("rf-cs-" + seq + "@tailtopia.test", false);
        accountService.createAccount("rf-lead-" + seq + "@tailtopia.test", "主管", AdminRole.CUSTOM, List.of(AdminPermissions.REFUND_APPROVE), actor);
        AdminUserDetails lead = userDetailsService.loadByEmail("rf-lead-" + seq + "@tailtopia.test", false);
        accountService.createAccount("rf-fin-" + seq + "@tailtopia.test", "财务", AdminRole.CUSTOM, List.of(AdminPermissions.REFUND_PAYOUT), actor);
        AdminUserDetails fin = userDetailsService.loadByEmail("rf-fin-" + seq + "@tailtopia.test", false);

        long userId = newUser().getId();
        String pending = refundService.createRefundRequest(completedQrisOrder(userId).getOrderToken(), null, actor);
        String pending2 = refundService.createRefundRequest(completedQrisOrder(userId).getOrderToken(), null, actor);
        // 超管作为提交人批准 need，用户填收款 → 待主管审批
        ConsultOrder o3 = completedQrisOrder(userId);
        String awaiting = refundService.createRefundRequest(o3.getOrderToken(), null, superAdmin.getAdminAccountId());
        refundService.approveNeed(awaiting, superAdmin.getAdminAccountId());
        refundService.fillPayoutByUser(awaiting, userId, PayoutChannel.OVO, "1234567890", "Budi");

        // ① 整页 200：四页签 + 判定段行；?open= 深链落到该单所在页签；旧整页详情路由 404
        String page = mvc.perform(get("/admin/refunds").with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"refund-tab-count-submit\"").contains("id=\"refund-tab-count-closed\"");
        // 共享库里判定段可能不止 20 条（先进先出，本次新建的在末页）：行断言走 detail 200 + queue 片段
        mvc.perform(get("/admin/refunds/" + pending + "/detail").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk());
        String deep = mvc.perform(get("/admin/refunds").param("open", awaiting).with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(deep).contains("data-open=\"" + awaiting + "\"").contains("id=\"refund-row-" + awaiting + "\"").contains("data-stage=\"approve\"");
        mvc.perform(get("/admin/refunds/" + pending).with(user(superAdmin))).andExpect(status().isNotFound());

        // ② HX-Request：行片段 / 四区片段；三种权限各只渲染自己段
        String rows = mvc.perform(get("/admin/refunds").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(rows).contains("refund-row-" + pending).doesNotContain("<html");
        String csOnSubmit = mvc.perform(get("/admin/refunds/" + pending + "/detail").with(user(cs)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csOnSubmit).contains("/admin/refunds/" + pending + "/approve").contains("name=\"reason\"")
                .doesNotContain("/approval\"");
        String leadOnSubmit = mvc.perform(get("/admin/refunds/" + pending + "/detail").with(user(lead)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(leadOnSubmit).doesNotContain("/admin/refunds/" + pending + "/approve\"").contains("提交退款需求");
        String leadOnApprove = mvc.perform(get("/admin/refunds/" + awaiting + "/detail").with(user(lead)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(leadOnApprove).contains("/admin/refunds/" + awaiting + "/approval").contains("name=\"note\"").contains("****7890")
                .contains("is-current");
        String finOnApprove = mvc.perform(get("/admin/refunds/" + awaiting + "/detail").with(user(fin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(finOnApprove).doesNotContain("/admin/refunds/" + awaiting + "/approval\"").contains("审批退款申请");

        // ③ 判定段：驳回缺 reason → 422 行内 err；批准 → 200 done + data-next-id（同段下一条 pending2）+ oob + HX-Trigger
        String err = mvc.perform(post("/admin/refunds/" + pending + "/reject").with(user(cs)).with(csrf())
                        .header("HX-Request", "true").header("HX-Target", "admin-inline-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#admin-inline-error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error");
        String done = mvc.perform(post("/admin/refunds/" + pending + "/approve").with(user(cs)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(done).contains("data-next-id=\"").contains("id=\"refund-row-" + pending + "\"").contains("hx-swap-oob=\"delete\"")
                .contains("id=\"refund-tab-count-submit\"");
        String rejected = mvc.perform(post("/admin/refunds/" + pending2 + "/reject").param("reason", "订单无异常")
                        .with(user(cs)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(rejected).contains("data-done");
        assertThat(refunds.findByRefundToken(pending2).orElseThrow().getNeedDecision()).isEqualTo(NeedDecision.REJECTED);
        assertThat(refunds.findByRefundToken(pending2).orElseThrow().getRejectReason()).isEqualTo("订单无异常");

        // ④ 🔴 职责分离：超管既是提交人又来审批 → 服务层拒绝 403 fragment（文案沿用异常消息）+ 违规审计
        long before = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.REFUND_DUTY_VIOLATION_BLOCKED.equals(a.getActionType())).count();
        String forbidden = mvc.perform(post("/admin/refunds/" + awaiting + "/approval").param("note", "自己批自己")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(forbidden).contains("职责分离");
        long after = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.REFUND_DUTY_VIOLATION_BLOCKED.equals(a.getActionType())).count();
        assertThat(after).isEqualTo(before + 1);

        // ⑤ 主管审批通过（htmx）→ 打款段；财务看到打款段带凭证上传；非 htmx PRG 302
        mvc.perform(post("/admin/refunds/" + awaiting + "/approval").param("note", "凭证已核")
                        .with(user(lead)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        String finOnPayout = mvc.perform(get("/admin/refunds/" + awaiting + "/detail").with(user(fin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(finOnPayout).contains("/admin/refunds/" + awaiting + "/payout").contains("name=\"proof\"").contains("multipart/form-data");
        mvc.perform(post("/admin/refunds/" + awaiting + "/approval").param("note", "again").with(user(lead)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/refunds"));
    }
}
