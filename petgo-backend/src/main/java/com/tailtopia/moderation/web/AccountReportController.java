package com.tailtopia.moderation.web;

import com.tailtopia.moderation.dto.AccountReportRequest;
import com.tailtopia.moderation.service.AccountReportService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号举报提交（Story 2.1，FR-58）。{@code POST /api/v1/account-reports}。
 *
 * <p><b>返回 204</b>，无响应体。既有内容举报返回 202 + 空体（「已受理、等人工看」），
 * 账号举报这边前端要的是一个明确的成功态收尾，204 更直白；两者都不下发任何工单信息 ——
 * <b>举报对被举报人完全不可见</b>，对举报人也不该回显「他已经被几个人报过」这类内部数据。
 *
 * <p>鉴权由 {@code SecurityConfig} 的 {@code anyRequest().authenticated()} 覆盖。
 */
@RestController
@RequestMapping("/api/v1/account-reports")
public class AccountReportController {

    private final AccountReportService accountReportService;

    public AccountReportController(AccountReportService accountReportService) {
        this.accountReportService = accountReportService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AccountReportRequest req) {
        // ⚠️ 不要在这里打任何带 req.detail() 的日志：那是用户自由文本（日志禁 PII）。
        accountReportService.submit(currentUserId(jwt), req.targetUserId(), req.reason(), req.detail());
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
