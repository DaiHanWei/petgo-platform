package com.tailtopia.notify.web;

import com.tailtopia.notify.dto.NotificationPage;
import com.tailtopia.notify.service.NotificationCenterService;
import com.tailtopia.shared.error.AppException;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知中心端点（Story 6.6，需 JWT，仅本人通知）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/notifications}：倒序游标分页四类通知。</li>
 *   <li>{@code GET /api/v1/notifications/unread-count}：未读角标计数（Redis + 库回算容错）。</li>
 *   <li>{@code POST /api/v1/notifications/{token}/read}：标记单条已读（token 定位）。</li>
 *   <li>{@code POST /api/v1/notifications/read-all}：全部已读。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final NotificationCenterService service;

    public NotificationController(NotificationCenterService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return service.list(currentUserId(jwt), cursor, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", service.unreadCount(currentUserId(jwt)));
    }

    @PostMapping("/{token}/read")
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        service.markRead(currentUserId(jwt), token);
    }

    @PostMapping("/read-all")
    public void readAll(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(currentUserId(jwt));
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
