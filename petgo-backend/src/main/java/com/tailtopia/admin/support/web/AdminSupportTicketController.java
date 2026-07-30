package com.tailtopia.admin.support.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.service.SupportTicketService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台客服工单管理（Story 4.7，FR-52）。Thymeleaf admin slice，{@code /admin/support-tickets/**}，redirect+flash，
 * **不返 JSON**。门控 {@code support.handle}（SUPER_ADMIN 隐式全权）。结案后果（结案/CSAT 通知）由 {@link SupportTicketService}。
 */
@Controller
public class AdminSupportTicketController {

    private static final String HANDLE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('support.handle')";

    private final AdminSupportTicketQueryService query;
    private final SupportTicketService ticketService;

    public AdminSupportTicketController(AdminSupportTicketQueryService query,
            SupportTicketService ticketService) {
        this.query = query;
        this.ticketService = ticketService;
    }

    @GetMapping("/admin/support-tickets")
    @PreAuthorize(HANDLE_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "support-tickets");
        model.addAttribute("items", query.list());
        return "admin/support-tickets";
    }

    @GetMapping("/admin/support-tickets/{ticketToken}")
    @PreAuthorize(HANDLE_AUTH)
    public String detail(@PathVariable String ticketToken, Model model) {
        model.addAttribute("active", "support-tickets");
        model.addAttribute("ticket", query.find(ticketToken));
        return "admin/support-ticket-detail";
    }

    /** 结案（客服勾「已联系+已解决」）→ RESOLVED + 发结案/CSAT 通知。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/resolve")
    @PreAuthorize(HANDLE_AUTH)
    public String resolve(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, RedirectAttributes flash) {
        try {
            ticketService.resolveTicket(ticketToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已结案（已通知用户并邀请 CSAT 评价；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/support-tickets/" + ticketToken;
    }
}
