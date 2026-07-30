package com.tailtopia.admin.consult.web;

import com.tailtopia.admin.consult.service.AdminConsultOrderService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.consult.domain.ConsultOrderVerifyStatus;
import com.tailtopia.shared.error.AppException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台兽医咨询订单只读管理（Story 9.3，AB-8B）。Thymeleaf admin slice，{@code /admin/consult-orders/**}。
 * 门控：查看/标记 {@code order.view}、导出 {@code order.export}（SUPER_ADMIN 隐式全权）。<b>无退款入口</b>。
 */
@Controller
public class AdminConsultOrderController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.view')";
    private static final String EXPORT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.export')";

    private final AdminConsultOrderService service;

    public AdminConsultOrderController(AdminConsultOrderService service) {
        this.service = service;
    }

    @GetMapping("/admin/consult-orders")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "consult-orders");
        model.addAttribute("orders", service.list());
        return "admin/consult-orders";
    }

    @GetMapping("/admin/consult-orders/{orderToken}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String orderToken, Model model) {
        model.addAttribute("active", "consult-orders");
        model.addAttribute("order", service.detail(orderToken));
        return "admin/consult-order-detail";
    }

    @PostMapping("/admin/consult-orders/{orderToken}/verify")
    @PreAuthorize(VIEW_AUTH)
    public String verify(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String orderToken, @RequestParam(required = false) String status,
            @RequestParam(required = false) String note, RedirectAttributes flash) {
        try {
            ConsultOrderVerifyStatus vs = (status == null || status.isBlank())
                    ? null : ConsultOrderVerifyStatus.valueOf(status);
            service.markVerify(orderToken, vs, note, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已更新待核查标记（纯注记，不改订单状态；操作留审计）");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", "非法的核查状态");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/consult-orders/" + orderToken;
    }

    @GetMapping("/admin/consult-orders/export")
    @PreAuthorize(EXPORT_AUTH)
    @ResponseBody
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"consult-orders.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(service.exportCsv());
    }
}
