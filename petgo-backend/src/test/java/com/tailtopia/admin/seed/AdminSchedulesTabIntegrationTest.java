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
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 * L1 集成：B5 排期发布并入批量内容页签（V1.3.0 Story 7.5）。
 *
 * <p>本 story 删掉了一个**还在用**的页面（{@code GET /admin/content-schedules}），所以钉的第一件事
 * 是「删之前那三个理由都处理掉了」：① 两个 POST 的重定向落点改成新页签；② 按发布账号筛选在新页签里
 * 仍然可用（Story 12.1 的「移出发布身份前」提示带着 {@code authorId} 跳进来）；③ 内容真的搬过来了。
 *
 * <p>⚠️ 与 {@code SeedScheduleIntegrationTest} 分工：那个类钉的是**机制**（到点发布走同一条链路含审核、
 * 失败行不自动重试、取消回草稿、WIB 折算），本 story 一条都没动，回归照跑。这里只钉页面组织与 htmx。
 */
class AdminSchedulesTabIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @Autowired
    private SeedBatchService batchService;

    @Autowired
    private SeedBatchRowRepository rowRepo;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "schtab-" + n + "@tailtopia.test", "排期页签测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        return staff(AdminAccountType.SUPER_ADMIN);
    }

    private Authentication staff(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "schtabv-" + n + "@tailtopia.test", "排期页签查看员", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它，少了拿到的是过滤链 403 —— 方法门控一次都没被验到。
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String s : permissions) {
            auths.add(new SimpleGrantedAuthority(s));
        }
        return new TestingAuthenticationToken(p, null, auths);
    }

    private long virtualAccount() {
        return virtualAccounts.create("排期号" + (SEQ.incrementAndGet() % 100000), null, 1L);
    }

    private long newBatch() {
        return batchService.openBatch(SeedBatch.Source.EXCEL, adminId()).getId();
    }

    private SeedBatchRow scheduledRow(long authorId, String body, Instant at) {
        SeedBatchRow r = batchService.addDraft(newBatch(), 1, authorId, ContentType.DAILY, null,
                body, null, null);
        batchService.markValidated(r.getId());
        batchService.schedule(r.getId(), at, adminId());
        return rowRepo.findById(r.getId()).orElseThrow();
    }

    /** 直接把一行推成 PUBLISHED（本类只关心它在页面上是只读的，不关心怎么发出去的）。 */
    private SeedBatchRow publishedRow(long authorId, String body) {
        SeedBatchRow r = scheduledRow(authorId, body, Instant.now().plus(1, ChronoUnit.DAYS));
        jdbc.update("update seed_batch_rows set status = 'PUBLISHED' where id = ?", r.getId());
        return rowRepo.findById(r.getId()).orElseThrow();
    }

    private static String wibInput(Instant at) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").format(at.atZone(WIB));
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC6 独立页退役 ———————————————————

    /**
     * 🔴 独立排期页**真的没了**（AC6）。
     *
     * <p>只把内容搬过去、旧页留着，就是同一份数据两个入口 —— 而那正是本 story 要消除的东西
     * （运营在两个地方看到同一批排期，改了这边不知道那边是不是也要改）。不做旧地址跳转（D-23）。
     */
    @Test
    void theStandaloneScheduleListPageIsGone() throws Exception {
        mvc.perform(get("/admin/content-schedules").with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    /** 两个 POST 的**路径与参数逐字未变**，非 htmx 时重定向落点改成新页签（带回发布账号筛选）。 */
    @Test
    void nonHtmxPostsRedirectToTheSchedulesTab() throws Exception {
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(authorId, "改时间的-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/admin/content-schedules/" + row.getId() + "/time")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("scheduledAt", wibInput(Instant.now().plus(3, ChronoUnit.DAYS)))
                        .param("authorId", String.valueOf(authorId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches?tab=schedules&authorId=" + authorId));

        mvc.perform(post("/admin/content-schedules/" + row.getId() + "/cancel")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches?tab=schedules"));
    }

    // ——————————————————— AC1 / AC2 页签与表格 ———————————————————

    @Test
    void schedulesTabRendersTemplateBWithSummaryAndFilters() throws Exception {
        long authorId = virtualAccount();
        String pending = "待发布的-" + SEQ.incrementAndGet();
        scheduledRow(authorId, pending, Instant.now().plus(1, ChronoUnit.DAYS));

        String html = body(mvc.perform(get("/admin/seed-batches").param("tab", "schedules")
                        .param("lang", "zh_CN").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("模板 B 壳 + 抽屉容器").contains("id=\"schedule-drawer\"").contains("data-drawer-mask");
        assertThat(html).as("摘要条三格").contains("id=\"schedules-summary\"")
                .contains("待发布").contains("今日已发").contains("发布失败");
        assertThat(html).as("三个筛选都在（authorId 是 12.1 跳进来的落点，不能丢）")
                .contains("name=\"authorId\"").contains("name=\"status\"").contains("name=\"date\"");
        assertThat(html).as("表格容器与行的抽屉入口").contains("id=\"schedules-rows\"").contains(pending)
                .contains("data-drawer-url=\"/admin/content-schedules/");
        assertThat(html).as("🛡 时间旁必须写 WIB").contains("WIB");
        assertThat(html).as("批次页签仍在，且默认停在批次").contains("排期发布").contains("批次");
    }

    @Test
    void batchesTabIsTheDefaultAndSchedulesFragmentIsHtmxOnly() throws Exception {
        String batchesFirst = body(mvc.perform(get("/admin/seed-batches").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(batchesFirst).as("默认页签是批次（排期表不在首屏）").doesNotContain("id=\"schedules-rows\"");

        String fragment = body(mvc.perform(get("/admin/seed-batches/schedules").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(fragment).as("局部刷新只回表格 + oob 摘要条，不回整页")
                .doesNotContain("<html").contains("hx-swap-oob").contains("id=\"schedules-summary\"");

        mvc.perform(get("/admin/seed-batches/schedules").with(authentication(superAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches?tab=schedules"));
    }

    /** 🔴 按发布账号筛选**必须还在**：Story 12.1 的「移出发布身份前」提示带着 authorId 跳进来。 */
    @Test
    void theTabKeepsTheAuthorFilterThatStory121LinksInto() throws Exception {
        long a = virtualAccount();
        long b = virtualAccount();
        String aBody = "A 的排期-" + SEQ.incrementAndGet();
        String bBody = "B 的排期-" + SEQ.incrementAndGet();
        scheduledRow(a, aBody, Instant.now().plus(1, ChronoUnit.DAYS));
        scheduledRow(b, bBody, Instant.now().plus(1, ChronoUnit.DAYS));

        String html = body(mvc.perform(get("/admin/seed-batches").param("tab", "schedules")
                        .param("authorId", String.valueOf(a)).param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains(aBody).doesNotContain(bBody);
    }

    /** 状态筛选：已发布行只在**显式选它**时才出现，且是只读的（AC2）。 */
    @Test
    void publishedRowsAppearOnlyUnderTheirOwnFilterAndAreReadOnly() throws Exception {
        long authorId = virtualAccount();
        String done = "已经发出去的-" + SEQ.incrementAndGet();
        publishedRow(authorId, done);

        String defaultView = body(mvc.perform(get("/admin/seed-batches").param("tab", "schedules")
                        .param("authorId", String.valueOf(authorId)).param("lang", "zh_CN")
                        .with(authentication(superAdmin()))).andReturn());
        assertThat(defaultView).as("默认口径仍是「待发布 + 失败」—— 已发布是终态，混进来会把待处理的淹掉")
                .doesNotContain(done);

        String publishedView = body(mvc.perform(get("/admin/seed-batches").param("tab", "schedules")
                        .param("status", "PUBLISHED").param("authorId", String.valueOf(authorId))
                        .param("lang", "zh_CN").with(authentication(superAdmin()))).andReturn());
        assertThat(publishedView).contains(done);
        // ⚠️ 不要断 "/time("：那个括号是 Thymeleaf 链接表达式的写法，渲染出来永远是
        //    /admin/content-schedules/3/time，断它恒真。断真正会出现在输出里的东西。
        assertThat(publishedView).as("已发布行只读：不给改时间 / 取消排期的入口")
                .doesNotContain("name=\"scheduledAt\"").doesNotContain("sched-edit")
                .doesNotContain("/cancel\"");
    }

    /** 🛡 失败行留在默认视图里（不自动消失、不自动重试），且不给「改时间」这个必报错的入口。 */
    @Test
    void failedRowsStayListedButGetNoRescheduleButton() throws Exception {
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(authorId, "会失败的-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));
        jdbc.update("update seed_batch_rows set status = 'FAILED', error_message = ? where id = ?",
                "发布账号已被移出发布身份池", row.getId());

        String html = body(mvc.perform(get("/admin/seed-batches").param("tab", "schedules")
                        .param("authorId", String.valueOf(authorId)).param("lang", "zh_CN")
                        .with(authentication(superAdmin()))).andReturn());
        assertThat(html).as("失败行必须留在列表里供运营处理").contains("发布失败");
        assertThat(html).as("失败原因要看得到（列里截断，完整原文在 title）").contains("发布账号已被移出");
        // ⚠️ 本次查询按 authorId 过滤、表里只有这一行，所以整页不含行内编辑态即等于「这一行没有」。
        //    （第一版拼的是 `schedule-row-N" class="sched-edit`，那是两个不同元素上的属性，恒不出现。）
        assertThat(html).as("失败行不给改时间：服务层要求先回工作台修好再重排")
                .doesNotContain("sched-edit");
    }

    // ——————————————————— AC3 / AC4 htmx 处置 ———————————————————

    @Test
    void reschedulingViaHtmxSavesAndAsksTheTableToRefresh() throws Exception {
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(authorId, "改时间的-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));
        Instant target = Instant.now().plus(5, ChronoUnit.DAYS);

        MvcResult r = mvc.perform(post("/admin/content-schedules/" + row.getId() + "/time")
                        .param("scheduledAt", wibInput(target)).param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getScheduledAt())
                .isCloseTo(target, org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
        assertThat(r.getResponse().getHeader("HX-Trigger"))
                .as("🔴 表按计划时间排序，改完时间那一行的位置会变 → 整表重拉，不做单行 oob")
                .contains("admin:schedule-list-refresh").contains("admin:schedule-drawer-refresh");
        assertThat(body(r)).contains("class=\"toast\"");
    }

    /** 🛡 排到过去的时间会被下一轮扫描立刻发出去且不可撤回 → 必须挡住，且报错要**看得见**。 */
    @Test
    void reschedulingIntoThePastIsA422InlineError() throws Exception {
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(authorId, "别排到过去-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));
        Instant before = rowRepo.findById(row.getId()).orElseThrow().getScheduledAt();

        MvcResult r = mvc.perform(post("/admin/content-schedules/" + row.getId() + "/time")
                        .param("scheduledAt", wibInput(Instant.now().minus(1, ChronoUnit.DAYS)))
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .header("HX-Target", "schedules-err")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();

        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#schedules-err");
        assertThat(body(r)).contains("inline-error");
        assertThat(rowRepo.findById(row.getId()).orElseThrow().getScheduledAt())
                .as("被拒的改期不得落库").isEqualTo(before);
    }

    @Test
    void cancellingViaHtmxDropsTheRowAndClosesTheDrawer() throws Exception {
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(authorId, "要取消的-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));

        MvcResult r = mvc.perform(post("/admin/content-schedules/" + row.getId() + "/cancel")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.DRAFT);
        assertThat(r.getResponse().getHeader("HX-Trigger"))
                .as("🔴 取消后这一行回到 DRAFT、已不属于这张表 —— 整表重拉让它消失，抽屉关掉")
                .contains("admin:schedule-list-refresh").contains("admin:drawer-close");
        assertThat(body(r)).contains("class=\"toast\"");
    }

    // ——————————————————— AC5 详情抽屉 ———————————————————

    @Test
    void drawerShowsThePreviewAndTheActions() throws Exception {
        long authorId = virtualAccount();
        String text = "抽屉里看的-" + SEQ.incrementAndGet();
        SeedBatchRow row = scheduledRow(authorId, text, Instant.now().plus(1, ChronoUnit.DAYS));

        String html = body(mvc.perform(get("/admin/content-schedules/" + row.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("id=\"schedule-drawer-panel\"").contains(text).contains("WIB");
        assertThat(html).as("🔴 err 槽必须带 id：htmx 只在目标带 id 时才发 HX-Target 头")
                .contains("id=\"schedule-drawer-err\"");
        assertThat(html).as("回这一批的工作台").contains("/admin/seed-batches/" + row.getBatchId());

        mvc.perform(get("/admin/content-schedules/" + row.getId() + "/drawer")
                        .with(authentication(superAdmin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/seed-batches?tab=schedules&open=" + row.getId()));
    }

    // ——————————————————— 权限 ———————————————————

    @Test
    void withoutVirtualAccountManageEverythingIsForbidden() throws Exception {
        Authentication outsider = staff(AdminAccountType.STAFF, "content.view");
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(authorId, "越权看不到-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(get("/admin/seed-batches").param("tab", "schedules")
                        .with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/seed-batches/schedules").header("HX-Request", "true")
                        .with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/content-schedules/" + row.getId() + "/drawer")
                        .header("HX-Request", "true").with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/content-schedules/" + row.getId() + "/cancel")
                        .header("HX-Request", "true")
                        .with(authentication(outsider)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(rowRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.SCHEDULED);
    }
}
