package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.tailtopia.admin.seed.service.SeedBatchPublishService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.seed.service.SeedSchedulePublishScanner;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：定时发布与排期管理（V1.1.6 Story 13.5 · AB-3L）。
 *
 * <h2>🛡 本类最重要的一条</h2>
 * {@link #publishingAtTheScheduledTimeStillGoesThroughAutoModeration()} ——
 * AC2 写着「走与即时发布**完全相同的链路**，含内容自动审核、**无豁免**」，
 * 而"另写一条到点发布的快路、顺便跳过审核"是最容易被想到的省事做法。
 * 这条用一段会被硬拦截的文案来证明审核**真的跑了**。
 *
 * <h2>🔴 AC6 是一处刻意的取舍，也有一条用例</h2>
 * {@link #nearDuplicateAppearingDuringTheScheduleWindowStillPublishes()} ——
 * 到点**不再做去重校验**：「到点必发」这一确定性比「避免重复内容」更重要，
 * 因为<b>"排好的内容莫名没发"会让运营对定时功能失去信任、退回手动发布</b>。
 */
class SeedScheduleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SeedBatchService batchService;

    @Autowired
    private SeedBatchPublishService publishing;

    @Autowired
    private SeedSchedulePublishScanner scanner;

    @Autowired
    private SeedBatchRowRepository rowRepo;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private PetProfileRepository pets;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    @Autowired
    private AdminPublishIdentityService identities;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "sch-" + n + "@tailtopia.test", "排期测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "schview-" + n + "@tailtopia.test", "排期查看员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    /** ⚠️ 昵称 ≤20 字；SEQ 是 nanoTime 种子的大整数。 */
    private long virtualAccount() {
        return virtualAccounts.create("排期号" + (SEQ.incrementAndGet() % 100000), null, 1L);
    }

    private long newBatch() {
        return batchService.openBatch(SeedBatch.Source.EXCEL, adminId()).getId();
    }

    /**
     * 造一条**已排期**的行。
     *
     * <p>⚠️ 计划时间先给未来（`schedule()` 之后再用 SQL 推到过去）——
     * 因为"不可早于当前"的校验在入口上，而这里要造的正是"已经到点"的状态。
     */
    private SeedBatchRow scheduledRow(long batchId, long authorId, String body, Instant at) {
        SeedBatchRow r = batchService.addDraft(batchId, 1, authorId, ContentType.DAILY, null,
                body, null, null);
        batchService.markValidated(r.getId());
        batchService.schedule(r.getId(), at, adminId());
        return rowRepo.findById(r.getId()).orElseThrow();
    }

    /** 把计划时间推到过去（模拟"到点了"）。 */
    private void makeDue(long rowId) {
        jdbc.update("update seed_batch_rows set scheduled_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), rowId);
    }

    private List<ContentPost> postsOf(long authorId) {
        return posts.findAll().stream()
                // ⚠️ Objects.equals：getAuthorId() 是装箱 Long，`==` 比引用、id 一大就恒 false。
                .filter(p -> Objects.equals(p.getAuthorId(), authorId)).toList();
    }

    // ——————————————————— 🛡 AC2 走同一条链路（含自动审核） ———————————————————

    /**
     * 🛡 <b>到点发布必须经过自动审核</b>（AC2，延续 V1.0.0「种子内容不设审核豁免」）。
     *
     * <p>判据：用一段会命中 L1 硬拦截词库的文案。走既有链路 ⇒ 发布被拦、该行转 FAILED；
     * 而"另写一条绕过审核的快路"会让它照发出去。
     */
    @Test
    void publishingAtTheScheduledTimeStillGoesThroughAutoModeration() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(batchId, authorId, "ayo main judi online",
                Instant.now().plus(1, ChronoUnit.DAYS));
        makeDue(row.getId());

        scanner.publishDueRows();

        SeedBatchRow after = rowRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("🛡 硬拦截词必须把它拦下 —— 说明审核真的跑了")
                .isEqualTo(SeedBatchRowStatus.FAILED);
        assertThat(postsOf(authorId)).as("被拦下的内容不该落库").isEmpty();
        // 🛡 失败原因要能让运营看懂"我该怎么办"。
        assertThat(after.getErrorMessage()).isNotBlank();
    }

    /** 正常内容到点发出。 */
    @Test
    void aDueRowIsPublishedAutomatically() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        String marker = "到点自动发的-" + SEQ.incrementAndGet();
        SeedBatchRow row = scheduledRow(batchId, authorId, marker,
                Instant.now().plus(1, ChronoUnit.DAYS));
        makeDue(row.getId());

        scanner.publishDueRows();

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.PUBLISHED);
        assertThat(postsOf(authorId)).extracting(ContentPost::getText).contains(marker);
        // 🔴 内容 id 回填 —— 「整批撤回」（本版本不做）唯一的抓手。
        assertThat(rowRepo.findById(row.getId()).orElseThrow().getContentPostId()).isNotNull();
    }

    /** 还没到点的不动 —— 提前发比晚发更糟（内容可能配合某个时间点）。 */
    @Test
    void rowsThatAreNotDueYetAreLeftAlone() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(batchId, authorId, "还没到点-" + SEQ.incrementAndGet(),
                Instant.now().plus(2, ChronoUnit.DAYS));

        scanner.publishDueRows();

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.SCHEDULED);
    }

    /** 扫两轮不会重发（幂等：状态转走之后就不在扫描集里了）。 */
    @Test
    void scanningTwiceDoesNotPublishTwice() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(batchId, authorId, "只发一次-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));
        makeDue(row.getId());

        scanner.publishDueRows();
        scanner.publishDueRows();

        assertThat(postsOf(authorId)).hasSize(1);
    }

    // ——————————————————— 🛡 AC5 失败处置 ———————————————————

    /**
     * 🛡 <b>发布账号被移出身份池 ⇒ 到点标记失败并注明原因，不自动重试</b>。
     *
     * <p>这正是 12-1 那条"移出前提示还有 N 条排期"要防的后果 ——
     * 提示存在的意义就是让运营在移出之前先看见这些行。
     */
    @Test
    void anAccountRemovedFromThePoolMakesTheDueRowFailWithAReason() throws Exception {
        long admin = adminId();
        User real = newUser();
        mvc.perform(post("/admin/publish-identities").with(authentication(superAdmin())).with(csrf())
                        .param("userId", String.valueOf(real.getId()))
                        .param("authorizationNote", "排期测试用"))
                .andExpect(status().is3xxRedirection());
        long batchId = newBatch();
        SeedBatchRow row = scheduledRow(batchId, real.getId(), "账号会被移出-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));
        makeDue(row.getId());

        // 移出身份池，然后到点。
        identities.remove(real.getId(), admin);
        scanner.publishDueRows();

        SeedBatchRow after = rowRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SeedBatchRowStatus.FAILED);
        assertThat(after.getErrorMessage()).isNotBlank();
        assertThat(postsOf(real.getId())).isEmpty();
    }

    /** 🛡 失败**不自动重试**：再扫一轮它仍是 FAILED，不会偷偷再试一次。 */
    @Test
    void failedRowsAreNotRetriedAutomatically() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(batchId, authorId, "ayo main judi online",
                Instant.now().plus(1, ChronoUnit.DAYS));
        makeDue(row.getId());
        scanner.publishDueRows();

        scanner.publishDueRows();

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.FAILED);
    }

    // ——————————————————— 🔴 AC6 到点不再去重 ———————————————————

    /**
     * 🔴 <b>排期期间出现近似内容，到点仍照发</b>（A-16 的刻意取舍）。
     *
     * <p>「到点必发」这一确定性比「避免重复内容」更重要：重复内容运营发现后可下架，
     * 而<b>"排好的内容莫名没发"会让运营对定时功能失去信任、退回手动发布</b>。
     *
     * <p>本例造的正是 AC6 点名的场景：运营排了明天的定时，
     * 而**同一账号今天先发了一条一模一样的** —— 明天那条照发。
     */
    @Test
    void nearDuplicateAppearingDuringTheScheduleWindowStillPublishes() {
        long admin = adminId();
        long authorId = virtualAccount();
        String sameCopy = "排期期间撞车的-" + SEQ.incrementAndGet();

        // ① 先排一条明天的。
        long scheduledBatch = newBatch();
        SeedBatchRow scheduled = scheduledRow(scheduledBatch, authorId, sameCopy,
                Instant.now().plus(1, ChronoUnit.DAYS));

        // ② 今天立刻发一条一模一样的（指纹因此已存在）。
        long todayBatch = newBatch();
        batchService.addDraft(todayBatch, 1, authorId, ContentType.DAILY, null, sameCopy, null, null);
        assertThat(publishing.confirm(todayBatch, admin, false).published()).isEqualTo(1);

        // ③ 明天到了。
        makeDue(scheduled.getId());
        scanner.publishDueRows();

        assertThat(rowRepo.findById(scheduled.getId()).orElseThrow().getStatus())
                .as("🔴 到点必发 —— 去重只在确认那一步查一次").isEqualTo(SeedBatchRowStatus.PUBLISHED);
        assertThat(postsOf(authorId)).hasSize(2);
    }

    // ——————————————————— AC1 计划时间不可早于当前 ———————————————————

    /**
     * 🔴 计划时间**不可早于当前时刻**（AC1）。
     *
     * <p>排一个已经过去的时间，下一轮扫描就会立刻发出去 —— 而运营的本意多半是
     * "改到某个更晚的时候"，手滑填成过去的日期就成了立即发布，且不可撤回。
     */
    @Test
    void aPastScheduledTimeIsRejectedAtTheInputBoundary() throws Exception {
        long batchId = newBatch();

        mvc.perform(post("/admin/seed-batches/" + batchId + "/settings")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("defaultContentType", ContentType.DAILY.name())
                        .param("defaultScheduledAt", "2020-01-01T08:00"))
                .andExpect(status().is3xxRedirection());

        // 没被保存进去才是对的（断言实体状态，不嗅页面文本）。
        assertThat(batchService.recentBatches().stream()
                .filter(b -> b.batchId() == batchId).findFirst()).isPresent();
        assertThat(jdbc.queryForObject(
                "select default_scheduled_at from seed_batches where id = ?",
                java.sql.Timestamp.class, batchId)).isNull();
    }

    /** 改排期时间同样拒绝过去的时刻。 */
    @Test
    void reschedulingToThePastIsRejected() throws Exception {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(batchId, authorId, "改时间-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));
        Instant before = row.getScheduledAt();

        mvc.perform(post("/admin/content-schedules/" + row.getId() + "/time")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("scheduledAt", "2020-01-01T08:00"))
                .andExpect(status().is3xxRedirection());

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getScheduledAt())
                .isEqualTo(before);
    }

    // ——————————————————— AC4 排期管理 ———————————————————

    /** 排期列表能打开、列出行，并**含失败行**（失败不自动消失）。 */
    @Test
    void theScheduleListShowsScheduledAndFailedRows() throws Exception {
        long batchId = newBatch();
        long authorId = virtualAccount();
        String pending = "在排队的-" + SEQ.incrementAndGet();
        scheduledRow(batchId, authorId, pending, Instant.now().plus(1, ChronoUnit.DAYS));
        SeedBatchRow willFail = batchService.addDraft(batchId, 2, authorId, ContentType.DAILY,
                null, "ayo main judi online", null, null);
        batchService.markValidated(willFail.getId());
        batchService.schedule(willFail.getId(), Instant.now().plus(1, ChronoUnit.DAYS), adminId());
        makeDue(willFail.getId());
        scanner.publishDueRows();

        String html = mvc.perform(get("/admin/content-schedules")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(pending);
        assertThat(html).as("🛡 失败行要留在列表里供运营处理").contains("FAILED");
    }

    /** 按发布账号过滤 —— 12-1 的移出提示会带 authorId 跳进来。 */
    @Test
    void theScheduleListCanBeFilteredByAuthor() throws Exception {
        long a = virtualAccount();
        long b = virtualAccount();
        String aBody = "A 的排期-" + SEQ.incrementAndGet();
        String bBody = "B 的排期-" + SEQ.incrementAndGet();
        scheduledRow(newBatch(), a, aBody, Instant.now().plus(1, ChronoUnit.DAYS));
        scheduledRow(newBatch(), b, bBody, Instant.now().plus(1, ChronoUnit.DAYS));

        String html = mvc.perform(get("/admin/content-schedules").param("authorId", String.valueOf(a))
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(aBody);
        assertThat(html).doesNotContain(bBody);
    }

    /** 取消排期 → 回退草稿，不发布。 */
    @Test
    void cancellingAScheduleReturnsTheRowToDraftWithoutPublishing() throws Exception {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(batchId, authorId, "要取消的-" + SEQ.incrementAndGet(),
                Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/admin/content-schedules/" + row.getId() + "/cancel")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        SeedBatchRow after = rowRepo.findById(row.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SeedBatchRowStatus.DRAFT);
        assertThat(after.getScheduledAt()).isNull();
        assertThat(postsOf(authorId)).isEmpty();
    }

    /**
     * 🛡 失败行**不能只改个时间就重排**。
     *
     * <p>失败多半有原因（账号被移出、审核拦下），直接改时间再排一次只会到点再失败一次 ——
     * 让运营回工作台修好再提交，比给他一个"看起来能用"的按钮好。
     */
    @Test
    void aFailedRowCannotBeRescheduledByJustChangingTheTime() throws Exception {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow row = scheduledRow(batchId, authorId, "ayo main judi online",
                Instant.now().plus(1, ChronoUnit.DAYS));
        makeDue(row.getId());
        scanner.publishDueRows();

        mvc.perform(post("/admin/content-schedules/" + row.getId() + "/time")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("scheduledAt", "2027-01-01T08:00"))
                .andExpect(status().is3xxRedirection());

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.FAILED);
    }

    // ——————————————————— AC3 WIB ———————————————————

    /** 🛡 界面须在时间旁明示「WIB」（四处口径一致：11-1/11-2/11-3 + 这里）。 */
    @Test
    void theSchedulePageStatesTheTimezone() throws Exception {
        String html = mvc.perform(get("/admin/content-schedules")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("WIB");
    }

    // ——————————————————— AC7 两类身份一视同仁 ———————————————————

    /** 运营真实账号（有宠物档案的 IP 号）同样能定时发。 */
    @Test
    void realOperationalAccountsCanAlsoBeScheduled() throws Exception {
        User real = newUser();
        mvc.perform(post("/admin/publish-identities").with(authentication(superAdmin())).with(csrf())
                        .param("userId", String.valueOf(real.getId()))
                        .param("authorizationNote", "IP 号定时发"))
                .andExpect(status().is3xxRedirection());
        pets.save(PetProfile.create(real.getId(), PetType.CAT, "小白" + (SEQ.incrementAndGet() % 10000),
                null, null, LocalDate.of(2025, 1, 1), null, "tok" + SEQ.incrementAndGet()));
        String marker = "IP 号定时发的-" + SEQ.incrementAndGet();
        SeedBatchRow row = scheduledRow(newBatch(), real.getId(), marker,
                Instant.now().plus(1, ChronoUnit.DAYS));
        makeDue(row.getId());

        scanner.publishDueRows();

        assertThat(rowRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.PUBLISHED);
        assertThat(postsOf(real.getId())).extracting(ContentPost::getText).contains(marker);
    }

    /** 状态机守卫：没到 VALIDATED 不能排期（13-1 的不变式在这里仍然成立）。 */
    @Test
    void aDraftRowCannotBeScheduledDirectly() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchRow r = batchService.addDraft(batchId, 1, authorId, ContentType.DAILY, null,
                "还没校验-" + SEQ.incrementAndGet(), null, null);

        assertThatThrownBy(() -> batchService.schedule(r.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), adminId()))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
    }
}
