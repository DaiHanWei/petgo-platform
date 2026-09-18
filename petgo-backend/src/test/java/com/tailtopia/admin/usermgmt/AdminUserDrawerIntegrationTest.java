package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1 集成：B7 用户列表套模板 B + 五页签详情抽屉（V1.3.0 Story 8.1）。
 *
 * <p>本 story 是**零功能变更**的页面重构：五个 POST 的路径 / 参数 / 权限表达式一个字没动，
 * 只把整页详情换成抽屉。所以钉的是「搬完之后每一样都还在、且权限没走散」——
 * 尤其那三张处置卡的门控：历史事故是**按钮在、点了 403**。
 *
 * <p>⚠️ 与既有四个用户集成测试分工：那些钉机制（搜索口径、停用、删除、手机号 PII），本 story 一条没动。
 */
class AdminUserDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private com.tailtopia.profile.repository.PetProfileRepository pets;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "userdrawer-" + n + "@tailtopia.test", "用户抽屉测试员", "{bcrypt}x"));
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

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 列表套模板 B ———————————————————

    @Test
    void listUsesTemplateBWithSummaryAndOpensTheDrawerByRow() throws Exception {
        User u = newUser();
        String phone = "+62811" + (4000000 + (SEQ.incrementAndGet() % 1000000));
        u.setPhone(phone);
        users.save(u);
        String html = body(mvc.perform(get("/admin/users").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("模板 B 壳 + 抽屉容器").contains("id=\"user-drawer\"").contains("data-drawer-mask");
        assertThat(html).as("摘要条四格").contains("id=\"users-summary\"")
                .contains("总用户数").contains("今日新增").contains("已停用").contains("已注销");
        assertThat(html).as("点行开抽屉；「查看详情」列已删")
                .contains("data-drawer-url=\"/admin/users/" + u.getId() + "/drawer\"")
                .doesNotContain("查看详情");
        // AC1「关键词配显式查询钮」：钉按钮本身。
        // ⚠️ 原来写的是 doesNotContain("hx-trigger=\"keyup") —— 旧模板从来没有过这个属性，
        //    那条断言在改造前后都绿，等于没钉。
        assertThat(html).as("关键词走显式查询钮（data-autosubmit 只作用于 select）")
                .contains("<button type=\"submit\"")
                .contains("data-autosubmit");
        // 🛡 原来断言的是 contains("name=\"phone\"")（筛选下拉存在）—— 号码真泄露了也不会红。
        //    改成：造一个有号码的用户，断言**号码本身**不在列表 HTML 里。
        assertThat(html).as("🛡 列表只出现「填了没填」，不出现号码本身")
                .doesNotContain(phone)
                .contains("已填写");
    }

    /** 整页详情已退役（AC4）：不做旧地址跳转（D-23）。 */
    @Test
    void theStandaloneDetailPageIsGone() throws Exception {
        User u = newUser();
        mvc.perform(get("/admin/users/" + u.getId()).with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    /** 非 htmx 直达抽屉地址 → 回列表并自动开该抽屉。 */
    @Test
    void nonHtmxDrawerUrlRedirectsToTheListWithOpenParam() throws Exception {
        User u = newUser();
        mvc.perform(get("/admin/users/" + u.getId() + "/drawer").with(authentication(superAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/users?open=" + u.getId()));
    }

    // ——————————————————— AC2 五页签 ———————————————————

    @Test
    void drawerRendersAllFiveTabsInOneRequest() throws Exception {
        User u = newUser();
        String html = body(mvc.perform(get("/admin/users/" + u.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"user-drawer-panel\"");
        assertThat(html).as("五个页签一次性渲染，切页签不再请求")
                .contains("data-utab=\"basic\"").contains("data-utab=\"pets\"")
                .contains("data-utab=\"posts\"").contains("data-utab=\"consults\"")
                .contains("data-utab=\"actions\"")
                .contains("data-utab-panel=\"basic\"").contains("data-utab-panel=\"actions\"");
        assertThat(html).as("🔴 err 槽必须带 id：htmx 只在目标带 id 时才发 HX-Target 头")
                .contains("id=\"user-drawer-err\"");
        assertThat(html).as("历史问诊那句「仅元数据」不可省 —— 少了它运营会来要聊天记录")
                .contains("仅元数据");
        assertThat(html).as("发布内容 / 历史问诊跳的是别的页的抽屉深链")
                .contains("/admin/content?open=").contains("/admin/consult-sessions?open=");
    }

    /** 🔴 宠物档案增列性别 / 生日（PRD §5 ③ 第 2 条例外，纯读展示）。 */
    @Test
    void petCardShowsSexAndBirthday() throws Exception {
        User u = newUser();
        // create(ownerId, petType, name, avatarUrl, breed, birthday, intro, cardToken)
        com.tailtopia.profile.domain.PetProfile pet = pets.save(
                com.tailtopia.profile.domain.PetProfile.create(u.getId(),
                        com.tailtopia.profile.domain.PetType.DOG, "旺财", null, "柴犬",
                        java.time.LocalDate.of(2024, 3, 8), null, null));
        pet.setSex(com.tailtopia.profile.domain.PetSex.MALE);
        pets.save(pet);

        String html = body(mvc.perform(get("/admin/users/" + u.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin()))).andReturn());
        assertThat(html).contains("旺财").contains("柴犬")
                .as("生日是 LocalDate，按日期原样展示（不折算时区）").contains("2024-03-08")
                .as("性别走三语 key，不直接印枚举名").contains("公");
    }

    // ——————————————————— AC2 / AC5 处置 ———————————————————

    @Test
    void deactivateViaHtmxRerendersTheDrawerAndSwapsTheRow() throws Exception {
        User u = newUser();
        MvcResult r = mvc.perform(post("/admin/users/" + u.getId() + "/deactivate")
                        .param("reason", "违规-" + SEQ.incrementAndGet()).param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(users.findById(u.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.DEACTIVATED);
        String html = body(r);
        assertThat(html).as("抽屉体 oob 重渲染 + 该行 oob 换掉 + toast")
                .contains("hx-swap-oob=\"innerHTML:#user-drawer .drawer-body\"")
                .contains("id=\"user-row-" + u.getId() + "\"")
                .contains("class=\"toast\"");
        // 🔴 oob 行必须包在**真的** <table hidden> 里：响应不以 `<tr` 开头时 htmx 走通用解析，
        //    裸 <tr>/<td> 会被 HTML 解析器丢掉 —— 行不换、单元格文本还泄进抽屉的 err 槽。
        //    只断言「字符串里有 user-row-N」是看不出这个的（服务端确实吐了这段）。
        assertThat(html.indexOf("<table hidden")).as("oob 行外壳是真 table 且在行之前")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(html.indexOf("id=\"user-row-" + u.getId() + "\""));
        assertThat(html).as("停用之后处置页签给的是「重新激活」而不是「停用」")
                .contains("/reactivate");
    }

    /** 🛡 停用缺 reason → 422 行内 err，且不落库（服务层校验，本 story 没动它）。 */
    @Test
    void deactivateWithoutReasonIsA422InlineError() throws Exception {
        User u = newUser();
        MvcResult r = mvc.perform(post("/admin/users/" + u.getId() + "/deactivate")
                        .param("reason", "  ").param("lang", "zh_CN")
                        .header("HX-Request", "true").header("HX-Target", "user-drawer-err")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();

        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#user-drawer-err");
        assertThat(body(r)).contains("inline-error");
        assertThat(users.findById(u.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * 🛡 三张处置卡逐卡门控（AC5）：只有 `user.view` 的人**一个写入口都拿不到**，
     * 而且看得到「缺哪个权限」——历史事故是按钮在、点了 403。
     */
    @Test
    void viewOnlyStaffSeesDisabledActionsWithTheMissingPermissionNamed() throws Exception {
        User u = newUser();
        Authentication viewer = auth(AdminAccountType.STAFF, "user.view");

        String html = body(mvc.perform(get("/admin/users/" + u.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("拿不到任何写入口").doesNotContain("hx-post");
        // ⚠️ 不能只 contains("赠送 PawCoin")：那和卡片标题 admin.userdetail.grant 字面完全相同，
        //    缺权限提示一个字没渲染也绿。钉完整的 admin.v130.err.forbidden 句子。
        assertThat(html).as("三个禁用态各自注明所缺权限名")
                .contains("disabled")
                .contains("需要「停用用户」权限")
                .contains("需要「赠送 PawCoin」权限")
                .contains("需要「删除用户」权限");

        mvc.perform(post("/admin/users/" + u.getId() + "/deactivate").param("reason", "x")
                        .header("HX-Request", "true").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/" + u.getId() + "/delete").param("type", "USER_REQUEST")
                        .param("note", "x").header("HX-Request", "true")
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(users.findById(u.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * 🔴 提交注销之后抽屉立刻变只读（AC5）。
     *
     * <p>⚠️ **不断言异步完成**：级联注销是 7.3 的 {@code @Async}，`deletedAt` 什么时候落库不确定
     * （同目录 AdminUserDeletionIntegrationTest 的类注释立的规矩）。所以这里钉的是
     * **删除请求自己的同步响应** —— 也正是那次响应里，`deleted()` 还是 false，
     * 全靠 `deletionPending` 把三张处置卡收掉；不然运营能在「注销已提交」下面再点一次删除。
     */
    @Test
    void submittingDeletionImmediatelyMakesTheDrawerReadOnly() throws Exception {
        User u = newUser();
        Authentication admin = superAdmin();
        String html = body(mvc.perform(post("/admin/users/" + u.getId() + "/delete")
                        .param("type", "USER_REQUEST").param("note", "用户申请-" + SEQ.incrementAndGet())
                        .param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(admin)).with(csrf()))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("同步响应里就不再有任何写入口").doesNotContain("hx-post");
        assertThat(html).as("并且说明白为什么没有").contains("注销已提交");
    }

    /**
     * 状态筛选（AC1，B7 规格「筛选：关键词 · 状态」）：三态各自只捞该态的账号，
     * 且**摘要条与列表同一口径**。
     *
     * <p>⚠️ 断言「该出现的出现 + 不该出现的不出现」两侧都要有：
     * 只断言前者的话，一个「筛选压根没生效、返回全量」的实现照样绿。
     */
    @Test
    void theStatusFilterNarrowsBothTheRowsAndTheSummary() throws Exception {
        Authentication admin = superAdmin();
        User active = newUser();
        User off = newUser();
        mvc.perform(post("/admin/users/" + off.getId() + "/deactivate").param("reason", "测试停用")
                        .header("HX-Request", "true").with(authentication(admin)).with(csrf()))
                .andExpect(status().isOk());

        String deactivated = body(mvc.perform(get("/admin/users").param("status", "deactivated")
                        .param("lang", "zh_CN").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(deactivated).as("已停用态列出被停用的账号")
                .contains("id=\"user-row-" + off.getId() + "\"");
        assertThat(deactivated).as("已停用态不该混进正常账号")
                .doesNotContain("id=\"user-row-" + active.getId() + "\"");

        String actives = body(mvc.perform(get("/admin/users").param("status", "active")
                        .param("lang", "zh_CN").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(actives).as("正常态列出正常账号")
                .contains("id=\"user-row-" + active.getId() + "\"");
        assertThat(actives).as("正常态不该混进已停用账号")
                .doesNotContain("id=\"user-row-" + off.getId() + "\"");
        // 🔴 摘要条随筛选联动（AC1 核心）：钉的是**格子里的数**，不是「摘要条在不在」。
        //    原来写的是 contains("id=\"users-summary\"") —— 一个完全不筛的实现照样绿。
        assertThat(actives).as("正常态里「已停用」这一格必然是 0")
                .contains("data-sum=\"deactivated\">0</div>");
        assertThat(deactivated).as("已停用态里「已停用」这一格必然大于 0")
                .doesNotContain("data-sum=\"deactivated\">0</div>");

        // 旧书签乱传的状态值一律当「全部」，不是 400、也不是空列表。
        String garbage = body(mvc.perform(get("/admin/users").param("status", "whatever")
                        .param("lang", "zh_CN").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(garbage).as("认不得的状态值退回「全部」")
                .contains("id=\"user-row-" + active.getId() + "\"")
                .contains("id=\"user-row-" + off.getId() + "\"");
    }

    /**
     * 🔴 搜索态下三个筛选必须一起生效（否则摘要条与列表说的是两批人）。
     *
     * <p>三个筛选合进同一个 form 之后，「关键词 + 手机号」是日常路径：
     * 列表若只按关键词筛、摘要条却按三条筛，屏幕上会出现「列了 8 行、总数写 3」。
     */
    @Test
    void inSearchModeThePhoneFilterNarrowsTheRowsToo() throws Exception {
        Authentication admin = superAdmin();
        String tag = "sfx" + SEQ.incrementAndGet();
        User withPhone = newUser();
        withPhone.setNickname(tag + "-有号");
        withPhone.setPhone("+62813" + (5000000 + (SEQ.incrementAndGet() % 1000000)));
        users.save(withPhone);
        User noPhone = newUser();
        noPhone.setNickname(tag + "-没号");
        noPhone.setPhone(null);
        users.save(noPhone);

        String html = body(mvc.perform(get("/admin/users").param("q", tag)
                        .param("phone", "empty").param("lang", "zh_CN")
                        .with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("搜索 + 未填写：只留没号的那个")
                .contains("id=\"user-row-" + noPhone.getId() + "\"")
                .doesNotContain("id=\"user-row-" + withPhone.getId() + "\"");
        assertThat(html).as("摘要条与列表同口径：总数就是这一行")
                .contains("data-sum=\"total\">1</div>");
    }

    /**
     * 🔴 状态筛选生效时导出置灰（bug 20260901-469 那一类的错位）：
     * 导出端点按 AC3 只认 {@code phone} 一个参数，导的是全部状态的人 ——
     * 屏幕上只剩「已停用」而文件里是全量，正是「屏幕一份、文件一份」。
     */
    @Test
    void exportIsGreyedOutWhileAStatusFilterIsActive() throws Exception {
        Authentication admin = superAdmin();
        String withPhoneOnly = body(mvc.perform(get("/admin/users").param("phone", "filled")
                        .param("lang", "zh_CN").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(withPhoneOnly).as("只选手机号筛选时导出可用").contains("phone-recall.xlsx");

        String withStatus = body(mvc.perform(get("/admin/users").param("phone", "filled")
                        .param("status", "deactivated").param("lang", "zh_CN")
                        .with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(withStatus).as("叠加状态筛选后导出链接必须消失").doesNotContain("phone-recall.xlsx");
        assertThat(withStatus).as("🔴 但按钮本身要留着、置灰（bug 20260901-475：整个消失会被读成功能没了）")
                .contains("disabled").contains("导出召回名单");
    }

    /** 无 `user.view` 的账号直访 403（导航另有 sec:authorize 挡着）。 */
    @Test
    void withoutUserViewEverythingIsForbidden() throws Exception {
        User u = newUser();
        Authentication outsider = auth(AdminAccountType.STAFF, "content.view");
        mvc.perform(get("/admin/users").with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/users/" + u.getId() + "/drawer").header("HX-Request", "true")
                        .with(authentication(outsider)))
                .andExpect(status().isForbidden());
    }
}
