package com.tailtopia.admin.virtual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
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
 * L1 集成：B9 运营发布身份双区块套模板 B（V1.3.0 Story 8.3）。
 *
 * <p>本 story 是**零端点变更**的页面重构：两组控制器的写端点、参数、权限表达式一个字没动，
 * 只把「改物种定位」「启停」收进抽屉、把两个整页确认并入弹层。所以钉的是
 * 「搬完之后每一样都还在、两套权限没走散、确认语义没被简化成 confirm()」。
 *
 * <p>⚠️ 与 {@code AdminPublishIdentityIntegrationTest} / {@code AdminVirtualAccountIntegrationTest}
 * 分工：那两个钉机制（授权说明必填、移出 ≠ 封号、排期计数口径、独立权限码），本 story 一条没动。
 */
class AdminPublishIdentityDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private UserRepository users;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "b9drawer-" + n + "@tailtopia.test", "发布身份抽屉测试员", "{bcrypt}x"));
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

    private long virtualAccount() {
        return virtualAccounts.create("虚拟号-" + SEQ.incrementAndGet(), null, "DOG", 1L);
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 区块一套模板 B ———————————————————

    @Test
    void bothBlocksRenderWithTemplateBAndTheirOwnIds() throws Exception {
        long id = virtualAccount();
        String html = body(mvc.perform(get("/admin/virtual-accounts").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("区块一：模板 B 壳 + 摘要条三格 + 点行开抽屉")
                .contains("id=\"virtual-drawer\"").contains("id=\"virtual-summary\"")
                .contains("data-sum=\"total\"").contains("data-sum=\"enabled\"")
                .contains("data-sum=\"published\"")
                .contains("data-drawer-url=\"/admin/virtual-accounts/" + id + "/drawer\"");
        assertThat(html).as("区块二：候选表 + 池内表各有自己的容器")
                .contains("id=\"identity-candidates\"").contains("id=\"identities-rows\"");
        assertThat(html).as("确认弹层的宿主").contains("id=\"confirm-host\"");

        // 🔴 页尾常驻的新建表单与「表格里选完即生效的物种下拉」都已收进抽屉：
        //    判据取**字段名**而不是标题文案 —— 文案随时可能改词，字段名是端点契约的一部分。
        assertThat(html).as("新建表单不再常驻整页").doesNotContain("name=\"nickname\"");
        assertThat(html).as("物种定位不再是表格里选完即生效的下拉")
                .doesNotContain("name=\"accountSpecies\"");
    }

    /**
     * 🔴 两个关键词参数刻意不合并：{@code vq} 筛区块一，{@code q} 搜区块二的候选。
     *
     * <p>共用一个 {@code q} 的话，运营在上面找一个马甲会顺手把下面的候选表刷成另一批人。
     */
    @Test
    void theTwoKeywordFiltersAreIndependent() throws Exception {
        long hit = virtualAccounts.create("找得到我-" + SEQ.incrementAndGet(), null, "DOG", 1L);
        long miss = virtualAccounts.create("别的号-" + SEQ.incrementAndGet(), null, "CAT", 1L);
        User real = newUser();
        real.setNickname("真人候选-" + SEQ.incrementAndGet());
        users.save(real);

        String html = body(mvc.perform(get("/admin/virtual-accounts").param("vq", "找得到我")
                        .param("lang", "zh_CN").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("id=\"virtual-row-" + hit + "\"")
                .doesNotContain("id=\"virtual-row-" + miss + "\"");
        assertThat(html).as("vq 不该把区块二的候选表也搜出来")
                .doesNotContain("id=\"identity-candidate-" + real.getId() + "\"");

        String withQ = body(mvc.perform(get("/admin/virtual-accounts")
                        .param("q", real.getNickname()).param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(withQ).as("q 搜候选").contains("id=\"identity-candidate-" + real.getId() + "\"");
        assertThat(withQ).as("q 不该把区块一筛掉").contains("id=\"virtual-row-" + hit + "\"");
    }

    // ——————————————————— AC1 抽屉 ———————————————————

    @Test
    void nonHtmxDrawerUrlRedirectsToTheListWithOpenParam() throws Exception {
        long id = virtualAccount();
        mvc.perform(get("/admin/virtual-accounts/" + id + "/drawer")
                        .with(authentication(superAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/virtual-accounts?open=" + id));
    }

    /**
     * 🛡 抽屉端点必须判 {@code accountType}：不判的话把一个**真实用户**的 id 敲进 URL 也能打开，
     * 里面还带着「改物种定位」「停用」两个只该对虚拟号生效的写入口。
     */
    @Test
    void theDrawerRefusesIdsThatAreNotVirtualAccounts() throws Exception {
        User real = newUser();
        mvc.perform(get("/admin/virtual-accounts/" + real.getId() + "/drawer")
                        .header("HX-Request", "true").with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    @Test
    void theDrawerCarriesSpeciesRewriteAndTheDisableConfirm() throws Exception {
        long id = virtualAccount();
        String html = body(mvc.perform(get("/admin/virtual-accounts/" + id + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"virtual-drawer-panel\"")
                .contains("hx-post=\"/admin/virtual-accounts/" + id + "/species\"")
                .contains("name=\"accountSpecies\"");
        assertThat(html).as("🔴 改物种定位要复述确认：它会立即改写该号全部历史内容的物种归属")
                .contains("data-confirm=");
        assertThat(html).as("🔴 停用必须先拉确认弹层（服务端算的排期数），不是直接 POST")
                .contains("hx-get=\"/admin/virtual-accounts/" + id + "/disable/confirm\"")
                .contains("hx-target=\"#confirm-host\"");
        assertThat(html).as("422 / 403 的落点必须带 id，否则 htmx 不发 HX-Target 头")
                .contains("id=\"virtual-drawer-err\"");
    }

    /** 🛡 只有禁用要确认，**启用不要** —— 启用是无害的（控制器注释明写）。 */
    @Test
    void enablingNeedsNoConfirmButDisablingDoes() throws Exception {
        long id = virtualAccount();
        Authentication admin = superAdmin();
        // 先停掉（走确认那条路），再看已停用的抽屉给的是直接 POST 的「启用」。
        mvc.perform(post("/admin/virtual-accounts/" + id + "/enabled").param("enabled", "false")
                        .header("HX-Request", "true").with(authentication(admin)).with(csrf()))
                .andExpect(status().isOk());

        String html = body(mvc.perform(get("/admin/virtual-accounts/" + id + "/drawer")
                .param("lang", "zh_CN").header("HX-Request", "true")
                .with(authentication(admin))).andReturn());
        assertThat(html).as("已停用 → 直接 POST 启用")
                .contains("hx-post=\"/admin/virtual-accounts/" + id + "/enabled\"");
        assertThat(html).as("已停用 → 不再给停用确认入口").doesNotContain("disable/confirm");
    }

    /** 改物种 / 启停走 htmx：抽屉重渲染 + 列表整表重拉。 */
    @Test
    void speciesRewriteViaHtmxRerendersTheDrawerAndRefreshesTheList() throws Exception {
        long id = virtualAccount();
        MvcResult r = mvc.perform(post("/admin/virtual-accounts/" + id + "/species")
                        .param("accountSpecies", "CAT").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(body(r)).contains("hx-swap-oob=\"innerHTML:#virtual-drawer .drawer-body\"")
                .contains("class=\"toast\"");
        assertThat(r.getResponse().getHeader("HX-Trigger"))
                .as("摘要条三格与状态列都要跟着变").contains("admin:virtual-account-list-refresh");
        assertThat(virtualAccounts.one(id).accountSpecies()).isEqualTo("CAT");
    }

    /** 停用成功后要把确认弹层收掉（否则它会挂在那儿，运营会以为没生效）。 */
    @Test
    void disablingClosesTheConfirmModal() throws Exception {
        long id = virtualAccount();
        MvcResult r = mvc.perform(post("/admin/virtual-accounts/" + id + "/enabled")
                        .param("enabled", "false").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("admin:confirm-close");
        assertThat(users.findById(id).orElseThrow().isEnabled()).isFalse();
    }

    // ——————————————————— AC2 区块二 ———————————————————

    /** 纳入走 htmx：池表整表 oob + toast + 候选刷新事件。 */
    @Test
    void grantingViaHtmxSwapsThePoolTable() throws Exception {
        User u = newUser();
        MvcResult r = mvc.perform(post("/admin/publish-identities")
                        .param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "公司 IP 号-" + SEQ.incrementAndGet())
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        String html = body(r);
        assertThat(html).as("池表整表换掉，刚纳入的那个人在里面")
                .contains("id=\"identities-rows\"").contains("id=\"identity-row-" + u.getId() + "\"")
                .contains("class=\"toast\"");
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("admin:identity-list-refresh");
    }

    /** 已在池内的候选：按钮**置灰而不是消失**，且不再渲染那张会报错的表单。 */
    @Test
    void candidatesAlreadyInThePoolAreDisabledNotHidden() throws Exception {
        User u = newUser();
        u.setNickname("已纳入-" + SEQ.incrementAndGet());
        users.save(u);
        Authentication admin = superAdmin();
        mvc.perform(post("/admin/publish-identities").param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "note").header("HX-Request", "true")
                        .with(authentication(admin)).with(csrf()))
                .andExpect(status().isOk());

        String html = body(mvc.perform(get("/admin/virtual-accounts").param("section", "candidates")
                        .param("q", u.getNickname()).param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("id=\"identity-candidate-" + u.getId() + "\"")
                .contains("已在池内").contains("disabled");
        assertThat(html).as("不渲染一张提交就报错的表单")
                .doesNotContain("id=\"grant-" + u.getId() + "\"");
    }

    /**
     * 🔴 纳入成功后，**候选表里那一行**要当场变成「已在池内」禁用态。
     *
     * <p>不换的话运营会对着一个仍然可点的「纳入」按钮再点一次，拿到 422。
     * ⚠️ 判据是这一行带 `hx-swap-oob`（真的会被换掉），不是「响应里出现了这个 id」——
     * 后者在池表那边也会出现，是假绿。
     */
    @Test
    void grantingSwapsThatCandidateRowIntoTheAlreadyInPoolState() throws Exception {
        User u = newUser();
        u.setNickname("待纳入-" + SEQ.incrementAndGet());
        users.save(u);

        String html = body(mvc.perform(post("/admin/publish-identities")
                        .param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "note-" + SEQ.incrementAndGet())
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn());

        int rowAt = html.indexOf("id=\"identity-candidate-" + u.getId() + "\"");
        assertThat(rowAt).as("候选行必须出现在响应里").isGreaterThanOrEqualTo(0);
        assertThat(html.substring(rowAt, Math.min(rowAt + 200, html.length())))
                .as("并且是 oob 换掉那一行").contains("hx-swap-oob=\"true\"");
        assertThat(html).as("换进去的是禁用态，且不再带那张授权说明表单")
                .contains("已在池内")
                .doesNotContain("id=\"grant-" + u.getId() + "\"");
        assertThat(html).as("🔴 外壳是真 table，否则 htmx 会把裸 <tr> 丢掉")
                .contains("<table hidden>");
    }

    // ——————————————————— AC4 两套权限 ———————————————————

    /**
     * 🛡 <b>能管虚拟账号 ≠ 能以真人身份发言</b>：只持 {@code virtual_account.manage} 的人
     * 看得到区块一、**看不到区块二整块**。
     */
    @Test
    void theRealIdentityBlockIsInvisibleWithoutItsOwnPermission() throws Exception {
        long id = virtualAccount();
        Authentication onlyVirtual =
                auth(AdminAccountType.STAFF, AdminPermissions.VIRTUAL_ACCOUNT_MANAGE);

        String html = body(mvc.perform(get("/admin/virtual-accounts").param("lang", "zh_CN")
                        .with(authentication(onlyVirtual)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("区块一照常").contains("id=\"virtual-row-" + id + "\"");
        assertThat(html).as("区块二整块不渲染")
                .doesNotContain("id=\"identities-rows\"")
                .doesNotContain("id=\"identity-candidates\"");

        // 🔴🔴 只查整页是**不够的**：section 分流是独立的 fragment 端点，模板上那句 sec:authorize
        //    在那里不存在。不单独判权限的话，只持 virtual_account.view/manage 的人
        //    直接请求 ?section=identities 就能拿到整个身份池（含**授权说明**、纳入人、发布数）。
        mvc.perform(get("/admin/virtual-accounts").param("section", "identities")
                        .header("HX-Request", "true").with(authentication(onlyVirtual)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/virtual-accounts").param("section", "candidates").param("q", "a")
                        .header("HX-Request", "true").with(authentication(onlyVirtual)))
                .andExpect(status().isForbidden());
        // 区块一自己的局部刷新照常可用（别把常用路径一起锁死）。
        mvc.perform(get("/admin/virtual-accounts")
                        .header("HX-Request", "true").with(authentication(onlyVirtual)))
                .andExpect(status().isOk());
    }

    /** 只读（virtual_account.view）：抽屉里一个写入口都拿不到，硬发请求照旧 403。 */
    @Test
    void viewOnlyStaffSeesNoWriteEntryAndStillGets403() throws Exception {
        long id = virtualAccount();
        Authentication viewer = auth(AdminAccountType.STAFF, AdminPermissions.VIRTUAL_ACCOUNT_VIEW);

        String html = body(mvc.perform(get("/admin/virtual-accounts/" + id + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("拿不到任何写入口").doesNotContain("hx-post").doesNotContain("disable/confirm");
        assertThat(html).as("并注明缺哪个权限").contains("需要「").contains("disabled");

        mvc.perform(post("/admin/virtual-accounts/" + id + "/species")
                        .param("accountSpecies", "CAT").header("HX-Request", "true")
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/virtual-accounts/" + id + "/disable/confirm")
                        .header("HX-Request", "true").with(authentication(viewer)))
                .andExpect(status().isForbidden());
        assertThat(users.findById(id).orElseThrow().isEnabled()).isTrue();
    }
}
