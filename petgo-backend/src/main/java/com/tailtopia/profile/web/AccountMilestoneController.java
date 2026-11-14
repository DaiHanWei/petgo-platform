package com.tailtopia.profile.web;

import com.tailtopia.profile.dto.AccountMilestoneCelebrationRequest;
import com.tailtopia.profile.dto.AccountMilestoneCelebrationResponse;
import com.tailtopia.profile.dto.AccountMilestoneListResponse;
import com.tailtopia.profile.service.AccountMilestoneQueryService;
import com.tailtopia.profile.service.MilestoneCelebrationService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号级里程碑（V1.5.0 onboarding-recall Story 2.3 · FR-178 · OR-10）。{@code /api/v1/me/account-milestones}
 *
 * <p>没宠物也能用（账号级里程碑页 / 「我的」页入口）。落在默认 authenticated 规则下，不改 {@code SecurityConfig}；
 * owner 取自 JWT。回报为 best-effort 语义（未知 / 非账号级编码静默忽略），同宠物里程碑庆祝回报端点。
 */
@RestController
@RequestMapping("/api/v1/me/account-milestones")
public class AccountMilestoneController {

    private final AccountMilestoneQueryService query;
    private final MilestoneCelebrationService celebrations;

    public AccountMilestoneController(AccountMilestoneQueryService query, MilestoneCelebrationService celebrations) {
        this.query = query;
        this.celebrations = celebrations;
    }

    @GetMapping
    public AccountMilestoneListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return query.list(currentUserId(jwt));
    }

    @PostMapping("/celebrations")
    public AccountMilestoneCelebrationResponse reportCelebrations(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountMilestoneCelebrationRequest req) {
        return new AccountMilestoneCelebrationResponse(
                celebrations.reportAccountCelebrated(currentUserId(jwt), req.codes()));
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
