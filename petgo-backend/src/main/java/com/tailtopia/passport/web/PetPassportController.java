package com.tailtopia.passport.web;

import com.tailtopia.passport.dto.PetPassportResponse;
import com.tailtopia.passport.service.PetPassportService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物护照（V1.3.2 batch-a Story 1.2 · AC3）。仅 {@code role=USER}（SecurityConfig 精确 matcher）。
 */
@RestController
public class PetPassportController {

    private final PetPassportService passports;

    public PetPassportController(PetPassportService passports) {
        this.passports = passports;
    }

    /**
     * 本人宠物的护照 + 全部章。
     *
     * <p>🔴 <b>GET 里签发</b>（D-6「首次进护照页」）：{@code ensureIssued} 幂等，重复 GET 无副作用；
     * 因此 {@link PetPassportService#pageFor} 是<b>写事务、不是 readOnly</b>。别另开 POST 让 App 先调 ——
     * 那会多一种「页面开了但没签发」的中间态。
     *
     * <p>无宠物档案 → 404 {@code not-found}（与时间线 {@code requireProfile} 同口径）。
     */
    @GetMapping("/api/v1/pet-profiles/me/passport")
    public PetPassportResponse passport(@AuthenticationPrincipal Jwt jwt) {
        return passports.pageFor(currentUserId(jwt));
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
