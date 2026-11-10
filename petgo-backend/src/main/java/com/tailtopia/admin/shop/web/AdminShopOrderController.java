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

    /** V1.4.2 shop-admin Story 6.1 · AD-14：Biteship 下单 / 重提 / 揽收失败处置（自带 REQUIRES_NEW 短事务，本类不开事务）。 */
    private final com.tailtopia.shop.logistics.BiteshipShipmentService waybills;
    private final com.tailtopia.shop.logistics.ShopLogisticsExceptionService logistics;
    private final com.tailtopia.shop.order.repository.ShipmentRepository shipmentRepo;
    private final com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts;
    /** V1.4.2 shop-admin Story 6.2：用户原因退回按行确认入库（库存只在这里加一次）。 */
    private final com.tailtopia.shop.order.refund.ShopOrderRefundService refundService;
    /** V1.4.2 shop-admin Story 7.1：抽屉「退款与成长值」一节（F7，只读）。 */
    private final com.tailtopia.admin.shop.service.AdminShopOrderAfterSalesService afterSales;
    /** V1.4.2 shop-admin Story 7.2：取消后到账（F2 ~ F6）。 */
    private final com.tailtopia.admin.shop.service.AdminShopLatePaymentService lateView;
    private final com.tailtopia.shop.order.service.LatePaymentService latePayments;

    public AdminShopOrderController(AdminShopOrderService adminOrders,
            ShopOrderFulfillmentService fulfillment, ShopOrderRepository orders,
            ShopOrderLineRepository orderLines, AdminShopOrderExceptionService exceptions,
            Messages msg, com.tailtopia.shop.logistics.BiteshipShipmentService waybills,
            com.tailtopia.shop.logistics.ShopLogisticsExceptionService logistics,
            com.tailtopia.shop.order.repository.ShipmentRepository shipmentRepo,
            com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts,
            com.tailtopia.shop.order.refund.ShopOrderRefundService refundService,
            com.tailtopia.admin.shop.service.AdminShopOrderAfterSalesService afterSales,
            com.tailtopia.admin.shop.service.AdminShopLatePaymentService lateView,
            com.tailtopia.shop.order.service.LatePaymentService latePayments) {
        this.lateView = lateView;
        this.latePayments = latePayments;
        this.refundService = refundService;
        this.afterSales = afterSales;
        this.waybills = waybills;
        this.logistics = logistics;
        this.shipmentRepo = shipmentRepo;
        this.adminAccounts = adminAccounts;
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
            @RequestParam(value = "flag", required = false) String flag,
            HxRequest hx, Model model) {
        // V1.4.2 shop-admin 7.1：履约页只认履约异常标记（LATE_PAID 只在「订单」页），其余当作不筛选
        if (flag != null && !AdminShopOrderService.FLAGS.contains(flag)) {
            flag = null;
        }
        boolean canPhoneSearch = has(admin, AdminPermissions.SHOP_ORDER_PHONE_SEARCH);
        ShopOrderStatus filter = parseStatus(status);
        List<ShopOrder> found;
        int current = Math.max(page, 0);
        boolean hasNext = false;
        // V1.4.2 通用分页条：只有分页浏览有总数；按单号 / 电话精确查是单页结果，不出分页条。
        com.tailtopia.admin.shared.AdminPager pager = com.tailtopia.admin.shared.AdminPager.unknown(0, false);

        if (orderToken != null && !orderToken.isBlank()) {
            // 订单号精确查。🔴 shop-v2 Story 4-3：新号 / 旧号 / 内部 token 三种输入都要命中同一单。
            found = searchByAnyOrderNo(orderToken).map(List::of).orElse(List.of());
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
            var p = adminOrders.page(filter, startOf(from), endOf(to), flag, current, QUEUE_PAGE_SIZE);
            found = p.rows();
            current = p.page();
            hasNext = p.hasNext();
            pager = com.tailtopia.admin.shared.AdminPager.of(current, QUEUE_PAGE_SIZE, p.total());
        }

        fillListing(found, filter, orderToken, from, to, flag, AdminShopOrderService.FLAGS, current, hasNext, model);
        model.addAttribute("pager", pager);
        model.addAttribute("summary", adminOrders.summary(startOf(from), endOf(to)));
        // 🔒 回显的是「是否搜过」，不回显号码本身 —— 回显会把 PII 带进 URL、浏览器历史与访问日志
        model.addAttribute("phoneSearched", phone != null && !phone.isBlank());
        model.addAttribute("canPhoneSearch", canPhoneSearch);
        model.addAttribute("canFulfill", has(admin, AdminPermissions.SHOP_ORDER_FULFILL));
        model.addAttribute("open", open == null || open.isBlank() ? null : open.trim());
        model.addAttribute("active", "shopOrders");
        return hx.isHtmx() ? "admin/fragments/shop-orders-list :: rows(oob=true)" : "admin/shop-orders";
    }

    // ---------- V1.4.2 shop-admin Story 7.1 · AD-12：「订单」总表（F1）+ 抽屉（页面上下文 = list） ----------

    /**
     * 「订单」总表：沿用履约页 7 列与查询（{@link AdminShopOrderService#page}），摘要换成待支付 / 待发货 / 待收货 / 退款中 /
     * 取消后到账待处理；「异常标记」筛选与履约页共用 {@link AdminShopOrderService#FLAGS}。不含按电话搜索（那是履约页的独立能力）。
     */
    @GetMapping("/admin/shop/order-list")
    @PreAuthorize(VIEW_AUTH)
    public String orderList(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderToken,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) String open,
            @RequestParam(value = "flag", required = false) String flag,
            HxRequest hx, Model model) {
        ShopOrderStatus filter = parseStatus(status);
        List<ShopOrder> found;
        int current = Math.max(page, 0);
        boolean hasNext = false;
        // V1.4.2 通用分页条：只有分页浏览有总数；按单号 / 电话精确查是单页结果，不出分页条。
        com.tailtopia.admin.shared.AdminPager pager = com.tailtopia.admin.shared.AdminPager.unknown(0, false);
        if (orderToken != null && !orderToken.isBlank()) {
            found = searchByAnyOrderNo(orderToken).map(List::of).orElse(List.of());
            current = 0;
        } else {
            var p = adminOrders.page(filter, startOf(from), endOf(to), flag, current, QUEUE_PAGE_SIZE);
            found = p.rows();
            current = p.page();
            hasNext = p.hasNext();
            pager = com.tailtopia.admin.shared.AdminPager.of(current, QUEUE_PAGE_SIZE, p.total());
        }
        fillListing(found, filter, orderToken, from, to, flag, AdminShopOrderService.LIST_FLAGS, current, hasNext,
                model);
        model.addAttribute("pager", pager);
        model.addAttribute("summary", adminOrders.listSummary(startOf(from), endOf(to)));
        model.addAttribute("drawerBase", LIST_BASE);
        model.addAttribute("open", open == null || open.isBlank() ? null : open.trim());
        model.addAttribute("active", "shopOrderList");
        return hx.isHtmx() ? "admin/fragments/shop-order-list-rows :: rows(oob=true)" : "admin/shop-order-list";
    }

    /** 两页共用的列表模型（行、A8 异常标、异常标记徽标与筛选回显、分页）。 */
    private void fillListing(List<ShopOrder> found, ShopOrderStatus filter, String orderToken, LocalDate from,
            LocalDate to, String flag, List<String> flagOptions, int current, boolean hasNext, Model model) {
        List<AdminShopOrderRow> rows = found.stream().map(this::toRow).toList();
        model.addAttribute("rows", rows);
        // AC1：有异常挂起的行打「异常」标并链到 A8。
        // 🔴 只判**本页这几条**（见 AdminShopOrderExceptionService#flagged）：拿 A8 的候选集来求交是错的
        //    —— 那份是「最旧的 N 条」，而本列表是「最新 20 条」，待发货一多两边零交集，标就永远不出现。
        model.addAttribute("exceptionTokens",
                exceptions.flagged(rows.stream().map(AdminShopOrderRow::orderToken).toList()));
        // V1.4.2 shop-admin Story 6.1（H1）/ 7.1（F1）：异常标记筛选 + 行徽标
        model.addAttribute("flags", flagOptions);
        model.addAttribute("flag", flag == null || !flagOptions.contains(flag) ? "" : flag);
        java.util.Map<String, List<String>> flagsByToken = new java.util.HashMap<>();
        found.forEach(o -> flagsByToken.put(o.getPublicToken(), AdminShopOrderService.flagsOf(o)));
        model.addAttribute("flagsByToken", flagsByToken);
        model.addAttribute("statuses", ShopOrderStatus.values());
        model.addAttribute("status", filter == null ? "" : filter.name());
        model.addAttribute("orderToken", orderToken == null ? "" : orderToken);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("page", current);
        model.addAttribute("hasNext", hasNext);
    }

    /** 「订单」页的抽屉：同一份 drawer-shop-order，吸底不出履约操作（AC2，履约页行为不变）。 */
    @GetMapping("/admin/shop/order-list/{token}")
    @PreAuthorize(VIEW_AUTH)
    public String orderListDrawer(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            throw AppException.notFound("该页面已并入订单列表的详情抽屉").code("admin.err.common.pageRetired");
        }
        populateDrawer(admin, token, model);
        model.addAttribute("ctx", CTX_LIST);
        model.addAttribute("active", "shopOrderList");
        return "admin/fragments/drawer-shop-order :: drawer";
    }

    // ---------- V1.4.2 shop-admin Story 7.2 · AD-11：取消后到账处置（F2 ~ F6；只在「订单」页抽屉） ----------

    /** F3 提交重新下单：手勾两项必须勾；券 / PawCoin / 库存服务端重查（AC2）。全部缺货 → 409 只能退款（AC4）。 */
    @PostMapping("/admin/shop/order-list/{token}/late/reorder")
    @PreAuthorize(FULFILL_AUTH)
    public String lateReorder(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String consented, @RequestParam(required = false) String addressValid,
            Model model) {
        com.tailtopia.shop.order.service.ReorderOutcome out;
        try {
            out = latePayments.reorderFromLatePayment(token, consented != null, addressValid != null, actorOf(admin));
        } catch (com.tailtopia.shop.order.dto.CheckoutUnavailableException e) {
            throw AppException.conflict("原订单商品全部缺货，只能退还到账款").code("admin.err.v142.late.allOutOfStock");
        }
        return listDone(admin, token, msg.get("admin.v142.late.flash.reordered", out.newDisplayNo()), model);
    }

    /** F6 确认弹层：复述到账款，提示 App 出「选择退款方式」、无补偿（只读，不写库）。 */
    @PostMapping("/admin/shop/order-list/{token}/late/refund/confirm")
    @PreAuthorize(FULFILL_AUTH)
    public String confirmLateRefund(@PathVariable String token, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        ShopOrder order = requireOrder(token);
        if (order.getLatePaidAmount() == null || order.getLatePaymentResolution() != null) {
            throw AppException.conflict("该笔迟到款已处理过，或订单没有迟到款").code("admin.err.latePayment.alreadyResolved");
        }
        var cm = new com.tailtopia.admin.shared.web.AdminConfirmModal(msg.get("admin.v142.late.refund.confirmTitle"),
                null, java.util.List.of(), msg.get("admin.v142.late.refund.confirmBody"), null, null, null,
                "/admin/shop/order-list/" + enc(token) + "/late/refund", java.util.Map.of(),
                msg.get("admin.v142.late.btn.refund"), false)
                .withAmounts(java.util.List.of(new com.tailtopia.admin.shared.web.AdminConfirmModal.AmountLine(
                        msg.get("admin.v142.late.refund.received"),
                        AdminShopOrderExceptionController.rp(order.getLatePaidAmount()), null, true)))
                .withSuccessTarget("#shop-order-drawer-body", "innerHTML");
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
    }

    /** F6 退还到账款：进待用户选择，无补偿（AC8）；注销用户进「注销用户 · 线下处理」（O-26）。 */
    @PostMapping("/admin/shop/order-list/{token}/late/refund")
    @PreAuthorize(FULFILL_AUTH)
    public String lateRefund(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Model model) {
        latePayments.refundLatePayment(token, actorOf(admin));
        return listDone(admin, token, msg.get("admin.v142.late.flash.refunded"), model);
    }

    /** 「订单」页抽屉处置成功：同 {@link #done}，带页面上下文（吸底不出履约操作、行抽屉地址指向订单页）。 */
    private String listDone(AdminUserDetails admin, String token, String message, Model model) {
        model.addAttribute("ctx", CTX_LIST);
        model.addAttribute("drawerBase", LIST_BASE);
        String view = done(admin, token, message, model);
        model.addAttribute("flagsByToken", java.util.Map.of(token,
                AdminShopOrderService.flagsOf(requireOrder(token))));
        model.addAttribute("active", "shopOrderList");
        return view;
    }

    static final String LIST_BASE = "/admin/shop/order-list";
    static final String CTX_LIST = "list";

    /** 日期按 UTC 起止换算（全库时间戳一律 UTC，CLAUDE.md 命名映射链）。 */
    /**
     * 🔴 Story 4-3：一个输入框吃三种号。
     *
     * <p>运营手里只有<b>用户报过来的那串字符</b>，他不知道（也不该知道）那是新号、旧号
     * 还是内部 token。此前这里只对 {@code public_token} 精确匹配 —— 用户报的
     * {@code TOKO-20260819-000673} 根本搜不出来，客服只能对着一个「系统里不存在的订单号」
     * 跟用户解释。
     *
     * <p>依次尝试：内部 token → 新展示号 → 旧展示号，任一命中即返回。
     * 三者都不可枚举，所以不需要额外权限门控（对照：按电话搜索要 NFR-11 权限）。
     *
     * <p>🔴 <b>大写归一只作用于展示号</b>：Crockford 字母表是大写，而运营从聊天窗口复制粘贴
     * 很可能带小写 —— 让他因为大小写搜不到，等于把这个修复做了一半。
     * ⚠️ 但 {@code public_token} 是 Base62、<b>大小写敏感</b>，对它归一会把它搜坏，
     * 所以第一跳用的是原串。
     */
    private java.util.Optional<ShopOrder> searchByAnyOrderNo(String raw) {
        String trimmed = raw.trim();
        String normalized = trimmed.toUpperCase(java.util.Locale.ROOT);
        return orders.findByPublicToken(trimmed)
                .or(() -> orders.findByDisplayNo(normalized))
                .or(() -> orders.findByLegacyDisplayNo(normalized));
    }

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
        List<Shipment> pkgs = fulfillment.shipmentsOf(order.getId());
        model.addAttribute("packages", pkgs);
        model.addAttribute("carriers", Carrier.values());
        model.addAttribute("canFulfill", has(admin, AdminPermissions.SHOP_ORDER_FULFILL));
        model.addAttribute("markable", order.getStatus() == ShopOrderStatus.SHIPPED);
        model.addAttribute("active", "shopOrders");
        // V1.4.2 shop-admin Story 6.1 · AD-14：吸底操作集中判定（DrawerActionsResolver）
        var active = shipmentRepo.findActiveByShopOrderId(order.getId());
        var acts = DrawerActionsResolver.resolve(new DrawerActionsResolver.Facts(order.getStatus(),
                order.getWaybillFailReason(), order.getWaybillFailedAt() != null, order.getLogisticsException(),
                active.stream().anyMatch(Shipment::isBiteship), !active.isEmpty(),
                order.getCourierCode() != null && !order.getCourierCode().isBlank(), order.isShortageFlagged(),
                order.getReturnCause(), order.getReturnInboundAt() != null, order.isClaimPending()));
        model.addAttribute("acts", acts);
        model.addAttribute("activeBiteship", active.stream().filter(Shipment::isBiteship).findFirst().orElse(null));
        // V1.4.2 shop-admin Story 7.1（F7）：退款与成长值（只读，两个页面上下文都显示）
        model.addAttribute("afterSales", afterSales.of(order));
        // V1.4.2 shop-admin Story 8.1 AC5：实际运费 = 未取消运单的承运成本合计（含重发；无运单 → 「—」）
        var nonCancelled = pkgs.stream()
                .filter(sh -> sh.getStatus() != com.tailtopia.shop.order.domain.ShipmentStatus.CANCELLED).toList();
        model.addAttribute("actualShipping", nonCancelled.isEmpty() ? null
                : nonCancelled.stream().mapToLong(Shipment::getCarrierCost).sum());
        // V1.4.2 shop-admin Story 7.2（F2 ~ F6）：取消后到账；重下单原快递不可用时运单改选的快递（O-5，抽屉提示）
        model.addAttribute("late", lateView.of(order));
        model.addAttribute("courierSwitched", active.stream().filter(Shipment::isBiteship).anyMatch(sh ->
                order.getCourierCode() != null && sh.getCourierCode() != null
                        && (!order.getCourierCode().equalsIgnoreCase(sh.getCourierCode())
                                || !String.valueOf(order.getCourierServiceCode())
                                        .equalsIgnoreCase(String.valueOf(sh.getCourierServiceCode())))));
        model.addAttribute("shippedByName", order.getShippedByAdminId() == null ? null
                : adminAccounts.findById(order.getShippedByAdminId()).map(a -> a.getDisplayName()).orElse(null));
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
                    s.carrierDisplayName(), s.getTrackingNo()), model);
        }
        try {
            Shipment s = adminOrders.ship(token, carrier, trackingNo, carrierCost,
                    admin == null ? null : admin.getAdminAccountId());
            // 🔒 回显单号（非 PII）；不回显收件人任何字段
            ra.addFlashAttribute("notice",
                    msg.get("admin.flash.shopOrder.shipped", s.carrierDisplayName(), s.getTrackingNo()));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    /**
     * V1.4.2 shop-admin Story 1.2 · AD-3：原生 {@code data-confirm} 改为服务端确认弹层。只读、不写库；
     * 弹层确认后再提交原端点（业务行为不变，AP-7）。权限同原端点。
     */
    @PostMapping("/admin/shop/orders/{token}/mark-delivered/confirm")
    @PreAuthorize(FULFILL_AUTH)
    public String confirmMarkDelivered(@PathVariable String token, Model model, jakarta.servlet.http.HttpServletResponse response) {
        orders.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        com.tailtopia.admin.shared.web.AdminConfirmModal cm = com.tailtopia.admin.shared.web.AdminConfirmModal.simple(msg.get("admin.confirm.title.default"),
                msg.get("admin.v130.shopOrders.confirm.markDelivered", token),
                "/admin/shop/orders/" + org.springframework.web.util.UriUtils.encodePathSegment(token,
                        java.nio.charset.StandardCharsets.UTF_8) + "/mark-delivered",
                java.util.Map.of(), msg.get("admin.shop.order.btn.markDelivered"), false)
                .withSuccessTarget("#shop-order-drawer-body", "innerHTML");
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
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

    // ---------- V1.4.2 shop-admin Story 6.1：Biteship 下单 / 重提 / 揽收失败 / 标记已发货（H2 ~ H7） ----------
    // 🔴 本类不开事务：BiteshipShipmentService / ShopLogisticsExceptionService 自带 REQUIRES_NEW 短事务，Biteship 调用在事务外

    /** H2 下单确认：复述快递（用户所选）、用户支付运费、保价。 */
    @PostMapping("/admin/shop/orders/{token}/waybill/confirm")
    @PreAuthorize(FULFILL_AUTH)
    public String confirmWaybill(@PathVariable String token, @RequestParam(defaultValue = "create") String mode,
            Model model, jakarta.servlet.http.HttpServletResponse response) {
        ShopOrder order = requireOrder(token);
        java.util.List<com.tailtopia.admin.shared.web.AdminConfirmModal.Fact> facts = java.util.List.of(
                new com.tailtopia.admin.shared.web.AdminConfirmModal.Fact(msg.get("admin.v142.ful.courier"),
                        msg.get("admin.v142.ful.courierUserChoice", nz(order.getCourierName()))),
                new com.tailtopia.admin.shared.web.AdminConfirmModal.Fact(msg.get("admin.v142.ful.shippingPaid"),
                        AdminShopOrderExceptionController.rp(Math.max(0L, order.getShippingFee() + order.getShippingDiscount()))),
                new com.tailtopia.admin.shared.web.AdminConfirmModal.Fact(msg.get("admin.v142.ful.insurance"),
                        order.isInsuranceSelected() ? msg.get("admin.v142.ful.insuranceYes",
                                AdminShopOrderExceptionController.rp(order.getInsuranceFee())) : msg.get("admin.v142.ful.insuranceNo")));
        boolean unknown = order.getWaybillFailReason() != null && order.getWaybillFailReason().startsWith("UNKNOWN_RESULT:");
        String action = switch (mode) {
            case "retry" -> "/waybill/retry";
            case "pickup-retry" -> "/pickup/retry";
            default -> "/waybill";
        };
        var cm = new com.tailtopia.admin.shared.web.AdminConfirmModal(msg.get("admin.v142.ful.waybill.title"),
                unknown && "retry".equals(mode) ? msg.get("admin.v142.ful.unknownWarn") : null, java.util.List.of(),
                "pickup-retry".equals(mode) ? msg.get("admin.v142.ful.pickup.hint") : msg.get("admin.v142.ful.waybill.hint"),
                null, null, null, "/admin/shop/orders/" + enc(token) + action, java.util.Map.of(),
                msg.get("create".equals(mode) ? "admin.v142.ful.btn.create" : "admin.v142.ful.btn.retry"), false,
                unknown && "retry".equals(mode), facts)
                .withSuccessTarget("#shop-order-drawer-body", "innerHTML");
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
    }

    @PostMapping("/admin/shop/orders/{token}/waybill")
    @PreAuthorize(FULFILL_AUTH)
    public String createWaybill(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Model model) {
        ShopOrder order = requirePendingNotPickupFailed(token);
        var r = waybills.createWaybill(order.getId(), actorOf(admin));
        return afterWaybill(admin, token, r, model);
    }

    @PostMapping("/admin/shop/orders/{token}/waybill/retry")
    @PreAuthorize(FULFILL_AUTH)
    public String retryWaybill(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Model model) {
        ShopOrder order = requirePendingNotPickupFailed(token);
        var r = waybills.retryWaybill(order.getId(), actorOf(admin));
        return afterWaybill(admin, token, r, model);
    }

    /** H6：揽收失败重新提交（先取消旧运单；已发货也可，状态不变，O-18）。 */
    @PostMapping("/admin/shop/orders/{token}/pickup/retry")
    @PreAuthorize(FULFILL_AUTH)
    public String pickupRetry(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Model model) {
        ShopOrder order = requireOrder(token);
        var res = logistics.resolvePickupFailureByRetry(order.getId(), actorOf(admin));
        return afterPickup(admin, token, res, "admin.v142.ful.flash.pickupRetried", model);
    }

    /** H6 / H7：揽收失败转手工运单（先取消旧运单）。 */
    @PostMapping("/admin/shop/orders/{token}/pickup/manual")
    @PreAuthorize(FULFILL_AUTH)
    public String pickupManual(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam String carrier, @RequestParam String trackingNo, @RequestParam(required = false) Long carrierCost,
            Model model) {
        if (carrierCost == null) {
            throw AppException.validation("请填写承运成本（S-11：不录则毛利看板缺行）").code("admin.err.shopOrder.carrierCostRequired");
        }
        ShopOrder order = requireOrder(token);
        var res = logistics.resolvePickupFailureByManualWaybill(order.getId(), Carrier.parse(carrier), trackingNo,
                carrierCost, actorOf(admin));
        return afterPickup(admin, token, res, "admin.v142.ful.flash.manualSaved", model);
    }

    /** H3：标记已发货（Biteship 运单待揽收），复用 markShippedWithoutWaybill，写操作人。 */
    @PostMapping("/admin/shop/orders/{token}/mark-shipped")
    @PreAuthorize(FULFILL_AUTH)
    public String markShipped(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Model model) {
        fulfillment.markShippedWithoutWaybill(token, actorOf(admin));
        return done(admin, token, msg.get("admin.v142.ful.flash.markedShipped"), model);
    }

    // ---------- V1.4.2 shop-admin Story 6.2：退回判定 / 重发 / 退回入库 / 丢件破损 / 索赔结案 / 清除停滞（H8 ~ H12） ----------
    // 🔴 同上：ShopLogisticsExceptionService 自带短事务，本类不包外层事务

    /** H8：判定退回原因（快递原因 → 待索赔；用户原因 → 抽屉出「确认退回入库」）。 */
    @PostMapping("/admin/shop/orders/{token}/returned/decide")
    @PreAuthorize(FULFILL_AUTH)
    public String decideReturned(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String cause, @RequestParam(required = false) String note, Model model) {
        com.tailtopia.shop.logistics.ReturnCause rc = parseReturnCause(cause);
        ShopOrder order = requireOrder(token);
        logistics.confirmReturned(order.getId(), rc, note, actorOf(admin));
        return done(admin, token, msg.get("admin.v142.ret.flash.decided"), model);
    }

    /** H9 确认：重发运单（平台承担运费、原运单保留）。 */
    @PostMapping("/admin/shop/orders/{token}/reship/confirm")
    @PreAuthorize(FULFILL_AUTH)
    public String confirmReship(@PathVariable String token, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        ShopOrder order = requireOrder(token);
        var cm = com.tailtopia.admin.shared.web.AdminConfirmModal.simple(msg.get("admin.v142.ret.reship.title"),
                msg.get("admin.v142.ret.reship.courierValue", nz(order.getCourierName())) + " · "
                        + msg.get("admin.v142.ret.reship.feeValue"),
                "/admin/shop/orders/" + enc(token) + "/reship", java.util.Map.of(),
                msg.get("admin.v142.ret.btn.reship"), false)
                .withSuccessTarget("#shop-order-drawer-body", "innerHTML");
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
    }

    /** H9：快递原因重发；失败不抛，订单写运单失败，抽屉可再次提交。 */
    @PostMapping("/admin/shop/orders/{token}/reship")
    @PreAuthorize(FULFILL_AUTH)
    public String reship(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token, Model model) {
        ShopOrder order = requireOrder(token);
        var r = logistics.reshipAfterCourierFault(order.getId(), actorOf(admin));
        return afterWaybill(admin, token, r, model);
    }

    /**
     * 用户原因退回按行确认入库（无 UI 稿，照 H7 表单写法）：参数 {@code qty_<lineId>}，空 / 0 的行跳过；可分次，
     * 累计不超应收；收齐后才能到异常订单整单取消（用户原因）。
     */
    @PostMapping("/admin/shop/orders/{token}/return-inbound")
    @PreAuthorize(FULFILL_AUTH)
    public String returnInbound(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam java.util.Map<String, String> params, Model model) {
        java.util.Map<Long, Integer> qty = parseInboundQty(params);
        var out = refundService.confirmReturnedInbound(token, qty, actorOf(admin));
        return done(admin, token, msg.get(out.complete() ? "admin.v142.ret.inbound.flash.complete"
                : "admin.v142.ret.inbound.flash.partial"), model);
    }

    /** H10 确认弹层：复述订单号与类型，提示 App 将显示配送异常。 */
    @PostMapping("/admin/shop/orders/{token}/lost-damaged/confirm")
    @PreAuthorize(FULFILL_AUTH)
    public String confirmLostDamaged(@PathVariable String token, @RequestParam(required = false) String type,
            @RequestParam(required = false) String note, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        com.tailtopia.shop.order.domain.LogisticsException le = parseLostDamaged(type);
        ShopOrder order = requireOrder(token);
        String label = msg.get("admin.v142.exc.lost." + le.name());
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("type", le.name());
        if (note != null && !note.isBlank()) {
            hidden.put("note", note.strip());
        }
        var cm = com.tailtopia.admin.shared.web.AdminConfirmModal.simple(
                msg.get("admin.v142.lost.confirm.title", order.getDisplayNo(), label),
                msg.get("admin.v142.lost.confirm.body"), "/admin/shop/orders/" + enc(token) + "/lost-damaged",
                hidden, msg.get("admin.v142.lost.confirm.btn", label), true)
                .withSuccessTarget("#shop-order-drawer-body", "innerHTML");
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
    }

    /** H10：标记丢件 / 破损 → 待索赔；退款到异常订单整单取消（丢件破损）。 */
    @PostMapping("/admin/shop/orders/{token}/lost-damaged")
    @PreAuthorize(FULFILL_AUTH)
    public String markLostDamaged(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String type, @RequestParam(required = false) String note, Model model) {
        com.tailtopia.shop.order.domain.LogisticsException le = parseLostDamaged(type);
        ShopOrder order = requireOrder(token);
        logistics.markLostOrDamaged(order.getId(), le, note, actorOf(admin));
        return done(admin, token, msg.get("admin.v142.lost.flash.marked"), model);
    }

    /** H11：索赔结案（任何订单状态），金额进对账。 */
    @PostMapping("/admin/shop/orders/{token}/claim/close")
    @PreAuthorize(FULFILL_AUTH)
    public String closeClaim(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String result, @RequestParam(required = false) Long amount, Model model) {
        // 结果 / 金额校验只在 ShopOrder.closeClaim 一处（金额留空按 0，已赔付 / 部分赔付会被拒）
        ShopOrder order = requireOrder(token);
        logistics.closeClaim(order.getId(), result, amount == null ? 0L : amount, actorOf(admin));
        return done(admin, token, msg.get("admin.v142.claim.flash.closed"), model);
    }

    /** H12：手工清除停滞（只允许 STALLED，其他异常 409）。 */
    @PostMapping("/admin/shop/orders/{token}/stalled/clear")
    @PreAuthorize(FULFILL_AUTH)
    public String clearStalled(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            Model model) {
        ShopOrder order = requireOrder(token);
        logistics.clearStalled(order.getId(), actorOf(admin));
        return done(admin, token, msg.get("admin.v142.stall.flash.cleared"), model);
    }

    /** H12 横幅「{days} 天没有更新」：从最后一条物流状态（没有则从标记时刻）算到现在，向下取整。 */
    public static long stalledDays(Shipment active, java.time.Instant flaggedAt) {
        java.time.Instant from = active != null && active.getProviderStatusAt() != null ? active.getProviderStatusAt()
                : flaggedAt;
        return from == null ? 0 : Math.max(0, java.time.Duration.between(from, java.time.Instant.now()).toDays());
    }

    private static com.tailtopia.shop.logistics.ReturnCause parseReturnCause(String raw) {
        if (raw != null) {
            for (var c : com.tailtopia.shop.logistics.ReturnCause.values()) {
                if (c.name().equals(raw)) {
                    return c;
                }
            }
        }
        throw AppException.validation("请选择退回原因").code("admin.err.v142.ret.causeRequired");
    }

    private static com.tailtopia.shop.order.domain.LogisticsException parseLostDamaged(String raw) {
        if ("LOST".equals(raw)) {
            return com.tailtopia.shop.order.domain.LogisticsException.LOST;
        }
        if ("DAMAGED".equals(raw)) {
            return com.tailtopia.shop.order.domain.LogisticsException.DAMAGED;
        }
        throw AppException.validation("请选择丢件或破损").code("admin.err.v142.lost.typeRequired");
    }

    /** {@code qty_<lineId>} → 件数；空白 / 0 跳过，非数字 / 负数 422，全空 422。 */
    static java.util.Map<Long, Integer> parseInboundQty(java.util.Map<String, String> params) {
        java.util.Map<Long, Integer> out = new java.util.LinkedHashMap<>();
        for (var e : params.entrySet()) {
            if (!e.getKey().startsWith("qty_") || e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            long lineId;
            int n;
            try {
                lineId = Long.parseLong(e.getKey().substring(4));
                n = Integer.parseInt(e.getValue().strip());
            } catch (NumberFormatException ex) {
                throw AppException.validation("入库件数必须是整数").code("admin.err.v142.inbound.qtyInvalid");
            }
            if (n < 0) {
                throw AppException.validation("入库件数必须是整数").code("admin.err.v142.inbound.qtyInvalid");
            }
            if (n > 0) {
                out.put(lineId, n);
            }
        }
        if (out.isEmpty()) {
            throw AppException.validation("请至少填写一行实收件数").code("admin.err.v142.inbound.empty");
        }
        return out;
    }

    private String afterWaybill(AdminUserDetails admin, String token, com.tailtopia.shop.logistics.WaybillResult r,
            Model model) {
        if (r.created()) {
            ShopOrder order = requireOrder(token);
            String no = shipmentRepo.findActiveByShopOrderId(order.getId()).stream().filter(Shipment::isBiteship)
                    .map(Shipment::getTrackingNo).filter(java.util.Objects::nonNull).findFirst().orElse("—");
            return done(admin, token, msg.get("admin.v142.ful.flash.created", no), model);
        }
        // 失败不抛：订单已写 waybill_failed_at，抽屉重渲染出 H5 三选一
        return done(admin, token, msg.get("admin.v142.ful.flash.failed"), model);
    }

    private String afterPickup(AdminUserDetails admin, String token, com.tailtopia.shop.logistics.PickupResolution res,
            String okKey, Model model) {
        if (res.resolvedOk()) {
            return done(admin, token, msg.get(okKey), model);
        }
        if (res.outcome() == com.tailtopia.shop.logistics.PickupResolution.Outcome.WAYBILL_FAILED) {
            // 旧运单已取消、新运单没建成：订单已写运单失败，抽屉重渲染出新的处置选项（复审修）
            return done(admin, token, msg.get("admin.v142.ful.flash.failed"), model);
        }
        throw AppException.conflict("揽收失败处置未完成：" + res.reason()).code("admin.err.v142.ful.pickupAborted");
    }

    /**
     * 普通下单 / 重提只给待发货且非揽收失败的单（揽收失败必须经 {@code resolvePickupFailureBy*} 先取消旧运单并清标记）。
     */
    private ShopOrder requirePendingNotPickupFailed(String token) {
        ShopOrder order = requireOrder(token);
        if (order.getStatus() != ShopOrderStatus.PENDING_SHIPMENT
                || order.getLogisticsException() == com.tailtopia.shop.order.domain.LogisticsException.PICKUP_FAILED) {
            throw AppException.conflict("该订单当前不能直接下运单：" + order.getStatus()).code("admin.err.v142.ful.notCreatable");
        }
        return order;
    }

    private ShopOrder requireOrder(String token) {
        return orders.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
    }

    private static String enc(String token) {
        return org.springframework.web.util.UriUtils.encodePathSegment(token, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nz(String v) {
        return v == null ? "—" : v;
    }

    // ---------- 标记缺货（A8 入口） ----------

    /**
     * 标记缺货：待发货单进入异常订单工作台（A8）。拣货时发现实物不够的那一刻，运营在这里点。
     *
     * <p>🔴 这是 A8 的<b>唯一入口</b>：库存不变式 {@code locked <= actual} 让账面上不可能出现缺货，
     * 系统无从自动识别（见 {@link AdminShopOrderExceptionService} 类注释）。
     */
    @PostMapping("/admin/shop/orders/{token}/flag-shortage")
    @PreAuthorize(FULFILL_AUTH)
    public String flagShortage(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String note,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            exceptions.flagShortage(token, note, admin == null ? null : admin.getAdminAccountId());
            return done(admin, token, msg.get("admin.flash.shopOrder.shortageFlagged"), model);
        }
        try {
            exceptions.flagShortage(token, note, admin == null ? null : admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.shopOrder.shortageFlagged"));
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
        return new AdminShopOrderRow(o.getPublicToken(), o.getDisplayNo(), o.getStatus().name(), o.getTotalAmount(),
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
