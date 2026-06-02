package com.petgo.notify.web;

import com.petgo.notify.domain.NotificationType;
import com.petgo.notify.dto.PushPayload;
import com.petgo.notify.service.NotificationService;
import com.petgo.shared.error.AppException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试推送端点（Story 6.1，<b>仅 dev profile</b>）。在无业务事件源阶段验「写库 + 角标 + IM 离线投递 + 客户端直达」。
 * 生产 profile 不暴露。
 */
@Profile("dev")
@RestController
@RequestMapping("/api/v1/notify")
public class NotifyTestController {

    private final NotificationService notificationService;

    public NotifyTestController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/_test-push")
    public PushPayload testPush(@AuthenticationPrincipal Jwt jwt) {
        long userId = currentUserId(jwt);
        var n = notificationService.send(userId, NotificationType.VET_REPLY,
                "测试推送", "这是一条测试通知", NotificationType.VET_REPLY.name(), "test-ref");
        return new PushPayload(n.getType().name(), n.getDeepLinkToken(), n.getTitle(), n.getBody());
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
