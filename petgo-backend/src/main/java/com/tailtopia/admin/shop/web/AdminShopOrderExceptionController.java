package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.service.AdminShopOrderExceptionService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
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

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminShopOrderExceptionController(AdminShopOrderExceptionService exceptions,
            ShopOrderLineRepository orderLines,
            Messages msg) {
        this.exceptions = exceptions;
        this.orderLines = orderLines;
        this.msg = msg;
    }

    @GetMapping("/admin/shop/order-exceptions")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin, Model model) {
        List<ShopOrder> candidates = exceptions.exceptionCandidates(PAGE_SIZE);
        Map<String, List<?>> linesByToken = new LinkedHashMap<>();
        for (ShopOrder o : candidates) {
            linesByToken.put(o.getPublicToken(), orderLines.findByOrderIdOrderByIdAsc(o.getId()));
        }
        model.addAttribute("orders", candidates);
        model.addAttribute("linesByToken", linesByToken);
        model.addAttribute("canHandle", has(admin, AdminPermissions.SHOP_ORDER_FULFILL));
        model.addAttribute("active", "shopOrders");
        return "admin/shop-order-exceptions";
    }

    @PostMapping("/admin/shop/order-exceptions/{token}/cancel")
    @PreAuthorize(HANDLE_AUTH)
    public String cancelWhole(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String reason, RedirectAttributes ra) {
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
            @RequestParam String reason, RedirectAttributes ra) {
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
            @PathVariable String token, @RequestParam String reason, RedirectAttributes ra) {
        try {
            exceptions.contactAndContinue(token, reason, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.orderException.contactAndContinue"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/order-exceptions";
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
