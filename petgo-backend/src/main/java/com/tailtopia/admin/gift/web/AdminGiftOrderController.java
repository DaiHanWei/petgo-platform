package com.tailtopia.admin.gift.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.gift.dto.GiftOrderAdminRow;
import com.tailtopia.admin.gift.service.AdminGiftOrderExportService;
import com.tailtopia.admin.gift.service.AdminGiftOrderQueryService;
import com.tailtopia.admin.gift.service.AdminGiftOrderQueryService.Filter;
import com.tailtopia.admin.gift.service.AdminGiftOrderQueryService.StatusGroup;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import java.time.LocalDate;
import java.util.Arrays;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 后台送礼记录（V1.4.0 ③ gift Story 3.2 · AB-36，UI M1 ~ M3）。照「支付记录」页（模板 B）：整页 GET 筛选 → 摘要条 → 表格 → 分页；
 * 状态筛「已付款未送达」换异常单视图；「导出当前结果」按当前筛选导出 xlsx（写审计）。
 *
 * <p>🔴 <b>只读</b>：本 Controller 没有任何 POST，页面无改单 / 补退 / 重试按钮（PRD「只读，不提供后台改单」；
 * 退回失败由 Story 1.2 的 {@code GiftRefundRetryJob} 自动重试）。行不可点（无抽屉）。
 *
 * <p>🛡 单码 {@code gift.order_view}（新码不预授，超管隐式拥有）：{@link #VIEW_AUTH} 与 {@code AdminPageCatalog} 里
 * {@code gift-orders} 页的 {@code navCodes} 逐字同集；导出同用此门（PRD 只定义一个只读码，Story 3.2「待确认」1）；
 * 模板里导出按钮的 {@code sec:authorize} 与本常量逐字一致。
 */
@Controller
public class AdminGiftOrderController {

    public static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.GIFT_ORDER_VIEW + "')";

    /** 每页条数（与支付记录一致）。 */
    private static final int PAGE_SIZE = 20;

    private final AdminGiftOrderQueryService service;
    private final AdminGiftOrderExportService exportService;

    public AdminGiftOrderController(AdminGiftOrderQueryService service, AdminGiftOrderExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    /**
     * 列表（AC2 / AC3）。全部参数<b>宽松解析</b>：不认识的渠道 / 状态当不限，绝不报错。
     * htmx 局部只回表格（摘要条随 oob 一并换）。
     */
    @GetMapping("/admin/gift-orders")
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(required = false) Long senderId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            HxRequest hx, Model model) {
        model.addAttribute("active", "gift-orders");
        Filter filter = filter(senderId, ownerId, channel, status, from, to);
        // ⚠️ 回显用解析后的值：分页 / 导出链接带的就是实际生效的筛选（不认识的值不会一路带下去）。
        model.addAttribute("senderId", senderId);
        model.addAttribute("ownerId", ownerId);
        model.addAttribute("channel", filter.channel());
        model.addAttribute("status", filter.status() == null ? null : filter.status().name());
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("channelOptions", AdminGiftOrderQueryService.CHANNELS);
        model.addAttribute("statusOptions", Arrays.stream(StatusGroup.values()).map(Enum::name).toList());
        model.addAttribute("anomalyView", filter.anomalyView());

        Page<GiftOrderAdminRow> result = service.search(filter, Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("orders", result.getContent());
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("totalElements", result.getTotalElements());
        model.addAttribute("hasPrev", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        // 🔴 摘要覆盖整个筛选结果，不是当前这一页。
        model.addAttribute("summary", service.summarize(filter));
        return hx.isHtmx() ? "admin/fragments/gift-orders-list :: rows(oob=true)" : "admin/gift-orders";
    }

    /** 按当前筛选导出 xlsx（AC4）：参数与列表逐个对齐，写审计。 */
    @GetMapping("/admin/gift-orders/export.xlsx")
    @PreAuthorize(VIEW_AUTH)
    public ResponseEntity<byte[]> exportXlsx(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) Long senderId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] body = exportService.exportXlsx(admin.getAdminAccountId(),
                filter(senderId, ownerId, channel, status, from, to));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gift-orders.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    static Filter filter(Long senderId, Long ownerId, String channel, String status, LocalDate from, LocalDate to) {
        return new Filter(senderId, ownerId, AdminGiftOrderQueryService.parseChannel(channel),
                StatusGroup.parse(status), from, to);
    }
}
