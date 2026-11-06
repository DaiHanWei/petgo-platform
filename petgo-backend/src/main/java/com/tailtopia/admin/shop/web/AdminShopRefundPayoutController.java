package com.tailtopia.admin.shop.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 「退款打款」（AB-28 · AD-10 / O-8）：查看 {@code refund.view} 或 {@code refund.payout}，操作只限 {@code refund.payout}。
 *
 * <p>V1.4.2 shop-admin Story 1.1 · AD-2：先渲染占位页（页头 + 空态），后续 story 替换为正式页面。
 * 🛡 入口门与 {@code AdminPageCatalog} 该页 navCodes 逐字同集（{@code AdminPagesRenderSmokeTest} 守门）。
 */
@Controller
public class AdminShopRefundPayoutController {

    static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('refund.view') or hasAuthority('refund.payout')";

    @GetMapping("/admin/shop/refund-payouts")
    @PreAuthorize(VIEW_AUTH)
    public String page(Model model) {
        model.addAttribute("active", "shopRefundPayouts");
        model.addAttribute("titleKey", "admin.page.shop-refund-payouts");
        return "admin/shop-v142-placeholder";
    }
}
