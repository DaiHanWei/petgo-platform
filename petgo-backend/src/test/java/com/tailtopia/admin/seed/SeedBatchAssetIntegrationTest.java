package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.SeedBatchAssetService;
import com.tailtopia.admin.seed.service.SeedBatchDraftCleanupScanner;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;

/**
 * L1 集成：批次素材上传、配额与清理（V1.1.6 Story 13.2 · AB-3K Step 1）。
 *
 * <h2>🛡 本类最重要的一条</h2>
 * {@link #cleanupOnlyTouchesDraftRowsAndLeavesScheduledOnesAlone()} ——
 * <b>按批次清理是事故</b>：会把同批次里已排期的行一起删掉。
 * 状态是行级的（13-1 AC2），「47 已发布 / 5 排期中 / 3 还是草稿」是常态，
 * 那 3 条草稿过期绝不意味着另外 52 条也该消失。
 *
 * <p>⚠️ 清理把废弃素材**标记**为可回收而<b>不物理删除</b>（2026-08-24 用户拍板）：
 * 既有决策 F21 明令 OSS 对象任何情况不物理删除。所以断言看的是 {@code orphanedAt}，
 * 不是"对象没了"。
 */
@Import(SeedBatchAssetIntegrationTest.StubOss.class)
@TestPropertySource(properties = {
        // 清理扫描器默认 1 小时一跑；测试里直接调方法，只把保留期缩短以便造"过期"。
        "petgo.seed-batch.draft-keep-days=7"
})
class SeedBatchAssetIntegrationTest extends ApiIntegrationTest {

    /** 假对象存储：本地无凭证。🛡 只替掉"把字节送出去"，校验/量宽高/配额全走真实代码。 */
    @TestConfiguration
    static class StubOss {
        @Bean
        @Primary
        AliyunOssClient stubOssClient(com.tailtopia.shared.media.MediaProperties props) {
            return new AliyunOssClient(props) {
                @Override
                public String putPublicObject(String objectKey, byte[] bytes, String contentType) {
                    return "https://cdn.test/" + objectKey;
                }
            };
        }
    }

    @Autowired
    private SeedBatchService batchService;

    @Autowired
    private SeedBatchAssetService assetService;

    @Autowired
    private SeedBatchAssetRepository assetRepo;

    @Autowired
    private SeedBatchRowRepository rowRepo;

    @Autowired
    private SeedBatchRepository batchRepo;

    @Autowired
    private SeedBatchDraftCleanupScanner scanner;

    @Autowired
    private AdminAccountRepository adminAccounts;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "asset-" + n + "@tailtopia.test", "素材测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "assetview-" + n + "@tailtopia.test", "素材查看员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private long newBatch() {
        return batchService.openBatch(SeedBatch.Source.EXCEL, adminId()).getId();
    }

    private SeedBatchAsset upload(long batchId, String name, int w, int h) throws Exception {
        return assetService.upload(batchId,
                new MockMultipartFile("file", name, "image/png", png(w, h)));
    }

    // ——————————————————— AC1 上传与墙 ———————————————————

    @Test
    void uploadedAssetKeepsFileNameSizeAndDimensions() throws Exception {
        long batchId = newBatch();

        SeedBatchAsset a = upload(batchId, "cat-01.png", 1200, 900);

        assertThat(a.getFileName()).isEqualTo("cat-01.png");
        assertThat(a.getWidth()).isEqualTo(1200);
        assertThat(a.getHeight()).isEqualTo(900);
        assertThat(a.getSizeBytes()).isPositive();
        // 🔴 对象 key 必须留下 —— 回收要靠它，丢了泄漏就从"有账可查"退回"无人知道"。
        assertThat(a.getObjectKey()).isNotBlank();
        assertThat(a.isOrphaned()).isFalse();
    }

    /** 缩略图墙回显文件名 —— **运营认的是文件名，光看图分不出哪张是哪张**。 */
    @Test
    void theWallShowsFileNames() throws Exception {
        long batchId = newBatch();
        upload(batchId, "dog-77.png", 1000, 1000);

        String html = mvc.perform(get("/admin/seed-batches/" + batchId + "/assets")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("dog-77.png");
    }

    /** ⚠️ 浏览器/拖文件夹可能带路径 —— 带路径会让"同名"判不出来。 */
    @Test
    void pathPrefixesAreStrippedFromFileNames() throws Exception {
        long batchId = newBatch();

        assertThat(upload(batchId, "猫/1.png", 800, 800).getFileName()).isEqualTo("1.png");
    }

    // ——————————————————— 🛡 AC3 分次追加与查重 ———————————————————

    /** 🛡 追加**不替换**，墙上累积。 */
    @Test
    void addingMoreAppendsInsteadOfReplacing() throws Exception {
        long batchId = newBatch();
        upload(batchId, "a.png", 900, 900);
        upload(batchId, "b.png", 900, 900);

        assertThat(assetService.wall(batchId)).extracting(SeedBatchAsset::getFileName)
                .containsExactly("a.png", "b.png");
    }

    /**
     * 🛡 <b>与已在墙上的素材一并查重</b>，且拦在**上传阶段**。
     *
     * <p>运营常见做法就是"先拖猫的文件夹、再拖狗的"（A-9）—— 分次追加时最容易撞的就是这个。
     * 拖到校验阶段才报错时，他已经把整份表格填完了。
     */
    @Test
    void duplicateFileNameIsRejectedAtUploadTimeAcrossSeparateDrops() throws Exception {
        long batchId = newBatch();
        upload(batchId, "same.png", 900, 900);

        String body = mvc.perform(multipart("/admin/seed-batches/" + batchId + "/assets")
                        .file(new MockMultipartFile("file", "same.png", "image/png", png(900, 900)))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("error").asText()).contains("same.png");
        assertThat(assetService.wall(batchId)).hasSize(1);
    }

    /** 不同批次之间**不查重** —— 那是两份互不相干的工作集。 */
    @Test
    void theSameFileNameInADifferentBatchIsFine() throws Exception {
        long a = newBatch();
        long b = newBatch();
        upload(a, "shared.png", 900, 900);

        assertThat(upload(b, "shared.png", 900, 900).getFileName()).isEqualTo("shared.png");
    }

    // ——————————————————— 🛡 AC2 上限按累计算 ———————————————————

    /**
     * 🛡 <b>上限按累计值算</b>，否则**分三次拖就能绕过限制**。
     *
     * <p>⚠️ 这里不真传 200 张（那要几百次请求）——直接把配额算法的入口拉到边界：
     * 造到上限之后再传一张必须被拒。上限值本身由 {@link SeedBatchAssetService#MAX_ASSETS} 定义。
     */
    @Test
    void theCountLimitIsCumulativeAcrossSeparateDrops() throws Exception {
        long batchId = newBatch();
        // 直接用仓储灌到上限（绕过上传是刻意的：这条测的是**配额判定**，不是上传链路）。
        for (int i = 0; i < SeedBatchAssetService.MAX_ASSETS; i++) {
            assetRepo.save(SeedBatchAsset.of(batchId, "bulk-" + i + ".png",
                    "key/" + i, "https://cdn.test/" + i, 900, 900, 1024));
        }
        assertThat(assetService.usage(batchId).count()).isEqualTo(SeedBatchAssetService.MAX_ASSETS);
        assertThat(assetService.usage(batchId).full()).isTrue();

        String body = mvc.perform(multipart("/admin/seed-batches/" + batchId + "/assets")
                        .file(new MockMultipartFile("file", "one-more.png", "image/png", png(900, 900)))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("error").asText())
                .contains(String.valueOf(SeedBatchAssetService.MAX_ASSETS));
    }

    /** 总字节上限同样按累计算。 */
    @Test
    void theByteLimitIsCumulativeToo() throws Exception {
        long batchId = newBatch();
        assetRepo.save(SeedBatchAsset.of(batchId, "huge.png", "key/huge",
                "https://cdn.test/huge", 900, 900, SeedBatchAssetService.MAX_TOTAL_BYTES));

        String body = mvc.perform(multipart("/admin/seed-batches/" + batchId + "/assets")
                        .file(new MockMultipartFile("file", "tiny.png", "image/png", png(100, 100)))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("error").asText()).contains("500");
    }

    /**
     * 🔴 <b>校验顺序：先查配额与重名，再落存储。</b>
     *
     * <p>反过来的话，一张注定被拒的图已经写进对象存储了 —— 而 F21 之下那个对象**删不掉**，
     * 于是每次重名重试都在攒垃圾。这条用"被拒之后素材数没变"来钉住。
     */
    @Test
    void rejectedUploadsDoNotLeaveAnythingBehind() throws Exception {
        long batchId = newBatch();
        upload(batchId, "dup.png", 900, 900);
        long before = assetRepo.count();

        mvc.perform(multipart("/admin/seed-batches/" + batchId + "/assets")
                        .file(new MockMultipartFile("file", "dup.png", "image/png", png(900, 900)))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isBadRequest());

        assertThat(assetRepo.count()).isEqualTo(before);
    }

    // ——————————————————— 🔴🛡 AC4 清理对象是行，不是批次 ———————————————————

    /**
     * 🔴🛡 <b>这是本类最重要的一条。</b>
     *
     * <p><b>按批次清理是事故</b>：会把同批次里已排期的行一起删掉。
     * 状态是行级的（13-1 AC2）——「47 已发布 / 5 排期中 / 3 还是草稿」是常态，
     * 那 3 条草稿过期绝不意味着另外 52 条也该消失。
     *
     * <p>本例造一个混合批次：1 条过期草稿 + 1 条已排期 + 1 条新草稿，
     * 断言清理后<b>只少了那条过期草稿</b>。
     */
    @Test
    void cleanupOnlyTouchesDraftRowsAndLeavesScheduledOnesAlone() throws Exception {
        User author = newUser();
        long admin = adminId();
        long batchId = newBatch();
        SeedBatchAsset usedByScheduled = upload(batchId, "keep-me.png", 900, 900);
        SeedBatchAsset usedByFreshDraft = upload(batchId, "fresh.png", 900, 900);
        SeedBatchAsset abandoned = upload(batchId, "nobody-wants-me.png", 900, 900);

        SeedBatchRow expiredDraft = batchService.addDraft(batchId, 1, author.getId(),
                ContentType.DAILY, null, "过期草稿", List.of(abandoned.getUrl()), null);
        SeedBatchRow scheduled = batchService.addDraft(batchId, 2, author.getId(),
                ContentType.DAILY, null, "已排期", List.of(usedByScheduled.getUrl()), null);
        SeedBatchRow freshDraft = batchService.addDraft(batchId, 3, author.getId(),
                ContentType.DAILY, null, "刚建的草稿", List.of(usedByFreshDraft.getUrl()), null);
        batchService.markValidated(scheduled.getId());
        batchService.schedule(scheduled.getId(), Instant.now().plus(3, ChronoUnit.DAYS), admin);

        // 把那条草稿的 updated_at 推到 8 天前（保留期 7 天）。
        ageRow(expiredDraft.getId(), 8);

        scanner.cleanupExpiredDrafts();

        List<SeedBatchRow> left = rowRepo.findByBatchIdOrderByRowNoAsc(batchId);
        assertThat(left).extracting(SeedBatchRow::getRowNo)
                .as("🛡 只该少掉那条过期草稿；已排期与新草稿必须原样在")
                .containsExactly(2, 3);
        assertThat(rowRepo.findById(scheduled.getId()).orElseThrow().getStatus())
                .isEqualTo(SeedBatchRowStatus.SCHEDULED);
        assertThat(rowRepo.findById(freshDraft.getId())).isPresent();
        // 批次记录也必须在 —— 它还有行。
        assertThat(batchRepo.findById(batchId)).isPresent();
    }

    /**
     * 🛡 <b>仍被引用的素材不动</b>；只有谁都不用的才被标记可回收。
     *
     * <p>⚠️ 判据是"任何**存留行**引用"，比 AC 字面的"非草稿行"更严 ——
     * 一条**刚建的草稿**同样在用那些图，照字面做会把图从它底下抽走，
     * 运营回来看到一墙裂图。
     */
    @Test
    void onlyUnreferencedAssetsAreMarkedReclaimable() throws Exception {
        User author = newUser();
        long admin = adminId();
        long batchId = newBatch();
        SeedBatchAsset keptByScheduled = upload(batchId, "sched.png", 900, 900);
        SeedBatchAsset keptByFreshDraft = upload(batchId, "draft.png", 900, 900);
        SeedBatchAsset orphan = upload(batchId, "orphan.png", 900, 900);

        SeedBatchRow expired = batchService.addDraft(batchId, 1, author.getId(),
                ContentType.DAILY, null, "过期", List.of(orphan.getUrl()), null);
        SeedBatchRow sched = batchService.addDraft(batchId, 2, author.getId(),
                ContentType.DAILY, null, "排期", List.of(keptByScheduled.getUrl()), null);
        batchService.addDraft(batchId, 3, author.getId(),
                ContentType.DAILY, null, "新草稿", List.of(keptByFreshDraft.getUrl()), null);
        batchService.markValidated(sched.getId());
        batchService.schedule(sched.getId(), Instant.now().plus(1, ChronoUnit.DAYS), admin);
        ageRow(expired.getId(), 8);

        scanner.cleanupExpiredDrafts();

        assertThat(assetRepo.findById(orphan.getId()).orElseThrow().isOrphaned())
                .as("谁都不用的才标记").isTrue();
        assertThat(assetRepo.findById(keptByScheduled.getId()).orElseThrow().isOrphaned())
                .as("🛡 被已排期行引用的不许动").isFalse();
        assertThat(assetRepo.findById(keptByFreshDraft.getId()).orElseThrow().isOrphaned())
                .as("🛡 被**新草稿**引用的也不许动 —— 否则运营回来看到裂图").isFalse();
    }

    /**
     * ⚠️ <b>标记不等于删除</b>（F21 未反转，2026-08-24 用户拍板）。
     *
     * <p>行仍在、对象 key 与字节数仍在 —— 这就是那份"有账可查"的台账本身。
     */
    @Test
    void reclaimableAssetsAreLedgeredNotDeleted() throws Exception {
        User author = newUser();
        long batchId = newBatch();
        SeedBatchAsset orphan = upload(batchId, "ledger.png", 900, 900);
        SeedBatchRow expired = batchService.addDraft(batchId, 1, author.getId(),
                ContentType.DAILY, null, "过期", List.of(orphan.getUrl()), null);
        ageRow(expired.getId(), 8);

        scanner.cleanupExpiredDrafts();

        SeedBatchAsset after = assetRepo.findById(orphan.getId()).orElseThrow();
        assertThat(after.isOrphaned()).isTrue();
        assertThat(after.getObjectKey()).as("回收要靠它，标记时绝不能丢").isNotBlank();
        assertThat(after.getSizeBytes()).isPositive();
        assertThat(assetService.orphanedBytes()).isPositive();
    }

    /**
     * 🔴 <b>最典型的泄漏：拖完图就关页面，一行都没录。</b>
     *
     * <p>这种批次没有任何过期草稿行，所以"按过期草稿行找批次"那一轮碰不到它 ——
     * 必须另按**批次自己的创建时间**兜一遍。漏了这条，最常见的那种废弃就永远清不到。
     */
    @Test
    void aBatchAbandonedBeforeAnyRowWasEnteredIsStillReclaimed() throws Exception {
        long batchId = newBatch();
        SeedBatchAsset a = upload(batchId, "dragged-then-left.png", 900, 900);
        ageBatch(batchId, 8);

        scanner.cleanupExpiredDrafts();

        assertThat(assetRepo.findById(a.getId()).orElseThrow().isOrphaned()).isTrue();
    }

    /** 没过期的批次不动 —— 7 天内的工作集是活的。 */
    @Test
    void freshBatchesAreLeftAlone() throws Exception {
        long batchId = newBatch();
        SeedBatchAsset a = upload(batchId, "still-working.png", 900, 900);

        scanner.cleanupExpiredDrafts();

        assertThat(assetRepo.findById(a.getId()).orElseThrow().isOrphaned()).isFalse();
        assertThat(batchRepo.findById(batchId)).isPresent();
    }

    /** 已废弃的素材**不占配额** —— 否则运营在一个放弃过一次的批次里会凭空少掉额度。 */
    @Test
    void reclaimableAssetsDoNotCountTowardsTheQuota() throws Exception {
        long batchId = newBatch();
        SeedBatchAsset a = upload(batchId, "gone.png", 900, 900);
        ageBatch(batchId, 8);
        scanner.cleanupExpiredDrafts();

        assertThat(assetService.usage(batchId).count()).isZero();
        assertThat(assetService.wall(batchId)).isEmpty();
    }

    // ——————————————————— 造"过期"的两个小工具 ———————————————————

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 把某行的 {@code updated_at} 推到 n 天前。
     *
     * <p>⚠️ 走 SQL 而不是实体 setter：{@code updated_at} 由领域层在每次改动时自己刷新，
     * 从外面用 setter 改会被下一次 save 覆盖掉 —— 那样测试会安静地测不到"过期"这件事。
     */
    private void ageRow(long rowId, int days) {
        jdbc.update("update seed_batch_rows set updated_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), rowId);
    }

    private void ageBatch(long batchId, int days) {
        jdbc.update("update seed_batches set created_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), batchId);
    }
}
