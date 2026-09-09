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
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库 + 真 Thymeleaf）：A1 统一复核工作台（V1.3.0 Story 2.4 AC9）——
 * 整页 200（含四页签 + {@code ?type=} 旧链接兼容）/ {@code HX-Request} 返 fragment / 处置成功 fragment 带
 * {@code data-next-id} + oob 行删除 + {@code HX-Trigger} / 422 行内 err / 403 禁用态（带权限名）。
 */
class ManualReviewWorkbenchMvcTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ManualReviewItemRepository queue;

    @Test
    void workbenchFourWays() throws Exception {
        long seq = SEQ.incrementAndGet();
        long actor = 950000L + seq;
        accountService.createAccount("wb-super-" + seq + "@tailtopia.test", "超管", AdminRole.SUPER_ADMIN, List.of(), actor);
        AdminUserDetails superAdmin = userDetailsService.loadByEmail("wb-super-" + seq + "@tailtopia.test", false);
        // 只有无关内容权的员工：整页 403；htmx 处置 403 禁用态（content.restore 既不在 QUEUE_AUTH 也不在 DECIDE_AUTH）。
        accountService.createAccount("wb-staff-" + seq + "@tailtopia.test", "员工", AdminRole.CUSTOM,
                List.of(AdminPermissions.CONTENT_RESTORE), actor);
        AdminUserDetails staff = userDetailsService.loadByEmail("wb-staff-" + seq + "@tailtopia.test", false);

        long author = newUser().getId();
        ContentPost p1 = posts.save(ContentPost.pendingReview(author, ContentType.DAILY, null, "工作台送审一", List.of(), null));
        ContentPost p2 = posts.save(ContentPost.pendingReview(author, ContentType.DAILY, null, "工作台送审二", List.of(), null));
        ManualReviewItem i1 = queue.save(ManualReviewItem.pending(p1.getId(), Instant.now().minusSeconds(120)));
        ManualReviewItem i2 = queue.save(ManualReviewItem.pending(p2.getId(), Instant.now().minusSeconds(60)));

        // ① 整页 200：四页签 + 队列行 + 旧 ?type= 兼容映射到举报页签
        String page = mvc.perform(get("/admin/manual-review").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page)
                .contains("review-tab-count-submission").contains("review-tab-count-report")
                .contains("review-tab-count-name").contains("review-tab-count-avatar")
                .contains("id=\"review-row-submission-" + i1.getId() + "\"")
                .doesNotContain("<option value=\"CONTENT_REPORT\"");
        String legacy = mvc.perform(get("/admin/manual-review").param("type", "CONTENT_REPORT").with(user(superAdmin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(legacy).contains("name=\"tab\" value=\"report\"").doesNotContain("review-row-submission-" + i1.getId());

        // ② HX-Request：只回左栏行 fragment（无整页壳）
        String rows = mvc.perform(get("/admin/manual-review").param("tab", "submission")
                        .with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(rows).contains("review-row-submission-" + i1.getId()).doesNotContain("<html");
        String detail = mvc.perform(get("/admin/manual-review/" + i1.getId() + "/detail").param("tab", "submission")
                        .with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("工作台送审一").contains("/admin/manual-review/" + i1.getId() + "/approve");

        // ③ htmx 处置成功：200 fragment 带 data-next-id（= 下一条 i2）+ oob 删行 + HX-Trigger 刷角标
        String done = mvc.perform(post("/admin/manual-review/" + i1.getId() + "/approve")
                        .with(user(superAdmin)).with(csrf())
                        .header("HX-Request", "true")
                        // q=作者 id：共享测试库里可能有别的 PENDING 送审项，把「下一条」限定到本测试的作者
                        .header("HX-Current-URL", "http://localhost/admin/manual-review?tab=submission&q=" + author))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(done)
                .contains("data-next-id=\"" + i2.getId() + "\"")
                .contains("id=\"review-row-submission-" + i1.getId() + "\"")
                .contains("hx-swap-oob=\"delete\"")
                .contains("id=\"review-tab-count-submission\"");

        // ④ 整页 POST 仍 PRG 302；htmx 业务错（重复处置已终态项）→ 422 行内 err
        mvc.perform(post("/admin/manual-review/" + i2.getId() + "/approve").with(user(superAdmin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/manual-review"));
        String err = mvc.perform(post("/admin/manual-review/" + i2.getId() + "/approve")
                        // admin-workbench.js 把操作区请求的 HX-Target 改为壳底部的行内错误宿主（复审 #2）
                        .with(user(superAdmin)).with(csrf()).header("HX-Request", "true").header("HX-Target", "admin-inline-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#admin-inline-error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error");

        // ⑤ htmx 无处置权 → 403 禁用态 fragment（带所缺权限名）；无入口权整页 → 403
        String forbidden = mvc.perform(post("/admin/manual-review/" + i2.getId() + "/approve")
                        .with(user(staff)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(forbidden).contains("forbidden");
        mvc.perform(get("/admin/manual-review").with(user(staff))).andExpect(status().isForbidden());
    }
}
