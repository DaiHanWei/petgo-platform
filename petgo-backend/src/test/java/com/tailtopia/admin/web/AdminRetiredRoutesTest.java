package com.tailtopia.admin.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1：本版退役的整页 GET **必须 404**（V1.3.0 Story 11.3 · AC2）。
 *
 * <h2>为什么钉 404 而不是「打得开就行」</h2>
 * 退役页有两种「还活着」的形态，都不算删干净：
 * <ul>
 *   <li><b>302</b> —— 留了个 redirect 壳把旧地址转到新页。看着体贴，实际是**永远删不掉的尾巴**：
 *       书签、外部文档、别人代码里的硬编码全靠它续命，一年后没人敢动。D-23 明确不做旧地址跳转。</li>
 *   <li><b>200</b> —— 模板还在、Controller 还在，重构根本没落地。</li>
 * </ul>
 * 所以断言是**严格 404**（Spring 无映射），不是「非 2xx」。
 *
 * <p>用超管跑：普通账号缺权限会拿到 403，403 和 404 都是「进不去」，
 * 但只有 404 能证明**路由不存在**。这条测试要的是后者。
 *
 * <p>⚠️ 商城两条（{@code /admin/shop/orders/{token}}、{@code /admin/shop/returns/{token}}）
 * **不在名单里**：归 Story 10.1 / 10.2，而 Epic 10 前置是 v1.4.0 电商线合入（AD-12），本轮未执行，
 * 它们现在仍在服役。Epic 10 落地时补进来。
 *
 * <p>⚠️ {@code /admin/vets/{id}/qualification} 与 {@code /admin/vets/{id}/ratings} 也不在名单里：
 * 11.3 AC1 把它们列进了退役表，但 Story 9.1b 实际是把它们改成了**抽屉页签的懒加载片段**，
 * 路径保留（htmx 请求返片段，直达则 302 回列表并开抽屉）。删了抽屉页签就空了。
 */
class AdminRetiredRoutesTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    /** 退役路由 → 取代它的东西（失败信息里直接说清楚该去哪儿）。 */
    private static final List<String[]> RETIRED = List.of(
            new String[] {"/admin/reports", "A1 统一复核工作台（2.4）"},
            new String[] {"/admin/tickets/detail", "被举报用户抽屉（2.5）"},
            new String[] {"/admin/anomalies/1", "问诊异常抽屉（2.6）"},
            new String[] {"/admin/support-tickets/tok-x", "客服工单抽屉（2.7）"},
            new String[] {"/admin/refunds/tok-x", "退款三段流抽屉（2.8）"},
            new String[] {"/admin/content/1", "B1 内容详情抽屉（7.1）"},
            new String[] {"/admin/content-schedules", "批量内容的排期页签（7.5）"},
            new String[] {"/admin/seed-batch", "E2 批量工作台（7.6）"},
            new String[] {"/admin/users/1", "B7 用户五页签抽屉（8.1）"},
            new String[] {"/admin/consult-orders/tok-x", "B10 兽医订单抽屉（8.4）"},
            new String[] {"/admin/ai-orders/tok-x", "B11 AI 订单抽屉（8.4）"},
            new String[] {"/admin/vets/online", "兽医列表的在线态列 + 抽屉资料页签（9.1a）"},
            new String[] {"/admin/vets/1/edit", "抽屉资料页签（9.1a）"},
            new String[] {"/admin/ratings", "兽医列表筛选栏 + 抽屉评分页签（9.1b）"});

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "retired-" + n + "@tailtopia.test", "退役路由核对", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    @TestFactory
    List<DynamicTest> everyRetiredPageRouteIsGoneNotRedirected() {
        Authentication auth = superAdmin();
        List<DynamicTest> tests = new ArrayList<>();
        for (String[] row : RETIRED) {
            tests.add(DynamicTest.dynamicTest(row[0] + " → 404（取代者：" + row[1] + "）",
                    () -> mvc.perform(get(row[0]).with(authentication(auth)))
                            .andExpect(status().isNotFound())));
        }
        return tests;
    }
}
