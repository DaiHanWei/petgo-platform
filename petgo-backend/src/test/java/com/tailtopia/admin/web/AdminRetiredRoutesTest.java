package com.tailtopia.admin.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
 * <p>⚠️ 商城两条都<b>不在名单里</b>，而且是同一个理由：它们的整页分别于 Story 10.1（退货）与
 * 10.2（订单）<b>已经退役</b>，但两个 story 都明确「不许新开 {@code /{token}/detail}（或 {@code /drawer}）端点」，
 * 于是右栏 / 抽屉片段<b>复用了原来那条整页 mapping</b>（{@code HX-Request} 返片段、直达抛 404）。
 * GET 映射<b>按设计仍然存在</b>，所以它们进不了 {@link #noRetiredPathStillHasAGetMapping} 的口径 ——
 * 写进来只会把两条正确的实现判成「没删干净」。
 * 「直达旧地址返 404、不跳转」分别由 {@code AdminReturnEndpointIntegrationTest} 与
 * {@code AdminShopOrderEndpointIntegrationTest} 钉住。
 *
 * <p>⚠️ {@code /admin/vets/{id}/qualification} 与 {@code /admin/vets/{id}/ratings} 也不在名单里：
 * 11.3 AC1 把它们列进了退役表，但 Story 9.1b 实际是把它们改成了**抽屉页签的懒加载片段**，
 * 路径保留（htmx 请求返片段，直达则 302 回列表并开抽屉）。删了抽屉页签就空了。
 */
class AdminRetiredRoutesTest extends ApiIntegrationTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * 退役路由 → 取代它的东西（失败信息里直接说清楚该去哪儿）。
     *
     * <p>14 条 = 11.3 AC1 表里已删的 13 条 + `/admin/seed-batch`。
     * 后者来自 Story 7.6（旧批量入口，E2 模板 E 取代），AC1 的表里没列，
     * 但它确实是本版删掉的整页 GET，在 Story 11.1 的白名单里有授权（复审 P2）。
     */
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

    /**
     * 🔴 **同路径还有 POST 的退役 GET，HTTP 状态是 405 不是 404。**
     *
     * <p>`POST /admin/vets/{id}` 在**路径层面**盖住了 `/admin/vets/online`（`{id}` 是路径变量，
     * Spring 匹配路径时不看它声明成 long），于是方法不匹配 → `HttpRequestMethodNotSupportedException`
     * → 405 + `Allow: POST`。本仓库 `GlobalExceptionHandler` 的 javadoc 里就写着这一条
     * 「只退役 GET、保留 POST 是重构里的常规动作」。
     *
     * <p>**405 同样证明「GET 映射不存在」**——它恰恰说明这条路径上只剩 POST。
     * 但拿 404 去断言它会让这条测试一上真库就红，而红的原因跟「有没有删干净」毫无关系。
     */
    private static final java.util.Set<String> SHADOWED_BY_POST = java.util.Set.of("/admin/vets/online");

    private Authentication superAdmin() {
        // ⚠️ 不落库：这 14 条断言的权限全部来自 TestingAuthenticationToken，一次库都不读；
        //    而 ApiIntegrationTest 不回滚，每跑一次就往共享库 admin_accounts 白扔一行（复审 P6）。
        AdminUserDetails p = new AdminUserDetails(
                900_000_000L + SEQ.incrementAndGet(), null, "retired-routes@tailtopia.test",
                "{bcrypt}x", AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    /**
     * 🔴 **最直接的判据：这些路径上不该再有任何 GET 映射。**
     *
     * <p>比断言 HTTP 状态码强，因为它不受「同路径 POST 遮蔽」影响 ——
     * 那种情况下状态码是 405，而 405 与「GET 还活着」是两回事。AC2 要的是「路由不存在」，
     * 这一条直接问 Spring 要答案。
     */
    @Test
    void noRetiredPathStillHasAGetMapping() {
        var offenders = new TreeSet<String>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            var methods = info.getMethodsCondition().getMethods();
            boolean isGet = methods.isEmpty() || methods.contains(RequestMethod.GET);
            if (!isGet) {
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                for (String[] row : RETIRED) {
                    // 模板化路径（/admin/users/{userId}）与实例路径（/admin/users/1）都要对上
                    if (pattern.equals(row[0]) || pattern.matches(templatize(row[0]))) {
                        offenders.add(pattern);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("退役页的 GET 映射又回来了 —— 模板删了不等于路由删了，"
                        + "返回一个片段视图名照样能让整页复活")
                .isEmpty();
    }

    /** `/admin/users/1` → `/admin/users/\{[^/]+\}`，用来匹配注册时的模板化 pattern。 */
    private static String templatize(String concrete) {
        return concrete.replaceAll("/(?:\\d+|tok-[a-z]+)(?=/|$)", "/\\{[^/]+\\}");
    }

    @TestFactory
    List<DynamicTest> everyRetiredPageRouteIsGoneNotRedirected() {
        Authentication auth = superAdmin();
        List<DynamicTest> tests = new ArrayList<>();
        for (String[] row : RETIRED) {
            boolean shadowed = SHADOWED_BY_POST.contains(row[0]);
            String want = shadowed ? "405 + Allow: POST（同路径仍有 POST）" : "404";
            tests.add(DynamicTest.dynamicTest(row[0] + " → " + want + "（取代者：" + row[1] + "）",
                    () -> {
                        var r = mvc.perform(get(row[0]).with(authentication(auth)));
                        if (shadowed) {
                            r.andExpect(status().isMethodNotAllowed())
                                    .andExpect(header().string("Allow", "POST"));
                        } else {
                            r.andExpect(status().isNotFound());
                        }
                    }));
        }
        return tests;
    }
}
