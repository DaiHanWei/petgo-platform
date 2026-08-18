package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 服务范围与运费表（AB-11C）。
 *
 * <p>⚠️ <b>本页补的是 Story 2.2 的缺口</b>：2.2 只落了
 * {@code AdminShippingZoneService} 与迁移，<b>没有建后台页面</b> —— 运营因此无从维护
 * 可配送区域与运费，而没有可配送区域时整个商城一单也发不出去。
 * 由 Epic 5 的 S-7（退货收件地址是 AB-11C 增配项）顺带补齐，已在 story 文档登记。
 *
 * <p>🔴 <b>界面不出现「配送方式」维度</b>（C-14 已把二维运费表降为一维，只剩 Reguler）。
 * 留一个恒等于 Reguler 的下拉只会让人误以为多档已经支持。
 *
 * <p>权限沿用既有运营配置码 {@code config.view} / {@code config.edit} —— 运费是钱，
 * 与定价、PawCoin 阈值同属一类。
 */
@Controller
public class AdminShippingController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('config.view') or hasAuthority('config.edit')";
    private static final String EDIT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('config.edit')";

    private final AdminShippingZoneService zones;
    private final ShippingSettingsRepository settings;

    public AdminShippingController(AdminShippingZoneService zones,
            ShippingSettingsRepository settings) {
        this.zones = zones;
        this.settings = settings;
    }

    @GetMapping("/admin/shop/shipping")
    @PreAuthorize(VIEW_AUTH)
    public String page(Model model) {
        model.addAttribute("zones", zones.list());
        model.addAttribute("settings", settings.findAll().stream().findFirst().orElse(null));
        model.addAttribute("active", "shopShipping");
        return "admin/shop-shipping";
    }

    @PostMapping("/admin/shop/shipping/zones")
    @PreAuthorize(EDIT_AUTH)
    public String upsertZone(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String kecamatan, @RequestParam String kotaKabupaten,
            @RequestParam String provinsi, @RequestParam long fee, RedirectAttributes ra) {
        try {
            zones.upsert(kecamatan, kotaKabupaten, provinsi, fee, actorOf(admin));
            ra.addFlashAttribute("notice", "配送区域已保存");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/shipping";
    }

    /** 🔴 停用用 {@code active=false}，不删行 —— 历史订单的运费需要可追溯（AB-13D 对账）。 */
    @PostMapping("/admin/shop/shipping/zones/toggle")
    @PreAuthorize(EDIT_AUTH)
    public String toggleZone(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String kecamatan, @RequestParam boolean active, RedirectAttributes ra) {
        try {
            zones.setActive(kecamatan, active, actorOf(admin));
            ra.addFlashAttribute("notice", active ? "区域已启用" : "区域已停用");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/shipping";
    }

    @PostMapping("/admin/shop/shipping/threshold")
    @PreAuthorize(EDIT_AUTH)
    public String setThreshold(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long threshold, RedirectAttributes ra) {
        try {
            zones.setFreeShippingThreshold(threshold, actorOf(admin));
            ra.addFlashAttribute("notice", "免运门槛已保存");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/shipping";
    }

    /** S-7：退货收件地址。🔴 用户自寄，本版本不做上门取件。 */
    @PostMapping("/admin/shop/shipping/return-address")
    @PreAuthorize(EDIT_AUTH)
    public String setReturnAddress(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) String addressText,
            @RequestParam(required = false) String receiverName,
            @RequestParam(required = false) String receiverPhone, RedirectAttributes ra) {
        try {
            zones.setReturnAddress(addressText, receiverName, receiverPhone, actorOf(admin));
            ra.addFlashAttribute("notice", "退货收件地址已保存");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/shipping";
    }

    private static long actorOf(AdminUserDetails admin) {
        if (admin == null) {
            throw AppException.unauthorized("需要登录");
        }
        return admin.getAdminAccountId();
    }
}
