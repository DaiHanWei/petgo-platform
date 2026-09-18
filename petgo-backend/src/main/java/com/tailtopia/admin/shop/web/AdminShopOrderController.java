package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shop.dto.AdminShopOrderRow;
import com.tailtopia.admin.shop.service.AdminShopOrderExceptionService;
import com.tailtopia.admin.shop.service.AdminShopOrderService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shared.i18n.Messages;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * 电商订单列表与发货（Story 4.2 发货 AB-11B；Story 4.3 补筛选与电话搜索 AB-11A）。模块 11。
 *
 * <p>🔒 <b>列表不渲染任何 PII</b>：收件人姓名 / 电话 / 详细地址只在详情页出现。
 * 一屏几十行 PII 是最容易被整屏截图外传的形态。
 *
 * <p>🔴 <b>三个 POST 端点全部本地 {@code catch AppException}</b>：{@code GlobalExceptionHandler}
 * 是 {@code @RestControllerAdvice}，不 catch 就给运营吐 RFC 9457 裸 JSON（仓库既有 17 个
 * admin 控制器的统一处置）。
 */
@Controller
public class AdminShopOrderController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.order_view') "
                    + "or hasAuthority('shop.order_fulfill')";
    private static final String FULFILL_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.order_fulfill')";

    private static final int PAGE_SIZE = 100;

    /** V1.3.0 Story 10.2 AC1：列表每页 20（订单号精确查与电话搜索是「一把抓」，不分页）。 */
    private static final int QUEUE_PAGE_SIZE = 20;

    /**
     * 🔴 非 htmx 处置后回<b>列表</b>而不是 {@code /{token}} —— 整页详情已退役（AC3），
     * 往那里 302 等于把旧地址永久续上（D-23 明确不做旧地址跳转）。
     */
    private static final String REDIRECT_LIST = "redirect:/admin/shop/orders";

    private final AdminShopOrderService adminOrders;
    private final ShopOrderFulfillmentService fulfillment;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    /** V1.3.0 Story 10.2 AC1：「有异常挂起」标的数据源 —— 复用 A8 的候选集，不另起一套判据。 */
    private final AdminShopOrderExceptionService exceptions;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminShopOrderController(AdminShopOrderService adminOrders,
            ShopOrderFulfillmentService fulfillment, ShopOrderRepository orders,
            ShopOrderLineRepository orderLines, AdminShopOrderExceptionService exceptions,
            Messages msg) {
        this.adminOrders = adminOrders;
        this.fulfillment = fulfillment;
        this.orders = orders;
        this.orderLines = orderLines;
        this.exceptions = exceptions;
        this.msg = msg;
    }

    // ---------- 列表 ----------

    /**
     * 订单列表（Story 4.2 基础列表 · Story 4.3 补筛选与电话搜索，AB-11A）。
     *
     * <p>🔒 <b>按电话搜索是独立能力</b>：独立权限位 {@code shop.order_phone_search}，
     * 每次使用写审计。无该权限时<b>连输入框都不渲染</b>，服务端另有独立判定
     * （沿用 Story 1.3 进货价的处置：模板隐藏可通过看源码绕过）。
     *
     * <p>⚠️ 脱敏口径与审计粒度待 <b>OQ-41</b> 拍板（Legal，安全攸关）——
     * 本 story 只落<b>权限位与审计记录的骨架</b>，脱敏规则留配置位，不写死。
     */
    @GetMapping("/admin/shop/orders")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderToken,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) String phone,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) String open,
            HxRequest hx, Model model) {
        boolean canPhoneSearch = has(admin, AdminPermissions.SHOP_ORDER_PHONE_SEARCH);
        ShopOrderStatus filter = parseStatus(status);
        List<ShopOrder> found;
        int current = Math.max(page, 0);
        boolean hasNext = false;

        if (orderToken != null && !orderToken.isBlank()) {
            // 订单号精确查（token 本就不可枚举，无需额外权限）
            found = orders.findByPublicToken(orderToken.trim()).map(List::of).orElse(List.of());
            current = 0;
        } else if (phone != null && !phone.isBlank()) {
            // 🔒 服务端独立再判一次——页面不渲染入口只是第一层
            if (!canPhoneSearch) {
                throw AppException.forbidden("按电话搜索订单需要「按电话搜索」权限（NFR-11）").code("admin.err.shopOrder.phoneSearchForbidden");
            }
            try {
                found = adminOrders.searchByPhone(phone, actorOf(admin), PAGE_SIZE);
            } catch (AppException e) {
                model.addAttribute("error", e.getMessage());
                found = List.of();
            }
            current = 0;
        } else {
            var p = adminOrders.page(filter, startOf(from), endOf(to), current, QUEUE_PAGE_SIZE);
            found = p.rows();
            current = p.page();
            hasNext = p.hasNext();
        }

        List<AdminShopOrderRow> rows = found.stream().map(this::toRow).toList();
        model.addAttribute("rows", rows);
        // AC1：有异常挂起的行打「异常」标并链到 A8。
        // 🔴 只判**本页这几条**（见 AdminShopOrderExceptionService#flagged）：拿 A8 的候选集来求交是错的
        //    —— 那份是「最旧的 N 条」，而本列表是「最新 20 条」，待发货一多两边零交集，标就永远不出现。
        model.addAttribute("exceptionTokens",
                exceptions.flagged(rows.stream().map(AdminShopOrderRow::orderToken).toList()));
        model.addAttribute("summary", adminOrders.summary(startOf(from), endOf(to)));
        model.addAttribute("statuses", ShopOrderStatus.values());
        model.addAttribute("status", filter == null ? "" : filter.name());
        model.addAttribute("orderToken", orderToken == null ? "" : orderToken);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("page", current);
        model.addAttribute("hasNext", hasNext);
        // 🔒 回显的是「是否搜过」，不回显号码本身 —— 回显会把 PII 带进 URL、浏览器历史与访问日志
        model.addAttribute("phoneSearched", phone != null && !phone.isBlank());
        model.addAttribute("canPhoneSearch", canPhoneSearch);
        model.addAttribute("canFulfill", has(admin, AdminPermissions.SHOP_ORDER_FULFILL));
        model.addAttribute("open", open == null || open.isBlank() ? null : open.trim());
        model.addAttribute("active", "shopOrders");
        return hx.isHtmx() ? "admin/fragments/shop-orders-list :: rows(true)" : "admin/shop-orders";
    }

    /** 日期按 UTC 起止换算（全库时间戳一律 UTC，CLAUDE.md 命名映射链）。 */
    private static Instant startOf(LocalDate d) {
        return d == null ? null : d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** 到期日<b>含当天</b>：用次日零点做开区间上界，否则「查 8 月 18 日」会漏掉当天的单。 */
    private static Instant endOf(LocalDate d) {
        return d == null ? null : d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Long actorOf(AdminUserDetails admin) {
        return admin == null ? null : admin.getAdminAccountId();
    }

    // ---------- 详情 → V1.3.0 Story 10.2：B15 抽屉 ----------

    /**
     * 订单抽屉 fragment（Story 10.2 AC2）。
     *
     * <p>🔴 <b>整页详情已退役</b>（AC3）：非 htmx 请求一律 404，<b>不做旧地址跳转</b>（D-23）。
     * 路径复用同一 mapping 而不是新开 {@code /{token}/drawer} —— AB-19A「零新端点」
     * （与 Story 10.1 的 A7 右栏同款处置）。
     *
     * <p>🔒 这里是收件人 PII（姓名 / 电话 / 详细地址）的<b>唯一出口</b>：列表不带、日志不记、审计摘要不记。
     */
    @GetMapping("/admin/shop/orders/{token}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            throw AppException.notFound("该页面已并入订单列表的详情抽屉")
                    .code("admin.err.common.pageRetired");
        }
        populateDrawer(admin, token, model);
        return "admin/fragments/drawer-shop-order :: drawer";
    }

    private ShopOrder populateDrawer(AdminUserDetails admin, String token, Model model) {
        ShopOrder order = orders.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));

        model.addAttribute("order", order);
        model.addAttribute("shipTo", order.shipTo());
        model.addAttribute("lines", orderLines.findByOrderIdOrderByIdAsc(order.getId()));
        model.addAttribute("packages", fulfillment.shipmentsOf(order.getId()));
        model.addAttribute("carriers", Carrier.values());
        model.addAttribute("canFulfill", has(admin, AdminPermissions.SHOP_ORDER_FULFILL));
        model.addAttribute("shippable", order.getStatus() == ShopOrderStatus.PENDING_SHIPMENT
                || order.getStatus() == ShopOrderStatus.SHIPPED);
        model.addAttribute("markable", order.getStatus() == ShopOrderStatus.SHIPPED);
        model.addAttribute("active", "shopOrders");
        return order;
    }

    // ---------- 发货（AB-11B） ----------

    @PostMapping("/admin/shop/orders/{token}/ship")
    @PreAuthorize(FULFILL_AUTH)
    public String ship(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token,
            @RequestParam String carrier,
            @RequestParam String trackingNo,
            @RequestParam(required = false) Long carrierCost,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            Shipment s = adminOrders.ship(token, carrier, trackingNo, carrierCost,
                    admin == null ? null : admin.getAdminAccountId());
            return done(admin, token, msg.get("admin.flash.shopOrder.shipped",
                    s.getCarrier().displayName(), s.getTrackingNo()), model);
        }
        try {
            Shipment s = adminOrders.ship(token, carrier, trackingNo, carrierCost,
                    admin == null ? null : admin.getAdminAccountId());
            // 🔒 回显单号（非 PII）；不回显收件人任何字段
            ra.addFlashAttribute("notice",
                    msg.get("admin.flash.shopOrder.shipped", s.getCarrier().displayName(), s.getTrackingNo()));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    /** SPEC-2 出口①：整单标记已送达。 */
    @PostMapping("/admin/shop/orders/{token}/mark-delivered")
    @PreAuthorize(FULFILL_AUTH)
    public String markDelivered(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            adminOrders.markDelivered(token, admin == null ? null : admin.getAdminAccountId());
            return done(admin, token, msg.get("admin.flash.shopOrder.delivered"), model);
        }
        try {
            adminOrders.markDelivered(token, admin == null ? null : admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.shopOrder.delivered"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    /** S-2：逐包裹标记送达（全部送达后订单才转已送达）。 */
    @PostMapping("/admin/shop/orders/{token}/packages/{shipmentId}/delivered")
    @PreAuthorize(FULFILL_AUTH)
    public String markPackageDelivered(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @PathVariable long shipmentId, HxRequest hx, Model model,
            RedirectAttributes ra) {
        if (hx.isHtmx()) {
            adminOrders.markPackageDelivered(token, shipmentId,
                    admin == null ? null : admin.getAdminAccountId());
            return done(admin, token, msg.get("admin.flash.shopOrder.parcelDelivered"), model);
        }
        try {
            adminOrders.markPackageDelivered(token, shipmentId,
                    admin == null ? null : admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.shopOrder.parcelDelivered"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    /**
     * 处置成功统一响应（AC2）：抽屉 fragment 原地刷新 + oob 列表该行 + toast。
     *
     * <p>抽屉<b>不自动关</b>（UI 稿 10-6）—— 发完货运营通常还要看一眼包裹表。
     * oob 行按 id 原位替换，状态色点与包裹数当场更新；摘要条不 oob
     * （它是整页范围的聚合，要刷得靠筛选栏重拉，代价不值）。
     */
    private String done(AdminUserDetails admin, String token, String message, Model model) {
        ShopOrder order = populateDrawer(admin, token, model);
        model.addAttribute("row", toRow(order));
        // oob 那一行的「异常」标：只判这一单（行片段把它当形参收，不去读 model —— 见 shop-orders-list.html 的注释）
        model.addAttribute("flagged", exceptions.isCandidate(token));
        model.addAttribute("message", message);
        return "admin/fragments/drawer-shop-order :: done";
    }

    // ---------- 内部 ----------

    private AdminShopOrderRow toRow(ShopOrder o) {
        return new AdminShopOrderRow(o.getPublicToken(), o.getStatus().name(), o.getTotalAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCoinAmount(), o.getCashAmount(),
                o.getShipKecamatan(), fulfillment.shipmentsOf(o.getId()).size(),
                o.getCreatedAt(), o.getShippedAt(), o.getDeliveredAt());
    }

    /** 未知筛选值当作「不筛选」，不报错 —— 运营手改 URL 不该看到 500。 */
    private static ShopOrderStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (ShopOrderStatus s : ShopOrderStatus.values()) {
            if (s.name().equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        return null;
    }

    /** ⚠️ 与既有两个 shop 控制器同款 —— 那两处是 {@code private static}，跨类不可调用。 */
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
