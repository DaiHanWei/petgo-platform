package com.tailtopia.admin.gift.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.gift.dto.GiftCatalogForm;
import com.tailtopia.admin.gift.service.AdminGiftCatalogService;
import com.tailtopia.admin.gift.service.AdminGiftCatalogService.Filter;
import com.tailtopia.admin.gift.service.AdminGiftCatalogService.QrisMinChange;
import com.tailtopia.admin.gift.service.GiftCatalogRules;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.tagicon.AdminTagIconService;
import com.tailtopia.admin.tagicon.IconPurpose;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 礼物目录（V1.4.0 ③ gift Story 3.1 · AB-35，UI 稿 L1 ~ L7）。页面形态照用户标签页（模板 B）：
 * 列表 + 480 抽屉（新建 / 编辑 / 上下线）+ 顶部「送礼 QRIS 最低金额」配置行（服务端确认弹层）。
 *
 * <h2>🛡 双权限码</h2>
 * 查看 {@code gift.catalog_view} / 编辑 {@code gift.catalog_edit}（新码不预授，超管隐式拥有）。
 * ⚠️ {@link #VIEW_AUTH} 的码集与 {@code AdminPageCatalog} 里 {@code gift-catalog} 页的 {@code navCodes} <b>逐字同集</b>；
 * 页面 {@code sec:authorize} / {@code #authorization.expression} 与 {@link #EDIT_AUTH} 逐字一致。
 *
 * <p>后台按自增 id 寻址礼物（与用户标签同口径，需登录、不对 App 暴露）；App 只见 {@code public_token}。
 * 下线用浏览器原生 confirm（{@code data-confirm}），QRIS 最低金额用服务端确认弹层（decision-log ③）。
 */
@Controller
public class AdminGiftCatalogController {

    static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.GIFT_CATALOG_VIEW
            + "') or hasAuthority('" + AdminPermissions.GIFT_CATALOG_EDIT + "')";
    static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.GIFT_CATALOG_EDIT + "')";

    private static final String BASE = "/admin/gift-catalog";

    private final AdminGiftCatalogService service;
    private final AdminTagIconService icons;
    private final Messages msg;

    public AdminGiftCatalogController(AdminGiftCatalogService service, AdminTagIconService icons, Messages msg) {
        this.service = service;
        this.icons = icons;
        this.msg = msg;
    }

    // ——————————————————— 列表 ———————————————————

    /**
     * 列表页（AC3）。筛选三项宽松解析（不认识的值当不限）；htmx 只回表格（摘要条随 oob 一并换），
     * 隐形刷新监听 {@code hx-include} 筛选表单，抽屉内处置后按原筛选重拉。
     */
    @GetMapping(BASE)
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "price", required = false) String price,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        Filter filter = new Filter(parsePrice(price), parseEnum(GiftKind.class, kind),
                parseEnum(GiftCatalogStatus.class, status));
        var page = service.page(filter);
        model.addAttribute("active", "gift-catalog");
        model.addAttribute("gifts", page.rows());
        model.addAttribute("summary", page.summary());
        model.addAttribute("priceOptions", page.priceOptions());
        model.addAttribute("catalogEmpty", page.catalogEmpty());
        model.addAttribute("fPrice", filter.price());
        model.addAttribute("fKind", filter.kind() == null ? null : filter.kind().name());
        model.addAttribute("fStatus", filter.status() == null ? null : filter.status().name());
        if (hx.isHtmx()) {
            return "admin/fragments/gift-catalog-list :: rows(oob=true)";
        }
        model.addAttribute("qrisMin", service.qrisMin());
        model.addAttribute("kinds", List.of(GiftKind.NORMAL.name(), GiftKind.EXCLUSIVE.name()));
        model.addAttribute("statuses", List.of(GiftCatalogStatus.ONLINE.name(), GiftCatalogStatus.OFFLINE.name()));
        model.addAttribute("open", open);
        model.addAttribute("openCreate", create != null);
        return "admin/gift-catalog";
    }

    // ——————————————————— 抽屉 ———————————————————

    /** 新建抽屉（AC4.1）。非 htmx 直达 → 回列表并自动开新建表单。 */
    @GetMapping(BASE + "/new/drawer")
    @PreAuthorize(EDIT_AUTH)
    public String newDrawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:" + BASE + "?create=1";
        }
        model.addAttribute("active", "gift-catalog");
        populateForm(model);
        return "admin/fragments/drawer-gift :: createForm";
    }

    /** 礼物详情抽屉（AC6.1）。非 htmx 直达 → 回列表并自动开该抽屉；不存在 → 404。 */
    @GetMapping(BASE + "/{id}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:" + BASE + "?open=" + id;
        }
        populateDrawer(id, model);
        return "admin/fragments/drawer-gift :: drawer";
    }

    private void populateForm(Model model) {
        // AC5.3：规格说明常驻在上传控件旁（礼物版文案，三语走 MessageSource）。
        model.addAttribute("iconSpecCreate", icons.specText(IconPurpose.GIFT, "create"));
        model.addAttribute("iconSpecEdit", icons.specText(IconPurpose.GIFT, "edit"));
        model.addAttribute("minLevel", 1);
        model.addAttribute("maxLevel", GiftCatalogRules.MAX_LEVEL_FALLBACK);
        model.addAttribute("minPrice", GiftCatalogRules.MIN_PRICE);
        model.addAttribute("maxPrice", GiftCatalogRules.MAX_PRICE);
        model.addAttribute("maxSortOrder", GiftCatalogRules.SORT_ORDER_MAX);
    }

    private void populateDrawer(long id, Model model) {
        var d = service.detail(id);
        model.addAttribute("active", "gift-catalog");
        model.addAttribute("g", d.gift());
        model.addAttribute("received", d.received());
        model.addAttribute("sent", d.sent());
        populateForm(model);
    }

    /** 抽屉内处置成功统一响应：抽屉体重渲染 + toast + 列表按原筛选刷新。 */
    private String afterAction(long id, String toast, Model model, HttpServletResponse response) {
        populateDrawer(id, model);
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminHxEvents.GIFT_CATALOG_LIST_REFRESH);
        return "admin/fragments/drawer-gift :: afterAction";
    }

    // ——————————————————— 礼物写入 ———————————————————

    /** 新建礼物（AC4）。htmx 成功：toast + 关抽屉 + 列表刷新；422 留在抽屉的行内错误槽。 */
    @PostMapping(BASE)
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam(value = "nameEn", required = false) String nameEn,
            @RequestParam(value = "nameId", required = false) String nameId,
            @RequestParam(value = "price", required = false) String price,
            @RequestParam(value = "forCat", defaultValue = "false") boolean forCat,
            @RequestParam(value = "forDog", defaultValue = "false") boolean forDog,
            @RequestParam(value = "forOther", defaultValue = "false") boolean forOther,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "minLevel", required = false) String minLevel,
            @RequestParam(value = "sortOrder", required = false) String sortOrder,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        GiftCatalogForm form = new GiftCatalogForm(nameEn, nameId, price, forCat, forDog, forOther, kind, minLevel,
                sortOrder);
        if (hx.isHtmx()) {
            service.create(admin.getAdminAccountId(), form, iconFile);
            AdminFragmentResponses.trigger(response, AdminHxEvents.GIFT_CATALOG_LIST_REFRESH,
                    AdminHxEvents.DRAWER_CLOSE);
            model.addAttribute("toast", msg.get("admin.flash.gift.created"));
            return "admin/fragments/drawer-gift :: created";
        }
        try {
            service.create(admin.getAdminAccountId(), form, iconFile);
            flash.addFlashAttribute("notice", msg.get("admin.flash.gift.created"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    /** 编辑礼物（AC6.3）。不选文件 = 保留原图标；无真变化不写审计，toast 照样「已保存」。 */
    @PostMapping(BASE + "/{id}/edit")
    @PreAuthorize(EDIT_AUTH)
    public String edit(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam(value = "nameEn", required = false) String nameEn,
            @RequestParam(value = "nameId", required = false) String nameId,
            @RequestParam(value = "price", required = false) String price,
            @RequestParam(value = "forCat", defaultValue = "false") boolean forCat,
            @RequestParam(value = "forDog", defaultValue = "false") boolean forDog,
            @RequestParam(value = "forOther", defaultValue = "false") boolean forOther,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "minLevel", required = false) String minLevel,
            @RequestParam(value = "sortOrder", required = false) String sortOrder,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        GiftCatalogForm form = new GiftCatalogForm(nameEn, nameId, price, forCat, forDog, forOther, kind, minLevel,
                sortOrder);
        if (hx.isHtmx()) {
            service.update(admin.getAdminAccountId(), id, form, iconFile);
            return afterAction(id, msg.get("admin.flash.gift.saved"), model, response);
        }
        try {
            service.update(admin.getAdminAccountId(), id, form, iconFile);
            flash.addFlashAttribute("notice", msg.get("admin.flash.gift.saved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    /** 下线 / 重新上线（AC7）。没有删除端点（只下线不物理删除，AD-1）。 */
    @PostMapping(BASE + "/{id}/status")
    @PreAuthorize(EDIT_AUTH)
    public String status(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("online") boolean online,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        String toast = online ? msg.get("admin.flash.gift.onlined") : msg.get("admin.flash.gift.offlined");
        if (hx.isHtmx()) {
            service.setOnline(admin.getAdminAccountId(), id, online);
            return afterAction(id, toast, model, response);
        }
        try {
            service.setOnline(admin.getAdminAccountId(), id, online);
            flash.addFlashAttribute("notice", toast);
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    // ——————————————————— 送礼 QRIS 最低金额 ———————————————————

    /**
     * 预检（AC8.2）：<b>只校验不写库</b>，通过则回服务端确认弹层到 {@code #confirm-host}（照 App 版本配置那一对）。
     * 校验不过 → 422 落在配置行自己的错误槽 {@code #gift-qris-err}，不弹层。非 htmx 没有弹层可回，重定向回页面。
     */
    @PostMapping(BASE + "/qris-min/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmQrisMin(@RequestParam(value = "amount", required = false) String amount,
            HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + BASE;
        }
        QrisMinChange change = service.previewQrisMin(amount);
        model.addAttribute("cm", qrisConfirmModal(change));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return "admin/fragments/confirm-modal :: modal";
    }

    /** 弹层内容（UI L6）：黄色说明条 + 一行新旧值（旧值是库里当前值）+ 品牌色确认钮。 */
    private AdminConfirmModal qrisConfirmModal(QrisMinChange change) {
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("amount", String.valueOf(change.newValue()));
        return new AdminConfirmModal(
                msg.get("admin.v140.gift.qris.confirm.title"),
                msg.get("admin.v140.gift.qris.confirm.banner"),
                List.of(new AdminConfirmModal.DiffLine(msg.get("admin.v140.gift.qris.confirm.diff"),
                        rp(change.oldValue()), rp(change.newValue()))),
                null, null, null, null, BASE + "/qris-min", hidden,
                msg.get("admin.v140.gift.qris.confirm.btn"), false, true);
    }

    /**
     * 保存（AC8.5）。htmx 成功：配置行原位刷新（{@code HX-Retarget #gift-qris-line}）+ toast，swap 后发
     * {@code admin:confirm-close} 收弹层；422 / 403 回弹层内的 {@code #confirm-modal-err}，弹层不消失。
     */
    @PostMapping(BASE + "/qris-min")
    @PreAuthorize(EDIT_AUTH)
    public String updateQrisMin(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "amount", required = false) String amount,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            QrisMinChange change = service.updateQrisMin(admin.getAdminAccountId(), amount);
            // 在 swap 之后再收弹层：先收的话发请求的表单已不在 DOM 里
            response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
            response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#gift-qris-line");
            response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
            model.addAttribute("qrisMin", change.newValue());
            model.addAttribute("toast", msg.get("admin.flash.gift.qrisMinSaved", grouped(change.newValue())));
            return "admin/fragments/gift-qris-line :: saved";
        }
        try {
            QrisMinChange change = service.updateQrisMin(admin.getAdminAccountId(), amount);
            flash.addFlashAttribute("notice", msg.get("admin.flash.gift.qrisMinSaved", grouped(change.newValue())));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    // ——————————————————— 工具 ———————————————————

    /** 后台金额惯例：千分位逗号（与语言无关）。 */
    static String grouped(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    static String rp(long v) {
        return "Rp" + grouped(v);
    }

    /** 价格筛选宽松解析：不是正整数就当不限。 */
    private static Long parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 宽松解析（照 {@code AdminPaymentController.parseEnum}）：不认识的值当不限。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
