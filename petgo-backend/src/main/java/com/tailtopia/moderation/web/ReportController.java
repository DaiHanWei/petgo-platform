package com.petgo.moderation.web;

import com.petgo.moderation.dto.ReportRequest;
import com.petgo.moderation.service.ReportService;
import com.petgo.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容举报提交端点（Story 3.7，FR-25）。**需 JWT**（未登录 → 401 → 前端 FR-0C）。
 * 写工单 status=PENDING，**不触发任何自动下架**；V1 仅内容举报。
 */
@RestController
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/api/v1/content-posts/{postId}/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@AuthenticationPrincipal Jwt jwt, @PathVariable long postId,
            @Valid @RequestBody ReportRequest req) {
        reportService.submit(postId, currentUserId(jwt), req.reasonType());
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
