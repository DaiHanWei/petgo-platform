package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.shop.service.ShopFinanceDashboardService;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 经营数据三页（Story 8.1 AB-13A 毛利 · 8.2 AB-13C 库存周转 · 8.3 AB-13D 对账）。
 *
 * <p>🔒 <b>毛利与对账走独立权限位 {@code shop.finance_view}</b>（NFR-11）——
 * 默认仅财务与管理层，<b>不默认授予任何既有运营角色</b>。
 * 库存周转页含按进货价的库存金额，故同样受 {@code shop.cost_view} 之外的这道门控。
 */
@Controller
public class AdminShopFinanceController {

    private static final String FINANCE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.finance_view')";

    private final ShopFinanceDashboardService finance;

    /** 滞销判定天数。可配 —— 不同品类的合理周转差得远，写死一个数会误报。 */
    private final int staleDays;

    public AdminShopFinanceController(ShopFinanceDashboardService finance,
            @Value("${petgo.shop.stale-days:60}") int staleDays) {
        this.finance = finance;
        this.staleDays = staleDays;
    }

    @GetMapping("/admin/shop/margin")
    @PreAuthorize(FINANCE_AUTH)
    public String margin(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) String category,
            Model model) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("category", category == null ? "" : category);
        model.addAttribute("m", finance.margin(start, end, category));
        model.addAttribute("bySku", finance.marginBySku(start, end));
        model.addAttribute("categories",
                com.tailtopia.shop.domain.ProductCategory.values());
        model.addAttribute("active", "shopMargin");
        return "admin/shop-margin";
    }

    @GetMapping("/admin/shop/inventory-turnover")
    @PreAuthorize(FINANCE_AUTH)
    public String turnover(@RequestParam(required = false) Integer days, Model model) {
        int window = days == null || days <= 0 ? staleDays : days;
        model.addAttribute("staleDays", window);
        model.addAttribute("rows", finance.inventoryTurnover(window));
        model.addAttribute("outOfStockCount", finance.outOfStockSkuCount());
        model.addAttribute("active", "shopTurnover");
        return "admin/shop-inventory-turnover";
    }

    @GetMapping("/admin/shop/reconciliation")
    @PreAuthorize(FINANCE_AUTH)
    public String reconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            Model model) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("r", finance.reconciliation(start, end));
        model.addAttribute("active", "shopReconciliation");
        return "admin/shop-reconciliation";
    }
}
