package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.dto.BatchSummary;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.PendingPublishScheduleCounter;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ImageSize;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：草稿 / 排期的行级状态机与批次模型（V1.1.6 Story 13.1 · AB-3K/3L）。
 *
 * <p>🔴 <b>"存下来但还没发"是系统此前完全不具备的能力</b>：内容状态只有
 * {@code PUBLISHED / UNDER_REVIEW / AUTHOR_DEACTIVATED}，没有草稿。
 *
 * <h2>🛡 本类最重要的一条</h2>
 * {@link #draftsNeverLeakIntoAnyPublishedContentSurface()} —— 它钉的是
 * 「草稿存在独立表、不进 content_posts」这个决定（AC4）。
 * 如果哪天有人图省事把草稿写进 content_posts 靠状态列区分，
 * <b>所有既有的已发布口径都得记得排除草稿，漏一处就是草稿泄漏到线上</b>。
 */
class SeedBatchStateMachineIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SeedBatchService service;

    @Autowired
    private SeedBatchRowRepository rowRepo;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private PendingPublishScheduleCounter scheduleCounter;

    @Autowired
    private com.tailtopia.content.repository.ContentPostRepository posts;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "batch-" + n + "@tailtopia.test", "批次测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "batchview-" + n + "@tailtopia.test", "批次查看员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    private SeedBatchRow draftRow(long batchId, int rowNo, long authorId, String body) {
        return service.addDraft(batchId, rowNo, authorId, ContentType.DAILY, null, body,
                List.of("https://cdn.test/a.jpg"), List.of(new ImageSize(1000, 1000)));
    }

    // ——————————————————— 🛡 AC4 草稿不泄漏 ———————————————————

    /**
     * 🛡 <b>草稿行不出现在任何"已发布内容"口径里。</b>
     *
     * <p>这条不是走个形式：它证明的是「独立表」这个设计选择**真的把这件事变成不可能**，
     * 而不是靠每个查询各自记得过滤。三个口径各查一遍：
     * 公开 Feed、后台内容管理列表、作者的已发布内容计数。
     */
    @Test
    void draftsNeverLeakIntoAnyPublishedContentSurface() throws Exception {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.ONLINE_PASTE, admin);
        String marker = "只是草稿绝不该出现-" + SEQ.incrementAndGet();
        draftRow(batch.getId(), 1, author.getId(), marker);

        // 🔴 **正向对照**：同一个作者真发一条，用来证明下面三个口径**确实在返回内容**。
        //    没有这一步，三条 doesNotContain 在"接口恰好返回空"时也会绿 ——
        //    那就分不清"排除了草稿"和"这个查询根本没查到东西"。
        String publishedMarker = "这条是真发的-" + SEQ.incrementAndGet();
        posts.save(com.tailtopia.content.domain.ContentPost.publish(author.getId(),
                ContentType.DAILY, null, publishedMarker, List.of()));

        // ① 公开 Feed
        String feed = mvc.perform(get("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(feed).as("Feed 确实在返回这个作者的内容").contains(publishedMarker);
        assertThat(feed).doesNotContain(marker);

        // ② 后台内容管理列表
        String adminList = mvc.perform(get("/admin/content").with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminList).as("后台列表确实在返回内容").contains(publishedMarker);
        assertThat(adminList).doesNotContain(marker);

        // ③ 作者的已发布内容计数（迷你主页 / 统计口径）
        String me = mvc.perform(get("/api/v1/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(me).as("「我的发布」确实在返回内容").contains(publishedMarker);
        assertThat(me).doesNotContain(marker);
    }

    // ——————————————————— 🛡 AC2 状态在行上 ———————————————————

    /**
     * 🛡 <b>同一批次里各行状态可以完全不同</b> —— 这正是"批次没有状态"的理由。
     *
     * <p>「47 已发布 / 5 排期中 / 3 待修正」是**常态**。批次级状态实现下，
     * 这种组合要么被迫定义一堆合成态，要么就得强行把整批拉成同一个状态。
     */
    @Test
    void rowsInOneBatchCanHoldCompletelyDifferentStates() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.EXCEL, admin);

        SeedBatchRow stillDraft = draftRow(batch.getId(), 1, author.getId(), "还在改-" + SEQ.incrementAndGet());
        SeedBatchRow validated = draftRow(batch.getId(), 2, author.getId(), "过了校验-" + SEQ.incrementAndGet());
        SeedBatchRow scheduled = draftRow(batch.getId(), 3, author.getId(), "排上了-" + SEQ.incrementAndGet());
        SeedBatchRow published = draftRow(batch.getId(), 4, author.getId(), "发了-" + SEQ.incrementAndGet());
        SeedBatchRow failed = draftRow(batch.getId(), 5, author.getId(), "挂了-" + SEQ.incrementAndGet());

        service.markValidated(validated.getId());
        service.markValidated(scheduled.getId());
        service.schedule(scheduled.getId(), Instant.now().plus(2, ChronoUnit.DAYS), admin);
        service.markValidated(published.getId());
        service.markPublished(published.getId(), 424242L);
        service.markValidated(failed.getId());
        service.schedule(failed.getId(), Instant.now().plus(1, ChronoUnit.DAYS), admin);
        service.markFailed(failed.getId(), "发布账号已被移出身份池");

        assertThat(service.rowsOf(batch.getId()))
                .extracting(SeedBatchRow::getStatus)
                .containsExactly(SeedBatchRowStatus.DRAFT, SeedBatchRowStatus.VALIDATED,
                        SeedBatchRowStatus.SCHEDULED, SeedBatchRowStatus.PUBLISHED,
                        SeedBatchRowStatus.FAILED);
        // 行号顺序 = 运营那份表格的顺序，不是 id 顺序。
        assertThat(service.rowsOf(batch.getId())).extracting(SeedBatchRow::getRowNo)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(stillDraft.getStatus()).isEqualTo(SeedBatchRowStatus.DRAFT);
    }

    /** 批次列表按各行状态**聚合**（AC2）—— 而不是给出一个单一的"批次状态"。 */
    @Test
    void batchListAggregatesRowStatesInsteadOfHavingItsOwn() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.EXCEL, admin);
        SeedBatchRow a = draftRow(batch.getId(), 1, author.getId(), "a-" + SEQ.incrementAndGet());
        draftRow(batch.getId(), 2, author.getId(), "b-" + SEQ.incrementAndGet());
        service.markValidated(a.getId());
        service.markPublished(a.getId(), 999999L);

        BatchSummary summary = service.recentBatches().stream()
                .filter(s -> s.batchId() == batch.getId()).findFirst().orElseThrow();

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.counts()).containsEntry(SeedBatchRowStatus.PUBLISHED, 1)
                .containsEntry(SeedBatchRowStatus.DRAFT, 1);
        // ⚠️ 只包含出现过的状态：0 的那些不留位置（「2 条：1 已发布 / 1 草稿」比列全五种好读）。
        assertThat(summary.counts()).hasSize(2);
        assertThat(summary.allPublished()).isFalse();
        assertThat(summary.pendingCount()).isEqualTo(1);
    }

    // ——————————————————— AC1 流转 ———————————————————

    /** 取消排期 → 回退草稿，且**计划时间被清掉**（留着会显示"未排期，计划 X 日发布"）。 */
    @Test
    void cancellingAScheduleClearsThePlannedTimeToo() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.ONLINE_PASTE, admin);
        SeedBatchRow row = draftRow(batch.getId(), 1, author.getId(), "取消排期-" + SEQ.incrementAndGet());
        service.markValidated(row.getId());
        service.schedule(row.getId(), Instant.now().plus(3, ChronoUnit.DAYS), admin);

        service.cancelSchedule(row.getId(), admin);

        SeedBatchRow reloaded = rowRepo.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeedBatchRowStatus.DRAFT);
        assertThat(reloaded.getScheduledAt()).as("取消之后还留着计划时间会显示自相矛盾的东西").isNull();
    }

    /** 🛡 非法流转被拒，且是可解释的校验失败而不是 500。 */
    @Test
    void illegalTransitionsAreRejectedAsValidationFailures() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.ONLINE_PASTE, admin);
        SeedBatchRow row = draftRow(batch.getId(), 1, author.getId(), "非法流转-" + SEQ.incrementAndGet());

        // 草稿直接排期 = 绕过校验。
        assertThatThrownBy(() -> service.schedule(row.getId(), Instant.now().plusSeconds(3600), admin))
                .isInstanceOf(AppException.class);

        // 已发布是终态。
        service.markValidated(row.getId());
        service.markPublished(row.getId(), 777777L);
        assertThatThrownBy(() -> service.cancelSchedule(row.getId(), admin))
                .isInstanceOf(AppException.class);
    }

    /** 🛡 排期必须带计划时间，否则"到点"永远不会到。 */
    @Test
    void schedulingWithoutATimeIsRejected() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.ONLINE_PASTE, admin);
        SeedBatchRow row = draftRow(batch.getId(), 1, author.getId(), "无时间-" + SEQ.incrementAndGet());
        service.markValidated(row.getId());

        assertThatThrownBy(() -> service.schedule(row.getId(), null, admin))
                .isInstanceOf(AppException.class);
    }

    /** 校验失败留在草稿并记错误 —— 不为它单开一个和 DRAFT 行为一样的状态。 */
    @Test
    void validationFailureStaysInDraftAndKeepsTheReason() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.EXCEL, admin);
        SeedBatchRow row = draftRow(batch.getId(), 7, author.getId(), "校验失败-" + SEQ.incrementAndGet());

        service.markValidationFailed(row.getId(), "第 7 行：正文超过 1000 字");

        SeedBatchRow reloaded = rowRepo.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeedBatchRowStatus.DRAFT);
        assertThat(reloaded.getErrorMessage()).contains("第 7 行");
    }

    /** 修错重提清掉失败原因 —— 留着旧错误会让运营以为没修好。 */
    @Test
    void reopeningAFailedRowClearsTheOldReason() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.EXCEL, admin);
        SeedBatchRow row = draftRow(batch.getId(), 1, author.getId(), "重提-" + SEQ.incrementAndGet());
        service.markValidated(row.getId());
        service.schedule(row.getId(), Instant.now().plus(1, ChronoUnit.DAYS), admin);
        service.markFailed(row.getId(), "对象存储不可达");

        service.reopenForFix(row.getId(), admin);

        SeedBatchRow reloaded = rowRepo.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeedBatchRowStatus.DRAFT);
        assertThat(reloaded.getErrorMessage()).isNull();
    }

    // ——————————————————— 与 12-1 的接线 ———————————————————

    /**
     * 📌 <b>Story 12.1 那个「该账号还有 N 条待发布排期」的提示，现在接到真实数据了。</b>
     *
     * <p>12-1 落地时排期这个概念还不存在，实现恒返回 0 —— 那个 0 当时是正确答案。
     * 建表之后它就变成一个**等着变错的硬编码**：13-4/13-5 一开始产生排期行，
     * 运营移出账号时会看到"0 条排期"，然后那些内容在之后几天里陆续失败。
     *
     * <p>🔴 <b>只数 SCHEDULED</b>：草稿与待确认还没被安排出去、不会到点失败；
     * 混进来会把数字说大，而一个说大的数字会让运营对这个提示失去信任。
     */
    @Test
    void theScheduleCounterFromStoryTwelveOneNowSeesRealRows() {
        User author = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.EXCEL, admin);

        SeedBatchRow scheduledA = draftRow(batch.getId(), 1, author.getId(), "排期1-" + SEQ.incrementAndGet());
        SeedBatchRow scheduledB = draftRow(batch.getId(), 2, author.getId(), "排期2-" + SEQ.incrementAndGet());
        SeedBatchRow justDraft = draftRow(batch.getId(), 3, author.getId(), "草稿-" + SEQ.incrementAndGet());
        SeedBatchRow onlyValidated = draftRow(batch.getId(), 4, author.getId(), "待确认-" + SEQ.incrementAndGet());
        for (SeedBatchRow r : List.of(scheduledA, scheduledB)) {
            service.markValidated(r.getId());
            service.schedule(r.getId(), Instant.now().plus(1, ChronoUnit.DAYS), admin);
        }
        service.markValidated(onlyValidated.getId());

        assertThat(scheduleCounter.countPendingFor(author.getId()))
                .as("只数 SCHEDULED：草稿与待确认不算").isEqualTo(2);
        assertThat(justDraft.getStatus()).isEqualTo(SeedBatchRowStatus.DRAFT);

        // 取消一条排期 ⇒ 数字随之减少（提示要跟着现实走）。
        service.cancelSchedule(scheduledA.getId(), admin);
        assertThat(scheduleCounter.countPendingFor(author.getId())).isEqualTo(1);
    }

    /** 🔴 计数**按作者**，不按批次 —— 这正是 author_user_id 挂在行上的理由。 */
    @Test
    void theCounterIsPerAuthorNotPerBatch() {
        User a = newUser();
        User b = newUser();
        long admin = adminId();
        SeedBatch batch = service.openBatch(SeedBatch.Source.EXCEL, admin);
        SeedBatchRow ra = draftRow(batch.getId(), 1, a.getId(), "A 的-" + SEQ.incrementAndGet());
        SeedBatchRow rb = draftRow(batch.getId(), 2, b.getId(), "B 的-" + SEQ.incrementAndGet());
        for (SeedBatchRow r : List.of(ra, rb)) {
            service.markValidated(r.getId());
            service.schedule(r.getId(), Instant.now().plus(1, ChronoUnit.DAYS), admin);
        }

        assertThat(scheduleCounter.countPendingFor(a.getId())).isEqualTo(1);
        assertThat(scheduleCounter.countPendingFor(b.getId())).isEqualTo(1);
    }
}
