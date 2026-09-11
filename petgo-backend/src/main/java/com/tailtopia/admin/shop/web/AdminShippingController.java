package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
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
 *
 * <p>V1.3.0 Story 10.6（模板 D 三卡）：四个 POST 加 htmx 分支 —— 成功回<b>该卡</b> fragment
 * （{@code HX-Retarget #<cardId>} + {@code HX-Reswap outerHTML} 原位替换，「已修改」标随之消退）+ toast；
 * 失败不在这里 catch，让 {@code AppException} 冒给 {@code AdminBusinessExceptionAdvice} 出 422 行内 err
 * （落到卡 / 行自己的 err 槽）。非 htmx 分支的 PRG 与 flash 一字未动。
 * <b>端点路径 / 参数 / 权限零变更</b>（AC3）。
 */
@Controller
public class AdminShippingController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('config.view') or hasAuthority('config.edit')";
    private static final String EDIT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('config.edit')";

    private final AdminShippingZoneService zones;
    private final ShippingSettingsRepository settings;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminShippingController(AdminShippingZoneService zones,
            ShippingSettingsRepository settings,
            Messages msg) {
        this.zones = zones;
        this.settings = settings;
        this.msg = msg;
    }

    @GetMapping("/admin/shop/shipping")
    @PreAuthorize(VIEW_AUTH)
    public String page(Model model) {
        populateCards(model);
        model.addAttribute("active", "shopShipping");
        return "admin/shop-shipping";
    }

    /** 三张卡共用的取数（整页与 htmx 回卡都走它，避免两处各查各的）。 */
    private void populateCards(Model model) {
        model.addAttribute("zones", zones.list());
        model.addAttribute("settings", settings.findAll().stream().findFirst().orElse(null));
    }

    /**
     * htmx 保存成功统一返回（AC1）：原位换掉该卡 + toast。
     *
     * <p>🔴 <b>必须换整张卡而不是只回一条提示</b>：卡里的「已修改」标与保存钮的禁用态是按
     * {@code data-config-card} 记的初值算的 —— 不把卡换掉，保存成功后那个标还挂着、
     * 按钮还是激活的，运营会以为没存上，于是再点一次。
     *
     * <p>区域卡另有一层：新增的那一行、启停后的状态、以及清空的新增表单，都只有整卡重渲染才对。
     */
    private String savedCard(String cardId, String fragment, String toastKey, Model model,
            HttpServletResponse response) {
        populateCards(model);
        model.addAttribute("toast", msg.get(toastKey));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#" + cardId);
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
        return "admin/shop-shipping :: " + fragment;
    }

    @PostMapping("/admin/shop/shipping/zones")
    @PreAuthorize(EDIT_AUTH)
    public String upsertZone(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String kecamatan, @RequestParam String kotaKabupaten,
            @RequestParam String provinsi, @RequestParam long fee,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            zones.upsert(kecamatan, kotaKabupaten, provinsi, fee, actorOf(admin));
            return savedCard("cfg-ship-zones", "savedZones", "admin.flash.shipping.zoneSaved",
                    model, response);
        }
        try {
            zones.upsert(kecamatan, kotaKabupaten, provinsi, fee, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.shipping.zoneSaved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/shipping";
    }

    /** 🔴 停用用 {@code active=false}，不删行 —— 历史订单的运费需要可追溯（AB-13D 对账）。 */
    @PostMapping("/admin/shop/shipping/zones/toggle")
    @PreAuthorize(EDIT_AUTH)
    public String toggleZone(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String kecamatan, @RequestParam boolean active,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            zones.setActive(kecamatan, active, actorOf(admin));
            return savedCard("cfg-ship-zones", "savedZones",
                    active ? "admin.flash.shipping.zoneEnabled" : "admin.flash.shipping.zoneDisabled",
                    model, response);
        }
        try {
            zones.setActive(kecamatan, active, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get(active ? "admin.flash.shipping.zoneEnabled" : "admin.flash.shipping.zoneDisabled"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/shipping";
    }

    @PostMapping("/admin/shop/shipping/threshold")
    @PreAuthorize(EDIT_AUTH)
    public String setThreshold(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long threshold,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            zones.setFreeShippingThreshold(threshold, actorOf(admin));
            return savedCard("cfg-ship-threshold", "savedThreshold",
                    "admin.flash.shipping.freeThresholdSaved", model, response);
        }
        try {
            zones.setFreeShippingThreshold(threshold, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.shipping.freeThresholdSaved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/shipping";
    }

    /** S-7：退货收件地址。🔴 用户自寄，本版本不做上门取件。 */
    @PostMapping("/admin/shop/shipping/return-address")
    @PreAuthorize(EDIT_AUTH)
    public String setReturnAddress(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) String addressText,
            @RequestParam(required = false) String receiverName,
            @RequestParam(required = false) String receiverPhone,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            zones.setReturnAddress(addressText, receiverName, receiverPhone, actorOf(admin));
            return savedCard("cfg-ship-return-address", "savedReturnAddress",
                    "admin.flash.shipping.returnAddressSaved", model, response);
        }
        try {
            zones.setReturnAddress(addressText, receiverName, receiverPhone, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.shipping.returnAddressSaved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/shipping";
    }

    private static long actorOf(AdminUserDetails admin) {
        if (admin == null) {
            throw AppException.unauthorized("需要登录").code("admin.err.common.loginRequired");
        }
        return admin.getAdminAccountId();
    }
}
