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
import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.HandledAction;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import com.tailtopia.admin.warmreply.repository.WarmReplyFollowupRepository;
import com.tailtopia.admin.warmreply.service.WarmReplyQueueService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1（真库 + Redis 幂等 + 真 Thymeleaf）：A9 暖贴回复跟进工作台（V1.3.0 Story 4.4 AC7 六条 + 服务层断言）：
 * 整页 200 / HX-Request 只回行 fragment / reply 成功（HANDLED/REPLIED + 评论落 UNDER_REVIEW、author = 虚拟账号、parent = 暖评 + 审计）
 * / reply 空内容 422 / 已 HANDLED 再操作 422 fragment / 无权限 403；read → HANDLED/READ + 审计 WARM_REPLY_READ；内容已删只允许已读。
 * 跟进项直接用 4-3 的 upsert SQL 造（入队链路由 WarmReplyEnqueueIntegrationTest 覆盖）；回复正文带 stub-high 稳定停在 UNDER_REVIEW。
 */
class AdminWarmReplyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentService contentService;
    @Autowired
    private CommentRepository comments;
    @Autowired
    private WarmReplyFollowupRepository followups;
    @Autowired
    private WarmReplyQueueService queue;
    @Autowired
    private AdminAuditLogRepository audits;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;
    @Autowired
    private JdbcTemplate jdbc;

    private AdminUserDetails admin(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "wr-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "暖贴跟进 " + seq, AdminRole.CUSTOM, List.of(perms), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private User virtual() {
        return users.save(User.newVirtual("virtual:" + UUID.randomUUID(), "Meow" + SEQ.incrementAndGet(), null, 1L));
    }

    private long publishPost(User author, String text) {
        return contentService.publish(author.getId(), new ContentPostCreateRequest(ContentType.DAILY, null, text, null),
                UUID.randomUUID().toString()).id();
    }

    /** 造一条 PENDING 跟进项：虚拟暖评 + 一条真实可见回复，走 4-3 的 upsert SQL。 */
    private record Fixture(long postId, User virtual, User real, long warmId, long replyId, long followupId) {
    }

    private Fixture pendingItem() {
        User author = newUser();
        User real = newUser();
        User v = virtual();
        long postId = publishPost(author, "冷帖 " + UUID.randomUUID());
        Comment warm = comments.save(Comment.create(postId, null, v.getId(), "暖评"));
        Comment reply = comments.save(Comment.create(postId, warm.getId(), real.getId(), "谢谢回复！"));
        jdbc.update(WarmReplyQueueService.UPSERT_SQL, warm.getId(), postId, v.getId(), reply.getId(), Timestamp.from(Instant.now()));
        WarmReplyFollowup f = followups.findByVirtualCommentIdAndStatus(warm.getId(), FollowupStatus.PENDING).orElseThrow();
        return new Fixture(postId, v, real, warm.getId(), reply.getId(), f.getId());
    }

    @Test
    void fullPageAndFragmentsRenderQueueAndDetail() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.COMMENT_VIRTUAL_POST);
        Fixture fx = pendingItem();

        String page = mvc.perform(get("/admin/warm-replies").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"warm-replies-queue\"").contains("warm-replies-row-" + fx.followupId())
                .contains("id=\"warm-replies-tab-count-pending\"").contains("id=\"warm-replies-tab-count-handled\"")
                .contains(fx.virtual().getNickname()).contains("收到回复").contains("帖 #" + fx.postId())
                .contains("暖贴回复跟进").contains("data-workbench");
        // 侧栏待办中心组出现本页（权限 comment.virtual_post）
        assertThat(page).contains("/admin/warm-replies\"");

        String rows = mvc.perform(get("/admin/warm-replies").param("lang", "zh_CN").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(rows).contains("warm-replies-row-" + fx.followupId()).doesNotContain("<html").doesNotContain("id=\"warm-replies-queue\"");

        String detail = mvc.perform(get("/admin/warm-replies/" + fx.followupId() + "/detail").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("data-followup-id=\"" + fx.followupId() + "\"").contains("暖评").contains("谢谢回复！")
                .contains("wr-fresh").contains("新回复")
                .contains("以 @" + fx.virtual().getNickname() + " 继续回复").contains("身份锁定")
                .contains("/admin/warm-replies/" + fx.followupId() + "/reply").contains("/admin/warm-replies/" + fx.followupId() + "/read")
                .contains("name=\"idempotencyKey\"").doesNotContain("name=\"virtualUserId\"").doesNotContain("<select");
    }

    @Test
    void replyGoesThroughCommentChainAndHandlesItem() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.COMMENT_VIRTUAL_POST);
        Fixture fx = pendingItem();
        String key = UUID.randomUUID().toString();
        String body = "不客气，有问题随时问～ stub-high";
        long before = comments.count();

        // 共享库不回滚：别的用例 / 测试类可能留有 PENDING 项 → 「下一条」按实际情况断言（本条处理后队列顶部那条）
        Long next = followups.findByStatusOrderByLastReplyAtDescIdDesc(FollowupStatus.PENDING, org.springframework.data.domain.PageRequest.of(0, 2))
                .getContent().stream().map(WarmReplyFollowup::getId).filter(x -> !x.equals(fx.followupId())).findFirst().orElse(null);
        MvcResult r = mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/reply").param("body", body).param("idempotencyKey", key)
                        .param("lang", "zh_CN").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn();
        String frag = r.getResponse().getContentAsString();
        assertThat(frag).contains("class=\"wr-done\"").contains("id=\"warm-replies-row-" + fx.followupId() + "\" hx-swap-oob=\"delete\"")
                .contains("id=\"warm-replies-tab-count-pending\"").contains("已提交，审核通过后显示").contains(body).contains("审核中")
                .contains("已回复");
        if (next == null) {
            assertThat(frag).doesNotContain("data-next-id"); // 没有别的待跟进项 → 留在本条（已跟进只读）
        } else {
            assertThat(frag).contains("data-next-id=\"" + next + "\"");
        }

        assertThat(comments.count()).isEqualTo(before + 1);
        WarmReplyFollowup f = followups.findById(fx.followupId()).orElseThrow();
        assertThat(f.getStatus()).isEqualTo(FollowupStatus.HANDLED);
        assertThat(f.getHandledAction()).isEqualTo(HandledAction.REPLIED);
        assertThat(f.getHandledBy()).isEqualTo(ops.getAdminAccountId());
        Comment created = comments.findById(f.getHandledCommentId()).orElseThrow();
        assertThat(created.getAuthorId()).isEqualTo(fx.virtual().getId()); // 身份锁定为被回复的虚拟账号
        assertThat(created.getParentId()).isEqualTo(fx.warmId()); // 二级评论，挂在暖评下
        assertThat(created.getModerationStatus()).isEqualTo(CommentModerationStatus.UNDER_REVIEW);
        var audit = audits.findAllByOrderByIdAsc().stream()
                .filter(a -> AuditActions.COMMENT_VIRTUAL_POST.equals(a.getActionType()) && String.valueOf(created.getId()).equals(a.getTargetId()))
                .findFirst().orElseThrow();
        assertThat(audit.getSummary()).contains("followupId=" + fx.followupId()).contains("virtualUserId=" + fx.virtual().getId());
        assertThat(queue.pendingCount()).isEqualTo(followups.countByStatus(FollowupStatus.PENDING));

        // 幂等重放：同 key → 200、不产生第二条；已 HANDLED 项换 key 再回复 → 422 行内 err
        mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/reply").param("body", body).param("idempotencyKey", key)
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(comments.count()).isEqualTo(before + 1);
        String err = mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/reply").param("body", body)
                        .param("idempotencyKey", UUID.randomUUID().toString()).param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("已跟进");
        // 已跟进页签能看到它
        String handled = mvc.perform(get("/admin/warm-replies/queue").param("tab", "handled").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(handled).contains("warm-replies-row-" + fx.followupId()).contains("已回复");
    }

    @Test
    void emptyBodyBlockedWordReadAndForbidden() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.COMMENT_VIRTUAL_POST);
        AdminUserDetails viewer = admin(AdminPermissions.CONTENT_VIEW);
        Fixture fx = pendingItem();

        // 空内容 → 422
        String err = mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/reply").param("body", "   ")
                        .param("idempotencyKey", UUID.randomUUID().toString()).param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error").contains("1～200");
        // L1 黑名单命中（V47 印尼语词库）→ 422，项仍 PENDING
        mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/reply").param("body", "ayo main judi online")
                        .param("idempotencyKey", UUID.randomUUID().toString())
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(followups.findById(fx.followupId()).orElseThrow().getStatus()).isEqualTo(FollowupStatus.PENDING);

        // 无权限（只有 content.view）：整页 / 写端点 403
        mvc.perform(get("/admin/warm-replies").with(user(viewer))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/read").with(user(viewer)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isForbidden());

        // 标记已读 → HANDLED/READ + 审计；再读 → 422
        String done = mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/read").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Trigger", "{\"admin:badge-refresh\":{}}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(done).contains("已标记为已读").contains("hx-swap-oob=\"delete\"").contains("已读");
        WarmReplyFollowup f = followups.findById(fx.followupId()).orElseThrow();
        assertThat(f.getStatus()).isEqualTo(FollowupStatus.HANDLED);
        assertThat(f.getHandledAction()).isEqualTo(HandledAction.READ);
        assertThat(audits.findAllByOrderByIdAsc().stream()
                .anyMatch(a -> AuditActions.WARM_REPLY_READ.equals(a.getActionType()) && String.valueOf(fx.followupId()).equals(a.getTargetId()))).isTrue();
        mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/read").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deletedContentShowsPlaceholderAndOnlyAllowsRead() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.COMMENT_VIRTUAL_POST);
        Fixture fx = pendingItem();
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", fx.postId());

        String detail = mvc.perform(get("/admin/warm-replies/" + fx.followupId() + "/detail").param("lang", "zh_CN").with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("内容已删除").contains("/admin/warm-replies/" + fx.followupId() + "/read")
                .doesNotContain("data-wr-form");
        mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/reply").param("body", "hi")
                        .param("idempotencyKey", UUID.randomUUID().toString())
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/warm-replies/" + fx.followupId() + "/read").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(followups.findById(fx.followupId()).orElseThrow().getHandledAction()).isEqualTo(HandledAction.READ);
    }

    @Test
    void handledItemWhenAnotherPendingExistsCarriesNextId() throws Exception {
        AdminUserDetails ops = admin(AdminPermissions.COMMENT_VIRTUAL_POST);
        Fixture a = pendingItem();
        Fixture b = pendingItem(); // last_reply_at 最新 → 处理 a 后队列顶部就是 b
        String done = mvc.perform(post("/admin/warm-replies/" + a.followupId() + "/read").with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(done).contains("data-next-id=\"" + b.followupId() + "\"");
    }
}
