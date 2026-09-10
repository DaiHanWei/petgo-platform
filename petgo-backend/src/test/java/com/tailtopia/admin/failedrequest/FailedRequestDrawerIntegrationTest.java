package com.tailtopia.admin.failedrequest;

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
import com.tailtopia.admin.failedrequest.domain.CancelReason;
import com.tailtopia.admin.failedrequest.domain.FailedConsultRequest;
import com.tailtopia.admin.failedrequest.repository.FailedConsultRequestRepository;
import com.tailtopia.admin.failedrequest.service.FailedConsultRequestService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
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
 * L1 集成：B21 未成功请求 + B22 历史会话查询套模板 B（V1.3.0 Story 9.2）。
 *
 * <p>本 story 是**零写端点变更**的页面重构：三个写端点的路径 / 参数 / 权限一个字没动，
 * 新增的只有两个 drawer GET。所以钉的是「三个动作仍是三件独立的事、归档前置仍然生效、
 * 取证抽屉不外泄任何会话内容」。
 */
class FailedRequestDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FailedConsultRequestRepository repo;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private FailedConsultRequestService service;
    @Autowired
    private ConsultSessionRepository consultSessions;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "failed-" + n + "@tailtopia.test", "掉单测试员", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它，少了拿到的是过滤链 403。
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

    /** 直接落库造记录：本 story 不碰「事件 → 落库」那条链（它由 FailedConsultRequestIntegrationTest 守）。 */
    private FailedConsultRequest seed(CancelReason reason) {
        long n = SEQ.incrementAndGet();
        return repo.save(FailedConsultRequest.of("req-" + n, 700L + n, 900L + n,
                Instant.parse("2026-09-01T02:00:00Z"), Instant.parse("2026-09-01T02:05:00Z"),
                reason, 0));
    }

    /** 造一条真实会话：抽屉是只读视图，WAITING 态就够把「摊开那一行」验完。 */
    private ConsultSession seedSession() {
        long n = SEQ.incrementAndGet();
        return consultSessions.save(ConsultSession.startWaiting(80000L + n, ConsultSource.DIRECT));
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 B21 ———————————————————

    @Test
    void theListUsesTemplateBWithTabsAndASummary() throws Exception {
        FailedConsultRequest r = seed(CancelReason.SYSTEM_FAILURE);
        String html = body(mvc.perform(get("/admin/failed-requests").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"failed-drawer\"").contains("id=\"failed-summary\"")
                .contains("data-sum=\"total\"").contains("data-sum=\"systemFailure\"")
                .contains("data-sum=\"followedUp\"");
        assertThat(html).contains("data-drawer-url=\"/admin/failed-requests/" + r.getId() + "/drawer\"");
        // 页签走查询参数（运营会把「已归档」那一页存书签）。
        assertThat(html).contains("/admin/failed-requests?tab=archived");
        // 🔴 行内三个动作都搬进抽屉了。
        assertThat(html).doesNotContain("/note\"").doesNotContain("/follow-up\"")
                .doesNotContain("/archive\"");
        // SYSTEM_FAILURE 行强提示 —— 它混在「用户自己取消」中间会被一起划过去。
        assertThat(html).contains("row-alert");
    }

    /** 🔴 摘要随页签联动：在「已归档」页签上给活动区的数，读起来是骗人的。 */
    @Test
    void theSummaryFollowsTheTabNotTheActiveQueueAlways() throws Exception {
        FailedConsultRequest r = seed(CancelReason.SYSTEM_FAILURE);
        // ⚠️ 不能钉「归档页签的数是 0」：ApiIntegrationTest 不回滚，同包别的测试会往库里留
        //    永久归档的记录（类名字母序在前时先跑）。钉的是**同一时刻的库现状**与页面数一致。
        int archivedTotal = service.archived().size();
        int activeTotal = service.active().size();
        assertThat(activeTotal).as("刚 seed 的这条必在活动区").isPositive();

        String archived = body(mvc.perform(get("/admin/failed-requests").param("tab", "archived")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(archived).as("局部刷新不该回整页").doesNotContain("<html");
        assertThat(archived).containsPattern("<div[^>]*id=\"failed-summary\"[^>]*hx-swap-oob=\"true\"");
        assertThat(archived).as("已归档页签给的是归档区的数，不是活动区的")
                .containsPattern("data-sum=\"total\"[^>]*>\\s*" + archivedTotal + "\\s*<");
        // 🔴 真正的反证：刚 seed 的那条在活动区，它不该出现在「已归档」页签里。
        assertThat(archived).doesNotContain(
                "data-drawer-url=\"/admin/failed-requests/" + r.getId() + "/drawer\"");

        String active = body(mvc.perform(get("/admin/failed-requests")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(active).containsPattern("data-sum=\"total\"[^>]*>\\s*" + activeTotal + "\\s*<");
        assertThat(active).contains(
                "data-drawer-url=\"/admin/failed-requests/" + r.getId() + "/drawer\"");
    }

    @Test
    void theDrawerCarriesAllThreeActionsAsSeparateThings() throws Exception {
        FailedConsultRequest r = seed(CancelReason.TIMEOUT);
        String html = body(mvc.perform(get("/admin/failed-requests/" + r.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).doesNotContain("<html");
        assertThat(html).contains("id=\"failed-drawer-panel\"").contains("id=\"failed-drawer-err\"");
        // 🔴 三个动作是三件事，不是一件事的三种叫法 —— 三个独立的提交入口。
        assertThat(html).contains("hx-post=\"/admin/failed-requests/" + r.getId() + "/note\"")
                .contains("hx-post=\"/admin/failed-requests/" + r.getId() + "/follow-up\"")
                .contains("hx-post=\"/admin/failed-requests/" + r.getId() + "/archive\"");
        assertThat(html).contains("name=\"note\"");
        // 有会话 id 就给一条去取证的路（B22 页内深链）。
        assertThat(html).contains("/admin/consult-sessions?open=" + r.getSessionId());
    }

    /**
     * 🔴 SYSTEM_FAILURE 未跟进不可归档：**服务端那道判定一个字没动**，抽屉只是提前告诉运营。
     *
     * <p>所以两件事都要钉：① 抽屉里归档钮是禁用的且写明原因；
     * ② 即便绕过界面直接 POST，服务端仍然拒（422 落进行内错误槽）。
     */
    @Test
    void aSystemFailureCannotBeArchivedBeforeFollowUpAndTheDrawerSaysSo() throws Exception {
        FailedConsultRequest r = seed(CancelReason.SYSTEM_FAILURE);
        String html = body(mvc.perform(get("/admin/failed-requests/" + r.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("data-notice=\"failed-archive-blocked\"").contains("disabled");
        assertThat(html).as("禁用态下不该留下可提交的归档表单")
                .doesNotContain("hx-post=\"/admin/failed-requests/" + r.getId() + "/archive\"");

        // 绕过界面直接 POST：服务端照拒。
        mvc.perform(post("/admin/failed-requests/" + r.getId() + "/archive")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(repo.findById(r.getId()).orElseThrow().getArchivedAt()).isNull();

        // 跟进之后就能归档了。
        mvc.perform(post("/admin/failed-requests/" + r.getId() + "/follow-up")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk());
        var result = mvc.perform(post("/admin/failed-requests/" + r.getId() + "/archive")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(repo.findById(r.getId()).orElseThrow().getArchivedAt()).isNotNull();

        assertThat(body(result)).contains("hx-swap-oob=\"innerHTML:#failed-drawer .drawer-body\"");
        assertThat(result.getResponse().getHeader("HX-Trigger"))
                .as("归档会把行移出活动区，列表必须按当前页签整表重拉")
                .contains("admin:failed-request-list-refresh");
        // 🔴 反过来钉住：**不做单行 oob** —— 原位换行会留下一张骗人的表（行还在活动页签里）。
        assertThat(body(result)).doesNotContain("id=\"failed-row-" + r.getId() + "\"");
    }

    /** 🛡 三个写端点与抽屉同一道门 {@code vet.view}。 */
    @Test
    void everythingRequiresVetView() throws Exception {
        FailedConsultRequest r = seed(CancelReason.TIMEOUT);
        Authentication outsider = auth(AdminAccountType.STAFF, AdminPermissions.CONTENT_VIEW);
        mvc.perform(get("/admin/failed-requests/" + r.getId() + "/drawer").header("HX-Request", "true")
                        .with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/failed-requests/" + r.getId() + "/follow-up")
                        .with(authentication(outsider)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ——————————————————— AC2 B22 ———————————————————

    @Test
    void theSessionPageUsesTemplateBAndNeverListsEverythingByDefault() throws Exception {
        ConsultSession s = seedSession();
        String html = body(mvc.perform(get("/admin/consult-sessions").param("lang", "zh_CN")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONSULT_VIEW_SESSIONS))))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"session-drawer\"");
        // 查询条件四项 + 显式「查询」钮（不 autosubmit）。
        assertThat(html).contains("name=\"userId\"").contains("name=\"vetId\"")
                .contains("name=\"from\"").contains("name=\"to\"");
        assertThat(html).doesNotContain("data-autosubmit");
        // 🔴 没查之前不该显示「无匹配会话」——那会被读成「系统里一条都没有」。
        assertThat(html).contains("data-notice=\"sessions-search-first\"");
        // 🔴 NFR5 说明常驻。
        assertThat(html).contains("data-notice=\"sessions-nfr5\"");
        // 🔴 方法名那句话得是真的：一个条件都没填时**一行都不列**（不是「查了但恰好为空」）。
        //    库里明明有这条会话，默认页上不该出现它。
        assertThat(html).as("空条件不该把整张 consult_sessions 摊出来")
                .doesNotContain("id=\"session-row-" + s.getId() + "\"");

        // 填了条件才查得到 —— 反证上面那条不是因为查询坏了。
        String found = body(mvc.perform(get("/admin/consult-sessions")
                        .param("userId", String.valueOf(s.getUserId())).param("lang", "zh_CN")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONSULT_VIEW_SESSIONS))))
                .andExpect(status().isOk()).andReturn());
        assertThat(found).contains("id=\"session-row-" + s.getId() + "\"")
                .doesNotContain("data-notice=\"sessions-search-first\"");
    }

    /**
     * 🔴 取证抽屉**只把列表那一行摊开**：会话元数据 + 评分，一个字段都不多。
     *
     * <p>绝不读 IM 正文 / AI 分诊结论 / 用户媒体（NFR5）。这条测试钉的是
     * 「将来有人为了让抽屉更丰富而加内容读取」时会红。
     */
    @Test
    void theSessionDrawerCarriesMetadataOnlyAndSaysWhy() throws Exception {
        ConsultSession s = seedSession();
        String html = body(mvc.perform(get("/admin/consult-sessions/" + s.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONSULT_VIEW_SESSIONS))))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).doesNotContain("<html");
        assertThat(html).contains("id=\"session-drawer-panel\"")
                .contains("data-section=\"session-meta\"").contains("data-section=\"session-rating\"");
        // 摊开的就是列表那一行：会话 id / 用户 / 状态。
        assertThat(html).contains(">" + s.getId() + "<").contains(">" + s.getUserId() + "<")
                .contains(s.getStatus().name());
        // 🔴 NFR5：抽屉里没有聊天记录**是刻意的**，说明必须在。
        assertThat(html).contains("data-notice=\"session-nfr5\"");
        // 🔴 反过来钉住：将来有人「为了让抽屉更丰富」去加内容读取，这里会红。
        assertThat(html).doesNotContain("imConversationId").doesNotContain("im_conversation_id")
                .doesNotContain("aiSymptomText").doesNotContain("ai_symptom_text")
                .doesNotContain("aiImageRefs").doesNotContain("vetDiagnosis");
        // 🔴 全只读：一个 <form>、一个写端点都没有。
        assertThat(html).doesNotContain("<form").doesNotContain("hx-post");

        // 不存在的会话 → 404（?open=<不存在的 id> 深链会走到这里）。
        mvc.perform(get("/admin/consult-sessions/999999999/drawer").header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONSULT_VIEW_SESSIONS))))
                .andExpect(status().isNotFound());
    }

    /**
     * 🔴 深链取不到时错误要落在**抽屉体里**（9.2 复审 M2）。
     *
     * <p>抽屉体带 id → htmx 发 HX-Target → AdminBusinessExceptionAdvice 按它回填；
     * 没有 id 时错误被退回表格下面的 {@code #admin-inline-error}，而那时它正被遮罩盖着 ——
     * 运营看到的是一扇空白的 480px 抽屉。
     */
    @Test
    void aDeepLinkToAMissingSessionPutsTheErrorInsideTheDrawerNotUnderTheMask() throws Exception {
        String page = body(mvc.perform(get("/admin/consult-sessions").param("open", "999999999")
                        .param("lang", "zh_CN")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONSULT_VIEW_SESSIONS))))
                .andExpect(status().isOk()).andReturn());
        assertThat(page).contains("id=\"session-drawer-body\"")
                .contains("data-drawer-deeplink=\"/admin/consult-sessions/999999999/drawer\"");

        var err = mvc.perform(get("/admin/consult-sessions/999999999/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .header("HX-Target", "session-drawer-body")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONSULT_VIEW_SESSIONS))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(err.getResponse().getHeader("HX-Retarget")).isEqualTo("#session-drawer-body");
        assertThat(body(err)).contains("inline-error");
    }

    /** 🛡 会话页与抽屉同一道门 {@code consult.view_sessions}。 */
    @Test
    void theSessionDrawerRequiresConsultViewSessions() throws Exception {
        mvc.perform(get("/admin/consult-sessions/1/drawer").header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.STAFF, AdminPermissions.VET_VIEW))))
                .andExpect(status().isForbidden());
    }

    /**
     * AC3：两页维持独立（2026-09-04 拍板不合并）。
     *
     * <p>「侧栏两项都在」不够 —— 合并之后侧栏也可能留着两个链接。真正的征兆是
     * **一页里出现另一页的控件**：两套筛选条、两张表、两个抽屉。
     */
    @Test
    void theTwoPagesStayIndependentInsteadOfBeingMerged() throws Exception {
        String failed = body(mvc.perform(get("/admin/failed-requests").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        String sessions = body(mvc.perform(get("/admin/consult-sessions").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        // 侧栏两项都在（没被合并成一项）。
        assertThat(failed).contains("/admin/failed-requests").contains("/admin/consult-sessions");
        // 各自只有自己的筛选条 / 表 / 抽屉。
        assertThat(failed).contains("id=\"failed-rows\"").contains("id=\"failed-drawer\"")
                .doesNotContain("id=\"session-filters-form\"").doesNotContain("id=\"session-rows\"")
                .doesNotContain("id=\"session-drawer\"");
        assertThat(sessions).contains("id=\"session-filters-form\"").contains("id=\"session-rows\"")
                .contains("id=\"session-drawer\"")
                .doesNotContain("id=\"failed-rows\"").doesNotContain("id=\"failed-summary\"")
                .doesNotContain("id=\"failed-drawer\"");
    }
}
