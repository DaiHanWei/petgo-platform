package com.tailtopia.admin.anomaly.web;

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
import com.tailtopia.admin.anomaly.repository.ConsultAnomalyRepository;
import com.tailtopia.admin.anomaly.service.ConsultAnomalyService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.consult.event.ConsultAnomalyRaisedEvent;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：A4 问诊异常工单工作台（V1.3.0 Story 2.6 AC7）——整页 200（两态页签 + 行）/ 旧 {@code ?status=} 兼容 /
 * {@code HX-Request} 返 fragment / 加备注 200 只回备注时间线（局部追加不换条）/ 空备注 422 行内 err / 标记已处理 200 带
 * {@code data-next-id} + oob + {@code HX-Trigger} / 已处理仍可加备注 / 只有查看权 403 禁用态 / 旧详情路由 404。
 */
class AnomaliesWorkbenchMvcTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private ConsultAnomalyService service;
    @Autowired
    private ConsultAnomalyRepository anomalies;

    @Test
    void anomaliesWorkbenchFourWays() throws Exception {
        long seq = SEQ.incrementAndGet();
        long actor = 970000L + seq;
        accountService.createAccount("an-super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(), actor);
        AdminUserDetails superAdmin = userDetailsService.loadByEmail("an-super-" + seq + "@tailtopia.test", false);
        accountService.createAccount("an-view-" + seq + "@tailtopia.test", "只看", AdminRole.CUSTOM,
                List.of(AdminPermissions.CONSULT_VIEW_ANOMALIES), actor);
        AdminUserDetails viewer = userDetailsService.loadByEmail("an-view-" + seq + "@tailtopia.test", false);

        long s1 = 9_700_000L + seq * 2;
        long s2 = s1 + 1;
        service.recordTicket(new ConsultAnomalyRaisedEvent(s1, 7777L, 8888L,
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T01:00:00Z"), "VET_BANNED"));
        service.recordTicket(new ConsultAnomalyRaisedEvent(s2, 7777L, 8888L,
                Instant.parse("2026-06-02T00:00:00Z"), Instant.parse("2026-06-02T01:00:00Z"), "VET_BANNED"));
        long a1 = anomalies.findBySessionId(s1).orElseThrow().getId();
        long a2 = anomalies.findBySessionId(s2).orElseThrow().getId();

        // ① 整页 200：两态页签 + 行；旧 ?status=RESOLVED 映射到已处理页签；旧详情路由 404
        String page = mvc.perform(get("/admin/anomalies").with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"anomaly-tab-count-open\"").contains("id=\"anomaly-tab-count-resolved\"")
                .contains("id=\"anomaly-row-" + a1 + "\"").contains("id=\"anomaly-row-" + a2 + "\"");
        String legacy = mvc.perform(get("/admin/anomalies").param("status", "RESOLVED").with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(legacy).contains("data-state=\"resolved\"").doesNotContain("id=\"anomaly-row-" + a1 + "\"");
        mvc.perform(get("/admin/anomalies/" + a1).with(user(superAdmin))).andExpect(status().isNotFound());

        // ② HX-Request：行片段 / 四区片段（去取证深链 + 备注时间线 + 操作区）
        String rows = mvc.perform(get("/admin/anomalies").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(rows).contains("anomaly-row-" + a1).doesNotContain("<html");
        String detail = mvc.perform(get("/admin/anomalies/" + a1 + "/detail").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("/admin/consult-sessions?open=" + s1).contains("id=\"anomaly-notes\"")
                .contains("/admin/anomalies/" + a1 + "/resolve").contains("/admin/anomalies/" + a1 + "/note");
        // 只有查看权：两钮禁用 + 所缺权限名
        String viewerDetail = mvc.perform(get("/admin/anomalies/" + a1 + "/detail").param("lang", "zh_CN").with(user(viewer)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewerDetail).contains("disabled").contains("处理问诊异常").doesNotContain("hx-post=\"/admin/anomalies/" + a1 + "/resolve\"");

        // ③ 加备注：200 只回备注时间线（局部追加，不换条）；空备注 422 行内 err
        String notes = mvc.perform(post("/admin/anomalies/" + a1 + "/note").param("note", "已电话联系用户")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(notes).contains("id=\"anomaly-notes\"").contains("已电话联系用户").contains("超管")
                .doesNotContain("data-next-id").doesNotContain("/admin/anomalies/" + a1 + "/resolve");
        String err = mvc.perform(post("/admin/anomalies/" + a1 + "/note").param("note", "  ")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true").header("HX-Target", "admin-inline-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#admin-inline-error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error");

        // ④ 标记已处理：200 done + data-next-id（= 另一条 OPEN a2 或更新的）+ oob 删行 + 计数 + HX-Trigger；非 htmx 仍 PRG 302
        String done = mvc.perform(post("/admin/anomalies/" + a1 + "/resolve").param("resolutionImageKey", "anomaly/" + s1 + ".jpg")
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(done).contains("data-next-id=\"").contains("id=\"anomaly-row-" + a1 + "\"").contains("hx-swap-oob=\"delete\"")
                .contains("id=\"anomaly-tab-count-open\"");
        mvc.perform(post("/admin/anomalies/" + a2 + "/note").param("note", "PRG 备注").with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/anomalies"));

        // ⑤ 已处理只读但备注仍可追加；只有查看权 htmx 备注 → 403 禁用态
        String resolvedDetail = mvc.perform(get("/admin/anomalies/" + a1 + "/detail").with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(resolvedDetail).doesNotContain("/admin/anomalies/" + a1 + "/resolve").contains("/admin/anomalies/" + a1 + "/note");
        mvc.perform(post("/admin/anomalies/" + a1 + "/note").param("note", "归档后追加").with(user(superAdmin)).with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isOk());
        String forbidden = mvc.perform(post("/admin/anomalies/" + a2 + "/note").param("note", "x").with(user(viewer)).with(csrf())
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(forbidden).contains("forbidden");
    }
}
