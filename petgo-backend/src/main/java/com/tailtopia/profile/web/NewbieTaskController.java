package com.tailtopia.profile.web;

import com.tailtopia.profile.dto.NewbieTaskResponse;
import com.tailtopia.profile.service.NewbieTaskService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 新手任务端点（Story 7.3 · FR-47）。资源化于当前用户 {@code /api/v1/me/newbie-tasks}（决策 C1，
 * 各模块自持 /me 子路径）。owner 取自 JWT，绝不信任客户端。无档案 → 404 防枚举。
 */
@RestController
@RequestMapping("/api/v1/me/newbie-tasks")
public class NewbieTaskController {

    private final NewbieTaskService newbieTaskService;

    public NewbieTaskController(NewbieTaskService newbieTaskService) {
        this.newbieTaskService = newbieTaskService;
    }

    /** 当前用户 6 新手任务进度 + Lulus Pemula 解锁态。 */
    @GetMapping
    public NewbieTaskResponse progress(@AuthenticationPrincipal Jwt jwt) {
        return newbieTaskService.progress(currentUserId(jwt));
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
