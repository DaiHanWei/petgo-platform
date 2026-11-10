package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shared.web.StateTab;
import com.tailtopia.admin.shop.service.AdminShopOrderExceptionService;
import com.tailtopia.admin.shop.service.CancelCategoryMapping;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shared.i18n.Messages;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 异常订单处置（Story 4.4，AB-11D / S-3）。模块 11 第二页。
 *
 * <p>🔴 <b>S-3：运营手工选单取消，不做自动取消</b> —— 本页只<b>列出候选</b>，
 * 每一次取消都由人点下去。SKU ≤ 30、单量低，手工完全可行；自动取消会误杀大客户。
 *
 * <p>三个 POST 端点全部本地 {@code catch AppException}（仓库统一处置）。
 */
@Controller
public class AdminShopOrderExceptionController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.order_view') "
                    + "or hasAuthority('shop.order_fulfill')";
    private static final String HANDLE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.order_fulfill')";

    private static final int PAGE_SIZE = 100;

    private final AdminShopOrderExceptionService exceptions;
    private final ShopOrderLineRepository orderLines;
    /** V1.3.0 Story 10.1：右栏按 {@code ?open=<token>} 取单（不新开 detail 端点）。 */
    private final ShopOrderRepository orders;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    /** V1.4.2 shop-admin Story 5.1：揽收失败取消在事务外先取消旧运单（AD-9）。 */
    private final com.tailtopia.shop.logistics.ShopLogisticsExceptionService logistics;
    private final com.tailtopia.shop.order.repository.ShipmentRepository shipments;

    public AdminShopOrderExceptionController(AdminShopOrderExceptionService exceptions,
            ShopOrderLineRepository orderLines, ShopOrderRepository orders,
            Messages msg, com.tailtopia.shop.logistics.ShopLogisticsExceptionService logistics,
            com.tailtopia.shop.order.repository.ShipmentRepository shipments) {
        this.exceptions = exceptions;
        this.orderLines = orderLines;
        this.orders = orders;
        this.msg = msg;
        this.logistics = logistics;
        this.shipments = shipments;
    }

    /**
     * A8 工作台（V1.3.0 Story 10.1 AC3，模板 A）。
     *
     * <p>🔴 <b>只有一个页签</b>：AC3 原写「待处理 ｜ 已处理」，但
     * {@link AdminShopOrderExceptionService#exceptionCandidates} 的候选集是「已付款待发货 ∧ 运营已标记缺货」，
     * 处置（整单取消 / 继续履约）会清掉标记、那一单就离开集合 —— 库里<b>不存在</b>「已处理」这个可查询的集合。
     * 造出来需要新查询甚至新持久化，破本 story 的「零后端功能改动」（见 story T0 重核 ③，AC 偏差已记 Completion Notes）。
     * 页内常驻说明把这件事讲清楚，处置记录在操作审计里。
     *
     * <p>{@code open=<token>} 既是页内深链，也是右栏 fragment 的取数入口 —— <b>不新开 detail 端点</b>（AB-19A）。
     */
    @GetMapping("/admin/shop/order-exceptions")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "open", required = false) String open,
            HxRequest hx, Model model) {
        boolean canHandle = has(admin, AdminPermissions.SHOP_ORDER_FULFILL);
        model.addAttribute("canHandle", canHandle);
        model.addAttribute("active", "shopOrderExceptions");
        String opened = open == null || open.isBlank() ? null : open.trim();
        if (hx.isHtmx() && opened != null) {
            populateDetail(opened, model);
            return "admin/fragments/shop-exception-panel :: detail";
        }
        populateQueue(model);
        model.addAttribute("open", opened);
        return hx.isHtmx() ? "admin/fragments/shop-exception-queue :: rows"
                : "admin/shop-order-exceptions";
    }

    // ---------- V1.4.2 shop-admin Story 5.1：原因类别 + 试算（D1 / D3 ~ D7） ----------

    /** 退款明细试算片段（切换原因类别 / 改协商额时 htmx 刷新）；只读、与落库同一计算。 */
    @GetMapping("/admin/shop/order-exceptions/{token}/preview")
    @PreAuthorize(VIEW_AUTH)
    public String preview(@PathVariable String token, @RequestParam(required = false) String category,
            @RequestParam(required = false) Long negotiatedAmount, Model model) {
        ShopOrder order = orders.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        model.addAttribute("order", order);
        putPreview(token, category == null ? null : CancelCategoryMapping.Category.parse(category), negotiatedAmount,
                model);
        return "admin/fragments/shop-exception-panel :: breakdown";
    }

    /** D3 ~ D5：整单取消确认弹层 —— 复述原因类别与退款明细（同一试算）。 */
    @PostMapping("/admin/shop/order-exceptions/{token}/cancel/confirm")
    @PreAuthorize(HANDLE_AUTH)
    public String confirmCancelWhole(@PathVariable String token, @RequestParam String category,
            @RequestParam(required = false) Long negotiatedAmount, @RequestParam String reason,
            Model model, jakarta.servlet.http.HttpServletResponse response) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("请填写处置原因（会一并告知用户）").code("admin.err.orderException.reasonRequired");
        }
        var cat = CancelCategoryMapping.Category.parse(category);
        exceptions.planWhole(token, cat);   // 落库口径校验（含用户原因须已入库）：不过 409，不弹确认
        var pv = exceptions.previewWhole(token, cat, negotiatedAmount);
        if (pv.quote() == null) {
            throw AppException.validation("未保价订单须填写协商赔付额，且不超过商品实付")
                    .code("admin.err.orderRefund.negotiatedInvalid", pv.goodsPaidCap());
        }
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("category", cat.name());
        hidden.put("negotiatedAmount", negotiatedAmount == null ? "" : String.valueOf(negotiatedAmount));
        hidden.put("reason", reason);
        String body = msg.get("admin.v142.exc.confirm.body." + (pv.couponReturned() ? "couponReturn" : "couponKeep")
                + (pv.restocks() ? "Restock" : "NoRestock"));
        var cm = new AdminConfirmModal(msg.get("admin.v142.exc.confirm.wholeTitle"), null, List.of(), body, null, null,
                null, "/admin/shop/order-exceptions/" + enc(token) + "/cancel", hidden,
                msg.get("admin.shop.exceptions.btn.cancelWhole"), true, false,
                List.of(new AdminConfirmModal.Fact(msg.get("admin.v142.exc.category.label"),
                        msg.get("admin.v142.exc.category." + cat.name()))))
                .withAmounts(amounts(pv.quote(), "admin.v142.exc.confirm.coinNote", "admin.v142.exc.confirm.cashNote"))
                .withSuccessTarget(".wb-detail-body", "innerHTML");
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model, response, cm);
    }

    /** D6 / D7：部分取消确认弹层 —— 复述拆分明细；最后一件转整单时复述整单口径（类别只能平台原因）。 */
    @PostMapping("/admin/shop/order-exceptions/{token}/cancel-line/confirm")
    @PreAuthorize(HANDLE_AUTH)
    public String confirmCancelLine(@PathVariable String token, @RequestParam long lineId, @RequestParam int qty,
            @RequestParam String reason, Model model, jakarta.servlet.http.HttpServletResponse response) {
        var order = orders.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        var line = orderLines.findById(lineId).filter(l -> l.getOrderId().equals(order.getId()))
                .orElseThrow(() -> AppException.notFound("订单行不存在").code("admin.err.order.notFound"));
        if (qty < 1 || qty > line.getQty() - line.getRefundedQty()) {
            throw AppException.validation("取消数量超出可取消范围").code("admin.err.v142.cancelQtyOutOfRange");
        }
        if (!shipments.findActiveByShopOrderId(order.getId()).isEmpty()) {
            throw AppException.conflict("该订单有活动运单，请先取消运单再部分取消")
                    .code("admin.err.v142.exception.activeWaybill");
        }
        var quote = exceptions.previewLine(token, lineId, qty);
        java.util.Map<String, String> hidden = new java.util.LinkedHashMap<>();
        hidden.put("lineId", String.valueOf(lineId));
        hidden.put("qty", String.valueOf(qty));
        hidden.put("reason", reason);
        AdminConfirmModal cm;
        if (quote.becameWhole()) {
            cm = new AdminConfirmModal(msg.get("admin.v142.exc.confirm.wholeTitle"), null, List.of(),
                    msg.get("admin.v142.exc.lastLine.hint"), null, null, null,
                    "/admin/shop/order-exceptions/" + enc(token) + "/cancel-line", hidden,
                    msg.get("admin.v142.exc.lastLine.btn"), true, false,
                    List.of(new AdminConfirmModal.Fact(msg.get("admin.v142.exc.category.label"),
                            msg.get("admin.v142.exc.category.PLATFORM"))))
                    .withAmounts(amounts(quote, "admin.v142.exc.confirm.coinNote", "admin.v142.exc.confirm.cashNote"));
        } else {
            cm = AdminConfirmModal.simple(msg.get("admin.v142.exc.confirm.lineTitle", line.getProductName(),
                            String.valueOf(qty)), msg.get("admin.v142.exc.confirm.lineBody"),
                    "/admin/shop/order-exceptions/" + enc(token) + "/cancel-line", hidden,
                    msg.get("admin.v130.shopExceptions.btn.cancelLine"), true)
                    .withAmounts(amounts(quote, "admin.v142.exc.confirm.coinNote", "admin.v142.exc.confirm.lineCashNote"));
        }
        return com.tailtopia.admin.shared.web.AdminConfirmModals.render(model,
                response, cm.withSuccessTarget(".wb-detail-body", "innerHTML"));
    }

    /** 弹层金额行：PawCoin 段 / 现金段 / 退款合计（金额已由同一试算给出，只格式化）。 */
    private List<AdminConfirmModal.AmountLine> amounts(com.tailtopia.shop.order.pricing.RefundQuote q, String coinNoteKey,
            String cashNoteKey) {
        return List.of(new AdminConfirmModal.AmountLine(msg.get("admin.v142.exc.coinSegment"), rp(q.coinRefund()),
                        msg.get(coinNoteKey), false),
                new AdminConfirmModal.AmountLine(msg.get("admin.v142.exc.cashSegment"), rp(q.cashRefund()),
                        msg.get(cashNoteKey), false),
                AdminConfirmModal.AmountLine.total(msg.get("admin.v142.exc.refundTotal"), rp(q.totalRefund())));
    }

    static String rp(long v) {
        return com.tailtopia.shop.logistics.balance.BiteshipBalanceService.rp(v);
    }

    private static String enc(String token) {
        return org.springframework.web.util.UriUtils.encodePathSegment(token, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 整单取消并退款 / 签收后赔付（D3 ~ D5 → D8）。揽收失败在<b>事务外</b>走
     * {@code resolvePickupFailureByCancel}（先取消旧运单，失败则中止、不回补库存），不直接调 {@code cancelWhole}。
     */
    @PostMapping("/admin/shop/order-exceptions/{token}/cancel")
    @PreAuthorize(HANDLE_AUTH)
    public String cancelWhole(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String category,
            @RequestParam(required = false) Long negotiatedAmount, @RequestParam String reason,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminShopOrderExceptionService.Outcome out = handleWhole(admin, token, category, negotiatedAmount, reason);
            return done(admin, token, toast(token, out), model);
        }
        try {
            AdminShopOrderExceptionService.Outcome out = handleWhole(admin, token, category, negotiatedAmount, reason);
            ra.addFlashAttribute("notice", toast(token, out));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/order-exceptions";
    }

    private AdminShopOrderExceptionService.Outcome handleWhole(AdminUserDetails admin, String token, String category,
            Long negotiatedAmount, String reason) {
        var cat = CancelCategoryMapping.Category.parse(category);
        var plan = exceptions.planWhole(token, cat);
        if (plan.action() == CancelCategoryMapping.Action.PICKUP_CANCEL) {
            if (reason == null || reason.isBlank()) {
                throw AppException.validation("请填写处置原因（会一并告知用户）").code("admin.err.orderException.reasonRequired");
            }
            ShopOrder order = orders.findByPublicToken(token)
                    .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
            // 🔴 不在任何外层事务里：本方法先在事务外取消旧 Biteship 运单，失败则中止（订单、库存都不动）
            var res = logistics.resolvePickupFailureByCancel(order.getId(), reason.trim(), actorOf(admin));
            if (!res.resolvedOk()) {
                throw AppException.conflict("旧运单取消失败，已中止：" + res.reason())
                        .code("admin.err.v142.exception.pickupCancelAborted");
            }
            return exceptions.auditPickupCancel(token, reason, actorOf(admin));
        }
        return exceptions.cancelWholeOrder(token, cat, negotiatedAmount, reason, actorOf(admin));
    }

    /** D8 toast：订单号 + PawCoin 段已退回 + 现金段待用户选择去向。 */
    private String toast(String token, AdminShopOrderExceptionService.Outcome out) {
        String no = orders.findByPublicToken(token).map(ShopOrder::getDisplayNo).orElse(token);
        return msg.get("admin.v142.exc.toast.whole", no, rp(out.coinRefunded()), rp(out.cashRefundDue()));
    }

    @PostMapping("/admin/shop/order-exceptions/{token}/cancel-line")
    @PreAuthorize(HANDLE_AUTH)
    public String cancelLine(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam long lineId, @RequestParam int qty,
            @RequestParam String reason, HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            exceptions.cancelLine(token, lineId, qty, reason, actorOf(admin));
            return done(admin, token, msg.get("admin.flash.orderException.partialCancelled"), model);
        }
        try {
            exceptions.cancelLine(token, lineId, qty, reason, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.orderException.partialCancelled"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/order-exceptions";
    }

    @PostMapping("/admin/shop/order-exceptions/{token}/continue")
    @PreAuthorize(HANDLE_AUTH)
    public String contactAndContinue(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String reason,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            exceptions.contactAndContinue(token, reason, actorOf(admin));
            return done(admin, token,
                    msg.get("admin.flash.orderException.contactAndContinue"), model);
        }
        try {
            exceptions.contactAndContinue(token, reason, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.orderException.contactAndContinue"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/order-exceptions";
    }

    // ---------- 内部 ----------

    private void populateQueue(Model model) {
        List<ShopOrder> candidates = exceptions.exceptionCandidates(PAGE_SIZE);
        Map<String, List<?>> linesByToken = new LinkedHashMap<>();
        for (ShopOrder o : candidates) {
            linesByToken.put(o.getPublicToken(), orderLines.findByOrderIdOrderByIdAsc(o.getId()));
        }
        model.addAttribute("orders", candidates);
        model.addAttribute("linesByToken", linesByToken);
        // V1.4.2 shop-admin Story 5.1：队列来源徽标
        Map<String, com.tailtopia.admin.shop.service.ExceptionSource> sourceByToken = new LinkedHashMap<>();
        for (ShopOrder o : candidates) {
            exceptions.source(o).ifPresent(src -> sourceByToken.put(o.getPublicToken(), src));
        }
        model.addAttribute("sourceByToken", sourceByToken);
        // 单页签（见 list() 的注释）：仍走公共页签行，计数与队列同源 —— 页签条本身就是「这里有多少条」的位置
        model.addAttribute("stateTabs", List.of(new StateTab("/admin/shop/order-exceptions",
                "admin.v130.shopExceptions.tab.pending", "shop-exception-tab-count-pending",
                exceptions.queueCount(), true)));   // V1.4.2 shop-admin 5.3：与侧栏角标同源（不受 100 条上限影响）
    }

    /** 右栏三区取数：异常原因说明卡 / 订单行表 / 操作区。 */
    private void populateDetail(String token, Model model) {
        ShopOrder order = orders.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
        var stocks = exceptions.lineStocks(order);
        Map<Long, AdminShopOrderExceptionService.LineStock> stockByLine = new LinkedHashMap<>();
        stocks.forEach(s -> stockByLine.put(s.lineId(), s));
        model.addAttribute("order", order);
        model.addAttribute("lines", orderLines.findByOrderIdOrderByIdAsc(order.getId()));
        model.addAttribute("stockByLine", stockByLine);
        model.addAttribute("stillCandidate", exceptions.isCandidate(token));
        // V1.4.2 shop-admin Story 5.1：来源、可选原因类别、部分取消可用态、默认类别的退款明细试算
        var source = exceptions.source(order).orElse(null);
        model.addAttribute("source", source);
        java.util.Map<String, Boolean> allowed = new LinkedHashMap<>();
        CancelCategoryMapping.Category first = null;
        for (var c : CancelCategoryMapping.Category.values()) {
            boolean ok = CancelCategoryMapping.allowed(source, c, order.getStatus(), order.getReturnCause());
            allowed.put(c.name(), ok);
            if (ok && first == null) {
                first = c;
            }
        }
        model.addAttribute("categoryAllowed", allowed);
        model.addAttribute("canPartial", source != null && order.getStatus() == com.tailtopia.shop.order.domain.ShopOrderStatus.PENDING_SHIPMENT
                && (source == com.tailtopia.admin.shop.service.ExceptionSource.OUT_OF_STOCK
                        || source == com.tailtopia.admin.shop.service.ExceptionSource.WAYBILL_FAILED
                        || source == com.tailtopia.admin.shop.service.ExceptionSource.PICKUP_FAILED)
                && shipments.findActiveByShopOrderId(order.getId()).isEmpty());
        model.addAttribute("canContinue", source == com.tailtopia.admin.shop.service.ExceptionSource.OUT_OF_STOCK);
        if (first != null) {
            putPreview(token, first, null, model);
        }
    }

    /** 试算放进 model：{@code preview}（WholePreview）或 {@code previewError}（如未保价须填协商额、用户原因未入库）。 */
    private void putPreview(String token, CancelCategoryMapping.Category category, Long negotiatedAmount, Model model) {
        model.addAttribute("selectedCategory", category == null ? null : category.name());
        model.addAttribute("negotiatedAmount", negotiatedAmount);
        if (category == null) {
            return;
        }
        try {
            model.addAttribute("preview", exceptions.previewWhole(token, category, negotiatedAmount));
        } catch (AppException e) {
            // 如协商额越界、已无可退金额：明细区显示错误，不弹确认
            model.addAttribute("previewError", msg.resolve(e));
        }
    }

    /**
     * 处置成功 fragment（AC3）：toast + oob 整条左栏队列 + {@code data-next-id}。
     *
     * <p>🔴 <b>左栏整体重算而不是删一行</b>：部分取消后这一单仍留着（标记保留，逐行处理），
     * 整单取消 / 继续履约后它才消失；同时别的运营可能刚标记了新的缺货单。
     * 只删被点的那一行，左栏从此与真相不符。
     */
    private String done(AdminUserDetails admin, String token, String message, Model model) {
        populateQueue(model);
        model.addAttribute("canHandle", has(admin, AdminPermissions.SHOP_ORDER_FULFILL));
        boolean still = exceptions.isCandidate(token);
        model.addAttribute("nextId", still ? token : firstOtherToken(model, token));
        model.addAttribute("message", message);
        return "admin/fragments/shop-exception-done :: done";
    }

    @SuppressWarnings("unchecked")
    private static String firstOtherToken(Model model, String token) {
        for (ShopOrder o : (List<ShopOrder>) model.getAttribute("orders")) {
            if (!o.getPublicToken().equals(token)) {
                return o.getPublicToken();
            }
        }
        return null;
    }

    private static Long actorOf(AdminUserDetails admin) {
        return admin == null ? null : admin.getAdminAccountId();
    }

    /** ⚠️ 与既有 shop 控制器同款（各自 {@code private static}，跨类不可调用）。 */
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
