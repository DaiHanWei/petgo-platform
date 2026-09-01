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
import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.dto.RowValidation;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.SeedBatchPublishService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.seed.service.SeedContentFingerprint;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：校验预览、确认发布与去重口径修正（V1.1.6 Story 13.4 · AB-3K Step 3）。
 *
 * <h2>🔴 本 story 修掉的是"提交即上线"</h2>
 * 此前**没有校验预览、没有确认入库** —— 50 行错 3 行就是 3 条线上真帖，
 * 只能逐条去找、逐条下架。
 *
 * <h2>🛡 去重口径三处修正，各有一条用例</h2>
 * <ul>
 *   <li>{@link #theSameCopyCanBePublishedByADifferentAccount()} —— 加作者维度。
 *       原先按 hash 单列判，"同一文案换个账号再发一遍"（内容运营常规操作）会被静默吞掉。</li>
 *   <li>{@link #deletingTheContentFreesTheFingerprintSoItCanBeRepublished()} —— 删除时清理。
 *       原先没有任何清理逻辑 ⇒ <b>同样的文案永久无法重发</b>。</li>
 *   <li>{@link #duplicatesAreSurfacedAsAWarningInsteadOfBeingSilentlySkipped()} —— 命中改提示。</li>
 * </ul>
 */
class SeedBatchPublishIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SeedBatchService batchService;

    @Autowired
    private SeedBatchPublishService publishing;

    @Autowired
    private SeedBatchRowRepository rowRepo;

    @Autowired
    private SeedBatchAssetRepository assetRepo;

    @Autowired
    private SeedContentHashRepository hashRepo;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentService contentService;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "pub-" + n + "@tailtopia.test", "发布测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "pubview-" + n + "@tailtopia.test", "发布查看员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    /** ⚠️ 昵称 ≤20 字；SEQ 是 nanoTime 种子的大整数，直接拼会超长。 */
    private long virtualAccount() {
        return virtualAccounts.create("批量号" + (SEQ.incrementAndGet() % 100000), null, 1L);
    }

    private long newBatch() {
        return batchService.openBatch(SeedBatch.Source.EXCEL, adminId()).getId();
    }

    private SeedBatchRow row(long batchId, int no, long authorId, String body) {
        return batchService.addDraft(batchId, no, authorId, ContentType.DAILY, null, body,
                null, null);
    }

    private List<ContentPost> postsOf(long authorId) {
        return posts.findAll().stream()
                // ⚠️ Objects.equals：getAuthorId() 是装箱 Long，`==` 比引用、id 一大就恒 false。
                .filter(p -> Objects.equals(p.getAuthorId(), authorId)).toList();
    }

    // ——————————————————— 🔴 AC2 只发通过的行 ———————————————————

    /**
     * 🔴 <b>50 行错 3 行 → 47 行发出、3 行留草稿</b>（AC2）。
     *
     * <p>这条钉的正是本 story 的意义：此前提交即上线，那 3 行会变成 3 条线上真帖。
     * ⚠️ 用 10 行代替 50 行（同一逻辑，跑得快）。
     */
    @Test
    void passingRowsPublishWhileFailingRowsStayAsDrafts() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        long admin = adminId();
        for (int i = 1; i <= 7; i++) {
            row(batchId, i, authorId, "好的一条-" + i + "-" + SEQ.incrementAndGet());
        }
        // 三行故意坏：正文与图皆空 / 账号缺失 / 类型不支持。
        row(batchId, 8, authorId, null);
        row(batchId, 9, 0L, "没有账号-" + SEQ.incrementAndGet());
        SeedBatchRow badType = batchService.addDraft(batchId, 10, authorId,
                ContentType.GROWTH_MOMENT, null, "类型不对-" + SEQ.incrementAndGet(), null, null);

        var out = publishing.confirm(batchId, admin, false);

        assertThat(out.published()).isEqualTo(7);
        assertThat(out.skippedByError()).isEqualTo(3);
        assertThat(rowRepo.findByBatchIdOrderByRowNoAsc(batchId).stream()
                .filter(r -> r.getStatus() == SeedBatchRowStatus.DRAFT))
                .as("🛡 失败行留在草稿态，可改后重提").hasSize(3);
        assertThat(rowRepo.findById(badType.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.DRAFT);
    }

    /** 失败行改好之后**再提交一次就能发** —— 不必重建整批。 */
    @Test
    void aFixedRowCanBeSubmittedAgain() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        long admin = adminId();
        SeedBatchRow empty = row(batchId, 1, authorId, null);

        publishing.confirm(batchId, admin, false);
        assertThat(rowRepo.findById(empty.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.DRAFT);

        // 补上正文再提交。
        SeedBatchRow reloaded = rowRepo.findById(empty.getId()).orElseThrow();
        reloaded.edit(ContentType.DAILY, null, "补好了-" + SEQ.incrementAndGet(), null, null);
        rowRepo.save(reloaded);

        var out = publishing.confirm(batchId, admin, false);
        assertThat(out.published()).isEqualTo(1);
    }

    /** 有计划时间 ⇒ 转排期而不是立即发（AC2 后半条）。 */
    @Test
    void rowsWithAScheduledTimeGoToTheScheduleInsteadOfPublishingNow() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        long admin = adminId();
        SeedBatchRow r = row(batchId, 1, authorId, "排期的一条-" + SEQ.incrementAndGet());
        r.setScheduledAt(Instant.now().plus(2, ChronoUnit.DAYS));
        rowRepo.save(r);

        var out = publishing.confirm(batchId, admin, false);

        assertThat(out.scheduled()).isEqualTo(1);
        assertThat(out.published()).isZero();
        assertThat(rowRepo.findById(r.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.SCHEDULED);
    }

    /** 重复点确认不会重发 —— 运营可能把预览页刷两遍。 */
    @Test
    void confirmingTwiceDoesNotPublishTwice() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        long admin = adminId();
        row(batchId, 1, authorId, "只该发一次-" + SEQ.incrementAndGet());

        publishing.confirm(batchId, admin, false);
        var second = publishing.confirm(batchId, admin, false);

        assertThat(second.published()).isZero();
        // bug 20260901-473：跳过的行必须**计数**（结果提示据此说「N 条此前已发布/已排期」）——
        // 原来这个桶不出声，第二次确认的汇总看起来像「表格里的 Pass 行凭空消失」。
        assertThat(second.alreadyDone()).isEqualTo(1);
        assertThat(postsOf(authorId)).hasSize(1);
    }

    // ——————————————————— 🔴 AC6 内容类型放开 ———————————————————

    /**
     * 🔴 <b>类型取自该行，不再硬编码 DAILY</b>（AC6）。
     *
     * <p>老路径把它写死成 {@code DAILY} —— 那是 V1.1.0 AB-1.1-02 的
     * **实现偏差而非需求变更**，本 story 把它恢复。
     */
    @Test
    void theRowContentTypeIsHonouredNotHardcodedToDaily() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        long admin = adminId();
        String marker = "科普一条-" + SEQ.incrementAndGet();
        batchService.addDraft(batchId, 1, authorId, ContentType.KNOWLEDGE, null, marker, null, null);

        publishing.confirm(batchId, admin, false);

        assertThat(postsOf(authorId)).singleElement()
                .satisfies(p -> {
                    assertThat(p.getText()).isEqualTo(marker);
                    assertThat(p.getType()).isEqualTo(ContentType.KNOWLEDGE);
                });
    }

    // ——————————————————— 🔴 AC4 去重口径三处 ———————————————————

    /**
     * 🔴 <b>同一文案换个账号能发出去</b>（加作者维度）。
     *
     * <p>原先 {@code seed_content_hashes} 以 content_hash 单列为主键（表里有 author_id
     * 但不参与键），所以"同一文案想用两个不同账号各发一遍" —— <b>内容运营的常规操作，
     * 引入运营真实账号后会更频繁</b> —— 第二次会被静默吞掉。
     */
    @Test
    void theSameCopyCanBePublishedByADifferentAccount() {
        long admin = adminId();
        long a = virtualAccount();
        long b = virtualAccount();
        String sameCopy = "两个号都要发的文案-" + SEQ.incrementAndGet();

        long batchA = newBatch();
        row(batchA, 1, a, sameCopy);
        assertThat(publishing.confirm(batchA, admin, false).published()).isEqualTo(1);

        long batchB = newBatch();
        row(batchB, 1, b, sameCopy);
        var out = publishing.confirm(batchB, admin, false);

        assertThat(out.published()).as("🔴 换个账号必须能发").isEqualTo(1);
        assertThat(out.skippedByDuplicate()).isZero();
        assertThat(postsOf(b)).hasSize(1);
    }

    /** 同一账号发同一文案 ⇒ 命中去重（判据没有被放宽掉）。 */
    @Test
    void theSameAccountPublishingTheSameCopyIsStillCaught() {
        long admin = adminId();
        long a = virtualAccount();
        String sameCopy = "同号重复-" + SEQ.incrementAndGet();

        long batch1 = newBatch();
        row(batch1, 1, a, sameCopy);
        publishing.confirm(batch1, admin, false);

        long batch2 = newBatch();
        row(batch2, 1, a, sameCopy);
        var out = publishing.confirm(batch2, admin, false);

        assertThat(out.skippedByDuplicate()).isEqualTo(1);
        assertThat(out.published()).isZero();
    }

    /**
     * 🔴 <b>删掉内容之后同一文案可以重发</b>（清理指纹）。
     *
     * <p>原先没有任何清理逻辑：运营发了一条、发现有错、删掉重发 ——
     * 第二次会被去重吞掉，而且<b>看不出原因</b>（界面只显示"跳过 1 条"）。
     */
    @Test
    void deletingTheContentFreesTheFingerprintSoItCanBeRepublished() {
        long admin = adminId();
        long a = virtualAccount();
        String copy = "删掉重发-" + SEQ.incrementAndGet();

        long batch1 = newBatch();
        row(batch1, 1, a, copy);
        publishing.confirm(batch1, admin, false);
        long postId = postsOf(a).get(0).getId();
        assertThat(hashRepo.existsByContentHashAndAuthorId(
                SeedContentFingerprint.of(ContentType.DAILY, copy, null), a)).isTrue();

        // 删掉它（作者自删这条路径）。
        contentService.softDelete(postId, DeleteReason.AUTHOR_DELETE);

        assertThat(hashRepo.existsByContentHashAndAuthorId(
                SeedContentFingerprint.of(ContentType.DAILY, copy, null), a))
                .as("🔴 指纹必须随内容删除被清掉，否则同一文案永久无法重发").isFalse();

        long batch2 = newBatch();
        row(batch2, 1, a, copy);
        assertThat(publishing.confirm(batch2, admin, false).published())
                .as("删掉之后必须能重发").isEqualTo(1);
    }

    /**
     * 🛡 <b>运营下架这条路径同样要清</b>。
     *
     * <p>story 写明「要挂在**所有**删除路径上，漏一条就是那条路径删掉的内容对应的文案
     * 永久无法重发」。所以清理挂在"内容不再可展示"这个通用事件上，而不是逐个入口各加一行。
     */
    @Test
    void theFingerprintIsAlsoClearedOnAdminTakedown() {
        long admin = adminId();
        long a = virtualAccount();
        String copy = "被下架的-" + SEQ.incrementAndGet();
        long batchId = newBatch();
        row(batchId, 1, a, copy);
        publishing.confirm(batchId, admin, false);
        long postId = postsOf(a).get(0).getId();

        contentService.softDelete(postId, DeleteReason.ADMIN_TAKEDOWN);

        assertThat(hashRepo.existsByContentHashAndAuthorId(
                SeedContentFingerprint.of(ContentType.DAILY, copy, null), a)).isFalse();
    }

    /**
     * 🔴 <b>去重命中在预览里是提示，不是静默跳过</b>（AC4 第二处）。
     *
     * <p>原先界面只显示一个跳过条数，运营根本不知道**哪一条**被吞了。
     */
    @Test
    void duplicatesAreSurfacedAsAWarningInsteadOfBeingSilentlySkipped() {
        long admin = adminId();
        long a = virtualAccount();
        String copy = "会命中的-" + SEQ.incrementAndGet();
        long batch1 = newBatch();
        row(batch1, 1, a, copy);
        publishing.confirm(batch1, admin, false);

        long batch2 = newBatch();
        row(batch2, 1, a, copy);
        List<RowValidation> checks = publishing.preview(batch2);

        assertThat(checks).singleElement().satisfies(c -> {
            assertThat(c.passes()).as("重复不是错误 —— 它能发，只是要提醒").isTrue();
            assertThat(c.duplicate()).isTrue();
            assertThat(c.warns()).isTrue();
        });
    }

    /** 运营明确选择"重复的也发" ⇒ 照发（决定权在人）。 */
    @Test
    void operatorCanChooseToPublishDuplicatesAnyway() {
        long admin = adminId();
        long a = virtualAccount();
        String copy = "明知重复也要发-" + SEQ.incrementAndGet();
        long batch1 = newBatch();
        row(batch1, 1, a, copy);
        publishing.confirm(batch1, admin, false);

        long batch2 = newBatch();
        row(batch2, 1, a, copy);
        var out = publishing.confirm(batch2, admin, true);

        assertThat(out.published()).isEqualTo(1);
        assertThat(postsOf(a)).hasSize(2);
    }

    // ——————————————————— AC3 异常场景 ———————————————————

    /** 🔴 `GROWTH_MOMENT` 的错误要**指向单条发布**，只说"类型不合法"运营以为是填错字。 */
    @Test
    void growthMomentFailsWithAMessagePointingToSinglePublish() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        batchService.addDraft(batchId, 1, authorId, ContentType.GROWTH_MOMENT, null,
                "日历内容-" + SEQ.incrementAndGet(), null, null);

        List<RowValidation> checks = publishing.preview(batchId);

        assertThat(checks).singleElement().satisfies(c -> {
            assertThat(c.passes()).isFalse();
            assertThat(String.join(" ", c.errors())).contains("单条发布");
        });
    }

    /** 账号不在身份池内 ⇒ 该行失败并注明原因（其余行不受影响）。 */
    @Test
    void anAuthorOutsideThePoolFailsThatRowOnly() {
        long batchId = newBatch();
        long good = virtualAccount();
        User outsider = newUser();
        row(batchId, 1, good, "好的-" + SEQ.incrementAndGet());
        row(batchId, 2, outsider.getId(), "池外的-" + SEQ.incrementAndGet());

        List<RowValidation> checks = publishing.preview(batchId);

        assertThat(checks.get(0).passes()).isTrue();
        assertThat(checks.get(1).passes()).isFalse();
        assertThat(String.join(" ", checks.get(1).errors())).contains("身份池");
    }

    /**
     * ⚠️ 录入之后素材被清理掉（13-2 的废弃回收）⇒ 该行失败。
     *
     * <p>不再看一遍的话会发出一条**图片 404** 的内容。
     */
    @Test
    void aRowWhoseAssetWasReclaimedFails() {
        long batchId = newBatch();
        long authorId = virtualAccount();
        SeedBatchAsset a = assetRepo.save(SeedBatchAsset.of(batchId, "gone.png", "k/gone",
                "https://cdn.test/gone", 900, 900, 100));
        batchService.addDraft(batchId, 1, authorId, ContentType.DAILY, null, "带图的",
                List.of(a.getUrl()), null);
        // 素材被标记废弃（不再属于本批工作集）。
        a.markOrphaned();
        assetRepo.save(a);

        List<RowValidation> checks = publishing.preview(batchId);

        assertThat(checks).singleElement().satisfies(c ->
                assertThat(String.join(" ", c.errors())).contains("素材"));
    }

    /** 预览页能打开、且列出行（AC1）。 */
    @Test
    void thePreviewPageRenders() throws Exception {
        long batchId = newBatch();
        long authorId = virtualAccount();
        String marker = "预览里应出现-" + SEQ.incrementAndGet();
        row(batchId, 1, authorId, marker);

        String html = mvc.perform(get("/admin/seed-batches/" + batchId + "/preview")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(marker);
    }

    /** 确认发布走 HTTP 也通（钉住控制器接线）。 */
    @Test
    void confirmingThroughTheHttpEndpointWorks() throws Exception {
        long batchId = newBatch();
        long authorId = virtualAccount();
        row(batchId, 1, authorId, "走 HTTP 发的-" + SEQ.incrementAndGet());

        mvc.perform(post("/admin/seed-batches/" + batchId + "/confirm")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(postsOf(authorId)).hasSize(1);
    }
}
