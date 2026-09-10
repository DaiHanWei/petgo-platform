package com.tailtopia.admin.payment;

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
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.repository.VetSettlementRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.repository.TriageTaskRepository;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1 集成：B12 支付记录 + B13 兽医月结 + B14 红色超额套模板 B（V1.3.0 Story 8.5）。
 *
 * <p>本 story 是**零写端点变更**的页面重构：三页原有的写端点路径 / 参数 / 权限一个字没动，
 * 新增的只有三个 drawer GET。所以钉的是「行内表单搬进抽屉之后每一样都还在、权限门与端点对齐、
 * 摘要条口径正确」。
 *
 * <p>⚠️ 模拟回调（AC2 / D-41）是 {@code @StagOnly} 的，在本类（默认 profile）下**路由不存在** ——
 * 那一侧由 {@code AdminPaymentSimulateStagTest} 在 stag profile 里验。
 */
class AdminMoneyPagesDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private VetSettlementRepository settlements;
    @Autowired
    private TriageTaskRepository triage;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private VetAccountRepository vets;
    @Autowired
    private com.tailtopia.admin.risk.repository.RedOverageReviewRepository redReviews;
    @Autowired
    private com.tailtopia.consult.repository.ConsultOrderRepository consultOrders;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "money-" + n + "@tailtopia.test", "钱页测试员", "{bcrypt}x"));
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

    private PaymentIntent seedIntent() {
        long n = SEQ.incrementAndGet();
        return intents.save(PaymentIntent.create(700L + n, PaymentPurpose.VET_CONSULT,
                PayChannel.QRIS, 50000L, "IDR", "pay-d-" + n));
    }

    /** 二次确认要复述兽医名，所以月结必须挂在一个真存在的兽医账号上。 */
    private static final String VET_NAME = "月结测试兽医";

    private VetAccount seedVet() {
        long n = SEQ.incrementAndGet();
        return vets.save(VetAccount.create("vet-money-" + n, "{bcrypt}x", VET_NAME));
    }

    private VetSettlement seedSettlement() {
        return settlements.save(VetSettlement.of(seedVet().getId(), "2026-05", 2,
                100000L, 60000L, Instant.now()));
    }

    /**
     * 🔴 症状文本用**哨兵串**，不用「症状」这类真词：抽屉里那句边界提示本身就写着
     * 「此处不展示症状描述与解析结果」—— 拿真词断言 doesNotContain 会与提示文案撞字，
     * 断言只能删或改，等于这条守门永远失效。哨兵串不会出现在任何文案里。
     */
    private static final String SYMPTOM_SENTINEL = "SYMPTOM-SENTINEL-8x5";

    private long seedRedUser() {
        long userId = 900_000L + SEQ.incrementAndGet();
        TriageTask t = TriageTask.submit(userId, null, SYMPTOM_SENTINEL, List.of(),
                "idem-" + userId, "zh_CN");
        t.markDone(DangerLevel.RED, Map.of(), Map.of());
        triage.save(t);
        return userId;
    }

    /** 窗口口径按 `sessionEndedAt` 归月，所以这里造的是**已完成**订单并显式指定会话结束时刻。 */
    private ConsultOrder completedOrder(long vetId, Instant sessionEndedAt) {
        long n = SEQ.incrementAndGet();
        ConsultOrder o = ConsultOrder.inProgress("st-" + n, 100L + n, vetId, 3L, 50000L,
                PayChannel.QRIS, null, 30000L, 60, 50000L, Instant.parse("2026-05-01T00:00:00Z"));
        o.markCompleted(sessionEndedAt);
        return consultOrders.save(o);
    }

    private String displayNoOf(ConsultOrder o) {
        return com.tailtopia.order.dto.OrderDisplayNo.of(
                com.tailtopia.order.dto.OrderDisplayNo.VET_CONSULT, o.getId(), o.getCreatedAt());
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 B12 支付记录 ———————————————————

    @Test
    void paymentListUsesTemplateBWithSummaryAndRowDrawerUrls() throws Exception {
        PaymentIntent p = seedIntent();
        String html = body(mvc.perform(get("/admin/payments").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"payment-drawer\"").contains("id=\"payments-summary\"")
                .contains("data-sum=\"orders\"").contains("data-sum=\"cash\"").contains("data-sum=\"unpaid\"");
        assertThat(html).contains("data-drawer-url=\"/admin/payments/" + p.getPublicToken() + "/drawer\"");
        // 🔴 口径说明不可删：「成功金额」只计真正到账的部分（PawCoin 抵扣不是现金收入）。
        assertThat(html).contains("data-notice=\"payments-summary-scope\"");
        // 筛选参数名逐字未动 —— 改名会让运营存的书签与导出按钮一起失效。
        assertThat(html).contains("name=\"userId\"").contains("name=\"purpose\"")
                .contains("name=\"status\"").contains("name=\"from\"").contains("name=\"to\"");
        assertThat(html).as("导出按钮带着当前筛选走，文案是「导出当前结果」").contains("导出当前结果");
    }

    @Test
    void paymentDrawerIsReadOnlyAndNeverShowsRawGatewayMeta() throws Exception {
        PaymentIntent p = seedIntent();
        String html = body(mvc.perform(get("/admin/payments/" + p.getPublicToken() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"payment-drawer-panel\"").contains(p.getPublicToken());
        // 🔴 网关回调原文（gateway_meta）绝不进后台展示：它是个会被塞进新字段的开放结构。
        assertThat(html).contains("data-notice=\"payment-no-meta\"");
        // 非 stag：模拟回调整块不渲染（AC2 的门 ①）。
        assertThat(html).as("生产 / dev 不该出现模拟回调").doesNotContain("data-stag-only")
                .doesNotContain("simulate-paid");
    }

    /** 🛡 抽屉与列表同一道门 {@code payment.view}；没有的人连抽屉都打不开。 */
    @Test
    void paymentDrawerRequiresPaymentView() throws Exception {
        PaymentIntent p = seedIntent();
        mvc.perform(get("/admin/payments/" + p.getPublicToken() + "/drawer")
                        .header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.STAFF, AdminPermissions.CONTENT_VIEW))))
                .andExpect(status().isForbidden());
    }

    /** 🔴 模拟回调端点在**非 stag 环境根本不存在**（不是 403）—— 这是 AC2 的核心边界。 */
    @Test
    void simulateEndpointsDoNotExistOutsideStag() throws Exception {
        PaymentIntent p = seedIntent();
        for (String suffix : List.of("simulate-paid", "simulate-failed", "simulate-expired")) {
            mvc.perform(post("/admin/payments/" + p.getPublicToken() + "/" + suffix)
                            .with(authentication(superAdmin())).with(csrf()))
                    .andExpect(status().isNotFound());
        }
    }

    // ——————————————————— AC3 B13 兽医月结 ———————————————————

    @Test
    void settlementListUsesTemplateBAndMovesActionsIntoTheDrawer() throws Exception {
        VetSettlement s = seedSettlement();
        String html = body(mvc.perform(get("/admin/settlements").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"settlement-drawer\"").contains("id=\"settlements-summary\"")
                .contains("data-sum=\"pendingCount\"").contains("data-sum=\"pendingPayout\"")
                .contains("data-sum=\"paidThisMonth\"");
        assertThat(html).contains("data-drawer-url=\"/admin/settlements/" + s.getId() + "/drawer\"");
        // 🔴 行内那两张表单已经搬进抽屉：列表里不该再有 pay / archive 的提交入口。
        assertThat(html).doesNotContain("/pay\"").doesNotContain("/archive\"");
        assertThat(html).as("页首「每月 1 日生成上月月结」提示保留").contains("每月 1 日生成上月月结");
    }

    /**
     * 🔴「订单构成」是**读时按 (兽医, 月份窗口) 重算**出来的（月结表只存聚合值，没有明细表），
     * 所以必须真的塞一批订单进去验：窗口内的要出现、窗口外的一条都不能漏进来。
     *
     * <p>只断言「`data-section="settlement-orders"` 这个标记在」的话，把 `>= :start` 改成
     * `> :start`、把 `sessionEndedAt` 换成 `createdAt`、把 WIB 换成 UTC，全都照样绿 ——
     * 而那正是这条口径唯一会出错的地方（它必须与生成月结时逐字一致）。
     */
    @Test
    void settlementDrawerShowsOrderCompositionAndOnlyTheActionThatFits() throws Exception {
        VetAccount vet = seedVet();
        VetSettlement s = settlements.save(VetSettlement.of(vet.getId(), "2026-05", 2,
                100000L, 60000L, Instant.now()));
        // 窗口 = 2026-05 的 WIB [1 日 00:00, 6/1 00:00)。
        ConsultOrder in1 = completedOrder(vet.getId(), Instant.parse("2026-04-30T17:00:00Z")); // = 5/1 00:00 WIB，含
        ConsultOrder in2 = completedOrder(vet.getId(), Instant.parse("2026-05-20T03:00:00Z"));
        ConsultOrder beforeWindow = completedOrder(vet.getId(), Instant.parse("2026-04-30T16:59:59Z"));
        ConsultOrder afterWindow = completedOrder(vet.getId(), Instant.parse("2026-05-31T17:00:00Z")); // = 6/1 00:00 WIB，不含
        ConsultOrder otherVet = completedOrder(seedVet().getId(), Instant.parse("2026-05-20T03:00:00Z"));

        String html = body(mvc.perform(get("/admin/settlements/" + s.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"settlement-drawer-panel\"")
                .contains("data-section=\"settlement-orders\"");
        assertThat(html).as("窗口内的两单都要列出来（含左端点 5/1 00:00 WIB）")
                .contains(displayNoOf(in1)).contains(displayNoOf(in2));
        assertThat(html).as("🔴 窗口外与别的兽医的单，一条都不能漏进来")
                .doesNotContain(displayNoOf(beforeWindow))
                .doesNotContain(displayNoOf(afterWindow))
                .doesNotContain(displayNoOf(otherVet));
        assertThat(html).as("兽医名而不是数字 id").contains(VET_NAME);
        // PENDING_FINANCE：只渲染「确认打款」，不渲染「归档」。
        assertThat(html).contains("/pay").doesNotContain("/archive");
        // 🔴 不可撤销的一步：确认文案必须复述**兽医名 + 到手金额** ——
        //    财务同时开着几笔月结时，一个他不认识的数字 id 挡不住点错行。
        assertThat(html).containsPattern("data-confirm=\"[^\"]*" + VET_NAME + "[^\"]*60,000");
        assertThat(html).as("「此处不发起真实转账」不可删").contains("不发起真实转账");
    }

    /**
     * 🔴 凭证只在 {@code PENDING_FINANCE} 时写得进去（{@code VetSettlement.markPaid} 带
     * {@code requireStatus}），所以拦在**打款那一步**：表单上 required。
     *
     * <p>而已经 PAID 且没有凭证的存量数据**仍然可归档**，只是把话说清楚 —— 真按
     * 「凭证为空则禁用归档」做，这类月结会永久卡在 PAID：归档按钮灰着、提示叫人回填凭证，
     * 而回填凭证的表单已经随状态消失，UI 里逃不出去。（Story AC3 的前提「现状仅服务层拒绝」
     * 不成立：{@code VetSettlement.archive} 只判 PAID，从来不看 proof。）
     */
    @Test
    void proofIsRequiredAtPayoutAndAMissingProofNeverBlocksArchiving() throws Exception {
        VetSettlement s = seedSettlement();
        String pending = body(mvc.perform(get("/admin/settlements/" + s.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(pending).as("凭证必填拦在打款这一步").containsPattern(
                "<input[^>]*name=\"proof\"[^>]*required");

        // 存量数据的形态：PAID 但没有凭证（服务层允许 proof 为空，本 story 未改写端点行为）。
        mvc.perform(post("/admin/settlements/" + s.getId() + "/pay")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        String paid = body(mvc.perform(get("/admin/settlements/" + s.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());
        assertThat(paid).as("说明为什么这笔没有流水可对").contains("data-notice=\"settlement-no-proof\"");
        assertThat(paid).as("🔴 归档必须仍然做得到，否则这笔月结永久卡在 PAID")
                .contains("hx-post=\"/admin/settlements/" + s.getId() + "/archive\"");
        mvc.perform(post("/admin/settlements/" + s.getId() + "/archive")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk());
        assertThat(settlements.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("ARCHIVED");
    }

    /** 🛡 打款 / 归档要 {@code settlement.payout}；只有查看权限的人看到的是禁用态 + 原因，不是 403 按钮。 */
    @Test
    void settlementDrawerGatesActionsOnPayoutPermission() throws Exception {
        VetSettlement s = seedSettlement();
        String html = body(mvc.perform(get("/admin/settlements/" + s.getId() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.STAFF, AdminPermissions.SETTLEMENT_VIEW))))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).doesNotContain("hx-post").contains("disabled");
        mvc.perform(post("/admin/settlements/" + s.getId() + "/pay").param("proof", "x")
                        .header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.STAFF, AdminPermissions.SETTLEMENT_VIEW)))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void payingViaHtmxSwapsTheRowAndTheSummary() throws Exception {
        VetSettlement s = seedSettlement();
        String html = body(mvc.perform(post("/admin/settlements/" + s.getId() + "/pay")
                        .param("proof", "TRX-8-5").param("lang", "zh_CN")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("hx-swap-oob=\"innerHTML:#settlement-drawer .drawer-body\"");
        // 🔴 行与摘要条都必须是 oob 换出去的：只断言 id 出现过会在「true 写成 false」时假绿，
        //    而那时界面上是零反馈（内容会落进已被换成游离节点的 err 槽）。
        assertThat(html).containsPattern("<tr[^>]*id=\"settlement-row-" + s.getId()
                + "\"[^>]*hx-swap-oob=\"true\"");
        assertThat(html).containsPattern("<div[^>]*id=\"settlements-summary\"[^>]*hx-swap-oob=\"true\"");
        // 🔴 oob 行外壳必须是**真的** <table hidden>（响应不以 `<tr` 开头时 htmx 走通用解析，
        //    裸 <tr>/<td> 会被 HTML 解析器丢掉）。
        assertThat(html.indexOf("<table hidden")).isGreaterThanOrEqualTo(0)
                .isLessThan(html.indexOf("id=\"settlement-row-" + s.getId() + "\""));
        assertThat(settlements.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("PAID");
    }

    // ——————————————————— AC4 B14 红色超额 ———————————————————

    @Test
    void redOverageListUsesTemplateBAndKeepsTheObserveOnlyNotice() throws Exception {
        long userId = seedRedUser();
        String html = body(mvc.perform(get("/admin/red-overage").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"red-drawer\"").contains("id=\"red-overage-summary\"")
                .contains("data-sum=\"toVerify\"");
        assertThat(html).contains("data-drawer-url=\"/admin/red-overage/" + userId + "/drawer\"");
        assertThat(html).as("🔴「不做任何自动拦截/限流/封禁」不可删").contains("系统不做任何自动拦截");
        assertThat(html).as("标记表单已搬进抽屉").doesNotContain("/review\"");
    }

    /** 🔴 抽屉里**不出现任何健康数据**：症状文本、解析结果都不进后台展示。 */
    @Test
    void redOverageDrawerListsHistoryWithoutAnyHealthData() throws Exception {
        long userId = seedRedUser();
        String html = body(mvc.perform(get("/admin/red-overage/" + userId + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"red-drawer-panel\"").contains("data-section=\"red-history\"");
        assertThat(html).as("症状文本绝不能出现在后台").doesNotContain(SYMPTOM_SENTINEL);
        assertThat(html).contains("data-notice=\"red-no-health-data\"");
    }

    /**
     * 🔴 标记表单的门是 {@code risk.edit}，与端点逐字一致。
     *
     * <p>整页那版挂的是 {@code risk.view} —— 典型的「按钮在、点了 403」：
     * 只有查看权限的人看得到表单，一提交拿到的是 403。
     */
    @Test
    void redOverageMarkFormIsGatedOnRiskEditNotRiskView() throws Exception {
        long userId = seedRedUser();
        Authentication viewer = auth(AdminAccountType.STAFF, AdminPermissions.RISK_VIEW);
        String html = body(mvc.perform(get("/admin/red-overage/" + userId + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).doesNotContain("hx-post").contains("disabled");

        mvc.perform(post("/admin/red-overage/" + userId + "/review").param("status", "TO_VERIFY")
                        .header("HX-Request", "true").with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void markingViaHtmxSwapsTheRowAndTheSummary() throws Exception {
        long userId = seedRedUser();
        String html = body(mvc.perform(post("/admin/red-overage/" + userId + "/review")
                        .param("status", "TO_VERIFY").param("note", "人工排查中")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("hx-swap-oob=\"innerHTML:#red-drawer .drawer-body\"");
        assertThat(html).containsPattern("<tr[^>]*id=\"red-overage-row-" + userId
                + "\"[^>]*hx-swap-oob=\"true\"");
        assertThat(html).containsPattern("<div[^>]*id=\"red-overage-summary\"[^>]*hx-swap-oob=\"true\"");
    }

    /** 一次 RED 都没有的用户没有抽屉可开 —— 回 404，而不是渲染一个「0 次」的空抽屉。 */
    @Test
    void redOverageDrawerIs404ForAUserWithoutAnyRedTask() throws Exception {
        mvc.perform(get("/admin/red-overage/999999999/drawer").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    /**
     * 🔴 对没有 RED 记录的 id 直接 POST：必须**先拒绝再写库**。
     *
     * <p>{@code service.mark} 对任意 userId 都会写一条 review 行，而列表只列有 RED 记录的人 ——
     * 先写后拒的话，库里会留下一条永远不会出现在任何页面上的孤儿行，运营还会看到 500。
     */
    @Test
    void markingAUserWithoutAnyRedTaskIs404AndWritesNothing() throws Exception {
        mvc.perform(post("/admin/red-overage/999999999/review").param("status", "TO_VERIFY")
                        .header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(redReviews.findById(999999999L)).as("拒绝之前不该已经写进去").isEmpty();
    }
}
