package com.tailtopia.pay.web;

import com.tailtopia.pay.dto.CreateTopupRequest;
import com.tailtopia.pay.dto.TopupOptions;
import com.tailtopia.pay.dto.TopupResponse;
import com.tailtopia.pay.dto.TopupStatusView;
import com.tailtopia.pay.service.PawCoinTopupService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PawCoin 充值端点（Story 1.3 下单 + Story 1.5 选项/状态轮询）。{@code /api/v1/me/pawcoin/*}
 * （JWT {@code role=user}）。<b>仅作用当前 JWT {@code sub}，绝不接受任意 userId（防越权）</b>——照
 * {@code auth/web/MeController}（决策 C1）。写限流 + {@code Idempotency-Key} 幂等在 service/意图层落实。
 */
@RestController
@RequestMapping("/api/v1/me")
public class PawCoinTopupController {

    private final PawCoinTopupService topupService;
    private final PaymentIntentService paymentIntentService;

    public PawCoinTopupController(PawCoinTopupService topupService,
            PaymentIntentService paymentIntentService) {
        this.topupService = topupService;
        this.paymentIntentService = paymentIntentService;
    }

    @PostMapping("/pawcoin/topups")
    public TopupResponse topup(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTopupRequest req) {
        // 资金创建端点强制幂等键（Review P3）：缺失/空则拒，杜绝双击/重试造成重复下单 + 重复网关 charge。
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AppException.validation("资金下单必须携带 Idempotency-Key 头");
        }
        return topupService.create(currentUserId(jwt), req, idempotencyKey);
    }

    /** 充值选项（Story 1.5）：可选档位 + 是否暂停（浮存门槛，AB-6C）。前端据此渲染档位或暂停态。 */
    @GetMapping("/pawcoin/topup-options")
    public TopupOptions topupOptions(@AuthenticationPrincipal Jwt jwt) {
        currentUserId(jwt); // 登录门控（档位对所有用户一致）
        return topupService.options();
    }

    /** 支付状态轮询（Story 1.5）：按对外 token 查本人意图状态（归属校验，越权 → 404）。 */
    @GetMapping("/pawcoin/topups/{token}/status")
    public TopupStatusView topupStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return new TopupStatusView(paymentIntentService.statusOf(currentUserId(jwt), token).name());
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
