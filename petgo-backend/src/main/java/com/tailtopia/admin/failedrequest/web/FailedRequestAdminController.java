package com.tailtopia.admin.failedrequest.web;

import com.tailtopia.admin.failedrequest.service.FailedConsultRequestService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 问诊请求未成功队列（Story 2.9，AB-2G）。活动/归档分区；SYSTEM_FAILURE 视觉预警 + 强制跟进方可归档。
 * 门控 {@code @PreAuthorize(vet.view)}（接诊能力监控归兽医管理权限组）；跟进/归档/备注写审计（service 层）。
 */
@Controller
public class FailedRequestAdminController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('vet.view')";

    private final FailedConsultRequestService service;

    public FailedRequestAdminController(FailedConsultRequestService service) {
        this.service = service;
    }

    @GetMapping("/admin/failed-requests")
    @PreAuthorize(AUTH)
    public String list(Model model) {
        model.addAttribute("active", "failed-requests");
        model.addAttribute("activeItems", service.active());
        model.addAttribute("archivedItems", service.archived());
        return "admin/failed-requests";
    }

    @PostMapping("/admin/failed-requests/{id}/follow-up")
    @PreAuthorize(AUTH)
    public String followUp(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        service.followUp(id, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已标记跟进");
        return "redirect:/admin/failed-requests";
    }

    @PostMapping("/admin/failed-requests/{id}/archive")
    @PreAuthorize(AUTH)
    public String archive(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            service.archive(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已归档");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/failed-requests";
    }

    @PostMapping("/admin/failed-requests/{id}/note")
    @PreAuthorize(AUTH)
    public String note(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("note") String note, RedirectAttributes flash) {
        service.note(id, note, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已更新备注");
        return "redirect:/admin/failed-requests";
    }
}
