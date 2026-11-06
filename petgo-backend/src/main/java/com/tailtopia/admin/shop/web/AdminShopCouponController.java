package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminConfirmModals;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shop.dto.AdminCouponForm;
import com.tailtopia.admin.shop.service.AdminShopCouponService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponAdminService.AdminCouponStatus;
import com.tailtopia.shop.coupon.service.CouponAdminService.CouponRisk;
import com.tailtopia.shop.coupon.service.CouponAdminService.CouponRiskNotAcknowledgedException;
import com.tailtopia.shop.domain.ProductCategory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 优惠券页（V1.4.2 shop-admin Story 2.2 · AB-24 · AD-2 / AD-6；UI A1~A10）。
 *
 * <p>模板 B 列表 + 右侧抽屉（新建 / 编辑 / 详情），抽屉取数复用 {@code GET /admin/shop/coupons}
 * （{@code ?create=1} / {@code ?edit=<token>} / {@code ?detail=<token>} + HX-Request），同 banner 页。
 * 🔴 业务规则全在电商线 {@link CouponAdminService}；这里只做页面编排。权限：查看 {@code shop.coupon_view}，
 * 写 / 导出 {@code shop.coupon_edit}（O-7，不预授）。
 *
 * <p>二次确认一律 confirm-modal：防误配（A7，O-14 起「面额 ≥ 门槛」已改硬拒，不在确认项）、停用（A9 单选继续可用 / 作废）。
 */
@Controller
public class AdminShopCouponController {

    static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.coupon_view') or hasAuthority('shop.coupon_edit')";
    static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('shop.coupon_edit')";
    private static final String DRAWER_BODY = "#shop-coupon-drawer-body";
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd")
            .withZone(com.tailtopia.shared.schedule.ScheduleWindow.WIB);
    private static final DateTimeFormatter MD_DATE = DateTimeFormatter.ofPattern("MM-dd");

    private final CouponAdminService coupons;
    private final AdminShopCouponService page;
    private final Messages msg;
    private final Clock clock;

    @Autowired
    public AdminShopCouponController(CouponAdminService coupons, AdminShopCouponService page, Messages msg) {
        this(coupons, page, msg, Clock.systemUTC());
    }

    AdminShopCouponController(CouponAdminService coupons, AdminShopCouponService page, Messages msg, Clock clock) {
        this.coupons = coupons;
        this.page = page;
        this.msg = msg;
        this.clock = clock;
    }

    @GetMapping("/admin/shop/coupons")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "acquire", required = false) String acquire,
            @RequestParam(value = "create", required = false) String create,
            @RequestParam(value = "edit", required = false) String edit,
            @RequestParam(value = "detail", required = false) String detail,
            @RequestParam(value = "focus", required = false) String focus,
            HxRequest hx, Model model) {
        model.addAttribute("active", "shopCoupons");
        boolean canEdit = has(admin, AdminPermissions.SHOP_COUPON_EDIT);
        model.addAttribute("canEdit", canEdit);
        if (hx.isHtmx() && (create != null || edit != null) && !canEdit) {
            // 只读账号拿到旧的新建 / 编辑链接：明确 403，不把列表塞进抽屉
            throw new org.springframework.security.access.AccessDeniedException("shop.coupon_edit");
        }
        if (hx.isHtmx() && create != null) {
            return form(new AdminCouponForm(), null, null, model);
        }
        if (hx.isHtmx() && edit != null) {
            ShopCoupon c = coupons.require(edit);
            return form(AdminShopCouponService.toForm(c), c, null, model);
        }
        if (hx.isHtmx() && detail != null) {
            return detail(coupons.require(detail), canEdit, model);
        }
        populateList(model, enumOrNull(AdminCouponStatus.class, status), enumOrNull(CouponKind.class, kind),
                enumOrNull(CouponAcquireType.class, acquire));
        model.addAttribute("focus", focus);
        return hx.isHtmx() ? "admin/fragments/shop-coupons-list :: rows" : "admin/shop-coupons";
    }

    // ---------- 写入 ----------

    /** 新建（A3~A5）。防误配未确认 → A7 弹层；校验失败 → 抽屉内 .banner.err（A6），表单保留。 */
    @PostMapping("/admin/shop/coupons")
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin, @ModelAttribute("form") AdminCouponForm form,
            @RequestParam(value = "acknowledgeRisk", defaultValue = "false") boolean acknowledgeRisk,
            HttpServletRequest request, Model model, HttpServletResponse response) {
        try {
            ShopCoupon saved = page.create(form, acknowledgeRisk, admin.getAdminAccountId());
            return done(msg.get("admin.v142.coupon.flash.created"), saved.getPublicToken(), admin, model, response);
        } catch (CouponRiskNotAcknowledgedException e) {
            return riskModal(e.risks(), "/admin/shop/coupons", request, model, response);
        } catch (AppException e) {
            if (acknowledgeRisk) {
                throw e; // 从 A7 弹层重提：错误落弹层内错误槽（advice 按 HX-Target 回填），不把整张表单塞进弹层
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            return form(form, null, msg.resolve(e), model);
        }
    }

    /** 编辑（A8）：未开始的券改全部；已开始的只改发放总量（服务端判定，只增不减）。 */
    @PostMapping("/admin/shop/coupons/{token}")
    @PreAuthorize(EDIT_AUTH)
    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @ModelAttribute("form") AdminCouponForm form,
            @RequestParam(value = "acknowledgeRisk", defaultValue = "false") boolean acknowledgeRisk,
            @RequestParam(value = "onlyTotal", defaultValue = "false") boolean onlyTotal,
            HttpServletRequest request, Model model, HttpServletResponse response) {
        ShopCoupon current = coupons.require(token);
        try {
            // 按「打开的是哪种抽屉」分流，不按提交时刻重判：未开始的整表编辑跨过发放开始再提交，
            // 由 CouponAdminService.update 拒绝（startedReadOnly），不会悄悄只保存总量。
            if (onlyTotal) {
                coupons.raiseTotalQuantity(token, form.getTotalQuantity(), acknowledgeRisk, admin.getAdminAccountId());
            } else {
                page.update(token, form, acknowledgeRisk, admin.getAdminAccountId());
            }
            return done(msg.get("admin.v142.coupon.flash.updated"), token, admin, model, response);
        } catch (CouponRiskNotAcknowledgedException e) {
            return riskModal(e.risks(), "/admin/shop/coupons/" + enc(token), request, model, response);
        } catch (AppException e) {
            if (acknowledgeRisk) {
                throw e;
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            AdminCouponForm back = started(current) ? AdminShopCouponService.toForm(current) : form;
            if (started(current)) {
                back.setTotalQuantity(form.getTotalQuantity());
            }
            return form(back, current, msg.resolve(e), model);
        }
    }

    /** 停用确认（A9）：单选「继续可用 / 作废」，复述已领未用与被待支付订单占用的张数。 */
    @PostMapping("/admin/shop/coupons/{token}/stop/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmStop(@PathVariable String token, Model model, HttpServletResponse response) {
        ShopCoupon c = coupons.require(token);
        AdminShopCouponService.StopCounts n = page.stopCounts(c);
        AdminConfirmModal cm = AdminConfirmModal.simple(msg.get("admin.v142.coupon.stop.title", c.getNameId()),
                        msg.get("admin.v142.coupon.stop.body", n.available()),
                        "/admin/shop/coupons/" + enc(token) + "/stop", Map.of(),
                        msg.get("admin.v142.coupon.btn.stop"), true)
                .withChoices(new AdminConfirmModal.ChoiceGroup("voidUnused", null, List.of(
                        new AdminConfirmModal.Choice("false", msg.get("admin.v142.coupon.stop.keep"),
                                msg.get("admin.v142.coupon.stop.keepHint"), true),
                        new AdminConfirmModal.Choice("true", msg.get("admin.v142.coupon.stop.void"),
                                msg.get("admin.v142.coupon.stop.voidHint", n.locked()), false))))
                // 成功响应只有 oob（整表 + toast），主体为空：主 swap 落进确认宿主（关弹层时本就清空），不碰列表
                .withSuccessTarget(AdminConfirmModals.HOST, "innerHTML");
        return AdminConfirmModals.render(model, response, cm);
    }

    @PostMapping("/admin/shop/coupons/{token}/stop")
    @PreAuthorize(EDIT_AUTH)
    public String stop(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "voidUnused", defaultValue = "false") boolean voidUnused, Model model,
            HttpServletResponse response) {
        coupons.stop(token, voidUnused, admin.getAdminAccountId());
        return done(msg.get("admin.v142.coupon.flash.stopped"), token, admin, model, response);
    }

    /** 导出批量一次性码 CSV（A10）：要 {@code shop.coupon_edit}；审计只记数量，🔒 不记码明文。 */
    @GetMapping("/admin/shop/coupons/{token}/codes.csv")
    @PreAuthorize(EDIT_AUTH)
    public ResponseEntity<byte[]> exportCodes(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token) {
        List<CouponAdminService.CodeExportRow> rows = coupons.exportSingleUseCodes(token, admin.getAdminAccountId());
        StringBuilder csv = new StringBuilder("﻿code,redeemed,redeemed_at\n");
        for (CouponAdminService.CodeExportRow r : rows) {
            csv.append(r.code()).append(',').append(r.redeemed() ? "Y" : "N").append(',')
                    .append(r.redeemedAt() == null ? "" : r.redeemedAt().toString()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"coupon-codes-" + token + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 内部 ----------

    private boolean started(ShopCoupon c) {
        return !clock.instant().isBefore(c.getIssueStartAt());
    }

    private String form(AdminCouponForm form, ShopCoupon current, String error, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("couponToken", current == null ? null : current.getPublicToken());
        model.addAttribute("started", current != null && started(current));
        model.addAttribute("current", current == null ? null : row(current, clock.instant()));
        model.addAttribute("codeMode", current == null ? form.getCodeMode() : page.codeModeOf(current));
        model.addAttribute("formError", error);
        model.addAttribute("categories", ProductCategory.values());
        return "admin/fragments/drawer-shop-coupon :: form";
    }

    private String detail(ShopCoupon c, boolean canEdit, Model model) {
        model.addAttribute("c", row(c, clock.instant()));
        model.addAttribute("stats", coupons.detailStats(c.getPublicToken()));
        model.addAttribute("codeMode", page.codeModeOf(c));
        // 🔒 通用码明文只给有编辑权的人（与导出同口径）
        model.addAttribute("universalCode", canEdit ? page.universalCodeOf(c) : null);
        return "admin/fragments/drawer-shop-coupon :: detail";
    }

    /** A7：多条风险用黄色警示（.cm-banner-warn）；原表单字段全部带上 + acknowledgeRisk=true 重提。 */
    private String riskModal(List<CouponRisk> risks, String action, HttpServletRequest request, Model model,
            HttpServletResponse response) {
        Map<String, String> hidden = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (!k.equals("acknowledgeRisk") && !k.startsWith("_") && v.length > 0) {
                hidden.put(k, String.join(",", v));
            }
        });
        hidden.put("acknowledgeRisk", "true");
        List<String> warns = new ArrayList<>();
        for (CouponRisk r : risks) {
            warns.add(msg.get("admin.v142.coupon.risk." + r.name()));
        }
        AdminConfirmModal cm = new AdminConfirmModal(msg.get("admin.v142.coupon.risk.title"), String.join(" · ", warns),
                List.of(), msg.get("admin.v142.coupon.risk.note"), null, null, null, action, hidden,
                msg.get("admin.v142.coupon.risk.confirm"), false, true)
                .withSuccessTarget(DRAWER_BODY, "innerHTML");
        return AdminConfirmModals.render(model, response, cm);
    }

    private String done(String message, String focusToken, AdminUserDetails admin, Model model,
            HttpServletResponse response) {
        model.addAttribute("canEdit", has(admin, AdminPermissions.SHOP_COUPON_EDIT));
        populateList(model, null, null, null);
        model.addAttribute("focus", focusToken);
        model.addAttribute("message", message);
        AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
        return "admin/fragments/shop-coupons-list :: done";
    }

    private void populateList(Model model, AdminCouponStatus status, CouponKind kind, CouponAcquireType acquire) {
        Instant now = clock.instant();
        List<CouponAdminService.AdminCouponRow> all = coupons.listForAdmin(null, null, null);
        model.addAttribute("summary", page.summary(all, now));
        Map<Long, Long> used = page.usedByCoupon();
        Map<String, Long> usedByToken = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CouponAdminService.AdminCouponRow r : all) {
            ShopCoupon c = r.coupon();
            if ((status != null && r.status() != status) || (kind != null && c.getKind() != kind)
                    || (acquire != null && c.getAcquireType() != acquire)) {
                continue;
            }
            rows.add(row(c, now));
            usedByToken.put(c.getPublicToken(), used.getOrDefault(c.getId(), 0L));
        }
        model.addAttribute("usedByToken", usedByToken);
        model.addAttribute("rows", rows);
        model.addAttribute("anyCoupon", !all.isEmpty());
        model.addAttribute("filterStatus", status == null ? "" : status.name());
        model.addAttribute("filterKind", kind == null ? "" : kind.name());
        model.addAttribute("filterAcquire", acquire == null ? "" : acquire.name());
        model.addAttribute("statuses", AdminCouponStatus.values());
        model.addAttribute("kinds", CouponKind.values());
        model.addAttribute("acquires", CouponAcquireType.values());
    }

    /** 一行展示数据（列表 / 详情共用）。日期按 WIB，结束日含当天。 */
    private Map<String, Object> row(ShopCoupon c, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", c.getPublicToken());
        m.put("name", c.getNameId());
        m.put("nameEn", c.getNameEn());
        m.put("kind", c.getKind().name());
        m.put("acquire", c.getAcquireType().name());
        m.put("amount", c.getAmount());
        m.put("minSpend", c.getMinSpend());
        m.put("scopeType", c.getScopeType().name());
        m.put("scopeValues", c.getScopeType() == CouponScopeType.ALL ? List.of()
                : c.getScopeType() == CouponScopeType.CATEGORY
                        ? c.getScopeValues().stream().map(v -> msg.get("admin.v130.shopProducts.category." + v)).toList()
                        : c.getScopeValues());
        m.put("issuePeriod", MD.format(c.getIssueStartAt()) + " – "
                + AdminShopCouponService.lastDayOf(c.getIssueEndAt()).format(MD_DATE));
        m.put("validDays", c.getValidDays());
        m.put("validPeriod", c.getValidStartAt() == null ? null : MD.format(c.getValidStartAt()) + " – "
                + AdminShopCouponService.lastDayOf(c.getValidEndAt()).format(MD_DATE));
        m.put("issued", c.getIssuedCount());
        m.put("total", c.getTotalQuantity());
        m.put("perUser", c.getPerUserLimit());
        AdminCouponStatus st = CouponAdminService.statusOf(c, now);
        m.put("status", st.name());
        m.put("editable", st == AdminCouponStatus.NOT_STARTED || st == AdminCouponStatus.RUNNING);
        return m;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String enc(String token) {
        return org.springframework.web.util.UriUtils.encodePathSegment(token, StandardCharsets.UTF_8);
    }

    private static boolean has(AdminUserDetails admin, String permission) {
        if (admin == null) {
            return false;
        }
        for (org.springframework.security.core.GrantedAuthority a : admin.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || permission.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
