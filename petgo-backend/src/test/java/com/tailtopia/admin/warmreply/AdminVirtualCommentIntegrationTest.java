package com.tailtopia.admin.warmreply;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1（真库 + Redis 幂等 + 真 Thymeleaf）：「去评论」抽屉与虚拟身份发布（V1.3.0 Story 4.2 AC8）：
 * 抽屉 fragment 200 / 提交成功 200 fragment（评论落 UNDER_REVIEW、author = 虚拟账号、审计行存在且不含全文）/
 * L1 命中 422 / 无权限 403 / 幂等重放不产生第二条 / 禁用虚拟账号 422 / 帖子已删 404。
 * 机审在测试 profile 为 stub（`moderation.mode=stub`）且 @Async：普通正文会被异步 PASS 翻成 VISIBLE 造成竞态，故成功用例正文带 `stub-high`
 * （RISKY → 人工队列，确定停在 UNDER_REVIEW）；PASS → VISIBLE 由既有 CommentModerationListener 测试覆盖。
 */
class AdminVirtualCommentIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentService contentService;
    @Autowired
    private CommentRepository comments;
    @Autowired
    private AdminAuditLogRepository audits;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private JdbcTemplate jdbc;

    private AdminUserDetails admin(AdminRole role, String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "vc-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "暖评 " + seq, role, List.of(perms), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private long publishedPost(User author, String text) {
        return contentService.publish(author.getId(), new ContentPostCreateRequest(ContentType.DAILY, null, text, null),
                UUID.randomUUID().toString()).id();
    }

    private User virtualAccount(String species) {
        User v = users.save(User.newVirtual("virtual:" + UUID.randomUUID(), "Meow" + SEQ.incrementAndGet(), null, 1L));
        if (species != null) {
            jdbc.update("UPDATE users SET account_species = ? WHERE id = ?", species, v.getId());
        }
        return v;
    }

    @Test
    void drawerRendersPreviewExistingCommentsAndIdentityGroups() throws Exception {
        AdminUserDetails commenter = admin(AdminRole.CUSTOM, AdminPermissions.COMMENT_VIRTUAL_POST);
        User author = newUser();
        long postId = publishedPost(author, "一条冷帖，等一条暖评");
        User real = newUser();
        comments.save(Comment.create(postId, null, real.getId(), "真实评论"));
        User cat = virtualAccount("CAT");
        User dog = virtualAccount("DOG");
        comments.save(Comment.createUnderReview(postId, null, cat.getId(), "审核中的虚拟评论"));

        String html = mvc.perform(get("/admin/comments/distribution/" + postId + "/drawer").param("lang", "zh_CN")
                        .with(user(commenter)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-post-id=\"" + postId + "\"").contains("一条冷帖").contains("id=\"vc-existing\"")
                .contains("真实评论").contains("审核中的虚拟评论").contains("审核中").contains("虚拟")
                .contains("id=\"vc-composer\"").contains("name=\"virtualUserId\"").contains("name=\"idempotencyKey\"")
                .contains("value=\"" + cat.getId() + "\"").contains("value=\"" + dog.getId() + "\"")
                .contains("今日已评").doesNotContain("<html");
        // 真实用户帖无物种 → 两个虚拟号都在「匹配」组；不预选（占位项 selected）
        assertThat(html).contains("disabled selected");
    }

    @Test
    void publishGoesThroughModerationAuditsPreviewAndIsIdempotent() throws Exception {
        AdminUserDetails commenter = admin(AdminRole.CUSTOM, AdminPermissions.COMMENT_VIRTUAL_POST);
        User author = newUser();
        long postId = publishedPost(author, "冷帖");
        User cat = virtualAccount("CAT");
        String key = UUID.randomUUID().toString();
        String body = "好可爱的猫，我家的也这样！stub-high"; // stub 机审见 stub-high → RISKY → 入人工队列，稳定停在 UNDER_REVIEW（避免与异步 PASS 竞态）
        long before = comments.count();

        String frag = mvc.perform(post("/admin/comments/virtual").param("postId", String.valueOf(postId))
                        .param("virtualUserId", String.valueOf(cat.getId())).param("body", body).param("idempotencyKey", key)
                        .param("lang", "zh_CN").with(user(commenter)).with(csrf()).header("HX-Request", "true").header("HX-Target", "vc-error"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Retarget", "#vc-composer"))
                .andReturn().getResponse().getContentAsString();
        assertThat(frag).contains("id=\"vc-composer\"").contains("已提交，审核通过后显示").contains("id=\"vc-existing\"")
                .contains(body).contains("审核中").contains("value=\"" + cat.getId() + "\" selected");
        assertThat(comments.count()).isEqualTo(before + 1);
        Comment saved = comments.findAll().stream().filter(c -> c.getPostId() == postId && c.getAuthorId().equals(cat.getId()))
                .findFirst().orElseThrow();
        assertThat(saved.getModerationStatus()).isEqualTo(CommentModerationStatus.UNDER_REVIEW);
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getReplyToCommentId()).isNull();
        var audit = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.COMMENT_VIRTUAL_POST.equals(a.getActionType()) && String.valueOf(saved.getId()).equals(a.getTargetId()))
                .findFirst().orElseThrow();
        assertThat(audit.getTargetType()).isEqualTo("COMMENT");
        assertThat(audit.getSummary()).contains("postId=" + postId).contains("virtualUserId=" + cat.getId()).contains(body.substring(0, 5));

        // 幂等重放：同 key 再提交 → 200，不产生第二条
        mvc.perform(post("/admin/comments/virtual").param("postId", String.valueOf(postId))
                        .param("virtualUserId", String.valueOf(cat.getId())).param("body", body).param("idempotencyKey", key)
                        .with(user(commenter)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(comments.count()).isEqualTo(before + 1);
    }

    @Test
    void blockedDisabledDeletedAndForbiddenCases() throws Exception {
        AdminUserDetails commenter = admin(AdminRole.CUSTOM, AdminPermissions.COMMENT_VIRTUAL_POST);
        AdminUserDetails viewer = admin(AdminRole.CUSTOM, AdminPermissions.CONTENT_VIEW);
        User author = newUser();
        long postId = publishedPost(author, "冷帖");
        User cat = virtualAccount("CAT");

        // L1 黑名单命中（V47 默认词库为印尼语：judi / togel …）→ 422 行内 err
        MvcResult blocked = mvc.perform(post("/admin/comments/virtual").param("postId", String.valueOf(postId))
                        .param("virtualUserId", String.valueOf(cat.getId())).param("body", "ayo main judi online")
                        .param("idempotencyKey", UUID.randomUUID().toString())
                        .with(user(commenter)).with(csrf()).header("HX-Request", "true").header("HX-Target", "vc-error"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(blocked.getResponse().getContentAsString()).contains("inline-error");
        assertThat(blocked.getResponse().getHeader("HX-Retarget")).isEqualTo("#vc-error");

        // 禁用的虚拟账号 → 422
        User off = virtualAccount(null);
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", off.getId());
        mvc.perform(post("/admin/comments/virtual").param("postId", String.valueOf(postId))
                        .param("virtualUserId", String.valueOf(off.getId())).param("body", "hi")
                        .param("idempotencyKey", UUID.randomUUID().toString())
                        .with(user(commenter)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());

        // 帖子已删 → 404（抽屉与提交）
        long deleted = publishedPost(author, "将被删的帖");
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", deleted);
        mvc.perform(get("/admin/comments/distribution/" + deleted + "/drawer").with(user(commenter)).header("HX-Request", "true"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/comments/virtual").param("postId", String.valueOf(deleted))
                        .param("virtualUserId", String.valueOf(cat.getId())).param("body", "hi")
                        .param("idempotencyKey", UUID.randomUUID().toString())
                        .with(user(commenter)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isNotFound());

        // 只有 content.view：抽屉可开但评论区是禁用态；提交 403 禁用态 fragment
        String viewerDrawer = mvc.perform(get("/admin/comments/distribution/" + postId + "/drawer").param("lang", "zh_CN")
                        .with(user(viewer)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewerDrawer).contains("disabled").contains("以虚拟身份评论").doesNotContain("name=\"virtualUserId\"");
        mvc.perform(post("/admin/comments/virtual").param("postId", String.valueOf(postId))
                        .param("virtualUserId", String.valueOf(cat.getId())).param("body", "hi")
                        .param("idempotencyKey", UUID.randomUUID().toString())
                        .with(user(viewer)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }
}
