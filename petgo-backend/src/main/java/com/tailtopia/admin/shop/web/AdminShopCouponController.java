package com.tailtopia.admin.shop.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 「优惠券」（AB-24 · AD-6 / O-7）：查看 {@code shop.coupon_view}，操作 {@code shop.coupon_edit}。
 *
 * <p>V1.4.2 shop-admin Story 1.1 · AD-2：先渲染占位页（页头 + 空态），后续 story 替换为正式页面。
 * 🛡 入口门与 {@code AdminPageCatalog} 该页 navCodes 逐字同集（{@code AdminPagesRenderSmokeTest} 守门）。
 */
@Controller
public class AdminShopCouponController {

    static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.coupon_view') or hasAuthority('shop.coupon_edit')";

    @GetMapping("/admin/shop/coupons")
    @PreAuthorize(VIEW_AUTH)
    public String page(Model model) {
        model.addAttribute("active", "shopCoupons");
        model.addAttribute("titleKey", "admin.page.shop-coupons");
        return "admin/shop-v142-placeholder";
    }
}
