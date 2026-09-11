package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shop.service.RepurchaseDashboardService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 复购引擎效果看板（Story 6.6，AB-13B）。
 *
 * <p>🔴 <b>它是裁决 A-16 的唯一依据</b> —— 所以口径以服务端业务库为准，
 * 后台<b>不反拉 PostHog API</b>（外部依赖违 NFR-1，且带丢失率与广告拦截偏差）。
 *
 * <p>权限沿用既有 {@code config.view}（看板属运营视图，与其他数据看板同类），不新增权限码。
 *
 * <p>V1.3.0 Story 10.5（模板 C 只读报表）：期间切换走 htmx，只换卡区 + 明细，页头与只读标识不重绘。
 * <b>本页没有任何写操作，也没有导出端点</b>（AC4 明确不新增），路径与权限零变更。
 */
@Controller
public class AdminRepurchaseDashboardController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('config.view') or hasAuthority('order.view')";

    private final RepurchaseDashboardService dashboard;

    public AdminRepurchaseDashboardController(RepurchaseDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/admin/shop/repurchase-dashboard")
    @PreAuthorize(VIEW_AUTH)
    public String page(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            HxRequest hx, Model model) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("s", dashboard.snapshot(start, end));
        model.addAttribute("active", "shopRepurchase");
        return hx.isHtmx() ? "admin/fragments/cards-shop-repurchase :: refresh"
                : "admin/shop-repurchase-dashboard";
    }
}
