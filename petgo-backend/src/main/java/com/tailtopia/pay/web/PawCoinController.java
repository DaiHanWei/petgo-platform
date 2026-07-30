package com.tailtopia.pay.web;

import com.tailtopia.pay.dto.PawCoinWalletView;
import com.tailtopia.pay.service.PawCoinQueryService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PawCoin 余额与流水读端点（Story 1.4）。{@code GET /api/v1/me/pawcoin?cursor=&limit=}（JWT
 * {@code role=user}）。<b>仅作用当前 JWT {@code sub}，绝不接受任意 userId（防越权，决策 C1）</b>——照
 * {@code auth/web/MeController} / {@code PawCoinTopupController}。余额是本人私有数据，{@code /api/v1/me/**}
 * 默认需 JWT（不改 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class PawCoinController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final PawCoinQueryService query;

    public PawCoinController(PawCoinQueryService query) {
        this.query = query;
    }

    @GetMapping("/pawcoin")
    public PawCoinWalletView pawcoin(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return query.view(currentUserId(jwt), cursor, size);
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
