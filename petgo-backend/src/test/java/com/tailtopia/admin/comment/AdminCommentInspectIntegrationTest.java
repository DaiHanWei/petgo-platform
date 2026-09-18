package com.tailtopia.admin.comment;

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
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（V1.3.0 Story 7.2 · B2 评论巡查页签套模板 B，需 Docker postgres+redis）：
 * 页签条 + 模板 B 壳 + 筛选（状态 / 帖子 ID / 关键词）+ 摘要条三格 + 分页 + 行开抽屉；
 * 抽屉四区与两个原端点的 htmx 分支（抽屉 + oob 行 + toast + 列表刷新事件，路径与参数不变）；门控 403。
 */
class AdminCommentInspectIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentService contentService;
    @Autowired
    private CommentRepository comments;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;

    private AdminUserDetails admin(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "b2-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "评论运营 " + seq, AdminRole.CUSTOM, List.of(perms), 950000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private long newPost(User author) {
        return contentService.publish(author.getId(),
                new ContentPostCreateRequest(ContentType.DAILY, null, "B2 帖子正文 " + SEQ.incrementAndGet(), null),
                UUID.randomUUID().toString()).id();
    }

    /** AC1 / AC2 / AC3：页签条 + 模板 B 壳 + 筛选参数 + 摘要条随筛选联动 + 行开抽屉、行内无处置表单。 */
    @Test
    void inspectTabIsTemplateBWithSummaryAndDrawerRows() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.CONTENT_PROACTIVE_TAKEDOWN, AdminPermissions.CONTENT_RESTORE);
        User author = newUser();
        long postId = newPost(author);
        long visible = comments.save(Comment.create(postId, null, author.getId(), "B2 可见评论")).getId();
        Comment down = comments.save(Comment.create(postId, null, author.getId(), "B2 待下架评论"));
        down.takedown();
        comments.save(down);

        String html = mvc.perform(get("/admin/comments").param("lang", "zh_CN")
                        .param("postId", String.valueOf(postId)).with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // ⚠️ 不要断言 "comments-tabs" 这个字符串：comments.html 的内联脚本里就有它
        //    （document.querySelectorAll('.comments-tabs .tab')），把页签条 include 整行删掉也照样绿。
        //    改断页签条真实渲染出来的结构（复审 C3）。
        assertThat(html).contains("role=\"tablist\"").contains("href=\"/admin/comments/distribution\"")
                .contains("aria-selected=").contains("data-list")
                .contains("id=\"comments-summary\"").contains("id=\"comments-rows\"").contains("id=\"comment-drawer\"")
                .contains("name=\"status\"").contains("name=\"postId\"").contains("name=\"q\"")
                .contains("data-drawer-url=\"/admin/comments/" + visible + "/drawer\"")
                .contains("id=\"comment-row-" + visible + "\"")
                // 行内处置表单已收进抽屉
                .doesNotContain("/takedown\"").doesNotContain("name=\"reason\"")
                // 所属帖子点进 B1 抽屉
                .contains("/admin/content?open=" + postId);

        String summary = html.substring(html.indexOf("id=\"comments-summary\""), html.indexOf("id=\"comments-rows\""));
        assertThat(digits(summary)).as("总评论数 · 今日新增 · 已下架").containsExactly(2L, 2L, 1L);

        // 状态筛选：只看已下架的那一条
        String onlyDown = mvc.perform(get("/admin/comments").param("postId", String.valueOf(postId))
                        .param("status", "TAKEN_DOWN").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(onlyDown).contains("id=\"comment-row-" + down.getId() + "\"")
                .doesNotContain("id=\"comment-row-" + visible + "\"")
                .contains("id=\"comments-summary\" hx-swap-oob=\"true\"")
                .doesNotContain("data-list"); // 片段不带整页壳
    }

    /** AC4 / AC5：抽屉四区；下架 / 恢复走原端点的 htmx 分支 → 抽屉 + oob 行 + toast + 列表刷新事件。 */
    @Test
    void drawerAndDispositionsKeepEndpointsUnchanged() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.CONTENT_PROACTIVE_TAKEDOWN, AdminPermissions.CONTENT_RESTORE);
        User author = newUser();
        long postId = newPost(author);
        long cid = comments.save(Comment.create(postId, null, author.getId(), "B2 抽屉评论")).getId();

        String drawer = mvc.perform(get("/admin/comments/" + cid + "/drawer").param("lang", "zh_CN")
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-comment-id=\"" + cid + "\"")
                .contains("B2 抽屉评论")                              // ① 评论全文
                .contains("data-section=\"comment-post\"")            // ② 所属帖子卡
                .contains("data-section=\"comment-author\"")          // ③ 作者卡
                .contains("hx-post=\"/admin/comments/" + cid + "/takedown\"") // ④ 操作条
                .contains("id=\"comment-drawer-err\"");
        // 非 htmx 直达 → 回列表并自动开该抽屉
        mvc.perform(get("/admin/comments/" + cid + "/drawer").with(user(ops)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/comments?open=" + cid));

        // 下架（htmx）：路径 / 参数不变
        String done = mvc.perform(post("/admin/comments/" + cid + "/takedown")
                        .param("reason", "违规测试").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("admin:comment-list-refresh")))
                .andReturn().getResponse().getContentAsString();
        assertThat(done).contains("hx-swap-oob=\"innerHTML:#comment-drawer .drawer-body\"")
                .contains("id=\"comment-row-" + cid + "\"").contains("class=\"toast\"")
                .contains("hx-post=\"/admin/comments/" + cid + "/restore\""); // 抽屉已切到「恢复」
        assertThat(comments.findById(cid).orElseThrow().getModerationStatus())
                .isEqualTo(CommentModerationStatus.TAKEN_DOWN);
        assertThat(comments.findById(cid).orElseThrow().isDeleted()).as("审核线语义：不软删").isFalse();

        // 下架原因为空 → 422 行内 err
        mvc.perform(post("/admin/comments/" + cid + "/takedown").param("reason", "")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());

        // 恢复（htmx）
        mvc.perform(post("/admin/comments/" + cid + "/restore")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(comments.findById(cid).orElseThrow().getModerationStatus())
                .isEqualTo(CommentModerationStatus.VISIBLE);
    }

    /**
     * 🔴 用户自删的评论：抽屉只显示删除态，两个处置都不给（Dev Notes：「已下架」与「已删除」不是一个状态）。
     * 并核对门控：无 {@code content.proactive_takedown} 的账号开列表 / 抽屉都 403。
     */
    @Test
    void deletedCommentIsReadonlyAndPermissionsAreEnforced() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.CONTENT_PROACTIVE_TAKEDOWN, AdminPermissions.CONTENT_RESTORE);
        User author = newUser();
        long postId = newPost(author);
        Comment c = comments.save(Comment.create(postId, null, author.getId(), "B2 自删评论"));
        c.softDelete();
        comments.save(c);

        String drawer = mvc.perform(get("/admin/comments/" + c.getId() + "/drawer").param("lang", "zh_CN")
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("/takedown\"").doesNotContain("/restore\"")
                .contains("已删除");

        mvc.perform(get("/admin/comments").with(user(admin(AdminPermissions.CONTENT_VIEW))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/comments/" + c.getId() + "/drawer")
                        .with(user(admin(AdminPermissions.CONTENT_VIEW))).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }

    /** 摘要条里的三个数字（按出现顺序）。 */
    private static List<Long> digits(String summaryHtml) {
        var m = java.util.regex.Pattern.compile("class=\"sum-value\">(\\d+)<").matcher(summaryHtml);
        List<Long> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(Long.parseLong(m.group(1)));
        }
        return out;
    }
}
