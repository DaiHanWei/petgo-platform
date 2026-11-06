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

    /** V1.4.2 shop-admin Story 4.1 · AD-13：Biteship 预估余额（setter 注入，存量切片测试不受影响）。 */
    private com.tailtopia.shop.logistics.balance.BiteshipBalanceService balance;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBalanceService(com.tailtopia.shop.logistics.balance.BiteshipBalanceService balance) {
        this.balance = balance;
    }

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
        var st = settings.findAll().stream().findFirst().orElse(null);
        model.addAttribute("settings", st);
        // V1.4.2 shop-admin Story 4.1：G2 发货地未配置（无邮编 = App 无法向 Biteship 询价）；G3 预估余额低于阈值
        model.addAttribute("originMissing", st == null || st.getOriginPostalCode() == null);
        var ov = balance == null ? null : balance.overview();
        model.addAttribute("balance", ov);
        model.addAttribute("balanceBelow", ov != null && ov.below());
        model.addAttribute("shipErrorFields", SHIP_ERROR_FIELDS);
    }

    /** 422 错误码 → 卡内字段红边（admin-core.js 读 data-error-fields）。 */
    static final java.util.Map<String, String> SHIP_ERROR_FIELDS = java.util.Map.of(
            "threshold", "{\"admin.err.v142.shipping.capRequired\":\"freeShippingCap\","
                    + "\"admin.err.v142.shipping.capNegative\":\"freeShippingCap\","
                    + "\"admin.err.v142.shipping.thresholdNegative\":\"threshold\"}",
            "origin", "{\"admin.err.v142.shipping.originPostalCode\":\"originPostalCode\"}");

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
            @RequestParam String provinsi, @RequestParam(required = false) Long fee,
            @RequestParam(required = false) String postalCode,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        // V1.4.2 shop-admin Story 4.1：区域表去运费列 —— fee 可选（更新保留原值、新增写 0），可填代表邮编
        if (hx.isHtmx()) {
            zones.upsert(kecamatan, kotaKabupaten, provinsi, fee, postalCode, actorOf(admin));
            return savedCard("cfg-ship-zones", "savedZones", "admin.flash.shipping.zoneSaved",
                    model, response);
        }
        try {
            zones.upsert(kecamatan, kotaKabupaten, provinsi, fee, postalCode, actorOf(admin));
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

    /**
     * V1.4.2 shop-admin Story 1.2 · AD-3：原生 {@code data-confirm} 改为服务端确认弹层。只读、不写库；
     * 弹层确认后再提交原端点（业务行为不变，AP-7）。权限同原端点。
     */
    @PostMapping("/admin/shop/shipping/zones/toggle/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmToggleZone(@RequestParam String kecamatan, @RequestParam boolean active,
            @RequestParam(defaultValue = "") String slot, Model model, HttpServletResponse response) {
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("kecamatan", kecamatan);
        hidden.put("active", String.valueOf(active));
        com.tailtopia.admin.shared.web.AdminConfirmModal cm = com.tailtopia.admin.shared.web.AdminConfirmModal.simple(msg.get("admin.confirm.title.default"),
                active ? msg.get("admin.shop.shipping.btn.enable") + "「" + kecamatan + "」"
                        : msg.get("admin.v130.shopShipping.confirmDisable", kecamatan),
                "/admin/shop/shipping/zones/toggle", hidden,
                msg.get(active ? "admin.shop.shipping.btn.enable" : "admin.shop.shipping.btn.disable"), !active)
                .withSuccessTarget(slot.isBlank() ? "#cfg-ship-zones" : "#cfg-ship-zone-err-" + slot.replaceAll("[^0-9]", ""), "innerHTML");
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
    }

    /**
     * 免运门槛 + 最高减免 · 保存前预检（V1.4.2 shop-admin Story 4.1 · AC4 / AC8 · O-27）：只校验、不写库，
     * 校验不过 422 落卡底错误槽（最高减免红边）；通过则回 confirm-modal 复述改动，确认后 POST {@code /threshold}。
     */
    @PostMapping("/admin/shop/shipping/threshold/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmThreshold(@RequestParam long threshold,
            @RequestParam(required = false) Long freeShippingCap,
            HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/shop/shipping";
        }
        AdminShippingZoneService.validateFreeShipping(threshold, freeShippingCap);
        var st = settings.findAll().stream().findFirst().orElse(null);
        java.util.List<com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine> lines = new java.util.ArrayList<>();
        diff(lines, "admin.shop.shipping.section.threshold", st == null ? null : rpOrNull(st.getFreeShippingThreshold()),
                rpOrNull(threshold));
        diff(lines, "admin.v142.shipping.cap", st == null ? null : rpOrNull(st.getFreeShippingCap()),
                rpOrNull(freeShippingCap));
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("threshold", String.valueOf(threshold));
        hidden.put("freeShippingCap", freeShippingCap == null ? "" : String.valueOf(freeShippingCap));
        hidden.put("confirmed", "true");
        return confirmModal(model, response, lines, "/admin/shop/shipping/threshold", hidden,
                "admin.shop.shipping.btn.saveThreshold");
    }

    @PostMapping("/admin/shop/shipping/threshold")
    @PreAuthorize(EDIT_AUTH)
    public String setThreshold(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long threshold,
            @RequestParam(required = false) Long freeShippingCap,
            @RequestParam(defaultValue = "false") boolean confirmed,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            zones.setFreeShipping(threshold, freeShippingCap, actorOf(admin));
            closeConfirm(confirmed, response);
            return savedCard("cfg-ship-threshold", "savedThreshold",
                    "admin.flash.shipping.freeThresholdSaved", model, response);
        }
        try {
            zones.setFreeShipping(threshold, freeShippingCap, actorOf(admin));
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

    // ---------- V1.4.2 shop-admin Story 4.1：发货地址 / Biteship 余额与预警阈值（G1~G3，全部走 confirm-modal） ----------

    /** 发货地址 · 保存前预检：复述改动字段（页面本就显示这些字段；审计仍只记邮编前 2 位）。 */
    @PostMapping("/admin/shop/shipping/origin/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmOrigin(@RequestParam(required = false) String originContactName,
            @RequestParam(required = false) String originContactPhone,
            @RequestParam(required = false) String originAddress,
            @RequestParam(required = false) String originPostalCode,
            HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/shop/shipping";
        }
        AdminShippingZoneService.validateOrigin(originPostalCode, originContactName, originContactPhone, originAddress);
        String pc = trimToNull(originPostalCode);
        var st = settings.findAll().stream().findFirst().orElse(null);
        java.util.List<com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine> lines = new java.util.ArrayList<>();
        diff(lines, "admin.v142.shipping.origin.contactName", st == null ? null : st.getOriginContactName(),
                trimToNull(originContactName));
        diff(lines, "admin.shop.order.col.phone", st == null ? null : st.getOriginContactPhone(),
                trimToNull(originContactPhone));
        diff(lines, "admin.shop.order.col.address", st == null ? null : st.getOriginAddress(), trimToNull(originAddress));
        diff(lines, "admin.v142.shipping.origin.postalCode", st == null ? null : st.getOriginPostalCode(), pc);
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("originContactName", nz(originContactName));
        hidden.put("originContactPhone", nz(originContactPhone));
        hidden.put("originAddress", nz(originAddress));
        hidden.put("originPostalCode", nz(originPostalCode));
        hidden.put("confirmed", "true");
        return confirmModal(model, response, lines, "/admin/shop/shipping/origin", hidden,
                "admin.v142.shipping.origin.save");
    }

    @PostMapping("/admin/shop/shipping/origin")
    @PreAuthorize(EDIT_AUTH)
    public String setOrigin(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) String originContactName,
            @RequestParam(required = false) String originContactPhone,
            @RequestParam(required = false) String originAddress,
            @RequestParam(required = false) String originPostalCode,
            @RequestParam(defaultValue = "false") boolean confirmed,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            saveOrigin(admin, originContactName, originContactPhone, originAddress, originPostalCode);
            closeConfirm(confirmed, response);
            return savedCard("cfg-ship-origin", "savedOrigin", "admin.v142.shipping.origin.saved", model, response);
        }
        try {
            saveOrigin(admin, originContactName, originContactPhone, originAddress, originPostalCode);
            ra.addFlashAttribute("notice", msg.get("admin.v142.shipping.origin.saved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/shipping";
    }

    private void saveOrigin(AdminUserDetails admin, String name, String phone, String address, String postalCode) {
        zones.setOrigin(postalCode, name, phone, address, actorOf(admin));
    }

    /** Biteship 录入实际余额 · 预检：复述录入值与当前预估（AD-13）。 */
    @PostMapping("/admin/shop/shipping/biteship/balance/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmBalance(@RequestParam long amount, HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/shop/shipping";
        }
        if (amount < 0) {
            throw AppException.validation("余额不能为负").code("admin.err.v142.biteship.balanceNegative");
        }
        var ov = requireBalance().overview();
        java.util.List<com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine> lines = new java.util.ArrayList<>();
        lines.add(new com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine(msg.get("admin.v142.biteship.estimate"),
                ov.result().recorded() ? rp(ov.result().estimate()) : msg.get("admin.v142.biteship.notRecorded"),
                rp(amount)));
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("amount", String.valueOf(amount));
        hidden.put("confirmed", "true");
        return confirmModal(model, response, lines, "/admin/shop/shipping/biteship/balance", hidden,
                "admin.v142.biteship.btn.updateBalance");
    }

    @PostMapping("/admin/shop/shipping/biteship/balance")
    @PreAuthorize(EDIT_AUTH)
    public String recordBalance(@AuthenticationPrincipal AdminUserDetails admin, @RequestParam long amount,
            @RequestParam(defaultValue = "false") boolean confirmed,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            requireBalance().record(amount, actorOf(admin));
            closeConfirm(confirmed, response);
            return savedCard("cfg-ship-biteship", "savedBiteship", "admin.v142.biteship.balanceSaved", model, response);
        }
        try {
            requireBalance().record(amount, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.v142.biteship.balanceSaved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/shipping";
    }

    /** Biteship 余额预警阈值 · 预检。 */
    @PostMapping("/admin/shop/shipping/biteship/threshold/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmBalanceThreshold(@RequestParam long balanceAlertThreshold, HxRequest hx, Model model,
            HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/shop/shipping";
        }
        if (balanceAlertThreshold < 0) {
            throw AppException.validation("预警阈值不能为负").code("admin.err.v142.biteship.thresholdNegative");
        }
        var st = settings.findAll().stream().findFirst().orElse(null);
        java.util.List<com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine> lines = new java.util.ArrayList<>();
        diff(lines, "admin.v142.biteship.threshold", st == null ? null : rp(st.getBalanceAlertThreshold()),
                rp(balanceAlertThreshold));
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("balanceAlertThreshold", String.valueOf(balanceAlertThreshold));
        hidden.put("confirmed", "true");
        return confirmModal(model, response, lines, "/admin/shop/shipping/biteship/threshold", hidden,
                "admin.shop.shipping.btn.saveThreshold");
    }

    @PostMapping("/admin/shop/shipping/biteship/threshold")
    @PreAuthorize(EDIT_AUTH)
    public String setBalanceThreshold(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long balanceAlertThreshold, @RequestParam(defaultValue = "false") boolean confirmed,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            requireBalance().setThreshold(balanceAlertThreshold, actorOf(admin));
            closeConfirm(confirmed, response);
            return savedCard("cfg-ship-biteship", "savedBiteship", "admin.v142.biteship.thresholdSaved", model, response);
        }
        try {
            requireBalance().setThreshold(balanceAlertThreshold, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.v142.biteship.thresholdSaved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/shipping";
    }

    private com.tailtopia.shop.logistics.balance.BiteshipBalanceService requireBalance() {
        if (balance == null) {
            throw new IllegalStateException("BiteshipBalanceService 未装配");
        }
        return balance;
    }

    private String confirmModal(Model model, HttpServletResponse response,
            java.util.List<com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine> lines, String action,
            java.util.Map<String, String> hidden, String confirmLabelKey) {
        var cm = new com.tailtopia.admin.shared.web.AdminConfirmModal(msg.get("admin.confirm.title.default"), null, lines,
                lines.isEmpty() ? msg.get("admin.v142.shipping.confirm.noChange") : null, null, null, null, action, hidden,
                msg.get(confirmLabelKey), false);
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
    }

    /** 弹层确认后再提交：回卡的同时关弹层（同运营配置卡，AdminHxEvents.CONFIRM_CLOSE）。 */
    private static void closeConfirm(boolean confirmed, HttpServletResponse response) {
        if (confirmed) {
            response.setHeader("HX-Trigger-After-Swap", com.tailtopia.admin.shared.web.AdminHxEvents.CONFIRM_CLOSE);
        }
    }

    private void diff(java.util.List<com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine> lines, String labelKey,
            String before, String after) {
        if (!java.util.Objects.equals(before, after)) {
            lines.add(new com.tailtopia.admin.shared.web.AdminConfirmModal.DiffLine(msg.get(labelKey),
                    before == null ? "" : before, after == null ? "" : after));
        }
    }

    private static String rp(long v) {
        return com.tailtopia.shop.logistics.balance.BiteshipBalanceService.rp(v);
    }

    private static String rpOrNull(Long v) {
        return v == null ? null : rp(v);
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static long actorOf(AdminUserDetails admin) {
        if (admin == null) {
            throw AppException.unauthorized("需要登录").code("admin.err.common.loginRequired");
        }
        return admin.getAdminAccountId();
    }
}
