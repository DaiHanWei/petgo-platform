package com.tailtopia.admin.aiorder.web;

import com.tailtopia.admin.aiorder.service.AdminAiOrderService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 后台 AI 问诊收入统计 + 订单只读查询（Story 9.4，AB-8C/8G）。Thymeleaf admin slice，{@code /admin/ai-orders/**}。
 * 门控：查看 {@code order.view}、导出 {@code order.export}（SUPER_ADMIN 隐式全权）。与兽医订单页命名空间隔离。
 */
@Controller
public class AdminAiOrderController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.view')";
    private static final String EXPORT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.export')";

    private final AdminAiOrderService service;

    public AdminAiOrderController(AdminAiOrderService service) {
        this.service = service;
    }

    @GetMapping("/admin/ai-orders")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "ai-orders");
        model.addAttribute("summary", service.summary());
        model.addAttribute("orders", service.list());
        return "admin/ai-orders";
    }

    @GetMapping("/admin/ai-orders/{orderToken}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String orderToken, Model model) {
        model.addAttribute("active", "ai-orders");
        model.addAttribute("order", service.detail(orderToken));
        return "admin/ai-order-detail";
    }

    @GetMapping("/admin/ai-orders/export")
    @PreAuthorize(EXPORT_AUTH)
    @ResponseBody
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-orders.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(service.exportCsv());
    }
}
