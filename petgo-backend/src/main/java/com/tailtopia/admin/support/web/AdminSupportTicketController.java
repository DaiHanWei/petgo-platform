package com.tailtopia.admin.support.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService.State;
import com.tailtopia.admin.support.service.AdminTicketRefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.service.SupportTicketService;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
 * 后台客服工单管理（Story 4.7，FR-52）。Thymeleaf admin slice，{@code /admin/support-tickets/**}，redirect+flash，
 * **不返 JSON**。门控 {@code support.handle}（SUPER_ADMIN 隐式全权）。结案后果（结案/CSAT 通知）由 {@link SupportTicketService}。
 *
 * <p>AB-5B 退款判定（bug 20260728-384/388）：补挂关联订单走 {@code support.handle}；
 * 批准/驳回退款需求属客服 need 判定，与 {@code AdminRefundController} 同权 {@code refund.submit}。
 *
 * <p>V1.3.0 Story 2.7：重构为模板 A 双栏工作台——三态页签（待处理 / 待联系 / 已结案）+ 左栏时间倒序队列 + 右栏五区。
 * 四个写端点路径 / 权限零变更（D-36 例外：{@code refund-reject} 正式加必填 {@code reason}），方法内按 {@link HxRequest} 分叉；
 * 旧 {@code GET /admin/support-tickets/{ticketToken}} 整页退役（并入 {@code /{ticketToken}/detail} fragment）。
 */
@Controller
public class AdminSupportTicketController {

    private static final int PAGE_SIZE = 20;

    private static final String HANDLE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('support.handle')";
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('support.view') or hasAuthority('support.handle')";
    private static final String REFUND_SUBMIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.submit')";

    private final AdminSupportTicketQueryService query;
    private final SupportTicketService ticketService;
    private final AdminTicketRefundService ticketRefund;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminSupportTicketController(AdminSupportTicketQueryService query,
            SupportTicketService ticketService, AdminTicketRefundService ticketRefund,
            Messages msg) {
        this.query = query;
        this.ticketService = ticketService;
        this.ticketRefund = ticketRefund;
        this.msg = msg;
    }

    /** 工作台整页；{@code state=pending|contact|closed}（默认待处理）。htmx 请求（滚动翻页）只回左栏行片段。 */
    @GetMapping("/admin/support-tickets")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) String open,
            HxRequest hx, Authentication auth) {
        model.addAttribute("active", "support-tickets");
        State st = State.of(state);
        String openToken = open == null || open.isBlank() ? null : open.trim();
        if (openToken != null && (state == null || state.isBlank())) {
            // ?open= 深链（D-23，A6 来源工单回链）：未指明页签时按该单状态落页签，行才会在左栏出现
            try {
                st = query.stateOf(query.find(openToken, canSeeContactPii(auth)));
            } catch (AppException ignore) {
                // 不存在的 token：留在默认页签，右栏由 JS 请求 detail 时得到 404
            }
        }
        populateQueue(st, page, model);
        model.addAttribute("open", openToken);
        return hx.isHtmx() ? "admin/fragments/support-queue :: rows" : "admin/support-tickets";
    }

    /** 左栏队列 fragment（切页签 / 滚动翻页）。 */
    @GetMapping("/admin/support-tickets/queue")
    @PreAuthorize(VIEW_AUTH)
    public String queueFragment(@RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        populateQueue(State.of(state), page, model);
        return page > 0 ? "admin/fragments/support-queue :: rows" : "admin/fragments/support-queue :: list";
    }

    /** 右栏五区 fragment（取代旧整页详情，已退役）。PII 最小可见面：联系方式原文仅 handle/SUPER_ADMIN，view-only 看脱敏值。 */
    @GetMapping("/admin/support-tickets/{ticketToken}/detail")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String ticketToken, Model model, Authentication auth) {
        populateDetail(ticketToken, model, auth);
        return "admin/fragments/support-panel :: detail";
    }

    private static boolean canSeeContactPii(Authentication auth) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || "support.handle".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** 结案（客服勾「已联系+已解决」）→ RESOLVED + 发结案/CSAT 通知。htmx → 处置 fragment（下一条）。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/resolve")
    @PreAuthorize(HANDLE_AUTH)
    public String resolve(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, HxRequest hx, Model model, HttpServletResponse response,
            RedirectAttributes flash) {
        if (hx.isHtmx()) {
            ticketService.resolveTicket(ticketToken, admin.getAdminAccountId());
            model.addAttribute("removedToken", ticketToken);
            // 「下一条」按当前页签取（待联系页签结案后不能指到不在左栏的单）
            model.addAttribute("nextId", query.nextPendingToken(State.of(stateParam(hx.currentUrl()))));
            model.addAttribute("counts", query.counts());
            model.addAttribute("message", msg.get("admin.flash.ticket.resolved"));
            AdminFragmentResponses.triggerBadgeRefresh(response);
            return "admin/fragments/support-done :: done";
        }
        try {
            ticketService.resolveTicket(ticketToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.resolved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets";
    }

    // ===== AB-5B 退款判定（bug 20260728-384/388）=====

    /** 补挂关联订单（按订单 token；归属校验在 service）。htmx → 刷新右栏（停留本条）。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/link-order")
    @PreAuthorize(HANDLE_AUTH)
    public String linkOrder(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, @RequestParam("orderToken") String orderToken,
            HxRequest hx, Model model, Authentication auth, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            ticketRefund.linkOrder(ticketToken, orderToken, admin.getAdminAccountId());
            return stay(ticketToken, msg.get("admin.flash.ticket.orderLinked"), model, auth);
        }
        try {
            ticketRefund.linkOrder(ticketToken, orderToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.orderLinked"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets";
    }

    /** 批准退款需求：建退款单（如无）+ need→APPROVED，订单进 REFUNDING，App 解锁「选退款方式」（不发通知，AB-5B）。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/refund-approve")
    @PreAuthorize(REFUND_SUBMIT_AUTH)
    public String approveRefundNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, HxRequest hx, Model model, Authentication auth,
            RedirectAttributes flash) {
        if (hx.isHtmx()) {
            ticketRefund.approveRefundNeed(ticketToken, admin.getAdminAccountId());
            return stay(ticketToken, msg.get("admin.flash.ticket.refundApproved"), model, auth);
        }
        try {
            ticketRefund.approveRefundNeed(ticketToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.refundApproved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets";
    }

    /**
     * 驳回退款需求：建退款单留痕（如无）+ need→REJECTED + 发用户通知（A-2 不撒谎）。
     * V1.3.0 D-36：正式加必填 {@code reason}（≤200 字），存退款单 {@code reject_reason} + 审计；
     * {@code required=false} + 服务端判空 → 422 行内 err（缺参 400 会绕过范式）。
     */
    @PostMapping("/admin/support-tickets/{ticketToken}/refund-reject")
    @PreAuthorize(REFUND_SUBMIT_AUTH)
    public String rejectRefundNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken,
            @RequestParam(value = "reason", required = false) String reason,
            HxRequest hx, Model model, Authentication auth, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            ticketRefund.rejectRefundNeed(ticketToken, admin.getAdminAccountId(), requireReason(reason));
            return stay(ticketToken, msg.get("admin.flash.ticket.refundRejected"), model, auth);
        }
        try {
            ticketRefund.rejectRefundNeed(ticketToken, admin.getAdminAccountId(), requireReason(reason));
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.refundRejected"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets";
    }

    /** 停留本条：重渲右栏五区 + toast（oob 到 #admin-toast-host）。 */
    private String stay(String ticketToken, String message, Model model, Authentication auth) {
        populateDetail(ticketToken, model, auth);
        model.addAttribute("toast", message);
        return "admin/fragments/support-panel :: detail";
    }

    private void populateQueue(State state, int page, Model model) {
        model.addAttribute("state", state.param());
        model.addAttribute("page", Math.max(page, 0));
        model.addAttribute("queue", query.page(state, PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        model.addAttribute("counts", query.counts());
    }

    private void populateDetail(String ticketToken, Model model, Authentication auth) {
        model.addAttribute("ticket", query.find(ticketToken, canSeeContactPii(auth)));
    }

    /** 从 {@code HX-Current-URL} 取 {@code state=}（无则 pending）。 */
    static String stateParam(String currentUrl) {
        if (currentUrl == null) {
            return null;
        }
        try {
            String q = java.net.URI.create(currentUrl.trim()).getRawQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    if (kv.startsWith("state=")) {
                        return java.net.URLDecoder.decode(kv.substring(6), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IllegalArgumentException ignore) {
            // 非法 URL 当默认页签
        }
        return null;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("驳回退款需求必须填写原因").code("admin.err.ticket.rejectReasonRequired");
        }
        String r = reason.trim();
        if (r.length() > 200) {
            throw AppException.validation("驳回原因不能超过 200 字").code("admin.err.ticket.rejectReasonTooLong");
        }
        return r;
    }
}
