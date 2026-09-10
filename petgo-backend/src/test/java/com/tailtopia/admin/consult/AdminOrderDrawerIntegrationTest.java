package com.tailtopia.admin.consult;

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
import com.tailtopia.consult.domain.ConsultOrderVerifyStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
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
 * L1 集成：B10 兽医订单 + B11 AI 订单套模板 B、抽屉吸收详情页（V1.3.0 Story 8.4）。
 *
 * <p>本 story 是**零写端点变更**的页面重构：唯一写端点
 * {@code POST /admin/consult-orders/{orderToken}/verify} 的路径 / 参数 / 权限一个字没动。
 * 所以钉的是「详情三段搬完之后每一样都还在、导出没变形、两页形态一致」。
 *
 * <p>⚠️ 与 {@code AdminConsultOrderIntegrationTest} / {@code AdminAiOrderIntegrationTest} 分工：
 * 那两个钉服务层机制（标记不改业务状态、收入口径、CSV 转义），本 story 一条没动。
 */
class AdminOrderDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultOrderRepository orders;

    @Autowired
    private AiConsultOrderRepository aiOrders;

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "orderdrawer-" + n + "@tailtopia.test", "订单抽屉测试员", "{bcrypt}x"));
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

    private ConsultOrder seedOrder() {
        long n = SEQ.incrementAndGet();
        return orders.save(ConsultOrder.inProgress("vc-" + n, 100L + n, 9L, 3L, 50000L,
                PayChannel.QRIS, null, 30000L, 60, 50000L, Instant.now()));
    }

    private AiConsultOrder seedAiOrder() {
        long n = SEQ.incrementAndGet();
        return aiOrders.save(AiConsultOrder.completedPawCoin("ai-d-" + n, 300L + n, 7L, 10000L));
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ——————————————————— AC1 / AC5 B10 列表与退役 ———————————————————

    @Test
    void consultOrderListUsesTemplateBAndDropsTheActionColumn() throws Exception {
        ConsultOrder o = seedOrder();
        String html = body(mvc.perform(get("/admin/consult-orders").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("模板 B 壳 + 摘要条三格")
                .contains("id=\"order-drawer\"").contains("id=\"orders-summary\"")
                .contains("data-sum=\"gross\"").contains("data-sum=\"count\"")
                .contains("data-sum=\"toVerify\"");
        assertThat(html).as("点行开抽屉")
                .contains("data-drawer-url=\"/admin/consult-orders/" + o.getOrderToken() + "/drawer\"");
        assertThat(html).as("「操作」列已删：整页上不该再有指向详情整页的链接")
                .doesNotContain("/admin/consult-orders/" + o.getOrderToken() + "\"");
        assertThat(html).as("🔴 导出按钮写「导出全部」——这个端点不接收任何筛选参数，永远全表")
                .contains("导出全部");
        assertThat(html).as("「本期」的口径要写在页面上，否则会被读成「本月」")
                .contains("data-notice=\"orders-scope\"");
    }

    /** 两个详情整页都已退役（AC5），不做旧地址跳转（D-23）。 */
    @Test
    void theStandaloneDetailPagesAreGone() throws Exception {
        ConsultOrder o = seedOrder();
        AiConsultOrder ai = seedAiOrder();
        Authentication admin = superAdmin();
        mvc.perform(get("/admin/consult-orders/" + o.getOrderToken()).with(authentication(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/ai-orders/" + ai.getOrderToken()).with(authentication(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonHtmxDrawerUrlsRedirectToTheListWithOpenParam() throws Exception {
        ConsultOrder o = seedOrder();
        AiConsultOrder ai = seedAiOrder();
        Authentication admin = superAdmin();
        mvc.perform(get("/admin/consult-orders/" + o.getOrderToken() + "/drawer")
                        .with(authentication(admin)))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/consult-orders?open=" + o.getOrderToken()));
        mvc.perform(get("/admin/ai-orders/" + ai.getOrderToken() + "/drawer")
                        .with(authentication(admin)))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/admin/ai-orders?open=" + ai.getOrderToken()));
    }

    // ——————————————————— AC2 B10 抽屉 ———————————————————

    /** 详情整页的三段必须**一段不少**地搬进抽屉（AC2）。 */
    @Test
    void theDrawerCarriesSnapshotTimelineAndVerifyForm() throws Exception {
        ConsultOrder o = seedOrder();
        String html = body(mvc.perform(get("/admin/consult-orders/" + o.getOrderToken() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"order-drawer-panel\"");
        assertThat(html).as("① 成交快照：字段全集不能少")
                .contains("成交快照").contains(o.getOrderToken())
                .contains("兽医 ID").contains("宠物档案 ID").contains("分成比例")
                .contains("单价快照").contains("重播次数").contains("会话起").contains("会话止");
        assertThat(html).as("🔴 单价快照旁必须注明「成交时点值」，否则会被当成计价错误来报")
                .contains("data-notice=\"unit-price-note\"");
        assertThat(html).as("② 阶段时间线（这单还没有事件 → 空态文案）")
                .contains("阶段时间线").contains("暂无阶段事件");
        assertThat(html).as("③ 待核查标记：端点与参数逐字不变")
                .contains("hx-post=\"/admin/consult-orders/" + o.getOrderToken() + "/verify\"")
                .contains("name=\"status\"").contains("name=\"note\"");
        assertThat(html).as("🔴 「退款不在此处理」不可删")
                .contains("退款不在此处理");
        assertThat(html).as("422 / 403 的落点必须带 id，否则 htmx 不发 HX-Target 头")
                .contains("id=\"order-drawer-err\"");
    }

    /** 标记走 htmx：抽屉重渲染 + 该行 oob + **摘要条也要换**（待核查数变了）。 */
    @Test
    void markingViaHtmxSwapsTheRowAndTheSummary() throws Exception {
        ConsultOrder o = seedOrder();
        MvcResult r = mvc.perform(post("/admin/consult-orders/" + o.getOrderToken() + "/verify")
                        .param("status", "TO_VERIFY").param("note", "对账存疑")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isOk()).andReturn();

        String html = body(r);
        assertThat(html).contains("hx-swap-oob=\"innerHTML:#order-drawer .drawer-body\"")
                .contains("class=\"toast\"");
        // 🔴 oob 行必须包在**真的** <table hidden> 里：响应不以 `<tr` 开头时 htmx 走通用解析，
        //    裸 <tr>/<td> 会被 HTML 解析器丢掉 —— 行不换、单元格文本还泄进抽屉的 err 槽。
        assertThat(html.indexOf("<table hidden")).as("oob 行外壳是真 table 且在行之前")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(html.indexOf("id=\"order-row-" + o.getOrderToken() + "\""));
        // 🔴 只断言「id 出现过」是假绿：把 row(${row}, true) 手滑写成 false，
        //    这两块就不再 oob，而是当作主 swap 内容塞进 #order-drawer-err ——
        //    而那个节点此时已被抽屉体的 oob 换成游离节点，界面上是**零反馈**。
        //    所以要钉住 hx-swap-oob 与 id 落在**同一个标签**上。
        assertThat(html).as("列表那一行必须是 oob 换出去的")
                .containsPattern("<tr[^>]*id=\"order-row-" + o.getOrderToken()
                        + "\"[^>]*hx-swap-oob=\"true\"");
        assertThat(html).as("🔴 摘要条的「待核查数」变了，只换行的话它会停在旧值")
                .containsPattern("<div[^>]*id=\"orders-summary\"[^>]*hx-swap-oob=\"true\"");
        assertThat(orders.findByOrderToken(o.getOrderToken()).orElseThrow().getAdminVerifyStatus())
                .isEqualTo(ConsultOrderVerifyStatus.TO_VERIFY);
    }

    /** 非法的 status 不能被静默当成「清除标记」—— 那会把一条待核查悄悄抹掉。 */
    @Test
    void anInvalidVerifyStatusIsA422NotASilentClear() throws Exception {
        ConsultOrder o = seedOrder();
        Authentication admin = superAdmin();
        mvc.perform(post("/admin/consult-orders/" + o.getOrderToken() + "/verify")
                        .param("status", "TO_VERIFY").header("HX-Request", "true")
                        .with(authentication(admin)).with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(post("/admin/consult-orders/" + o.getOrderToken() + "/verify")
                        .param("status", "NOT_A_STATUS").header("HX-Request", "true")
                        .header("HX-Target", "order-drawer-err")
                        .with(authentication(admin)).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(orders.findByOrderToken(o.getOrderToken()).orElseThrow().getAdminVerifyStatus())
                .as("原来的标记必须还在").isEqualTo(ConsultOrderVerifyStatus.TO_VERIFY);
    }

    /**
     * 🛡 只有 {@code order.view} 的人：标记区是禁用态 + 注明所缺权限，硬发请求照旧 403。
     *
     * <p>⚠️ 整页那版把这张表单的 {@code sec:authorize} 写成了 {@code order.view}，
     * 而端点要 {@code order.edit} —— 典型的「按钮在、点了 403」。抽屉这版按 AC2 改成 {@code order.edit}。
     */
    @Test
    void viewOnlyStaffCannotMarkAndSeesWhichPermissionIsMissing() throws Exception {
        ConsultOrder o = seedOrder();
        Authentication viewer = auth(AdminAccountType.STAFF, AdminPermissions.ORDER_VIEW);

        String html = body(mvc.perform(get("/admin/consult-orders/" + o.getOrderToken() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).as("拿不到写入口").doesNotContain("hx-post");
        assertThat(html).as("并注明缺哪个权限").contains("需要「").contains("disabled");
        assertThat(html).as("只读也要能看到「退款不在此处理」").contains("退款不在此处理");

        mvc.perform(post("/admin/consult-orders/" + o.getOrderToken() + "/verify")
                        .param("status", "TO_VERIFY").header("HX-Request", "true")
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(orders.findByOrderToken(o.getOrderToken()).orElseThrow().getAdminVerifyStatus())
                .isNull();
    }

    // ——————————————————— AC3 / AC4 B11 ———————————————————

    @Test
    void aiOrderListKeepsTheSixRevenueCellsAsItsSummary() throws Exception {
        AiConsultOrder ai = seedAiOrder();
        String html = body(mvc.perform(get("/admin/ai-orders").param("lang", "zh_CN")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).as("收入汇总六格照搬进摘要条")
                .contains("id=\"ai-orders-summary\"")
                .contains("data-sum=\"totalRevenue\"").contains("data-sum=\"revenueQris\"")
                .contains("data-sum=\"revenuePawcoin\"").contains("data-sum=\"completedCount\"")
                .contains("data-sum=\"pendingCount\"").contains("data-sum=\"abnormalCount\"");
        assertThat(html).as("🔴 收入口径提示不可删：不写的话运营会拿总收入直接对渠道流水")
                .contains("data-notice=\"ai-revenue-scope\"");
        assertThat(html).contains("data-drawer-url=\"/admin/ai-orders/" + ai.getOrderToken() + "/drawer\"");
        assertThat(html).contains("导出全部");
        // 🔴 抽屉壳的 id 由 th:with="res='ai-order'" 决定，而那句 th:with 一旦被挪到带 th:replace
        //    的标签上（8.3 实测踩过）就会被静默丢弃，抽屉渲染成 id="item-drawer" —— 点行毫无反应。
        //    AdminTemplateStructureTest 只守「同标签」这一种写法，挪到别的祖先它不红，所以这里钉一次。
        assertThat(html).as("抽屉壳 id 必须是 res 决定的 ai-order-drawer")
                .contains("id=\"ai-order-drawer\"");
    }

    /** AC4：AI 抽屉**全只读** —— 一个按钮、一张表单都没有。 */
    @Test
    void theAiDrawerIsFullyReadOnly() throws Exception {
        AiConsultOrder ai = seedAiOrder();
        String html = body(mvc.perform(get("/admin/ai-orders/" + ai.getOrderToken() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("id=\"ai-order-drawer-panel\"")
                .contains(ai.getOrderToken()).contains("分诊任务 ID").contains("支付意图");
        assertThat(html).as("🔴 一个操作入口都不该有")
                .doesNotContain("<form").doesNotContain("<button").doesNotContain("hx-post");
        assertThat(html).as("「AI 一次性解锁，无退款/分成入口」不可删")
                .contains("data-notice=\"ai-no-refund\"");
    }

    /**
     * 🔴 导出的 CSV 必须带 UTF-8 BOM。
     *
     * <p>表头从写死的 {@code order_token,…} 改成随界面语言的文案之后，首行不再是 ASCII，
     * 而 Excel（简中 Windows）打开**无 BOM** 的 UTF-8 CSV 会按本地代码页解 —— 整行表头乱码。
     * 写入器的约定是「BOM 由调用方拼」（内容列表导出一直是这么做的），本次复审发现这两个端点漏了。
     */
    @Test
    void bothExportsCarryTheUtf8Bom() throws Exception {
        seedOrder();
        seedAiOrder();
        for (String path : List.of("/admin/consult-orders/export", "/admin/ai-orders/export")) {
            String csv = body(mvc.perform(get(path).param("lang", "zh_CN")
                            .with(authentication(superAdmin())))
                    .andExpect(status().isOk()).andReturn());
            assertThat(csv).as(path + " 缺 BOM，Excel 会把中文表头显示成乱码")
                    .startsWith("\uFEFF");
        }
    }

    /** 🛡 两页的抽屉都要 order.view；没有的人连抽屉都打不开。 */
    @Test
    void bothDrawersRequireOrderView() throws Exception {
        ConsultOrder o = seedOrder();
        AiConsultOrder ai = seedAiOrder();
        Authentication outsider = auth(AdminAccountType.STAFF, AdminPermissions.CONTENT_VIEW);
        mvc.perform(get("/admin/consult-orders/" + o.getOrderToken() + "/drawer")
                        .header("HX-Request", "true").with(authentication(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/ai-orders/" + ai.getOrderToken() + "/drawer")
                        .header("HX-Request", "true").with(authentication(outsider)))
                .andExpect(status().isForbidden());
    }
}
