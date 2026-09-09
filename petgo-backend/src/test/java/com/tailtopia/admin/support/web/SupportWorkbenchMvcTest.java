package com.tailtopia.admin.support.web;

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
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.domain.NeedDecision;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.support.service.SupportTicketService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：A5 客服工单工作台（V1.3.0 Story 2.7 AC7）——整页 200（三态页签 + 行）/ 旧详情路由 404 /
 * {@code HX-Request} 返 fragment / 未关联订单时判定区禁用（防呆）/ 关联订单 200 停留本条 / 驳回缺 reason 422 /
 * 驳回带 reason 200 回显原因且退款单落 reject_reason / 结案 200 带 {@code data-next-id} + oob + {@code HX-Trigger} /
 * 结案后只读 / 只有查看权禁用态 + 403。
 */
class SupportWorkbenchMvcTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private SupportTicketService support;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private RefundRequestRepository refunds;

    @Test
    void supportWorkbenchFourWays() throws Exception {
        long seq = SEQ.incrementAndGet();
        long actor = 980000L + seq;
        accountService.createAccount("sp-super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(), actor);
        AdminUserDetails superAdmin = userDetailsService.loadByEmail("sp-super-" + seq + "@tailtopia.test", false);
        accountService.createAccount("sp-view-" + seq + "@tailtopia.test", "只看", AdminRole.CUSTOM,
                List.of(AdminPermissions.SUPPORT_VIEW), actor);
        AdminUserDetails viewer = userDetailsService.loadByEmail("sp-view-" + seq + "@tailtopia.test", false);

        long userId = newUser().getId();
        String t1 = support.createTicket(userId, "无法登录 " + seq, "帮我看看", "EMAIL", "a" + seq + "@b.com", true, null, List.of(), List.of());
        String t2 = support.createTicket(userId, "退款问题 " + seq, "想退款", "EMAIL", "a" + seq + "@b.com", false, null, List.of(), List.of());
        ConsultOrder order = ConsultOrder.inProgress("ord-sp-" + seq, userId, 1L, 1L,
                50000, PayChannel.QRIS, null, 30000, 60, 50000, Instant.now());
        order.markCompleted(Instant.now());
        order = orders.save(order);

        // ① 整页 200：三态页签 + 行（t1 需联系 → 待联系页签也有）；旧整页详情路由 404
        String page = mvc.perform(get("/admin/support-tickets").with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"support-tab-count-pending\"").contains("id=\"support-tab-count-contact\"")
                .contains("id=\"support-tab-count-closed\"").contains("id=\"support-row-" + t1 + "\"");
        String contact = mvc.perform(get("/admin/support-tickets").param("state", "contact").with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(contact).contains("id=\"support-row-" + t1 + "\"").doesNotContain("id=\"support-row-" + t2 + "\"");
        mvc.perform(get("/admin/support-tickets/" + t2).with(user(superAdmin))).andExpect(status().isNotFound());
        // ?open= 深链：需联系的 t1 落到待联系页签并带 data-open
        String deep = mvc.perform(get("/admin/support-tickets").param("open", t1).with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(deep).contains("data-open=\"" + t1 + "\"").contains("data-state=\"contact\"");

        // ② HX-Request：行片段 / 五区片段；未关联订单 → 判定区两钮禁用（防呆 F7-4）
        String rows = mvc.perform(get("/admin/support-tickets").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(rows).contains("support-row-" + t2).doesNotContain("<html");
        String detail = mvc.perform(get("/admin/support-tickets/" + t2 + "/detail").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("/admin/support-tickets/" + t2 + "/link-order").contains("先关联订单")
                .contains("/admin/support-tickets/" + t2 + "/refund-reject").contains("name=\"reason\"");
        assertThat(detail.substring(detail.indexOf("refund-approve"), detail.indexOf("refund-reject"))).contains("disabled=\"disabled\"");
        // 只有查看权：关联 / 结案 / 判定禁用 + 所缺权限名；联系方式脱敏
        String viewerDetail = mvc.perform(get("/admin/support-tickets/" + t2 + "/detail").with(user(viewer)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewerDetail).contains("处理客服工单").contains("提交退款需求").contains("****")
                .doesNotContain("hx-post=\"/admin/support-tickets/" + t2 + "/resolve\"");

        // ③ 关联订单：200 回右栏（停留本条，订单摘要卡 + 改绑）；驳回缺 reason → 422；带 reason → 200 回显 + 落库
        String linked = mvc.perform(post("/admin/support-tickets/" + t2 + "/link-order").param("orderToken", order.getOrderToken())
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(linked).contains(order.getOrderToken()).contains("改绑订单").contains("50,000");
        // 不带 HX-Target：浏览器端由 admin-workbench.js 改写；服务端缺省也应落 #admin-inline-error
        String err = mvc.perform(post("/admin/support-tickets/" + t2 + "/refund-reject")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#admin-inline-error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error");
        String rejected = mvc.perform(post("/admin/support-tickets/" + t2 + "/refund-reject").param("reason", "订单已完成且无异常")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(rejected).contains("REJECTED").contains("订单已完成且无异常");
        assertThat(refunds.findByOrderId(order.getId()).orElseThrow().getNeedDecision()).isEqualTo(NeedDecision.REJECTED);
        assertThat(refunds.findByOrderId(order.getId()).orElseThrow().getRejectReason()).isEqualTo("订单已完成且无异常");

        // ④ 结案：200 done + data-next-id + oob 删行 + 计数 + HX-Trigger；非 htmx 仍 PRG 302；结案后只读
        String done = mvc.perform(post("/admin/support-tickets/" + t2 + "/resolve").with(user(superAdmin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(done).contains("data-next-id=\"").contains("id=\"support-row-" + t2 + "\"").contains("hx-swap-oob=\"delete\"")
                .contains("id=\"support-tab-count-closed\"");
        String closed = mvc.perform(get("/admin/support-tickets/" + t2 + "/detail").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(closed).contains("已结案").doesNotContain("/admin/support-tickets/" + t2 + "/resolve");
        mvc.perform(post("/admin/support-tickets/" + t1 + "/resolve").with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/support-tickets"));

        // ⑤ 只有查看权 htmx 结案 → 403 禁用态
        String forbidden = mvc.perform(post("/admin/support-tickets/" + t1 + "/resolve").with(user(viewer)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(forbidden).contains("forbidden");
    }
}
