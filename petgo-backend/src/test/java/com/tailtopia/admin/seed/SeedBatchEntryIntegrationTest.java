package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchAsset;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.admin.seed.service.SeedBatchExcelService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.dto.PublishIdentityOption;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.support.ApiIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：批量内容录入（V1.1.6 Story 13.3 · AB-3K Step 0/2）。
 *
 * <p><b>此前是两条并列路径</b>：「多行纯文本 {@code 文本 ||| 图URL1, 图URL2}」与「Excel 导入」——
 * 共用同一个后端，且**各自带一个一模一样的账号下拉，所以下拉在同一页面出现两次**。
 *
 * <h2>🛡 三条最容易在赶工时被删掉的东西，各有一条用例守着</h2>
 * <ul>
 *   <li>粘贴分行限制的**界面提示**（AC3）—— 没有它，运营粘一篇长科普会被拆成残句
 *       <b>而且不会有任何报错</b>。</li>
 *   <li>「先配账号物种定位」的**界面提示**（AC6）—— 工程与运营都不会自行想到这个先后关系。</li>
 *   <li>Excel 模板的内容类型下拉**不含 {@code GROWTH_MOMENT}**（AC4）——
 *       能选到就是整行必然失败。</li>
 * </ul>
 */
class SeedBatchEntryIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SeedBatchService batchService;

    @Autowired
    private SeedBatchExcelService excel;

    @Autowired
    private com.tailtopia.admin.seed.service.SeedBatchEntryService entry;

    @Autowired
    private SeedBatchAssetRepository assetRepo;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "entry-" + n + "@tailtopia.test", "录入测试员", "{bcrypt}x")).getId();
    }

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "entryview-" + n + "@tailtopia.test", "录入查看员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    /**
     * ⚠️ 虚拟账号昵称 **≤20 字**。{@code SEQ} 是 nanoTime 种子的大整数，
     * 直接拼进昵称会超长 —— 表现是 "昵称必填且不超过 20 字"，
     * 而那看起来像是被测功能坏了。取后 5 位即可。
     */
    private static String shortName(String prefix) {
        return prefix + (SEQ.incrementAndGet() % 100000);
    }

    private long newBatch() {
        return batchService.openBatch(SeedBatch.Source.ONLINE_PASTE, adminId()).getId();
    }

    private String workspace(long batchId) throws Exception {
        return mvc.perform(get("/admin/seed-batches/" + batchId)
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ——————————————————— 🛡 AC1 下拉只有一处 ———————————————————

    /**
     * 🔴 <b>账号下拉全页只出现一次</b>（AC1）。
     *
     * <p>此前在线录入与 Excel 导入各带一个一模一样的下拉。判据用 `name="defaultAuthorUserId"`
     * 的出现次数 —— 行卡片里那个是 `authorUserId`（**行级覆盖**，语义不同，不算重复）。
     */
    @Test
    void theBatchLevelAccountSelectAppearsExactlyOnce() throws Exception {
        long batchId = newBatch();

        String html = workspace(batchId);

        assertThat(html.split("name=\"defaultAuthorUserId\"", -1).length - 1)
                .as("批次级账号下拉必须只有一处").isEqualTo(1);
    }

    // ——————————————————— 🛡 AC3/AC6 两条必须明示的提示 ———————————————————

    /**
     * 🔴 <b>粘贴分行限制必须在界面明示</b>（AC3）。
     *
     * <p>没有它：运营粘一篇带段落的长科普 → 拆成一堆残句 → <b>不会有任何报错</b>
     * （每一段都是合法正文）→ 只能手动合并回去，比原来的竖线格式更糟。
     */
    @Test
    void thePasteSplittingLimitIsStatedOnThePage() throws Exception {
        String html = workspace(newBatch());

        // 🔴 断言的是**那个提示元素本身**（稳定标记），不是"页面上有没有这几个字"。
        //    先按 contains("一行一条") 写过一版，反证时**删掉整条提示它照样绿** ——
        //    因为同样的字还出现在 HTML 注释与输入框占位文案里。
        //    这条 AC 守的恰恰是"赶工时最容易被删掉的东西"，空转等于没守。
        assertThat(html).as("这条提示不可省略").contains("data-notice=\"paste-split-limit\"");
        // 文案本身也钉一下：得说清"一行一条"与"长正文走 Excel"两件事。
        assertThat(html).contains("一行一条").contains("Excel");
    }

    /**
     * 🔴 <b>「先配账号物种定位」必须在界面明示</b>（AC6）。
     *
     * <p>未配置时该批内容的物种会**全部落到 GENERAL**，只能逐行补救。
     * <b>工程与运营都不会自行想到这个先后关系。</b>
     */
    @Test
    void theConfigureSpeciesFirstWarningIsStatedOnThePage() throws Exception {
        String html = workspace(newBatch());

        // 同上：断言元素，不断言字符串 —— "GENERAL" 还出现在物种下拉的选项里，
        // "物种" 出现在行卡片的字段标签上，光看这两个词删没删都是绿的。
        assertThat(html).as("这条提示不可省略").contains("data-notice=\"species-first\"");
        assertThat(html).contains("GENERAL");
    }

    // ——————————————————— AC2/AC3 粘贴分行 ———————————————————

    /** 粘 20 行 → 20 行。 */
    @Test
    void pastingTwentyLinesCreatesTwentyRows() throws Exception {
        long batchId = newBatch();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("第 ").append(i).append(" 条短文案-").append(SEQ.incrementAndGet()).append('\n');
        }

        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/paste")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("lines", sb.toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(batchService.rowsOf(batchId)).hasSize(20);
    }

    /** 🛡 空行被跳过、**不生成空内容行** —— 生成一堆空行的话运营还得逐个删。 */
    @Test
    void blankLinesAreSkippedInsteadOfBecomingEmptyRows() throws Exception {
        long batchId = newBatch();

        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/paste")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("lines", "第一条\n\n\n第二条\n   \n第三条\n"))
                .andExpect(status().is3xxRedirection());

        assertThat(batchService.rowsOf(batchId)).hasSize(3);
        assertThat(batchService.rowsOf(batchId)).extracting(SeedBatchRow::getBody)
                .containsExactly("第一条", "第二条", "第三条");
    }

    /** 🛡 分次粘贴时行号**接着往后排**，不从 1 重来 —— 重号会让"第 7 行错了"指向两处。 */
    @Test
    void rowNumbersContinueAcrossSeparatePastes() throws Exception {
        long batchId = newBatch();
        for (String batch : List.of("a1\na2", "b1\nb2")) {
            mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/paste")
                            .with(authentication(superAdmin())).with(csrf())
                            .param("lines", batch))
                    .andExpect(status().is3xxRedirection());
        }

        assertThat(batchService.rowsOf(batchId)).extracting(SeedBatchRow::getRowNo)
                .containsExactly(1, 2, 3, 4);
    }

    // ——————————————————— 🔴 AC5 继承规则 ———————————————————

    /**
     * 🔴 <b>发布账号留空 → 继承批次默认，不再报错。</b>
     *
     * <p>这**覆盖**了 V1.1.0 原「留空 = 校验失败、视为必填缺失」的规则：
     * 逐行必填意味着 50 行填 50 次、其中大多数是同一个值，纯重复劳动，
     * 且手打账号名比选下拉更易错（§7.5 第 2 条）。
     */
    @Test
    void anEmptyAccountInheritsTheBatchDefaultInsteadOfFailing() throws Exception {
        long batchId = newBatch();
        long virtualId = virtualAccounts.create(shortName("默认号"), null, 1L);
        mvc.perform(post("/admin/seed-batches/" + batchId + "/settings")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("defaultAuthorUserId", String.valueOf(virtualId))
                        .param("defaultContentType", ContentType.DAILY.name()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/paste")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("lines", "继承默认账号-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        SeedBatchRow row = batchService.rowsOf(batchId).get(0);
        assertThat(row.getAuthorUserId()).isEqualTo(virtualId);
        assertThat(row.getContentType()).isEqualTo(ContentType.DAILY);
        assertThat(row.getErrorMessage()).as("留空继承是正常用法，不该记成错误").isNull();
    }

    /**
     * 🛡 批次也没设默认 ⇒ **不阻止入库**，而是记成该行的校验错误。
     *
     * <p>在录入阶段抛错会把整批粘贴一起挡掉，而运营的本意只是"先把文案贴进来"。
     * 那份错误由 13-4 的校验预览逐行展示、并拦住它不让发。
     */
    @Test
    void missingAccountBecomesARowErrorRatherThanBlockingTheWholePaste() throws Exception {
        long batchId = newBatch();

        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/paste")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("lines", "没有账号的一条-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        SeedBatchRow row = batchService.rowsOf(batchId).get(0);
        assertThat(row.getErrorMessage()).contains("发布账号");
    }

    /**
     * 🔴 <b>「关联物种」刻意没有批次默认</b>（A-14）。
     *
     * <p>⚠️ <b>本用例的期望已随 V1.1.6 Story 14.1 变过一次</b>：
     * 13-3 落地时「账号物种定位」这个字段还不存在，继承读到的是空，所以当时断言 null；
     * 13-3 的注释里就预告了这件事（「本用例同时是 14-1 的接线提醒」）。
     * 14-1 建好字段并换掉那个恒空实现之后，虚拟账号的默认定位 {@code GENERAL} 会被继承下来。
     *
     * <p>🔴 <b>但本用例真正守的那一条没变</b>：页头设置里**没有**物种这一项 ——
     * 它的默认来自**账号属性**，不是批次（A-14：再加一层批次默认会与账号定位冲突，
     * 批次默认设「猫」、行留空、而该行账号是狗号时，取谁没有正确答案）。
     */
    @Test
    void speciesHasNoBatchLevelDefaultAndComesFromTheAccountInstead() throws Exception {
        long batchId = newBatch();
        long virtualId = virtualAccounts.create(shortName("待配位"), null, 1L);
        mvc.perform(post("/admin/seed-batches/" + batchId + "/settings")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("defaultAuthorUserId", String.valueOf(virtualId))
                        .param("defaultContentType", ContentType.DAILY.name()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/seed-batches/" + batchId + "/rows/paste")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("lines", "物种留空-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        // 继承自该行发布账号的「账号物种定位」（虚拟账号未配 ⇒ 读时默认 GENERAL）。
        assertThat(batchService.rowsOf(batchId).get(0).getSpecies())
                .isEqualTo(com.tailtopia.content.species.ContentSpecies.GENERAL);
        // 🛡 页头设置里**没有**物种这一项 —— 它的默认来自账号属性，不是批次。
        assertThat(workspace(batchId)).doesNotContain("name=\"defaultSpecies\"");
    }

    // ——————————————————— 🔴 AC1/AC4 不支持成长日历 ———————————————————

    /** 🔴 服务端自己拦 `GROWTH_MOMENT`：下拉里没有它 ≠ 请求里发不上来。 */
    @Test
    void growthMomentIsRejectedAsABatchDefault() throws Exception {
        long batchId = newBatch();

        mvc.perform(post("/admin/seed-batches/" + batchId + "/settings")
                        .with(authentication(superAdmin())).with(csrf())
                        .param("defaultContentType", ContentType.GROWTH_MOMENT.name()))
                .andExpect(status().is3xxRedirection());

        // 🔴 断言的是**实体状态**，不是页面文本。
        //    先按 `doesNotContain("GROWTH_MOMENT")` 断言页面，红了一次 ——
        //    撞上的是模板里我自己那句"🔴 不含 GROWTH_MOMENT（A-10）"的 HTML 注释。
        //    那是**断言选错了对象**：要验的是"没存进去"，而不是"页面上没出现这个词"。
        assertThat(entry.findBatch(batchId).orElseThrow().getDefaultContentType())
                .as("不支持的类型不该被存成批次默认").isNull();
        // 顺带钉住下拉里确实没有它（按 option 的 value 判，不受注释影响）。
        assertThat(workspace(batchId)).doesNotContain("value=\"GROWTH_MOMENT\"");
    }

    // ——————————————————— 🔴 AC4 Excel 模板 ———————————————————

    /**
     * 🔴 <b>模板的内容类型下拉只含 DAILY / KNOWLEDGE</b>。
     *
     * <p>若能选到 {@code GROWTH_MOMENT}，运营选了就是**整行必然失败**（A-10），
     * 属可以从源头避免的错误。判据是读「选项」表 A 列的真实取值。
     */
    @Test
    void theExcelTemplateTypeDropdownExcludesGrowthMoment() throws Exception {
        byte[] bytes = excel.template(List.of(
                new PublishIdentityOption(7L, "虚拟号", false, false)));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet opts = wb.getSheet("选项");
            List<String> types = new java.util.ArrayList<>();
            for (var row : opts) {
                if (row.getCell(0) != null && !row.getCell(0).getStringCellValue().isBlank()) {
                    types.add(row.getCell(0).getStringCellValue());
                }
            }
            assertThat(types).containsExactly("DAILY", "KNOWLEDGE");
            assertThat(types).doesNotContain("GROWTH_MOMENT");
        }
    }

    /** 模板六列，且真的挂了数据校验（下拉）——「比事后校验拦截体验好一个量级」。 */
    @Test
    void theTemplateHasSixColumnsAndRealDropdownValidation() throws Exception {
        byte[] bytes = excel.template(List.of(
                new PublishIdentityOption(7L, "虚拟号", false, false),
                new PublishIdentityOption(8L, "IP 号", true, false)));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("批量内容");
            assertThat((int) sheet.getRow(0).getLastCellNum()).isEqualTo(6);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).contains("图片");
            // 三列下拉：内容类型 / 关联物种 / 发布账号。
            assertThat(sheet.getDataValidations()).hasSize(3);
            // 列头下面那行说明 —— 运营打开模板第一眼看到的就是它。
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).contains("文件名");
        }
    }

    /** 真实账号在模板里带 ⚠️ 标记，与选择器同源（误发不可撤回）。 */
    @Test
    void realAccountsAreFlaggedInTheTemplateOptions() {
        assertThat(SeedBatchExcelService.identityLabel(
                new PublishIdentityOption(8L, "IP 号", true, false)))
                .startsWith("⚠️").contains("id=8");
    }

    // ——————————————————— 🔴 AC4 Excel 导入 ———————————————————

    /**
     * 图片列填的是**素材文件名**、不再填 URL；顺序即展示顺序。
     */
    @Test
    void importResolvesAssetsByFileNameInTheGivenOrder() throws Exception {
        long batchId = newBatch();
        long virtualId = virtualAccounts.create(shortName("导入1"), null, 1L);
        SeedBatchAsset a = assetRepo.save(SeedBatchAsset.of(batchId, "b.png", "k/b",
                "https://cdn.test/b", 900, 900, 100));
        SeedBatchAsset b = assetRepo.save(SeedBatchAsset.of(batchId, "a.png", "k/a",
                "https://cdn.test/a", 900, 900, 100));

        byte[] xlsx = sheetWith(new String[][] {
                {"导入的一条", "a.png, b.png", "x (id=" + virtualId + ")", "DAILY", "", ""}});
        mvc.perform(multipart("/admin/seed-batches/" + batchId + "/import")
                        .file(new MockMultipartFile("file", "in.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsx))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        SeedBatchRow row = batchService.rowsOf(batchId).get(0);
        assertThat(row.getImageUrls()).as("顺序即展示顺序")
                .containsExactly(b.getUrl(), a.getUrl());
        assertThat(row.getAuthorUserId()).isEqualTo(virtualId);
    }

    /**
     * ⚠️ 素材名对不上 ⇒ 记成**该行**的错误、行仍然入库。
     *
     * <p>丢弃的话运营对不上"我明明有 50 行，怎么只进来 47 行"。
     * 报的是**文件名**而不是内部 id —— 运营认的是文件名。
     */
    @Test
    void anUnknownAssetNameBecomesARowErrorAndTheRowStillExists() throws Exception {
        long batchId = newBatch();
        long virtualId = virtualAccounts.create(shortName("导入2"), null, 1L);

        byte[] xlsx = sheetWith(new String[][] {
                {"素材名写错了", "nope.png", "x (id=" + virtualId + ")", "DAILY", "", ""}});
        mvc.perform(multipart("/admin/seed-batches/" + batchId + "/import")
                        .file(new MockMultipartFile("file", "in.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsx))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        SeedBatchRow row = batchService.rowsOf(batchId).get(0);
        assertThat(row.getErrorMessage()).contains("nope.png");
    }

    /** 🔴 手打进 `GROWTH_MOMENT` 也要被拒 —— 静默继承默认更糟（发出去的类型不是他写的）。 */
    @Test
    void growthMomentTypedIntoTheSpreadsheetIsRejected() throws Exception {
        long batchId = newBatch();

        byte[] xlsx = sheetWith(new String[][] {
                {"手打了不支持的类型", "", "", "GROWTH_MOMENT", "", ""}});
        mvc.perform(multipart("/admin/seed-batches/" + batchId + "/import")
                        .file(new MockMultipartFile("file", "in.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsx))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(batchService.rowsOf(batchId)).as("整批被拒，一行都不该进").isEmpty();
    }

    /** ⚠️ 运营常直接在模板上填、把说明行留着 —— 不跳过就会多出一条正文是说明文字的内容。 */
    @Test
    void theTemplateHintRowIsSkippedOnImport() throws Exception {
        long batchId = newBatch();
        long virtualId = virtualAccounts.create(shortName("导入3"), null, 1L);

        byte[] xlsx = sheetWith(new String[][] {
                {"一条一行。长正文可含换行，写在同一单元格里即可",
                        "填本批已上传素材的文件名，多张用英文逗号分隔，顺序即展示顺序",
                        "留空则继承页头设置的默认发布账号", "", "", ""},
                {"真正的内容", "", "x (id=" + virtualId + ")", "DAILY", "", ""}});
        mvc.perform(multipart("/admin/seed-batches/" + batchId + "/import")
                        .file(new MockMultipartFile("file", "in.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsx))
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(batchService.rowsOf(batchId)).extracting(SeedBatchRow::getBody)
                .containsExactly("真正的内容");
    }

    /** 🛡 只认 id，不按昵称反查 —— 昵称可能重复也可能被改，猜一个账号出来会发错人。 */
    @Test
    void accountIsParsedByIdOnly() {
        assertThat(SeedBatchExcelService.parseAccount("⚠️ 某个号 (id=4242)")).isEqualTo(4242L);
        assertThat(SeedBatchExcelService.parseAccount("4242")).isEqualTo(4242L);
        assertThat(SeedBatchExcelService.parseAccount("某个号")).isNull();
        assertThat(SeedBatchExcelService.parseAccount("")).isNull();
    }

    /** WIB 墙上时间 → UTC（面向印尼市场，运营心里那个"早 8 点"是 WIB）。 */
    @Test
    void scheduledTimeIsParsedAsJakartaWallClock() {
        var utc = SeedBatchExcelService.parseTime("2026-09-01 08:30");

        assertThat(utc).isEqualTo(java.time.ZonedDateTime
                .of(2026, 9, 1, 8, 30, 0, 0, java.time.ZoneId.of("Asia/Jakarta")).toInstant());
        // 解析不了就当没填 —— 一个格式错的时间不该把整行挡住。
        assertThat(SeedBatchExcelService.parseTime("下周一")).isNull();
    }

    /** 造一个只有数据行的 xlsx（第 0 行当列头被跳过）。 */
    private static byte[] sheetWith(String[][] dataRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("批量内容");
            var header = sheet.createRow(0);
            String[] headers = {"正文", "图片文件名", "发布账号", "内容类型", "关联物种", "计划发布时间"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < dataRows.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < dataRows[r].length; c++) {
                    row.createCell(c).setCellValue(dataRows[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
