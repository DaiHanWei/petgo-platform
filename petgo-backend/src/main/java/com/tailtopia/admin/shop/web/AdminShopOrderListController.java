package com.tailtopia.admin.shop.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 「订单」总表（AB-38 · AD-12）：查看 {@code shop.order_view}，操作 {@code shop.order_fulfill}。
 *
 * <p>V1.4.2 shop-admin Story 1.1 · AD-2：先渲染占位页（页头 + 空态），后续 story 替换为正式页面。
 * 🛡 入口门与 {@code AdminPageCatalog} 该页 navCodes 逐字同集（{@code AdminPagesRenderSmokeTest} 守门）。
 */
@Controller
public class AdminShopOrderListController {

    static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.order_view') "
                    + "or hasAuthority('shop.order_fulfill')";

    @GetMapping("/admin/shop/order-list")
    @PreAuthorize(VIEW_AUTH)
    public String page(Model model) {
        model.addAttribute("active", "shopOrderList");
        model.addAttribute("titleKey", "admin.page.shop-order-list");
        return "admin/shop-v142-placeholder";
    }
}
