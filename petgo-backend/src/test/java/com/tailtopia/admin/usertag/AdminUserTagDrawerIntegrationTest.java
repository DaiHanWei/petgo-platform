package com.tailtopia.admin.usertag;

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
import com.tailtopia.auth.domain.UserTag;
import com.tailtopia.auth.repository.UserTagAssignmentRepository;
import com.tailtopia.auth.repository.UserTagRepository;
import com.tailtopia.auth.service.UserTagQueryService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
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
 * L1 集成：B8 用户标签套模板 B + 两页签抽屉（V1.3.0 Story 8.2）。
 *
 * <p>本 story 是**零端点变更**的页面重构：5 个 POST 的路径 / 参数 / 权限表达式一个字没动，
 * 只把编辑 / 分配记录 / 加人从页尾三块区域收进抽屉。所以钉的是「搬完之后每一样都还在、
 * 且权限没走散」—— 尤其分配那条动线：多选、手填兜底、注销不可选、顶掉最早，一条都不能丢。
 *
 * <p>⚠️ 与 {@code AdminUserTagIntegrationTest} 分工：那个钉机制（展示上限口径、下线语义、
 * WIB 解释、双权限码），本 story 一条没动；这里只钉页面形态与 htmx 动线。
 */
class AdminUserTagDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserTagRepository tags;

    @Autowired
    private UserTagAssignmentRepository assignments;

    @Autowired
    private UserTagQueryService tagService;

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "utagdrawer-" + n + "@tailtopia.test", "用户标签抽屉测试员", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它，少了拿到的是过滤链 403 ——
        //    那样「无 xx 权限应 403」会假绿（方法门控一次都没被验到）。
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String s : permissions) {
            auths.add(new SimpleGrantedAuthority(s));
        }
        return new TestingAuthenticationToken(p, null, auths);
    }

    private Authentication superAdmin() {
        return auth(AdminAccountType.SUPER_ADMIN);
    }

    private UserTag tag(String label) {
        UserTag t = tags.save(UserTag.of("ut-pending-" + SEQ.incrementAndGet(),
                label + SEQ.incrementAndGet(), "https://cdn.example/x.png", "说明文案"));
        t.assignGeneratedCode("ut-" + t.getId());
        return tags.save(t);
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 主表套模板 B ———————————————————

    @Test
    void listUsesTemplateBWithOnlyTheTagTable() throws Exception {
        UserTag t = tag("B");
        String html = body(mvc.perform(get("/admin/user-tags").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("模板 B 壳 + 抽屉容器").contains("id=\"utag-drawer\"").contains("data-drawer-mask");
        assertThat(html).as("摘要条两格").contains("id=\"utags-summary\"")
                .contains("data-sum=\"tags\"").contains("data-sum=\"assignments\"");
        assertThat(html).as("点行开抽屉").contains("data-drawer-url=\"/admin/user-tags/" + t.getId() + "/drawer\"");
        assertThat(html).as("右上「＋新建标签」走抽屉，不再是页尾常驻表单")
                .contains("data-drawer-open=\"/admin/user-tags/new/drawer\"");

        // 🔴 三块区域整体移除（AC1）：判据取**表单字段名**而不是标题文案 ——
        //    文案随时可能改词，字段名是端点契约的一部分，留在整页上就说明区块还在。
        assertThat(html).as("「批量分配」区块已移除：整页上不该再有分配表单的字段")
                .doesNotContain("name=\"pickedUserIds\"")
                .doesNotContain("name=\"startsAt\"");
        assertThat(html).as("「新建标签」区块已移除：整页上不该再有新建/编辑表单的字段")
                .doesNotContain("name=\"iconFile\"");
        assertThat(html).as("「分配记录」筛选区块已移除")
                .doesNotContain("name=\"tagId\"");
    }

    /** AC1：标签码只读展示 {@code ut-<id>}，任何表单都不再出现输入位。 */
    @Test
    void theTagCodeIsShownButNeverEditable() throws Exception {
        UserTag t = tag("CODE");
        String list = body(mvc.perform(get("/admin/user-tags").param("lang", "zh_CN")
                .with(authentication(superAdmin()))).andReturn());
        String drawer = body(mvc.perform(get("/admin/user-tags/" + t.getId() + "/drawer")
                .param("lang", "zh_CN").header("HX-Request", "true")
                .with(authentication(superAdmin()))).andReturn());

        assertThat(list).contains("ut-" + t.getId());
        assertThat(drawer).contains("ut-" + t.getId());
        assertThat(list + drawer).as("🔴 全程没有标签码输入位（手填会互相撞码）")
                .doesNotContain("name=\"code\"");
        assertThat(drawer).as("徽章底色控件已随「整图即标签」废弃")
                .doesNotContain("name=\"badgeColor\"");
    }

    // ——————————————————— AC2 / AC3 抽屉两页签 ———————————————————

    @Test
    void nonHtmxDrawerUrlRedirectsToTheListWithOpenParam() throws Exception {
        UserTag t = tag("RD");
        mvc.perform(get("/admin/user-tags/" + t.getId() + "/drawer")
                        .with(authentication(superAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/user-tags?open=" + t.getId()));
    }

    @Test
    void theEditTabCarriesTheWholeEditFormAndTheRetireAction() throws Exception {
        UserTag t = tag("EDIT");
        String html = body(mvc.perform(get("/admin/user-tags/" + t.getId() + "/drawer")
                        .param("tab", "edit").param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"utag-drawer-panel\"")
                .contains("hx-post=\"/admin/user-tags/" + t.getId() + "/edit\"")
                .contains("hx-post=\"/admin/user-tags/" + t.getId() + "/retire\"")
                .contains("name=\"name\"").contains("name=\"description\"").contains("name=\"iconFile\"");
        assertThat(html).as("🛡 编辑时图标不设 required —— 不选文件 = 保留原图")
                .doesNotContain("name=\"iconFile\" accept=\"image/png,image/webp\" required");
        assertThat(html).as("422 / 403 的落点必须带 id，否则 htmx 不发 HX-Target 头")
                .contains("id=\"utag-drawer-err\"");
    }

    /**
     * AC3：分配记录**含已到期**，三态并列。
     *
     * <p>🔴 只列生效中的话，运营看不出「上周那次分配到期了没」—— 它只是凭空消失了。
     */
    @Test
    void theAssignmentTabListsExpiredAndScheduledRowsToo() throws Exception {
        UserTag t = tag("THREE");
        Instant now = Instant.now();
        User active = newUser();
        User expired = newUser();
        User scheduled = newUser();
        tagService.assign(active.getId(), t.getId(), now.minusSeconds(600), null);
        tagService.assign(expired.getId(), t.getId(), now.minusSeconds(9000), now.minusSeconds(60));
        tagService.assign(scheduled.getId(), t.getId(), now.plusSeconds(9000), null);

        String html = body(mvc.perform(get("/admin/user-tags/" + t.getId() + "/drawer")
                        .param("tab", "assignments").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("三态都要出现；少任何一态运营都会做出错误处置")
                .contains("生效中").contains("已到期").contains("待生效");
        assertThat(html).as("三条记录都在（含已到期那条）")
                .contains("#" + active.getId()).contains("#" + expired.getId())
                .contains("#" + scheduled.getId());
        assertThat(html).as("「＋添加用户」是唯一分配入口，端点与参数逐字不变")
                .contains("hx-post=\"/admin/user-tags/assign\"")
                .contains("name=\"tagId\"").contains("name=\"userIds\"")
                .contains("name=\"startsAt\"").contains("name=\"endsAt\"");
        assertThat(html).as("展示上限提示在分配页签里也有一份")
                .contains("id=\"userTagCapNotice\"");
    }

    /** AC3：操作人列 —— 经后台分配的记录能反查到是谁分的。 */
    @Test
    void theOperatorColumnResolvesWhoAssignedIt() throws Exception {
        UserTag t = tag("WHO");
        User u = newUser();
        Authentication admin = superAdmin();
        mvc.perform(post("/admin/user-tags/assign").param("pickedUserIds", String.valueOf(u.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", "2026-01-01T10:00").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(admin)).with(csrf()))
                .andExpect(status().isOk());

        String html = body(mvc.perform(get("/admin/user-tags/" + t.getId() + "/drawer")
                .param("tab", "assignments").param("lang", "zh_CN")
                .header("HX-Request", "true").with(authentication(admin))).andReturn());
        assertThat(html).as("操作人取自审计里那条 USER_TAG_ASSIGN（target = 分配 id）")
                .contains("用户标签抽屉测试员");
    }

    // ——————————————————— htmx 动线 ———————————————————

    @Test
    void assigningViaHtmxRerendersTheDrawerAndRefreshesTheList() throws Exception {
        UserTag t = tag("HX");
        User u = newUser();
        MvcResult r = mvc.perform(post("/admin/user-tags/assign")
                        .param("pickedUserIds", String.valueOf(u.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", "2026-01-01T10:00").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        String html = body(r);
        assertThat(html).as("抽屉体 oob 重渲染 + toast")
                .contains("hx-swap-oob=\"innerHTML:#utag-drawer .drawer-body\"")
                .contains("class=\"toast\"");
        assertThat(html).as("分配完停在分配记录页签，刚加的那条就在里面")
                .contains("#" + u.getId());
        assertThat(r.getResponse().getHeader("HX-Trigger"))
                .as("列表整表重拉：摘要条与「生效中分配数」都要跟着变")
                .contains("admin:user-tag-list-refresh");
        assertThat(assignments.findActiveByTag(t.getId(), Instant.parse("2026-06-01T00:00:00Z")))
                .hasSize(1);
    }

    /**
     * 🔴 批量分配**部分失败 = 部分成功**：成功的那几条必须当场看得见，失败的 id 必须当场说出来。
     *
     * <p>⚠️ 曾经写成「有失败就抛 422」：那样已经入库的几条既不显示、列表也不刷新，
     * 运营的合理解读是「整批都没成功」，于是原样再提交一遍。
     */
    @Test
    void aPartiallyFailedBulkAssignStillShowsWhatSucceeded() throws Exception {
        UserTag t = tag("PART");
        User ok = newUser();
        long ghost = 999_000_000L + SEQ.incrementAndGet();   // 不存在的用户 → 服务层拒绝这一个

        MvcResult r = mvc.perform(post("/admin/user-tags/assign")
                        .param("pickedUserIds", String.valueOf(ok.getId()))
                        .param("pickedUserIds", String.valueOf(ghost))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", "2026-01-01T10:00").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        String html = body(r);
        assertThat(html).as("成功的那条已经随抽屉重渲染显示出来")
                .contains("hx-swap-oob=\"innerHTML:#utag-drawer .drawer-body\"")
                .contains("#" + ok.getId());
        // 🔴 失败提示必须落在**抽屉体这一整块 oob 内容里面**，不能是响应末尾那段等着主 swap 的内容：
        //    htmx 先做 oob、后做主 swap，而主 swap 的目标是发请求时捕获的**节点引用** ——
        //    #utag-assign-err 就在 .drawer-body 里，oob 一执行它已经脱离文档，
        //    再写进去就是写进一个游离节点，界面上一个字都不会出现。
        //    ⚠️ 只断言「响应字符串里有这个 id」是假绿的（服务端确实渲染了），所以这里断言**位置**。
        int oobStart = html.indexOf("hx-swap-oob=\"innerHTML:#utag-drawer .drawer-body\"");
        int oobEnd = html.indexOf("class=\"toast\"");
        int ghostAt = html.indexOf(String.valueOf(ghost));
        assertThat(ghostAt).as("失败的 id 出现在抽屉体 oob 块内部（否则浏览器里看不到）")
                .isGreaterThan(oobStart).isLessThan(oobEnd);
        assertThat(html).as("并且是渲染在分配表单的行内错误槽里")
                .contains("id=\"utag-assign-err\"");
        assertThat(html).as("承载提示的 <details> 必须是展开的，折叠着等于没提示")
                .contains("<details class=\"tag-assign-details\" open");
        assertThat(r.getResponse().getHeader("HX-Trigger")).as("列表照常刷新")
                .contains("admin:user-tag-list-refresh");
        assertThat(assignments.findActiveByTag(t.getId(), Instant.parse("2026-06-01T00:00:00Z")))
                .as("只有合法的那一条入库").hasSize(1);
    }

    /**
     * 🔴 移除一条分配走 htmx 后，抽屉必须**换掉**那条记录。
     *
     * <p>并且「没找到」要当 404 抛，不是回一份原样的抽屉 —— 否则运营会以为「点了没反应」再点一次。
     */
    @Test
    void removingAnAssignmentSwapsTheDrawerAndUnknownIdIsA404() throws Exception {
        UserTag t = tag("RM");
        User u = newUser();
        Authentication admin = superAdmin();
        var saved = tagService.assign(u.getId(), t.getId(), Instant.now().minusSeconds(60), null);

        String html = body(mvc.perform(post("/admin/user-tags/assignments/" + saved.getId() + "/remove")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(admin)).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("抽屉重渲染，且那条记录已经不在了")
                .contains("hx-swap-oob=\"innerHTML:#utag-drawer .drawer-body\"")
                .doesNotContain("id=\"utag-assign-" + saved.getId() + "\"");

        mvc.perform(post("/admin/user-tags/assignments/" + saved.getId() + "/remove")
                        .header("HX-Request", "true").with(authentication(admin)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    /**
     * 🔴 「满 3 会顶掉最早的那个」的预告必须在**选人的那一刻**给（AC3）。
     *
     * <p>分配完再说，运营已经不知道被顶掉的是谁了。只提示、不禁选 ——
     * 顶掉最早的常常正是他想要的效果。
     */
    @Test
    void theUserPickerWarnsAboutUsersAlreadyAtTheDisplayCap() throws Exception {
        User full = newUser();
        User light = newUser();
        for (int i = 0; i < UserTagQueryService.MAX_VISIBLE; i++) {
            tagService.assign(full.getId(), tag("CAP").getId(), Instant.now().minusSeconds(60 + i), null);
        }
        tagService.assign(light.getId(), tag("ONE").getId(), Instant.now().minusSeconds(60), null);

        String html = body(mvc.perform(get("/admin/user-tags/pick").param("q", "")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("已满上限的候选要有醒目预告").contains("data-notice=\"pick-at-cap\"");
        assertThat(html).as("并说清会顶掉最早的那个").contains("顶掉最早");
        // 只提示不禁选：候选行照常可勾。
        assertThat(html).contains("name=\"pickedUserIds\" value=\"" + full.getId() + "\"");
    }

    /**
     * 🔴 toast 里的「已为 N 个用户分配」不能被重复 id 灌水。
     *
     * <p>候选表里勾了 1001、手填框又粘了「1001, 1002」是保留手填框的典型用法：
     * 不去重的话报 3、实际入库 2，运营会以为漏了一条、再提交一遍。
     */
    @Test
    void duplicateIdsAcrossBothInputsAreCountedOnce() throws Exception {
        UserTag t = tag("DUP");
        User a = newUser();
        User b = newUser();
        MvcResult r = mvc.perform(post("/admin/user-tags/assign")
                        .param("pickedUserIds", String.valueOf(a.getId()))
                        .param("userIds", a.getId() + ", " + b.getId())
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", "2026-01-01T10:00").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(body(r)).as("报的是去重后的数量").contains("已为 2 个用户分配标签");
        assertThat(assignments.findActiveByTag(t.getId(), Instant.parse("2026-06-01T00:00:00Z")))
                .as("同一用户不会被分配两条").hasSize(2);
    }

    /** AC4：新建成功 → HX-Trigger 让前端打开**新标签**的抽屉并停在分配记录页签。 */
    @Test
    void creatingATagOpensItsDrawerOnTheAssignmentTab() throws Exception {
        MvcResult r = mvc.perform(multipart("/admin/user-tags")
                        .file(new MockMultipartFile("iconFile", "i.png", "image/png", pngBytes()))
                        .param("name", "新标签" + SEQ.incrementAndGet())
                        .param("description", "说明").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        String trigger = r.getResponse().getHeader("HX-Trigger");
        assertThat(trigger).as("建完标签紧接着要做的就是给它加人")
                .contains("admin:drawer-open").contains("tab=assignments");
        assertThat(body(r)).as("只回一个 toast —— 抽屉由 drawer-open 走正常流程打开")
                .contains("class=\"toast\"");
    }

    /** 缺 name → 400（缺必填参数），不落库。 */
    @Test
    void creatingWithoutANameDoesNotCreateAnything() throws Exception {
        long before = tags.findAllByOrderByIdDesc().size();
        mvc.perform(multipart("/admin/user-tags")
                        .file(new MockMultipartFile("iconFile", "i.png", "image/png", pngBytes()))
                        .param("description", "说明")
                        .header("HX-Request", "true").with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is4xxClientError());
        assertThat(tags.findAllByOrderByIdDesc()).hasSize((int) before);
    }

    // ——————————————————— AC5 权限 ———————————————————

    /**
     * 🛡 只有 VIEW 的人：抽屉里**一个写入口都拿不到**，而且看得到缺哪个权限；
     * 硬发请求也照旧 403（历史事故是按钮在、点了 403）。
     */
    @Test
    void viewOnlyStaffSeesDisabledActionsAndStillGets403() throws Exception {
        UserTag t = tag("VIEW");
        User u = newUser();
        tagService.assign(u.getId(), t.getId(), Instant.now().minusSeconds(60), null);
        Authentication viewer = auth(AdminAccountType.STAFF, "user.tag_view");

        for (String tab : List.of("edit", "assignments")) {
            String html = body(mvc.perform(get("/admin/user-tags/" + t.getId() + "/drawer")
                            .param("tab", tab).param("lang", "zh_CN").header("HX-Request", "true")
                            .with(authentication(viewer)))
                    .andExpect(status().isOk()).andReturn());
            assertThat(html).as(tab + " 页签拿不到任何写入口").doesNotContain("hx-post");
            assertThat(html).as(tab + " 页签注明所缺权限名")
                    .contains("需要「编辑用户标签（含批量分配）」权限");
        }

        mvc.perform(post("/admin/user-tags/" + t.getId() + "/retire").param("retired", "true")
                        .header("HX-Request", "true").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/user-tags/assign").param("pickedUserIds", String.valueOf(u.getId()))
                        .param("tagId", String.valueOf(t.getId())).param("startsAt", "2026-01-01T10:00")
                        .header("HX-Request", "true").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(tags.findById(t.getId()).orElseThrow().isRetired()).isFalse();
    }

    /** 无任何标签权限：抽屉端点也要 403（导航另有 sec:authorize 挡着）。 */
    @Test
    void withoutViewPermissionTheDrawerIsForbidden() throws Exception {
        UserTag t = tag("NOPE");
        Authentication outsider = auth(AdminAccountType.STAFF, "content.view");
        mvc.perform(get("/admin/user-tags/" + t.getId() + "/drawer").header("HX-Request", "true")
                        .with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/user-tags/new/drawer").header("HX-Request", "true")
                        .with(authentication(outsider)))
                .andExpect(status().isForbidden());
    }

    /** 最小合法 PNG（1×1）。图标校验只看得懂真图片。 */
    private static byte[] pngBytes() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }
}
