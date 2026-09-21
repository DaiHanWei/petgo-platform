package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1 集成：E1 单发页与 E2 批量工作台套模板 E（V1.3.0 Story 7.6）。
 *
 * <p>本 story 是本版**唯一主动删写端点**的地方（旧轻量批量 {@code /admin/seed-batch*}），
 * 所以第一件事是钉「删干净了、且删掉的能力真的有人接」：那条路径**没有预览、提交即上线**，
 * 50 行错 3 行就是 3 条线上真帖；四步流（建批次 → 素材 → 录内容 → 预览确认）是它的替代。
 *
 * <p>⚠️ 与 {@code SeedBatch*IntegrationTest} 分工：那些钉的是机制（状态机、校验、发布、素材配额），
 * 本 story 一条都没动，回归照跑。这里只钉页面组织：步骤条、分步渲染、行内编辑态、成帖效果抽屉。
 */
class AdminSeedTemplateEIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SeedBatchService batchService;

    @Autowired
    private SeedBatchRowRepository rowRepo;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "tple-" + n + "@tailtopia.test", "模板E测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "tplev-" + n + "@tailtopia.test", "模板E查看员", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private long newBatch() {
        return batchService.openBatch(SeedBatch.Source.ONLINE_PASTE, adminId()).getId();
    }

    private SeedBatchRow draftRow(long batchId, String body) {
        long authorId = virtualAccounts.create("模板E号" + (SEQ.incrementAndGet() % 100000), null, 1L);
        return batchService.addDraft(batchId, 1, authorId, ContentType.DAILY, null, body, null, null);
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC2 旧轻量批量端点删除 ———————————————————

    /**
     * 🔴 四个旧端点**真的没了**。
     *
     * <p>删的理由不是"重复"而是"危险"：那条路径贴进去就发、没有校验预览、去重命中静默跳过。
     * 留着它等于在页面上并排摆着一条安全的路和一条不安全的路。
     */
    @Test
    void theLegacyLightweightBatchEndpointsAreGone() throws Exception {
        Authentication admin = superAdmin();
        mvc.perform(get("/admin/seed-batch").with(authentication(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/seed-batch/template").with(authentication(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/seed-batch").with(authentication(admin)).with(csrf())
                        .param("virtualUserId", "1").param("lines", "x"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/seed-batch/import").with(authentication(admin)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ——————————————————— AC1 E1 单发页 ———————————————————

    @Test
    void singlePostPageKeepsEverySingleFieldAndDropsTheTwoLegacyBlocks() throws Exception {
        String html = body(mvc.perform(get("/admin/seed-post").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("模板 E 单步壳").contains("step-bar").contains("step--on");
        assertThat(html).as("单发表单的字段一个都不能少")
                .contains("name=\"authorUserId\"").contains("name=\"species\"")
                .contains("name=\"type\"").contains("name=\"text\"")
                .contains("maxlength=\"1000\"")
                .contains("data-seed-file").contains("name=\"imageUrlsRaw\"")
                .contains("name=\"imageSizesRaw\"").contains("name=\"petId\"");
        assertThat(html).as("提交钮在吸底条里，用 form= 关联到上面的表单")
                .contains("sticky-footer").contains("form=\"seedPostForm\"");
        assertThat(html).as("🔴 页内两个旧批量区块必须删干净 —— 留着就是两条路并排摆着")
                .doesNotContain("/admin/seed-batch\"")
                .doesNotContain("name=\"lines\"")
                // ⚠️ 别断「批量发布」四个字：旧区块的 key 已从四包删除，即使 HTML 留着也印不出来，
                //    那样断是恒真的。断结构：页签面板与旧端点地址。
                .doesNotContain("data-tab-panel");
    }

    // ——————————————————— AC3 E2 四步 ———————————————————

    @Test
    void workspaceShowsOneStepAtATimeWithTheStepBar() throws Exception {
        long batchId = newBatch();
        draftRow(batchId, "工作台里的一行-" + SEQ.incrementAndGet());
        Authentication admin = superAdmin();

        String step0 = body(mvc.perform(get("/admin/seed-batches/" + batchId).param("lang", "zh_CN")
                        .with(authentication(admin))).andExpect(status().isOk()).andReturn());
        assertThat(step0).as("默认停在第 0 步：批次设置").contains("step-bar")
                .contains("name=\"defaultContentType\"");
        // ⚠️ 不能断 data-row-form：那个字面量在「未保存行提示」的内联脚本里，每一步都会渲染。
        //    断只可能出现在行卡片上的东西。
        assertThat(step0).as("第 1 / 2 步的东西不该同屏").doesNotContain("data-batch-uploader")
                .doesNotContain("class=\"card seed-row-card\"").doesNotContain("name=\"assetFileNames\"");

        String step1 = body(mvc.perform(get("/admin/seed-batches/" + batchId).param("step", "1")
                        .param("lang", "zh_CN").with(authentication(admin))).andReturn());
        assertThat(step1).contains("data-batch-uploader");
        assertThat(step1).as("🔴 图片命名与引用说明默认展开：第 2 步的图片列填的是文件名")
                .contains("class=\"card batch-naming\" open");

        String step2 = body(mvc.perform(get("/admin/seed-batches/" + batchId).param("step", "2")
                        .param("lang", "zh_CN").with(authentication(admin))).andReturn());
        assertThat(step2).contains("data-row-form").contains("工作台里的一行");
        assertThat(step2).as("吸底条给得出上一步与下一步").contains("sticky-footer")
                .contains("data-step-next");

        // 手改 URL 的越界 step 夹回来，不 500 也不空屏
        mvc.perform(get("/admin/seed-batches/" + batchId).param("step", "99")
                        .with(authentication(admin)))
                .andExpect(status().isOk());
    }

    /** 🔴 新增空行直接落在编辑态上：回到列表再让运营自己找那一行，在几十行的批次里就是一次翻找。 */
    @Test
    void addingABlankRowLandsOnThatRowInEditState() throws Exception {
        long batchId = newBatch();
        Authentication admin = superAdmin();

        MvcResult r = mvc.perform(post("/admin/seed-batches/" + batchId + "/rows")
                        .with(authentication(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = r.getResponse().getRedirectedUrl();
        assertThat(location).contains("step=2").contains("edit=");

        long rowId = Long.parseLong(location.substring(location.indexOf("edit=") + 5));
        String html = body(mvc.perform(get("/admin/seed-batches/" + batchId)
                        .param("step", "2").param("edit", String.valueOf(rowId))
                        .param("lang", "zh_CN").with(authentication(admin))).andReturn());
        // ⚠️ th:open 是**布尔属性**处理器：条件为真时输出的是 open="open"，不是裸 open。
        //    （反过来把 th:open 换成 th:attr="open=..." 才是真 bug —— 条件为假时会输出 open=""，
        //      在 HTML 里等于展开。）
        assertThat(html).as("那一行的 <details> 是展开的（紫底编辑态）")
                .contains("<details class=\"row-edit\" open=\"open\">");
        assertThat(rowRepo.findById(rowId)).isPresent();
    }

    /** 逐行保存 / 删除 / 粘贴 / 导入之后都回到第 2 步 —— 落回第 0 步等于把运营刚在做的事丢掉。 */
    @Test
    void rowActionsComeBackToStepTwo() throws Exception {
        long batchId = newBatch();
        SeedBatchRow row = draftRow(batchId, "要保存的一行-" + SEQ.incrementAndGet());
        Authentication admin = superAdmin();

        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/" + row.getId())
                        .with(authentication(admin)).with(csrf())
                        .param("body", "改过的正文-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches/" + batchId + "?step=2"));

        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/paste")
                        .with(authentication(admin)).with(csrf()).param("lines", "粘一行"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches/" + batchId + "?step=2"));

        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/" + row.getId() + "/delete")
                        .with(authentication(admin)).with(csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches/" + batchId + "?step=2"));

        // 批次设置回第 0 步（它就在那一步上）
        mvc.perform(post("/admin/seed-batches/" + batchId + "/settings")
                        .with(authentication(admin)).with(csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches/" + batchId + "?step=0"));
    }

    // ——————————————————— AC3 预览与成帖效果抽屉 ———————————————————

    @Test
    void previewPageIsStepFourAndEveryRowOpensTheCardDrawer() throws Exception {
        long batchId = newBatch();
        SeedBatchRow row = draftRow(batchId, "看看发出去什么样-" + SEQ.incrementAndGet());
        Authentication admin = superAdmin();

        String html = body(mvc.perform(get("/admin/seed-batches/" + batchId + "/preview")
                        .param("lang", "zh_CN").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("与工作台共用同一条步骤条，停在第 4 步").contains("step-bar")
                .contains("预览确认");
        assertThat(html).as("点行开抽屉")
                .contains("data-drawer-url=\"/admin/seed-batches/" + batchId + "/preview/"
                        + row.getId() + "/drawer\"")
                .contains("id=\"batchrow-drawer\"");
        assertThat(html).as("确认发布的二次确认要复述行数").contains("data-confirm-args");

        String drawer = body(mvc.perform(get("/admin/seed-batches/" + batchId + "/preview/"
                        + row.getId() + "/drawer").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn());
        assertThat(drawer).contains("id=\"batchrow-drawer-panel\"").contains("看看发出去什么样");
        assertThat(drawer).as("🔴 抽屉只读：这里放写入口会绕开第 2 步的逐行校验")
                .doesNotContain("<form").doesNotContain("hx-post");

        mvc.perform(get("/admin/seed-batches/" + batchId + "/preview/" + row.getId() + "/drawer")
                        .with(authentication(admin)))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches/" + batchId + "/preview?open=" + row.getId()));
    }

    /** 🛡 抽屉里翻行只在本批次内：拿别的批次的行 id 过来必须 404，而不是渲染出别人的内容。 */
    @Test
    void theCardDrawerRefusesRowsFromAnotherBatch() throws Exception {
        long batchA = newBatch();
        long batchB = newBatch();
        SeedBatchRow inB = draftRow(batchB, "另一批的行-" + SEQ.incrementAndGet());

        mvc.perform(get("/admin/seed-batches/" + batchA + "/preview/" + inB.getId() + "/drawer")
                        .header("HX-Request", "true").with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    // ——————————————————— 权限 ———————————————————

    @Test
    void withoutVirtualAccountManageTheWholeFlowIsForbidden() throws Exception {
        long batchId = newBatch();
        SeedBatchRow row = draftRow(batchId, "越权看不到-" + SEQ.incrementAndGet());
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "tpleo-" + n + "@tailtopia.test", "无权测试员", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        var auths = new ArrayList<org.springframework.security.core.GrantedAuthority>();
        auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("content.view"));
        Authentication outsider = new TestingAuthenticationToken(p, null, auths);

        mvc.perform(get("/admin/seed-batches/" + batchId).with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/seed-batches/" + batchId + "/preview").with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/seed-batches/" + batchId + "/preview/" + row.getId() + "/drawer")
                        .header("HX-Request", "true").with(authentication(outsider)))
                .andExpect(status().isForbidden());
    }
}
