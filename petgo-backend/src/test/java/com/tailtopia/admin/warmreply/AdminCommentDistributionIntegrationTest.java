package com.tailtopia.admin.warmreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.admin.warmreply.dto.DistributionFilter;
import com.tailtopia.admin.warmreply.dto.DistributionRow;
import com.tailtopia.admin.warmreply.dto.DistributionSummary;
import com.tailtopia.admin.warmreply.repository.PostDistributionQuery;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（真库 + 真 Thymeleaf）：帖子评论分布页签（V1.3.0 Story 4.1 AC6）。
 * MockMvc 四条：整页 200 / {@code HX-Request} 返 fragment / 只看权限 → 「去评论」禁用态 / 非法 N → 422 行内 err；
 * 聚合口径：3 帖（真实作者 2、虚拟作者 1）+ 真实 / 虚拟 / 已删 / 非 VISIBLE 评论，断言双口径篇均、0 评论占比、虚拟占比。
 * 样本发布时间回拨到 2020-02（共享库不回滚，用远离其它数据的日期段筛选）。
 */
class AdminCommentDistributionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentService contentService;

    @Autowired
    private CommentRepository comments;

    @Autowired
    private PostDistributionQuery query;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminAccountService accountService;

    @Autowired
    private AdminUserDetailsService userDetailsService;

    /** 每个测试各用一天（共享库不回滚、测试方法顺序不定）：2020-02-10 起按 SEQ 错开。 */
    private final LocalDate DAY = LocalDate.of(2020, 2, 10).plusDays(SEQ.incrementAndGet() % 3000);

    /** 真建账号再取 principal（admin 链每请求复核账号状态 / security_version，手造 id 会被踢回登录页）。 */
    private AdminUserDetails admin(AdminRole role, String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "cd-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "分布 " + seq, role, List.of(perms), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private long post(User author, String text) {
        long id = contentService.publish(author.getId(), new ContentPostCreateRequest(ContentType.DAILY, null, text, null),
                UUID.randomUUID().toString()).id();
        jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(DAY.atStartOfDay(java.time.ZoneId.of("Asia/Jakarta")).plusHours(10).toInstant()), id);
        return id;
    }

    private Comment comment(long postId, User author, String body) {
        return comments.save(Comment.create(postId, null, author.getId(), body));
    }

    /** 造样本：P1 真实作者 3 条可见（2 真实 + 1 虚拟）+ 1 已删 + 1 UNDER_REVIEW；P2 真实作者 0 条；P3 虚拟作者 1 条虚拟评论。 */
    private long[] seed() {
        User a = newUser();
        User b = newUser();
        User v = users.save(User.newVirtual("virtual:" + UUID.randomUUID(), "MeowSisters", null, 1L));
        long p1 = post(a, "第一帖：一段足够长的正文用来验证摘要只取前四十个字符然后加省略号的行为是否正确无误");
        long p2 = post(b, "第二帖 冷帖");
        long p3 = post(v, "第三帖 虚拟作者");
        comment(p1, b, "真实评论 1");
        comment(p1, a, "真实评论 2");
        comment(p1, v, "虚拟评论");
        Comment deleted = comment(p1, b, "已删评论");
        jdbc.update("UPDATE comments SET deleted_at = now() WHERE id = ?", deleted.getId());
        Comment pending = comment(p1, b, "审核中评论");
        jdbc.update("UPDATE comments SET moderation_status = 'UNDER_REVIEW' WHERE id = ?", pending.getId());
        comment(p3, v, "虚拟作者帖上的虚拟评论");
        return new long[] {p1, p2, p3};
    }

    @Test
    void aggregatesDualScopeAveragesZeroShareAndVirtualShare() {
        long[] ids = seed();
        DistributionFilter excl = DistributionFilter.of("all", null, DAY, DAY, null, "all", true, 0);
        DistributionFilter incl = DistributionFilter.of("all", null, DAY, DAY, null, "all", false, 0);

        // 排除虚拟账号内容（默认）：P1 + P2；篇均 含虚拟 = 3/2 = 1.5，剔除虚拟 = 2/2 = 1.0；0 评论帖 1 (50%)；虚拟占比 1/3
        DistributionSummary s = query.summary(excl);
        assertThat(s.posts()).isEqualTo(2);
        assertThat(s.avgAll()).isEqualByComparingTo("1.5");
        assertThat(s.avgReal()).isEqualByComparingTo("1");
        assertThat(s.zeroPosts()).isEqualTo(1);
        assertThat(s.zeroPercent()).isEqualByComparingTo("50.0");
        assertThat(s.virtualPercent()).isEqualByComparingTo("33.3");

        // 含虚拟账号的帖：P1 + P2 + P3；篇均 含虚拟 = 4/3，剔除虚拟 = 2/3；虚拟占比 2/4
        DistributionSummary all = query.summary(incl);
        assertThat(all.posts()).isEqualTo(3);
        assertThat(all.avgAllRounded()).isEqualByComparingTo("1.33");
        assertThat(all.avgRealRounded()).isEqualByComparingTo("0.67");
        assertThat(all.virtualPercent()).isEqualByComparingTo("50.0");

        // 列表：评论数升序（P2 0 → P1 3）；摘要 40 字 + …；虚拟计数；=0 快捷只剩 P2
        List<DistributionRow> rows = query.list(excl);
        assertThat(rows).extracting(DistributionRow::postId).containsExactly(ids[1], ids[0]);
        assertThat(rows.get(1).commentCount()).isEqualTo(3);
        assertThat(rows.get(1).virtualCount()).isEqualTo(1);
        assertThat(rows.get(1).summary()).hasSize(41).endsWith("…");
        assertThat(rows.get(0).authorVirtual()).isFalse();
        assertThat(query.list(DistributionFilter.of("zero", null, DAY, DAY, null, "all", true, 0)))
                .extracting(DistributionRow::postId).containsExactly(ids[1]);
        assertThat(query.fetch(excl).total()).isEqualTo(2);
        // 虚拟作者帖带标识
        assertThat(query.list(incl)).filteredOn(r -> r.postId() == ids[2]).singleElement()
                .satisfies(r -> assertThat(r.authorVirtual()).isTrue());
        // 「审核挂起 / 已隐藏」= 未删且非 PUBLISHED；已删帖（含运营软删下架）不出现在任何状态筛选下（AC5）
        jdbc.update("UPDATE content_posts SET status = 'UNDER_REVIEW' WHERE id = ?", ids[0]);
        assertThat(query.list(DistributionFilter.of("all", null, DAY, DAY, null, "takendown", true, 0)))
                .extracting(DistributionRow::postId).containsExactly(ids[0]);
        assertThat(query.list(DistributionFilter.of("all", null, DAY, DAY, null, "visible", true, 0)))
                .extracting(DistributionRow::postId).containsExactly(ids[1]);
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", ids[1]);
        assertThat(query.list(DistributionFilter.of("all", null, DAY, DAY, null, "takendown", true, 0)))
                .extracting(DistributionRow::postId).doesNotContain(ids[1]);
        assertThat(query.fetch(excl).total()).isEqualTo(1);
    }

    @Test
    void fullPageAndFragmentAndPermissionsAndBadN() throws Exception {
        seed();
        AdminUserDetails superAdmin = admin(AdminRole.SUPER_ADMIN);
        AdminUserDetails viewer = admin(AdminRole.CUSTOM, AdminPermissions.CONTENT_VIEW);
        AdminUserDetails commenter = admin(AdminRole.CUSTOM, AdminPermissions.COMMENT_VIRTUAL_POST);

        // ① 整页 200：页签条 + 分布片段 + 摘要四格
        String page = mvc.perform(get("/admin/comments/distribution").param("from", DAY.toString()).param("to", DAY.toString())
                        .param("lang", "zh_CN").with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("comments-tabs").contains("id=\"comments-tab-body\"").contains("id=\"comments-distribution\"")
                .contains("篇均评论数").contains("data-post-id=").contains("tab--on");

        // ② HX-Request 只回片段
        String frag = mvc.perform(get("/admin/comments/distribution").param("from", DAY.toString()).param("to", DAY.toString())
                        .with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(frag).contains("id=\"comments-distribution\"").doesNotContain("<html").doesNotContain("comments-tabs");

        // ③ 只有 content.view：能看，但「去评论」禁用并注明所缺权限；有 comment.virtual_post 则可用
        String viewerHtml = mvc.perform(get("/admin/comments/distribution").param("from", DAY.toString()).param("to", DAY.toString())
                        .param("lang", "zh_CN").with(user(viewer)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewerHtml).contains("disabled").contains("以虚拟身份评论").doesNotContain("data-drawer-url");
        String commenterHtml = mvc.perform(get("/admin/comments/distribution").param("from", DAY.toString()).param("to", DAY.toString())
                        .with(user(commenter)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(commenterHtml).contains("data-drawer-url");
        // 无任一相关权限 → 403
        mvc.perform(get("/admin/comments/distribution").with(user(admin(AdminRole.CUSTOM, AdminPermissions.VET_VIEW))))
                .andExpect(status().isForbidden());

        // ④ 自定义 N 非正整数 / 非数字 → 422 行内 err（htmx）；整页手输 → 200 降级为「全部」并挂 error 提示
        String err = mvc.perform(get("/admin/comments/distribution").param("count", "custom").param("n", "0")
                        .with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(err).contains("inline-error");
        mvc.perform(get("/admin/comments/distribution").param("count", "custom").param("n", "abc")
                        .with(user(superAdmin)).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());
        String lenient = mvc.perform(get("/admin/comments/distribution").param("count", "custom").param("n", "0").param("lang", "zh_CN")
                        .with(user(superAdmin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(lenient).contains("banner err").contains("id=\"comments-distribution\"");

        // 页签一不变：/admin/comments 仍 200 且带页签条 + 原表格
        String inspect = mvc.perform(get("/admin/comments").with(user(superAdmin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(inspect).contains("comments-tabs").contains("id=\"comments-inspect\"");
    }
}
