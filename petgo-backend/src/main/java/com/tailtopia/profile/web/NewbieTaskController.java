package com.tailtopia.profile.web;

import com.tailtopia.profile.dto.NewbieTaskResponse;
import com.tailtopia.profile.service.ClientAppVersion;
import com.tailtopia.profile.service.LegacyNewbieTaskCompat;
import com.tailtopia.profile.service.NewbieTaskService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 新手任务端点（Story 7.3 · FR-47；V1.5.0 onboarding-recall Story 2.1 · FR-176 起按客户端版本分流）。
 * 资源化于当前用户 {@code /api/v1/me/newbie-tasks}（决策 C1）。owner 取自 JWT，绝不信任客户端。
 *
 * <h2>新旧版本分流（OR-9）</h2>
 * {@code X-App-Version} ≥ 1.5.0 → 7 项账号级（没宠物也 200）；&lt; 1.5.0（缺头 / 解析不了也算旧版）→
 * {@link LegacyNewbieTaskCompat} 旧 6 项旧逻辑（含无宠物 404）。旧包把未知 key 原样显示，所以必须分流。
 */
@RestController
@RequestMapping("/api/v1/me/newbie-tasks")
public class NewbieTaskController {

    private final NewbieTaskService newbieTaskService;
    private final LegacyNewbieTaskCompat legacy;

    public NewbieTaskController(NewbieTaskService newbieTaskService, LegacyNewbieTaskCompat legacy) {
        this.newbieTaskService = newbieTaskService;
        this.legacy = legacy;
    }

    @GetMapping
    public NewbieTaskResponse progress(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = ClientAppVersion.HEADER, required = false) String appVersion) {
        long userId = currentUserId(jwt);
        return ClientAppVersion.isV150OrLater(appVersion)
                ? newbieTaskService.progress(userId, Instant.now())
                : legacy.progress(userId);
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
