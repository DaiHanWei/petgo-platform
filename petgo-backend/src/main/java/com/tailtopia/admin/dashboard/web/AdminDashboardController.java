package com.tailtopia.admin.dashboard.web;

import com.tailtopia.admin.dashboard.service.AdminDashboardService;
import com.tailtopia.admin.dashboard.service.DashboardQueryService;
import com.tailtopia.admin.shared.web.HxRequest;
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
 * 看板对所有后台账号可进（与原首页一致，不新增 permission_code）；付费卡权限门控归 Story 3.5。
 */
@Controller
public class AdminDashboardController {

    private final AdminDashboardService overview;
    private final DashboardQueryService charts;

    public AdminDashboardController(AdminDashboardService overview, DashboardQueryService charts) {
        this.overview = overview;
        this.charts = charts;
    }

    @GetMapping({"/admin", "/admin/dashboard"})
    public String dashboard(@RequestParam(value = "range", required = false) String range, Model model) {
        model.addAttribute("active", "dashboard");
        model.addAttribute("metrics", overview.overview());
        model.addAttribute("charts", charts.chartData(DashboardQueryService.rangeOrDefault(range)));
        return "admin/dashboard";
    }

    /** 图表区 fragment（AC2）：数据只读长表，JSON 内嵌片段，不开 JSON API（AD-9 / AD-11）。 */
    @GetMapping("/admin/charts")
    public String charts(@RequestParam(value = "range", required = false) String range, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin?range=" + DashboardQueryService.rangeOrDefault(range);
        }
        model.addAttribute("charts", charts.chartData(DashboardQueryService.rangeOrThrow(range)));
        return "admin/fragments/dashboard-charts :: charts";
    }
}
