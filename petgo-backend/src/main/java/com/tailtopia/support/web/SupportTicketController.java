package com.tailtopia.support.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.dto.CreateTicketRequest;
import com.tailtopia.support.dto.SubmitCsatRequest;
import com.tailtopia.support.dto.SupportTicketView;
import com.tailtopia.support.service.SupportTicketService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服工单用户端点（Story 4.1，FR-52）。**需 JWT role=USER**（SecurityConfig 门控；vet/guest → 403）。
 * 建单/查单；admin 处理 UI 在 4-4/4-7。前端投诉 UI 在 4-2（本 story 无前端）。
 */
@RestController
public class SupportTicketController {

    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    /** 建工单（≤5 附件 objectKey / 标签 / 自填联系方式 / 关联订单）。返回用户视图。 */
    @PostMapping("/api/v1/support-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicketView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTicketRequest req) {
        long userId = currentUserId(jwt);
        String token = service.createTicket(userId, req.subject(), req.body(), req.contactType(),
                req.contactValue(), req.needContact(), req.relatedOrderToken(),
                req.labels(), req.attachmentObjectKeys());
        return service.viewForUser(userId, token);
    }

    /** 我的工单列表（created_at 倒序）。 */
    @GetMapping("/api/v1/support-tickets")
    public List<SupportTicketView> myTickets(@AuthenticationPrincipal Jwt jwt) {
        return service.myTickets(currentUserId(jwt));
    }

    /** 工单详情（owner 校验，非本人 → 404）。 */
    @GetMapping("/api/v1/support-tickets/{ticketToken}")
    public SupportTicketView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String ticketToken) {
        return service.viewForUser(currentUserId(jwt), ticketToken);
    }

    /** 提交 CSAT（Story 4.7，owner + 仅 RESOLVED 未评窗口内；提交即 CLOSED）。返回更新后视图。 */
    @PostMapping("/api/v1/support-tickets/{ticketToken}/csat")
    public SupportTicketView submitCsat(@AuthenticationPrincipal Jwt jwt, @PathVariable String ticketToken,
            @Valid @RequestBody SubmitCsatRequest req) {
        long userId = currentUserId(jwt);
        service.submitCsat(userId, ticketToken, req.score(), req.comment());
        return service.viewForUser(userId, ticketToken);
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
