package com.tailtopia.admin.contenttag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentTag;
import com.tailtopia.content.domain.ContentTagAssignment;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentTagAssignmentRepository;
import com.tailtopia.content.repository.ContentTagRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1 集成：B4 内容标签的模板 B 壳与抽屉（V1.3.0 Story 7.4）。
 *
 * <p>本 story 改的是**页面组织**不是机制：主表只留标签一张表，编辑 / 分配记录 / 打标全部收进抽屉，
 * 5 个 POST 的路径与参数逐字未动。因此这里钉三类东西：
 * <ul>
 *   <li>页尾那三块（新建标签 / 给内容打标 / 分配记录表）**真的消失了** ——
 *       只把抽屉加上、旧区块留着，就是同一件事有两个入口，比改之前更乱；</li>
 *   <li>抽屉两个页签各自渲染得出来，且分配记录带**状态列与操作人**（本 story 的功能增量）；</li>
 *   <li>htmx 写分支回的是 fragment + 正确的 HX-Trigger，失败落在**带 id 的** err 槽里。</li>
 * </ul>
 *
 * <p>⚠️ 与 {@code AdminContentTagIntegrationTest} 分开：那个类钉的是机制（私密内容不可打标、
 * 下线只挡新分配、×1.3 提示、双权限码），本 story 一条都没动，回归照跑。
 */
class AdminContentTagDrawerIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @Autowired
    private ContentTagRepository tags;

    @Autowired
    private ContentTagAssignmentRepository assignments;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private AdminAccountRepository adminAccounts;

    /** 操作人列要显示的名字（审计 → 账号显示名）。 */
    private String lastAdminName;

    private Authentication superAdminAuth() {
        return staffAuth(AdminAccountType.SUPER_ADMIN);
    }

    private Authentication staffAuth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        lastAdminName = "标签抽屉测试员" + n;
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "tagdrawer-" + n + "@tailtopia.test", lastAdminName, "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(principal, null,
                    new ArrayList<>(principal.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它，少了拿到的是过滤链 403，
        //    那样「无 manage 应 403」的断言会假绿 —— @PreAuthorize 一次都没被验到。
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private ContentTag tag(String prefix) {
        String code = prefix + "_" + SEQ.incrementAndGet();
        return tags.save(ContentTag.of(code, "标签" + code, "icon.png", "编辑精选"));
    }

    private ContentPost publicPost(String text) {
        User author = newUser();
        return posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, text, List.of()));
    }

    private static String wib(int day, int hour) {
        return String.format("2026-11-%02dT%02d:00", day, hour);
    }

    private static Instant wibInstant(int day, int hour) {
        return ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, WIB).toInstant();
    }

    /** 把一个绝对时刻写成表单里的 `datetime-local`（按 WIB）—— 状态三态要相对**此刻**摆夹具。 */
    private static String localWib(Instant at) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                .format(at.atZone(WIB));
    }

    private static MockMultipartFile iconPng() {
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return new MockMultipartFile("iconFile", "icon.png", "image/png", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 主表只有标签 ———————————————————

    @Test
    void listPageUsesTemplateBShellAndDropsTheThreeLegacyBlocks() throws Exception {
        ContentTag t = tag("SHELL");
        String html = body(mvc.perform(get("/admin/content-tags").param("lang", "zh_CN")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("模板 B 壳 + 抽屉容器").contains("id=\"tag-drawer\"").contains("data-drawer-mask");
        assertThat(html).as("摘要条两格").contains("id=\"tags-summary\"")
                .contains("在线标签数").contains("生效中分配数");
        assertThat(html).as("表格容器与行的抽屉入口").contains("id=\"tags-rows\"")
                .contains("data-drawer-url=\"/admin/content-tags/" + t.getId() + "/drawer\"");
        assertThat(html).as("胶囊按真实样子画出来").contains("border-radius:999px");
        assertThat(html).as("＋新建标签是抽屉入口，不再是页尾常驻表单")
                .contains("data-drawer-open=\"/admin/content-tags/new/drawer\"");

        assertThat(html).as("🔴 页尾「给内容打标」区块必须删干净（否则同一件事两个入口）")
                .doesNotContain("给内容打标").doesNotContain("选择标签");
        assertThat(html).as("🔴 页内第二张「分配记录」表与其筛选栏必须删干净")
                .doesNotContain("按标签看").doesNotContain("按内容看");
        assertThat(html).as("🔴 页尾常驻的「新建标签」表单也必须删掉")
                .doesNotContain("name=\"description\"");
    }

    @Test
    void htmxListRefreshReturnsRowsPlusOobSummary() throws Exception {
        tag("OOB");
        String html = body(mvc.perform(get("/admin/content-tags").param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("局部刷新只回表格 + oob 摘要条，不回整页")
                .doesNotContain("<html").contains("hx-swap-oob").contains("id=\"tags-summary\"");
    }

    // ——————————————————— AC2 抽屉页签一「编辑」———————————————————

    @Test
    void editTabRendersTheFormWithCurrentValuesAndRetireAction() throws Exception {
        ContentTag t = tag("EDIT_TAB");
        String html = body(mvc.perform(get("/admin/content-tags/" + t.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"tag-drawer-panel\"").contains("data-tab=\"edit\"");
        assertThat(html).as("表单带当前值").contains(t.getName());
        assertThat(html).as("三个字段 + 图标上传，标签码不出现在表单里")
                .contains("name=\"name\"").contains("name=\"description\"")
                .contains("name=\"badgeStyle\"").contains("name=\"iconFile\"")
                .doesNotContain("name=\"code\"");
        // 🔴 「未修改保存禁用」由抽屉里那段脚本加上，HTML 里刻意**不**写死 disabled：
        //    写死的话脚本一旦没跑（将来上 CSP 会拦内联脚本），保存钮就永久禁用、编辑整个不可用。
        assertThat(html).contains("data-tag-save").contains("save.disabled = !dirty()");
        assertThat(html).as("按钮本身不带服务端渲染的 disabled")
                .doesNotContain("data-tag-save disabled");
        assertThat(html).as("🔴 err 槽必须带 id：htmx 只在目标带 id 时才发 HX-Target 头")
                .contains("id=\"tag-drawer-err\"");
        assertThat(html).as("下线确认必须复述生效分配数").contains("data-confirm").contains("生效中的分配");
        assertThat(html).as("排序副作用提示随表单一起进抽屉").contains("流量动作");
    }

    @Test
    void nonHtmxDrawerUrlRedirectsBackToTheListWithOpenParam() throws Exception {
        ContentTag t = tag("DEEPLINK");
        mvc.perform(get("/admin/content-tags/" + t.getId() + "/drawer")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/content-tags?open=" + t.getId()));
    }

    // ——————————————————— AC3 抽屉页签二「分配记录」———————————————————

    /**
     * 🔴 状态列必须是**三态**，且三条夹具的时间要相对 <b>此刻</b> 摆放。
     *
     * <p>⚠️ 本条第一版把三条分配全排在了一个固定的未来日期上 —— 于是它们在页面上**全是同一个状态**，
     * `.contains("已到期")` 恒真、`active` 写死成 false 也照样绿，而那正是它本该抓住的缺陷
     * （排期中的分配被显示成「已到期」，运营会当成排期作废、再打一次标）。
     */
    @Test
    void assignmentsTabShowsThreeStateStatusAndOperator() throws Exception {
        ContentTag t = tag("RECORDS");
        ContentPost active = publicPost("生效中的内容");
        ContentPost expired = publicPost("已到期的内容");
        ContentPost scheduled = publicPost("下周才生效的内容");
        Authentication admin = superAdminAuth();
        String actor = lastAdminName;
        Instant now = Instant.now();

        // 经后台打标 → 审计里有 CONTENT_TAG_ASSIGN，操作人列才有值（这一条同时是「生效中」那档）
        mvc.perform(post("/admin/content-tags/assign").with(authentication(admin)).with(csrf())
                        .param("postId", String.valueOf(active.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", localWib(now.minusSeconds(3600))))
                .andExpect(status().is3xxRedirection());
        assignments.save(ContentTagAssignment.of(expired.getId(), t.getId(),
                now.minusSeconds(7200), now.minusSeconds(3600)));
        assignments.save(ContentTagAssignment.of(scheduled.getId(), t.getId(),
                now.plusSeconds(7 * 24 * 3600), null));

        String html = body(mvc.perform(get("/admin/content-tags/" + t.getId() + "/drawer")
                        .param("tab", "assignments").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("data-tab=\"assignments\"");
        assertThat(html).as("内容 + 打标时间 + 起止 + 状态 + 操作人五列都在")
                .contains("生效中的内容").contains("已到期的内容").contains("下周才生效的内容")
                .contains("打标时间").contains("起止时间（WIB）").contains("操作人");
        assertThat(html).as("🔴 三态各出现一次：只有「生效中 / 已到期」两档时，"
                        + "排在下周的分配会被写成「已到期」")
                .contains("badge-ok\">生效中").contains("badge-muted\">已到期")
                .contains("badge-neutral\">待生效");
        assertThat(html).as("操作人取自审计（分配表没有这一列）").contains(actor);
        assertThat(html).as("操作人可能整列为空，得说清为什么").contains("操作日志");
        assertThat(html).as("永久分配显示「永久」").contains("永久");
        assertThat(html).as("＋添加内容是唯一打标入口").contains("添加内容")
                .contains("name=\"tagId\"");
    }

    @Test
    void assignmentPageBeyondTheEndFallsBackInsteadOf500() throws Exception {
        ContentTag t = tag("PAGER");
        ContentPost p = publicPost("只有一条");
        assignments.save(ContentTagAssignment.of(p.getId(), t.getId(), wibInstant(2, 8), null));

        String html = body(mvc.perform(get("/admin/content-tags/" + t.getId() + "/drawer")
                        .param("tab", "assignments").param("page", "99999999")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("页码越界回退到最后一页，不是 500 也不是空表").contains("只有一条");
    }

    @Test
    void retiredTagDoesNotRenderAnAssignFormThatCouldOnlyFail() throws Exception {
        ContentTag t = tag("RETIRED_FORM");
        Authentication admin = superAdminAuth();
        mvc.perform(post("/admin/content-tags/" + t.getId() + "/retire")
                        .with(authentication(admin)).with(csrf()).param("retired", "true"))
                .andExpect(status().is3xxRedirection());

        String html = body(mvc.perform(get("/admin/content-tags/" + t.getId() + "/drawer")
                        .param("tab", "assignments").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("🔴 已下线的标签不可再分配 —— 渲染一张提交必报错的表单等于让人白填一遍")
                .doesNotContain("name=\"startsAt\"");
        assertThat(html).as("但要说清为什么，否则那一块只是一片空白")
                .contains("data-notice=\"assign-retired\"");
    }

    // ——————————————————— AC4 / AC5 htmx 写分支 ———————————————————

    @Test
    void editViaHtmxReturnsTheDrawerAndAsksTheListToRefresh() throws Exception {
        ContentTag t = tag("HX_EDIT");
        MvcResult r = mvc.perform(multipart("/admin/content-tags/" + t.getId() + "/edit")
                        .param("name", "改过的名字").param("description", "改过的说明")
                        .param("badgeStyle", "VIOLET").param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(tags.findById(t.getId()).orElseThrow().getName()).isEqualTo("改过的名字");
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("admin:tag-list-refresh");
        String html = body(r);
        assertThat(html).as("抽屉体 oob 回带重渲染结果 + toast")
                .contains("hx-swap-oob=\"innerHTML:#tag-drawer .drawer-body\"")
                .contains("改过的名字").contains("class=\"toast\"");
    }

    @Test
    void createViaHtmxOpensTheNewTagDrawerOnTheAssignmentsTab() throws Exception {
        String name = "新建标签" + SEQ.incrementAndGet();
        MvcResult r = mvc.perform(multipart("/admin/content-tags").file(iconPng())
                        .param("name", name).param("description", "编辑精选")
                        .param("badgeStyle", "GOLD").param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        ContentTag created = tags.findAllByOrderByIdDesc().stream()
                .filter(x -> name.equals(x.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("标签没落库"));
        String trigger = r.getResponse().getHeader("HX-Trigger");
        assertThat(trigger).as("🔴 AC4：建完直接停在新标签的分配记录页签 —— 建标签紧接着就是给它加内容")
                .contains("admin:drawer-open")
                .contains("/admin/content-tags/" + created.getId() + "/drawer?tab=assignments");
        assertThat(trigger).as("列表也要重拉：新行只能靠整表重拉才会出现")
                .contains("admin:tag-list-refresh");
        assertThat(body(r)).as("响应体只是一个 toast（抽屉由事件重开）").contains("class=\"toast\"");
    }

    @Test
    void assigningFromTheDrawerLandsOnTheAssignmentsTab() throws Exception {
        ContentTag t = tag("HX_ASSIGN");
        ContentPost p = publicPost("抽屉里打的标");
        MvcResult r = mvc.perform(post("/admin/content-tags/assign")
                        .param("postId", String.valueOf(p.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(3, 10)).param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(3, 12))).hasSize(1);
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("admin:tag-list-refresh");
        assertThat(body(r)).as("回到分配记录页签，刚打的那条就在里面")
                .contains("data-tab=\"assignments\"").contains("抽屉里打的标");
    }

    @Test
    void removingAnAssignmentViaHtmxKeepsTheDrawerOnTheSameTag() throws Exception {
        ContentTag t = tag("HX_REMOVE");
        ContentPost p = publicPost("待取消的内容");
        ContentTagAssignment a = assignments.save(
                ContentTagAssignment.of(p.getId(), t.getId(), wibInstant(4, 8), null));

        MvcResult r = mvc.perform(post("/admin/content-tags/assignments/" + a.getId() + "/remove")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(assignments.findById(a.getId())).isEmpty();
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("admin:tag-list-refresh");
        assertThat(body(r)).as("🔴 删之前得先读出它属于哪个标签，否则抽屉不知道该重渲染谁")
                .contains("data-tab=\"assignments\"")
                .contains("hx-swap-oob=\"innerHTML:#tag-drawer .drawer-body\"");
    }

    /** 🛡 打不成的标要落在**带 id 的**行内 err 槽上出 422，而不是整页 302 把错咽掉。 */
    @Test
    void assigningPrivateContentViaHtmxIsA422InlineError() throws Exception {
        ContentTag t = tag("HX_422");
        User author = newUser();
        ContentPost hidden = ContentPost.publish(author.getId(), ContentType.GROWTH_MOMENT, null,
                "私密日记不可打标", List.of());
        hidden.setVisibility(com.tailtopia.content.domain.ContentVisibility.PRIVATE);
        posts.save(hidden);

        MvcResult r = mvc.perform(post("/admin/content-tags/assign")
                        .param("postId", String.valueOf(hidden.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(5, 10)).param("lang", "zh_CN")
                        .header("HX-Request", "true").header("HX-Target", "tag-assign-err")
                        .with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();

        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#tag-assign-err");
        assertThat(body(r)).contains("inline-error");
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(5, 12))).isEmpty();
    }

    /**
     * 🔴 **没选内容就点「打标」必须看得见报错**。
     *
     * <p>候选项是一排 radio，在 480px 抽屉里离提交钮很远，只填了时间就提交是常见操作。
     * 若 {@code postId} 声明成必填基本类型，Spring 抛的是缺参数 <b>400 ProblemDetail</b>，
     * 而 {@code admin-core.js} 的 {@code htmx:beforeSwap} 只放行 422/403/404 ——
     * 响应被丢弃、err 槽空白、按钮弹回，界面上**什么都不发生**（改版前整页 POST 至少还会跳一张报错页）。
     */
    @Test
    void assigningWithoutPickingContentIsA422NotASilent400() throws Exception {
        ContentTag t = tag("NO_POST");
        MvcResult r = mvc.perform(post("/admin/content-tags/assign")
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(6, 10)).param("lang", "zh_CN")
                        .header("HX-Request", "true").header("HX-Target", "tag-assign-err")
                        .with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#tag-assign-err");
        assertThat(body(r)).contains("inline-error").contains("选");
    }

    // ——————————————————— AC5 权限 ———————————————————

    @Test
    void viewOnlyStaffSeesTheDrawerButGetsNoWriteEntry() throws Exception {
        ContentTag t = tag("VIEW_ONLY");
        Authentication viewer = staffAuth(AdminAccountType.STAFF, "content.tag_view");

        String html = body(mvc.perform(get("/admin/content-tags/" + t.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("只读身份看得到抽屉，但拿不到任何写入口").doesNotContain("hx-post");
        assertThat(html).as("AC5：禁用注明所缺权限名，整块空白会让人以为「这个标签没有操作」")
                .contains("disabled").contains("编辑内容标签");

        mvc.perform(get("/admin/content-tags/new/drawer").header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/admin/content-tags/" + t.getId() + "/edit")
                        .param("name", "越权改名").param("description", "x")
                        .header("HX-Request", "true").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(tags.findById(t.getId()).orElseThrow().getName()).isEqualTo(t.getName());
    }

    @Test
    void createDrawerFormHasNoTagCodeField() throws Exception {
        String html = body(mvc.perform(get("/admin/content-tags/new/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("name=\"name\"").contains("name=\"iconFile\"")
                .as("标签码自动生成（ct-<id>），表单里不许再出现").doesNotContain("name=\"code\"");
        assertThat(html).as("图标必传 + 尺寸规范常驻在控件旁")
                .contains("required").contains("data-notice=\"tag-icon-spec\"");
    }
}
