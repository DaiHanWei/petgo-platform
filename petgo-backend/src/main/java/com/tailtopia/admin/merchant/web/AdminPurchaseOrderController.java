package com.tailtopia.admin.merchant.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.merchant.service.AdminPurchaseOrderQueryService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderShipment;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import com.tailtopia.merchant.po.service.PurchaseOrderFulfillmentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

/**
 * 门店进货单（商家平台契约第 2、5 节）：列表 + 详情 + 录入包裹 + 标记送达。
 *
 * <p>权限<b>复用 Toko 订单履约的两个码</b>（不新增）：查看 = {@code shop.order_view} 或 {@code shop.order_fulfill}，
 * 写 = {@code shop.order_fulfill}。表达式与 {@code AdminShopOrderController} 逐字一致。
 *
 * <p>🔒 列表不渲染 PII；收货人 / 电话 / 地址只在详情页出现，不进日志、不进审计摘要（审计由领域服务写，只记单号）。
 *
 * <p>🔴 两个 POST 都是整页表单 + PRG，本地 {@code catch AppException} 转 flash：
 * {@code GlobalExceptionHandler} 是 {@code @RestControllerAdvice}，不 catch 就给运营吐 RFC 9457 裸 JSON。
 */
@Controller
public class AdminPurchaseOrderController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.order_view') "
                    + "or hasAuthority('shop.order_fulfill')";
    private static final String FULFILL_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.order_fulfill')";

    static final String LIST_ROUTE = "/admin/merchant/purchase-orders";
    private static final int PAGE_SIZE = 20;

    private final AdminPurchaseOrderQueryService query;
    private final PurchaseOrderFulfillmentService fulfillment;
    private final Messages msg;

    public AdminPurchaseOrderController(AdminPurchaseOrderQueryService query,
            PurchaseOrderFulfillmentService fulfillment, Messages msg) {
        this.query = query;
        this.fulfillment = fulfillment;
        this.msg = msg;
    }

    @GetMapping("/admin/merchant/purchase-orders")
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) {
        PurchaseOrderStatus filter = parseStatus(status);
        String keyword = q == null ? "" : q.trim();
        var p = query.page(filter, keyword, page, PAGE_SIZE);
        model.addAttribute("rows", p.rows());
        model.addAttribute("page", p.page());
        model.addAttribute("totalPages", p.totalPages());
        model.addAttribute("totalElements", p.totalElements());
        model.addAttribute("hasNext", p.hasNext());
        model.addAttribute("counts", query.counts());
        model.addAttribute("statuses", PurchaseOrderStatus.values());
        model.addAttribute("status", filter == null ? "" : filter.name());
        model.addAttribute("q", keyword);
        model.addAttribute("active", "merchantPurchaseOrders");
        return "admin/merchant-purchase-orders";
    }

    @GetMapping("/admin/merchant/purchase-orders/{token}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Model model, RedirectAttributes ra) {
        AdminPurchaseOrderQueryService.Detail d;
        try {
            d = query.detail(token);
        } catch (AppException e) {
            // 单号不存在：回列表提示，而不是给运营一页 RFC 9457 裸 JSON
            ra.addFlashAttribute("error", msg.resolve(e));
            return "redirect:" + LIST_ROUTE;
        }
        PurchaseOrder po = d.order();
        PurchaseOrderStatus st = po.getStatus();
        model.addAttribute("po", po);
        model.addAttribute("shipTo", po.shipTo());
        model.addAttribute("lines", d.lines());
        model.addAttribute("shipments", d.shipments());
        model.addAttribute("canFulfill", has(admin, AdminPermissions.SHOP_ORDER_FULFILL));
        // 领域规则：PAID 首包推到 SHIPPED；SHIPPED / DELIVERED（以及 COMPLETED）只追加。页面按需求只给前三态开表单。
        model.addAttribute("shippable", st == PurchaseOrderStatus.PAID || st == PurchaseOrderStatus.SHIPPED
                || st == PurchaseOrderStatus.DELIVERED);
        model.addAttribute("markable", st == PurchaseOrderStatus.SHIPPED);
        model.addAttribute("active", "merchantPurchaseOrders");
        return "admin/merchant-purchase-order-detail";
    }

    @PostMapping("/admin/merchant/purchase-orders/{token}/shipments")
    @PreAuthorize(FULFILL_AUTH)
    public String addShipment(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) String trackingNo,
            @RequestParam(required = false) String trackingUrl,
            RedirectAttributes ra) {
        try {
            PurchaseOrderShipment s = fulfillment.addShipment(token, carrier, trackingNo, trackingUrl,
                    actorOf(admin));
            // 回显快递公司与单号（非 PII）
            ra.addFlashAttribute("notice", msg.get("admin.flash.merchantPo.shipmentAdded",
                    s.getCarrier(), s.getTrackingNo()));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return redirectDetail(token);
    }

    @PostMapping("/admin/merchant/purchase-orders/{token}/mark-delivered")
    @PreAuthorize(FULFILL_AUTH)
    public String markDelivered(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            RedirectAttributes ra) {
        try {
            fulfillment.markDelivered(token, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.merchantPo.delivered"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return redirectDetail(token);
    }

    // ---------- 内部 ----------

    private static String redirectDetail(String token) {
        return "redirect:" + LIST_ROUTE + "/" + UriUtils.encodePathSegment(token, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 写端点都在 FULFILL_AUTH 之后，principal 必然存在；仍兜一下，免得 NPE 变 500。 */
    private static long actorOf(AdminUserDetails admin) {
        if (admin == null) {
            throw AppException.forbidden("需要登录后台账号").code("admin.err.po.actorRequired");
        }
        return admin.getAdminAccountId();
    }

    /** 未知筛选值当作「不筛选」，不报错 —— 运营手改 URL 不该看到 500。 */
    private static PurchaseOrderStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (PurchaseOrderStatus s : PurchaseOrderStatus.values()) {
            if (s.name().equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        return null;
    }

    private static boolean has(AdminUserDetails admin, String permission) {
        if (admin == null) {
            return false;
        }
        for (GrantedAuthority a : admin.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || permission.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
