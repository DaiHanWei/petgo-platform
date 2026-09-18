package com.tailtopia.admin.vet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
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
 * L1 集成：B20 兽医账号套模板 B、抽屉吸收 vet-edit 与 vet-online（V1.3.0 Story 9.1a）。
 *
 * <p>本 story 是**零写端点变更**的页面重构：五个写端点的路径 / 参数 / 权限一个字没动，
 * 新增的只有一个 drawer GET，删掉的是两个整页 GET。所以钉的是「两页内容搬完之后每一样都还在、
 * 权限门与端点对齐、旧地址真的 404」。
 *
 * <p>⚠️ 与 {@code AdminVet{Create,Edit,Ban}IntegrationTest} 分工：那三个钉服务层机制
 * （建号 / 改资料 / 封禁的落库与审计），本 story 一条没动。
 */
class AdminVetDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private VetAccountRepository vets;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "vetdrawer-" + n + "@tailtopia.test", "兽医抽屉测试员", "{bcrypt}x"));
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

    private VetAccount seedVet() {
        long n = SEQ.incrementAndGet();
        return vets.save(VetAccount.create("vet-drawer-" + n + "@x.test", "{bcrypt}x", "抽屉兽医" + n));
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 列表 ———————————————————

    @Test
    void vetListUsesTemplateBAndDropsTheThreeActionColumns() throws Exception {
        VetAccount v = seedVet();
        String html = body(mvc.perform(get("/admin/vets").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"vet-drawer\"").contains("id=\"vets-summary\"")
                .contains("data-sum=\"total\"").contains("data-sum=\"online\"")
                .contains("data-sum=\"qualPending\"").contains("data-sum=\"banned\"");
        assertThat(html).contains("data-drawer-url=\"/admin/vets/" + v.getId() + "/drawer\"");
        // 🔴 行内三列（编辑链接 / 改密表单 / 封禁表单）都搬进抽屉了，列表里不该再有提交入口。
        assertThat(html).doesNotContain("/password\"").doesNotContain("/status\"")
                .doesNotContain("/edit\"");
        // 筛选参数名逐字未动 —— 改名会让运营存的书签失效。
        assertThat(html).contains("name=\"accountStatus\"").contains("name=\"qualStatus\"")
                .contains("name=\"online\"").contains("name=\"q\"");
        assertThat(html).as("最后在线并入列表（原 vet-online 整页的数据）").contains("最后在线");
        assertThat(html).as("开户仍是模态框，不是抽屉").contains("id=\"createVetDialog\"");
        assertThat(html).as("＋开户在筛选栏里（AC1）")
                .containsPattern("id=\"vet-filters-form\"[\\s\\S]*data-open-dialog=\"createVetDialog\"[\\s\\S]*</form>");
    }

    @Test
    void filteringViaHtmxReturnsTheTableWithAnOobSummary() throws Exception {
        seedVet();
        String html = body(mvc.perform(get("/admin/vets").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("局部刷新不该回整页").doesNotContain("<html");
        // 🔴 摘要条随筛选联动：只换表格的话四个数会停在上一次筛选的值。
        assertThat(html).containsPattern("<div[^>]*id=\"vets-summary\"[^>]*hx-swap-oob=\"true\"");
    }

    // ——————————————————— AC2 / AC3 抽屉 ———————————————————

    @Test
    void theDrawerHasFourTabsWithQualAndRatingLazyLoaded() throws Exception {
        VetAccount v = seedVet();
        String html = body(mvc.perform(get("/admin/vets/" + v.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("局部片段不该回整页").doesNotContain("<html");
        assertThat(html).contains("id=\"vet-drawer-panel\"").contains("id=\"vet-drawer-err\"")
                .contains("data-vtab=\"profile\"").contains("data-vtab=\"qual\"")
                .contains("data-vtab=\"rating\"").contains("data-vtab=\"account\"");
        // V1.3.0 Story 9.1b：两个页签由占位换成**懒加载槽**（占位文案随之删除）。
        // 🔴 槽位必须说明「正在做什么」：一块空白会被读成「这个兽医没有资质记录」。
        assertThat(html).contains("data-notice=\"vet-qual-loading\"")
                .contains("data-notice=\"vet-rating-loading\"");
        // 资料页签吸收了 vet-online 的两项。
        assertThat(html).contains("data-section=\"vet-presence\"")
                .contains("data-notice=\"vet-presence-explicit\"");
        // 账号页签的两个操作都在。
        assertThat(html).contains("/admin/vets/" + v.getId() + "/password")
                .contains("/admin/vets/" + v.getId() + "/status")
                .contains("data-notice=\"vet-ban-scope\"");
    }

    /** 🛡 抽屉与列表同一道门 {@code vet.view}；没有的人连抽屉都打不开。 */
    @Test
    void theDrawerRequiresVetView() throws Exception {
        VetAccount v = seedVet();
        mvc.perform(get("/admin/vets/" + v.getId() + "/drawer").header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.STAFF, AdminPermissions.CONTENT_VIEW))))
                .andExpect(status().isForbidden());
    }

    /**
     * 🔴 只读者（只有 vet.view）看到的必须是**禁用态 + 原因**，而不是点了拿 403 的按钮。
     *
     * <p>三块处置的门各不相同（编辑 vet.edit|vet.create、改密 vet.reset_password、封禁 vet.ban），
     * 模板里的表达式与端点的 @PreAuthorize 必须逐字一致。
     */
    @Test
    void aViewerSeesDisabledActionsWithReasonsAndTheEndpointsStillRefuse() throws Exception {
        VetAccount v = seedVet();
        Authentication viewer = auth(AdminAccountType.STAFF, AdminPermissions.VET_VIEW);
        String html = body(mvc.perform(get("/admin/vets/" + v.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("一个能提交的入口都不该有").doesNotContain("hx-post");
        assertThat(html).contains("disabled").contains("需要「");

        mvc.perform(post("/admin/vets/" + v.getId() + "/password").param("newPassword", "NewPass#1")
                        .header("HX-Request", "true").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/vets/" + v.getId() + "/status").param("banned", "true")
                        .header("HX-Request", "true").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * 封禁成功：抽屉体 oob + toast，列表由 {@code admin:vet-list-refresh} **带着当前筛选**重拉。
     *
     * <p>🔴 不在服务端算行与摘要条：这几个 POST 身上没有筛选参数，只能按全库算，
     * 而屏幕上的表格是筛选后的 —— 摘要条会跳成全库的数，运营读成统计坏了。
     */
    @Test
    void banningViaHtmxRefreshesTheListWithTheCurrentFilters() throws Exception {
        VetAccount v = seedVet();
        var result = mvc.perform(post("/admin/vets/" + v.getId() + "/status")
                        .param("banned", "true").param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String html = body(result);

        assertThat(html).contains("hx-swap-oob=\"innerHTML:#vet-drawer .drawer-body\"");
        assertThat(result.getResponse().getHeader("HX-Trigger"))
                .as("列表靠事件按当前筛选重拉").contains("admin:vet-list-refresh");
        // 🔴 反过来钉住：这里**不该**再自己算行与摘要条（那是全库口径）。
        assertThat(html).doesNotContain("id=\"vets-summary\"")
                .doesNotContain("id=\"vet-row-" + v.getId() + "\"");
        assertThat(vets.findById(v.getId()).orElseThrow().getStatus().name()).isEqualTo("BANNED");
    }

    /** 🔴 刷新槽必须 {@code hx-include} 筛选表单本体，而不是把筛选值烤进 URL（烤进去的会过期）。 */
    @Test
    void theRefreshSlotPullsWithTheLiveFilterForm() throws Exception {
        String html = body(mvc.perform(get("/admin/vets").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).containsPattern(
                "<span[^>]*id=\"vets-refresh\"[^>]*hx-include=\"#vet-filters-form\"");
        assertThat(html).contains("hx-trigger=\"admin:vet-list-refresh from:body\"")
                .contains("id=\"vet-filters-form\"");
    }

    /**
     * 资料校验失败：**422**（不是 200）并且连着表单一起回显。
     *
     * <p>🔴 状态码不能是 200：全站约定是「4xx 出 fragment」（admin-core.js 专门放行
     * 422/403/404）。回 200 的话，任何按状态码判成败的监控与自动化都会把校验失败记成成功。
     * <p>🔴 回显必须带表单：只回一句错误的话，运营刚填的内容会被换掉，得从头再填一遍。
     */
    @Test
    void anInvalidProfileIs422AndComesBackWithTheFormNotJustAnError() throws Exception {
        VetAccount v = seedVet();
        String html = body(mvc.perform(post("/admin/vets/" + v.getId())
                        .param("displayName", "").param("username", "not-an-email")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn());

        assertThat(html).contains("id=\"vet-profile-form\"").contains("name=\"displayName\"")
                .contains("name=\"username\"");
        assertThat(html).as("错误要看得见").contains("class=\"err\"");
    }

    /**
     * 🔴 重置密码的明文**只此一屏**：随本次响应渲染，之后再打开抽屉就没有了。
     *
     * <p>后端不保存明文，所以这一屏是唯一的获取窗口 —— 关闭文案必须强调这一点。
     */
    @Test
    void aResetPasswordIsShownOnceAndNeverAgain() throws Exception {
        VetAccount v = seedVet();
        String issued = "NewPass#1a";
        String html = body(mvc.perform(post("/admin/vets/" + v.getId() + "/password")
                        .param("newPassword", issued).param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("data-notice=\"vet-issued-password\"").contains(issued);
        // 🔴 只断言「块在页面上」是假绿：明文渲染在「账号」页签里，而抽屉是整体重渲染的 ——
        //    页签状态若回到默认的「资料」，这一块就落进一个 hidden 的 section：
        //    DOM 里有、屏幕上看不见，而这是唯一的获取窗口。所以要钉住那个 section **可见**。
        assertThat(html).as("账号页签必须是当前页签")
                .containsPattern("data-vtab=\"account\"[^>]*class=\"tab tab--on\"|class=\"tab tab--on\"[^>]*data-vtab=\"account\"");
        assertThat(html).as("账号 panel 不能是 hidden 的")
                .doesNotContainPattern("<section[^>]*data-vtab-panel=\"account\"[^>]*hidden");
        assertThat(html).as("🔴 关闭文案要强调「已记录」，中性的「关闭」会被顺手点掉")
                .contains("data-dismiss-secret");

        String again = body(mvc.perform(get("/admin/vets/" + v.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(again).as("🔴 再打开抽屉绝不能还看得到明文").doesNotContain(issued)
                .doesNotContain("data-notice=\"vet-issued-password\"");
    }

    // ——————————————————— AC5 退役 ———————————————————

    /**
     * 🔴 两个整页路由**真的没了**；不做旧地址跳转（D-23）——
     * 旧书签拿到 404 / 405 比拿到一个「看着像成功了」的重定向诚实。
     *
     * <p>⚠️ 两条的状态码**不一样**，原因是路径遮蔽，与「有没有删干净」无关：
     * <ul>
     *   <li>{@code /admin/vets/{id}/edit} —— 这条路径上没有同名 POST，干净的 <b>404</b>；</li>
     *   <li>{@code /admin/vets/online} —— 被 {@code POST /admin/vets/{id}} 在**路径层面**盖住
     *       （{@code {id}} 是路径变量，Spring 匹配路径时不看它声明成 long），方法不匹配 →
     *       <b>405 + Allow: POST</b>。405 同样证明 GET 映射不存在，它恰恰说明这条路径上只剩 POST。</li>
     * </ul>
     * 本注释原来写的是「这条路径上没有同名的 POST，所以是干净的 404」——
     * 作者意识到了遮蔽这回事，却把结论用反了，两条都断言成 404（V1.3.0 Story 11.3 复审 C1 修正）。
     */
    @Test
    void theTwoRetiredPagesAreGone() throws Exception {
        VetAccount v = seedVet();
        mvc.perform(get("/admin/vets/online").with(authentication(superAdmin())))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        mvc.perform(get("/admin/vets/" + v.getId() + "/edit").with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    /**
     * 非 htmx 直达抽屉 → 回列表并**自动开该抽屉**（与 B1～B14 同一机制）。
     *
     * <p>⚠️ 必须验 Location 里的 {@code ?open=}：只断言 3xx 的话，把落点改成
     * {@code /admin/vets}（深链彻底失效）这条测试照样绿，而方法名声称验的就是它。
     */
    @Test
    void aDirectHitOnTheDrawerRedirectsToTheListWithOpen() throws Exception {
        VetAccount v = seedVet();
        mvc.perform(get("/admin/vets/" + v.getId() + "/drawer").with(authentication(superAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/vets?open=" + v.getId()));
    }

    /** 🔴 头像上传的三条失败路径在抽屉里都要落进行内错误槽（422），不能像整页那样吞成 flash。 */
    @Test
    void avatarUploadFailuresAre422InTheDrawer() throws Exception {
        VetAccount v = seedVet();
        var notImage = new org.springframework.mock.web.MockMultipartFile(
                "avatar", "a.txt", "text/plain", "hi".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/admin/vets/" + v.getId() + "/avatar").file(notImage)
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());

        byte[] big = new byte[5 * 1024 * 1024 + 1];
        var tooLarge = new org.springframework.mock.web.MockMultipartFile(
                "avatar", "a.png", "image/png", big);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/admin/vets/" + v.getId() + "/avatar").file(tooLarge)
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }
}
