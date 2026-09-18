package com.tailtopia.admin.moderation;

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
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（V1.3.0 Story 7.1 · B1 内容管理 + 详情抽屉，需 Docker postgres+redis）：
 * 列表套模板 B（筛选参数逐字不变 / 摘要条五格随筛选联动 / 行开抽屉、行内不再有处置表单）、
 * 抽屉六区、处置端点 htmx 分支（抽屉 + oob 行 + toast，路径与参数不变）、限流走既有 back=content
 * 触发内容页刷新事件、权限不足 403。
 */
class AdminContentDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;

    private AdminUserDetails admin(String... perms) {
        long seq = SEQ.incrementAndGet();
        String email = "b1-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "内容运营 " + seq, AdminRole.CUSTOM, List.of(perms), 960000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private AdminUserDetails fullOps() {
        return admin(AdminPermissions.CONTENT_VIEW, AdminPermissions.CONTENT_PROACTIVE_TAKEDOWN,
                AdminPermissions.CONTENT_RESTORE, AdminPermissions.CONTENT_THROTTLE_VIEW,
                AdminPermissions.CONTENT_THROTTLE_MANAGE);
    }

    private long newPost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of())).getId();
    }

    /** AC1 / AC2 / AC3：模板 B 壳 + 筛选参数名不变 + 摘要条随筛选联动 + 行开抽屉、行内无处置表单。 */
    @Test
    void listIsTemplateBWithSummaryAndDrawerRows() throws Exception {
        AdminUserDetails ops = fullOps();
        long author = newUser().getId();
        long online = newPost(author, "B1-列表-" + SEQ.incrementAndGet());
        long gone = newPost(author, "B1-已下架-" + SEQ.incrementAndGet());
        var p = posts.findById(gone).orElseThrow();
        p.softDelete();
        posts.save(p);

        String html = mvc.perform(get("/admin/content").param("lang", "zh_CN")
                        .param("authorId", String.valueOf(author)).with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-list").contains("id=\"content-summary\"").contains("id=\"content-rows\"")
                .contains("id=\"content-drawer\"")
                // 筛选参数名逐字不变（AC1）
                .contains("name=\"type\"").contains("name=\"authorId\"").contains("name=\"status\"")
                .contains("name=\"dateBasis\"").contains("name=\"from\"").contains("name=\"to\"")
                .contains("name=\"q\"").contains("name=\"species\"").contains("name=\"speciesSource\"")
                // 行可开抽屉；🔴 行内处置表单已全部收进抽屉（逐页规格 B1）
                .contains("data-drawer-url=\"/admin/content/" + online + "/drawer\"")
                .contains("id=\"content-row-" + online + "\"")
                .doesNotContain("/takedown\"").doesNotContain("name=\"duration\"");

        // 摘要条随筛选联动：按该作者筛 ⇒ 总数 2、已下架 1（今日新增同为 2，都是本次造的）
        String summary = html.substring(html.indexOf("id=\"content-summary\""),
                html.indexOf("id=\"content-rows\""));
        assertThat(digits(summary)).as("总帖数 · 今日新增 · 审核中 · 限流中 · 已下架")
                .containsExactly(2L, 2L, 0L, 0L, 1L);

        // htmx 局部刷新：只回表格片段，摘要条随 oob 一并换
        String frag = mvc.perform(get("/admin/content").param("authorId", String.valueOf(author))
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(frag).contains("id=\"content-summary\" hx-swap-oob=\"true\"")
                .doesNotContain("data-list"); // 片段不带整页壳
    }

    /** AC4 / AC5 / AC6：抽屉六区；下架 → 抽屉 + oob 行 + toast 且落库；恢复同理；端点与参数不变。 */
    @Test
    void drawerCarriesAllSectionsAndDispositionsReturnDrawerPlusRow() throws Exception {
        AdminUserDetails ops = fullOps();
        long author = newUser().getId();
        long postId = newPost(author, "B1-抽屉-" + SEQ.incrementAndGet());

        String drawer = mvc.perform(get("/admin/content/" + postId + "/drawer").param("lang", "zh_CN")
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-post-id=\"" + postId + "\"")
                .contains("B1-抽屉-")                                   // ① 正文全文
                .contains("浏览次数").contains("浏览人数")                  // ② 数据卡（原详情页字段一个不少）
                .contains("data-section=\"content-species\"")            // ③ 物种归属
                .contains("data-section=\"content-throttle\"")           // ④ 限流卡
                .contains("data-section=\"content-comments\"")           // ⑤ 评论区
                .contains("hx-post=\"/admin/content/" + postId + "/takedown\"") // ⑥ 操作条
                .contains("id=\"content-drawer-err\"");

        // 下架（htmx）：端点 / 参数不变 → 抽屉重渲染 + oob 行 + toast，且真落库
        String done = mvc.perform(post("/admin/content/" + postId + "/takedown")
                        .param("reason", "违规测试").param("lang", "zh_CN")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(done).contains("class=\"toast\"")
                // 🔴 抽屉体走 oob 换回去（主目标是 err 槽）：htmx 只有目标带 id 才发 HX-Target 头，
                //    指 .drawer-body（无 id）会让 422 落到表格下方看不见的地方（复审 C1）
                .contains("hx-swap-oob=\"innerHTML:#content-drawer .drawer-body\"")
                .contains("id=\"content-row-" + postId + "\"").contains("hx-swap-oob=\"true\"")
                .contains("hx-post=\"/admin/content/" + postId + "/restore\""); // 抽屉已切到「恢复」
        assertThat(posts.findById(postId).orElseThrow().getDeletedAt()).isNotNull();

        // 下架原因为空 → 422 行内 err（校验码不变），库里状态不变
        mvc.perform(post("/admin/content/" + postId + "/takedown").param("reason", "")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());

        // 恢复（htmx）
        mvc.perform(post("/admin/content/" + postId + "/restore")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk());
        assertThat(posts.findById(postId).orElseThrow().getDeletedAt()).isNull();
    }

    /**
     * AC5 限流：端点与参数零变更（{@code scope} / {@code postTargetId} / {@code duration} / {@code back}），
     * htmx 成功回 toast 片段并带 {@code HX-Trigger: admin:content-refresh} —— 内容页抽屉与列表各自重拉。
     */
    @Test
    void throttleFromDrawerTriggersContentRefresh() throws Exception {
        AdminUserDetails ops = fullOps();
        long postId = newPost(newUser().getId(), "B1-限流-" + SEQ.incrementAndGet());

        mvc.perform(post("/admin/throttles").param("scope", "POST")
                        .param("postTargetId", String.valueOf(postId))
                        .param("duration", "DAYS_7").param("back", "content")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk())
                // 列表与抽屉各自重拉：两个事件都得发（合成一个会让抽屉刚渲染完又被重拉一遍，复审 C2）
                .andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("admin:content-list-refresh")))
                .andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("admin:content-drawer-refresh")));

        String drawer = mvc.perform(get("/admin/content/" + postId + "/drawer").param("lang", "zh_CN")
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("hx-post=\"/admin/throttles/lift\"");
    }

    /**
     * 🛡 限流粒度漏选**必须报错**：空串经 Spring 的枚举转换器会变成 null，
     * 而「null 不是 POST 就当 ACCOUNT」会把整个账号降权，且界面上看不出降错了对象（复审 C5）。
     */
    @Test
    void throttleWithoutScopeIsRejected() throws Exception {
        AdminUserDetails ops = fullOps();
        var author = newUser();
        long postId = newPost(author.getId(), "B1-漏选粒度-" + SEQ.incrementAndGet());

        mvc.perform(post("/admin/throttles").param("scope", "")
                        .param("postTargetId", String.valueOf(postId))
                        .param("accountTargetId", String.valueOf(author.getId()))
                        .param("duration", "PERMANENT").param("back", "content")
                        .with(user(ops)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());

        // 账号一行都不该被降权
        String drawer = mvc.perform(get("/admin/content/" + postId + "/drawer").param("lang", "zh_CN")
                        .with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("hx-post=\"/admin/throttles/lift\"");
    }

    /**
     * AC6 深链：`?open=<postId>` 指向的帖子**十有八九不在首页 20 行里**（复核队列 / 工单点过来的）。
     * 页面必须给出按 id 直取抽屉的兜底入口，否则 admin-drawer.js 按行找不到就静默什么都不发生（复审 C6）。
     */
    @Test
    void deepLinkWorksForPostsOutsideTheFirstPage() throws Exception {
        AdminUserDetails ops = fullOps();
        long postId = newPost(newUser().getId(), "B1-深链-" + SEQ.incrementAndGet());
        // 造一页之后的位置：再发 PAGE_SIZE 条更新的内容，把它挤出首页
        for (int i = 0; i < 21; i++) {
            newPost(newUser().getId(), "B1-深链-占位-" + SEQ.incrementAndGet());
        }

        String html = mvc.perform(get("/admin/content").param("open", String.valueOf(postId)).with(user(ops)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("id=\"content-row-" + postId + "\"")   // 确实不在首页
                .contains("data-drawer-deeplink=\"/admin/content/" + postId + "/drawer\"");
    }

    /** AC5 门控：只看不改的账号拿不到处置表单；完全无权 403。 */
    @Test
    void permissionsGateTheDrawerActions() throws Exception {
        long postId = newPost(newUser().getId(), "B1-门控-" + SEQ.incrementAndGet());

        String viewer = mvc.perform(get("/admin/content/" + postId + "/drawer")
                        .with(user(admin(AdminPermissions.CONTENT_VIEW))).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(viewer).doesNotContain("/takedown\"").doesNotContain("hx-post=\"/admin/throttles\"");

        mvc.perform(get("/admin/content/" + postId + "/drawer")
                        .with(user(admin(AdminPermissions.CONTENT_THROTTLE_VIEW))).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }

    /** 摘要条里的五个数字（按出现顺序）。 */
    private static List<Long> digits(String summaryHtml) {
        var m = java.util.regex.Pattern.compile("class=\"sum-value\">(\\d+)<").matcher(summaryHtml);
        List<Long> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(Long.parseLong(m.group(1)));
        }
        return out;
    }
}
