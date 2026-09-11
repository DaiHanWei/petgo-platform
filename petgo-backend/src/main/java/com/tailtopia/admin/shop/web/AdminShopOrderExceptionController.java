package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shared.web.StateTab;
import com.tailtopia.admin.shop.service.AdminShopOrderExceptionService;
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

    public AdminShopOrderExceptionController(AdminShopOrderExceptionService exceptions,
            ShopOrderLineRepository orderLines, ShopOrderRepository orders,
            Messages msg) {
        this.exceptions = exceptions;
        this.orderLines = orderLines;
        this.orders = orders;
        this.msg = msg;
    }

    /**
     * A8 工作台（V1.3.0 Story 10.1 AC3，模板 A）。
     *
     * <p>🔴 <b>只有一个页签</b>：AC3 原写「待处理 ｜ 已处理」，但
     * {@link AdminShopOrderExceptionService#exceptionCandidates} 是<b>实时计算</b>的候选集
     * （已付款待发货 ∧ 库存不足），处置完那一单就离开集合 —— 库里<b>不存在</b>「已处理」这个可查询的集合。
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

    @PostMapping("/admin/shop/order-exceptions/{token}/cancel")
    @PreAuthorize(HANDLE_AUTH)
    public String cancelWhole(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String reason,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            var out = exceptions.cancelWholeOrder(token, reason, actorOf(admin));
            return done(admin, token, msg.get("admin.flash.orderException.fullCancelled",
                    out.coinRefunded(), out.compensationPremium(), out.cashRefundDue()), model);
        }
        try {
            var out = exceptions.cancelWholeOrder(token, reason, actorOf(admin));
            ra.addFlashAttribute("notice",
                    msg.get("admin.flash.orderException.fullCancelled", out.coinRefunded(),
                            out.compensationPremium(), out.cashRefundDue()));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/order-exceptions";
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
        // 单页签（见 list() 的注释）：仍走公共页签行，计数与队列同源 —— 页签条本身就是「这里有多少条」的位置
        model.addAttribute("stateTabs", List.of(new StateTab("/admin/shop/order-exceptions",
                "admin.v130.shopExceptions.tab.pending", "shop-exception-tab-count-pending",
                candidates.size(), true)));
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
    }

    /**
     * 处置成功 fragment（AC3）：toast + oob 整条左栏队列 + {@code data-next-id}。
     *
     * <p>🔴 <b>左栏整体重算而不是删一行</b>：候选集是实时计算的 —— 部分取消可能让这一单
     * 不再缺货（于是它该消失），也可能仍然缺货（于是它该留下）；同一次库存变动还可能让
     * <b>别的</b>单子进出候选集。只删被点的那一行，左栏从此与真相不符。
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
