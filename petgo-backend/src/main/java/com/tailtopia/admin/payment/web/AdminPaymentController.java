package com.tailtopia.admin.payment.web;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.admin.payment.service.AdminPaymentQueryService;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 后台支付记录通用查询（Story 9.6，AB-8E）。Thymeleaf admin slice，{@code /admin/payments}。
 * 门控 {@code payment.view}（SUPER_ADMIN 隐式全权）。按 userId 跨类型只读查。
 */
@Controller
public class AdminPaymentController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('payment.view')";

    /** 默认视图每页条数。 */
    private static final int PAGE_SIZE = 20;

    private final AdminPaymentQueryService service;

    public AdminPaymentController(AdminPaymentQueryService service) {
        this.service = service;
    }

    @GetMapping("/admin/payments")
    @PreAuthorize(VIEW_AUTH)
    public String search(@RequestParam(required = false) Long userId,
                         @RequestParam(required = false) String purpose,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("active", "payments");
        model.addAttribute("userId", userId);
        model.addAttribute("purpose", purpose);
        model.addAttribute("status", status);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("purposeOptions", PaymentPurpose.values());
        model.addAttribute("statusOptions", PaymentStatus.values());

        // ⚠️ 用途/状态**宽松解析**：值不认识就当"不限"，不报错。
        //    这两个值只能从下拉里来，出现非法值只可能是有人手改了 URL ——
        //    为此给运营一个报错页不划算，何况"不限"是最无害的退化。
        var filter = new AdminPaymentQueryService.Filter(
                userId, parseEnum(PaymentPurpose.class, purpose),
                parseEnum(PaymentStatus.class, status), from, to);

        Page<AdminPaymentRow> result = service.search(filter, Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("payments", result.getContent());
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("totalElements", result.getTotalElements());
        model.addAttribute("hasPrev", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        // 🔴 汇总覆盖**整个筛选结果**，不是当前这一页（见 summarize 的说明）。
        model.addAttribute("summary", service.summarize(filter));
        return "admin/payments";
    }

    /** 宽松解析：空 / 不认识 → null（= 不限），绝不抛。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
