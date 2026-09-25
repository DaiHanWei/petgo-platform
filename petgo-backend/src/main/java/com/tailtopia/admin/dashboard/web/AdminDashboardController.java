package com.tailtopia.admin.dashboard.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.dashboard.service.AdminDashboardService;
import com.tailtopia.admin.dashboard.service.DashboardQueryService;
import com.tailtopia.admin.payment.web.AdminPaymentController;
import com.tailtopia.admin.shared.web.HxRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 内容运营数据看板（V1.3.0 Story 3.4，AB-15A；原 {@code AdminWebController.dashboard()} 迁到此处）。
 * <ul>
 * <li>{@code GET /admin} / {@code /admin/dashboard}：整页（模板 C），首屏内嵌默认近 7 天的图表 fragment + 底部摘要行（复用 overview()）。</li>
 * <li>{@code GET /admin/charts?range=7|30}（HX-Request）：只回 {@code fragments/dashboard-charts :: charts}；非 htmx 访问回整页。
 * {@code range} 非法 → 422 行内 err（{@code AdminBusinessExceptionAdvice}）。</li>
 * </ul>
 * 看板对所有后台账号可进（与原首页一致，不新增 permission_code）。付费卡（Story 3.5，D-17）：登录者须有 {@code payment.view}
 * （与支付记录页 {@link AdminPaymentController#VIEW_AUTH} 同码）或 SUPER_ADMIN，否则<b>服务端不取不下发</b>付费四项，模板层
 * {@code #authorization} 再挡一道。
 */
@Controller
public class AdminDashboardController {

    private final AdminDashboardService overview;
    private final DashboardQueryService charts;

    public AdminDashboardController(AdminDashboardService overview, DashboardQueryService charts) {
        this.overview = overview;
        this.charts = charts;
    }

    /**
     * 付费卡可见条件 = {@link AdminPaymentController#VIEW_AUTH} 原文；{@code dashboard-charts.html} 的 {@code sec:authorize} 与此逐字一致
     * （{@code AdminPageCatalogAuthorityTest} 把看板纳入守卫），{@link #canViewPayment} 是它的 Java 侧判定。
     */
    public static final String PAYMENT_CARD_AUTH = AdminPaymentController.VIEW_AUTH;

    @GetMapping({"/admin", "/admin/dashboard"})
    public String dashboard(@RequestParam(value = "range", required = false) String range, Authentication auth, Model model) {
        model.addAttribute("active", "dashboard");
        model.addAttribute("metrics", overview.overview());
        model.addAttribute("charts", charts.chartData(DashboardQueryService.rangeOrDefault(range), canViewPayment(auth)));
        return "admin/dashboard";
    }

    /** 图表区 fragment（AC2）：数据只读长表，JSON 内嵌片段，不开 JSON API（AD-9 / AD-11）。 */
    @GetMapping("/admin/charts")
    public String charts(@RequestParam(value = "range", required = false) String range, Authentication auth, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin?range=" + DashboardQueryService.rangeOrDefault(range);
        }
        model.addAttribute("charts", charts.chartData(DashboardQueryService.rangeOrThrow(range), canViewPayment(auth)));
        return "admin/fragments/dashboard-charts :: charts";
    }

    /** {@code hasRole('SUPER_ADMIN') or hasAuthority('payment.view')} 的 Java 侧判定（authorities 由 AdminUserDetails 装配）。 */
    static boolean canViewPayment(Authentication auth) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            String code = a.getAuthority();
            if ("ROLE_SUPER_ADMIN".equals(code) || AdminPermissions.PAYMENT_VIEW.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
