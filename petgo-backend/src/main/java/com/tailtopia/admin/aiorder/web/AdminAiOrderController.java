package com.tailtopia.admin.aiorder.web;

import com.tailtopia.admin.aiorder.service.AdminAiOrderService;
import com.tailtopia.admin.shared.web.HxRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    public String list(@RequestParam(value = "open", required = false) String open,
            HxRequest hx, Model model) {
        model.addAttribute("active", "ai-orders");
        model.addAttribute("summary", service.summary());
        model.addAttribute("orders", service.list());
        model.addAttribute("open", open);
        return hx.isHtmx() ? "admin/fragments/ai-orders-list :: rows(true)" : "admin/ai-orders";
    }

    /**
     * 订单抽屉（Story 8.4 · AC4）：**全只读**，一个操作按钮都没有
     * （AI 是一次性解锁，无退款 / 分成入口）。
     *
     * <p>📌 整页 {@code GET /admin/ai-orders/{orderToken}} 已删除（AC5），不做旧地址跳转（D-23）。
     */
    @GetMapping("/admin/ai-orders/{orderToken}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@PathVariable String orderToken, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/ai-orders?open=" + orderToken;
        }
        model.addAttribute("active", "ai-orders");
        model.addAttribute("order", service.detail(orderToken));
        return "admin/fragments/drawer-ai-order :: drawer";
    }

    @GetMapping("/admin/ai-orders/export")
    @PreAuthorize(EXPORT_AUTH)
    @ResponseBody
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-orders.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                // 🔴 BOM 不能省：表头随界面语言输出后首行是中文/印尼文，
                //    Excel 打开无 BOM 的 UTF-8 CSV 会按本地代码页解，整行乱码。
                .body('\uFEFF' + service.exportCsv());
    }
}
