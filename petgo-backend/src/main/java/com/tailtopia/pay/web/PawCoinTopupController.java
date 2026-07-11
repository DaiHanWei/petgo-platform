package com.tailtopia.pay.web;

import com.tailtopia.pay.dto.CreateTopupRequest;
import com.tailtopia.pay.dto.TopupResponse;
import com.tailtopia.pay.service.PawCoinTopupService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PawCoin 充值下单端点（Story 1.3）。{@code POST /api/v1/me/pawcoin/topups}（JWT {@code role=user}）。
 * <b>仅作用当前 JWT {@code sub}，绝不接受任意 userId（防越权）</b>——照 {@code auth/web/MeController}（决策 C1）。
 * 写限流 + {@code Idempotency-Key} 幂等在 service/意图层落实。
 */
@RestController
@RequestMapping("/api/v1/me")
public class PawCoinTopupController {

    private final PawCoinTopupService topupService;

    public PawCoinTopupController(PawCoinTopupService topupService) {
        this.topupService = topupService;
    }

    @PostMapping("/pawcoin/topups")
    public TopupResponse topup(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTopupRequest req) {
        return topupService.create(currentUserId(jwt), req, idempotencyKey);
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
