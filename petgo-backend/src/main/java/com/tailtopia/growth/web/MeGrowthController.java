package com.tailtopia.growth.web;

import com.tailtopia.growth.service.GrowthLedgerPageQuery;
import com.tailtopia.growth.service.GrowthPageQuery;
import com.tailtopia.growth.web.dto.GrowthLedgerPage;
import com.tailtopia.growth.web.dto.GrowthPageResponse;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人等级页与 EXP 明细（V1.4.0 ① Story 1.4 · AD-16）。<b>只作用于 JWT {@code sub} 本人，不接受任何 userId / token 参数</b>
 * （别人的成长值明细无任何接口可取，NFR9）。SecurityConfig 显式限 {@code ROLE_USER}（兽医 token 的 sub 与 users.id 撞号）。
 */
@RestController
@RequestMapping("/api/v1/me/growth")
public class MeGrowthController {

    private final GrowthPageQuery page;
    private final GrowthLedgerPageQuery ledger;

    public MeGrowthController(GrowthPageQuery page, GrowthLedgerPageQuery ledger) {
        this.page = page;
        this.ledger = ledger;
    }

    @GetMapping
    public GrowthPageResponse growth(@AuthenticationPrincipal Jwt jwt) {
        return page.page(currentUserId(jwt));
    }

    @GetMapping("/ledger")
    public GrowthLedgerPage ledger(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor) {
        long userId = currentUserId(jwt);
        if (!page.participating(userId)) {
            return GrowthLedgerPage.empty();
        }
        return ledger.page(userId, cursor);
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
