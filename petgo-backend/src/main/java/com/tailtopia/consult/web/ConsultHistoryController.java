package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.ConsultHistoryPage;
import com.tailtopia.consult.service.ConsultHistoryService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧问诊历史（Story 5.8，{@code hasRole('USER')}）。
 *
 * <p>{@code GET /api/v1/consult/history?cursor=&limit=}：AI + 兽医两类条目混排倒序，游标分页。
 * 历史<b>独立于存档</b>。
 */
@RestController
@RequestMapping("/api/v1/consult")
public class ConsultHistoryController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ConsultHistoryService historyService;

    public ConsultHistoryController(ConsultHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/history")
    public ConsultHistoryPage history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return historyService.history(currentUserId(jwt), cursor, size);
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
