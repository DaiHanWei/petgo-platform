package com.tailtopia.admin.vet;

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
    void theDrawerHasFourTabsWithQualAndRatingAsPlaceholders() throws Exception {
        VetAccount v = seedVet();
        String html = body(mvc.perform(get("/admin/vets/" + v.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("局部片段不该回整页").doesNotContain("<html");
        assertThat(html).contains("id=\"vet-drawer-panel\"").contains("id=\"vet-drawer-err\"")
                .contains("data-vtab=\"profile\"").contains("data-vtab=\"qual\"")
                .contains("data-vtab=\"rating\"").contains("data-vtab=\"account\"");
        // 🔴 占位必须说明「什么时候会有」：空白页签会被读成「这个兽医没有资质记录」。
        assertThat(html).contains("data-notice=\"vet-qual-placeholder\"")
                .contains("data-notice=\"vet-rating-placeholder\"");
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

    @Test
    void banningViaHtmxSwapsTheRowAndTheSummary() throws Exception {
        VetAccount v = seedVet();
        String html = body(mvc.perform(post("/admin/vets/" + v.getId() + "/status")
                        .param("banned", "true").param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("hx-swap-oob=\"innerHTML:#vet-drawer .drawer-body\"");
        // 🔴 行与摘要条都必须是 oob 换出去的：只断言 id 出现过，把 true 写成 false 也照样绿，
        //    而实机是列表与四个数一动不动、界面零反馈。
        assertThat(html).containsPattern("<tr[^>]*id=\"vet-row-" + v.getId()
                + "\"[^>]*hx-swap-oob=\"true\"");
        assertThat(html).containsPattern("<div[^>]*id=\"vets-summary\"[^>]*hx-swap-oob=\"true\"");
        // 🔴 oob 行外壳必须是真的 <table hidden>（响应不以 `<tr` 开头时 htmx 走通用解析）。
        assertThat(html.indexOf("<table hidden")).isGreaterThanOrEqualTo(0)
                .isLessThan(html.indexOf("id=\"vet-row-" + v.getId() + "\""));
        assertThat(vets.findById(v.getId()).orElseThrow().getStatus().name()).isEqualTo("BANNED");
    }

    /** 资料校验失败：422 并且**连着表单一起回显**（只回一句错误的话，运营刚填的会被换掉）。 */
    @Test
    void anInvalidProfileComesBackWithTheFormNotJustAnError() throws Exception {
        VetAccount v = seedVet();
        String html = body(mvc.perform(post("/admin/vets/" + v.getId())
                        .param("displayName", "").param("username", "not-an-email")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn());

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

        String again = body(mvc.perform(get("/admin/vets/" + v.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(again).as("🔴 再打开抽屉绝不能还看得到明文").doesNotContain(issued)
                .doesNotContain("data-notice=\"vet-issued-password\"");
    }

    // ——————————————————— AC5 退役 ———————————————————

    /**
     * 🔴 两个整页路由**真的没了**，且拿到的是 404 而不是 500。
     *
     * <p>{@code /admin/vets/{id}/edit} 这条路径上没有同名的 POST，所以是干净的 404；
     * 不做旧地址跳转（D-23）—— 旧书签拿到 404 比拿到一个「看着像成功了」的重定向诚实。
     */
    @Test
    void theTwoRetiredPagesAreGone() throws Exception {
        VetAccount v = seedVet();
        mvc.perform(get("/admin/vets/online").with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/vets/" + v.getId() + "/edit").with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    /** 非 htmx 直达抽屉 → 回列表并自动开该抽屉（与 B1～B14 同一机制）。 */
    @Test
    void aDirectHitOnTheDrawerRedirectsToTheListWithOpen() throws Exception {
        VetAccount v = seedVet();
        mvc.perform(get("/admin/vets/" + v.getId() + "/drawer").with(authentication(superAdmin())))
                .andExpect(status().is3xxRedirection());
    }
}
