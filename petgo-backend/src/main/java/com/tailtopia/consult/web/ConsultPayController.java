package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.ConsultPayRequest;
import com.tailtopia.consult.dto.ConsultPayResponse;
import com.tailtopia.consult.service.ConsultPayService;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧计费流限时支付端点（Story 3.4，{@code hasRole('USER')} 由 SecurityConfig {@code /api/v1/consultations/**} 门控）。
 * 接 3-3 接单后的 {@code consult_requests}(ACCEPTED_AWAIT_PAY)：
 *
 * <ul>
 *   <li>{@code POST /consultations/{requestToken}/pay}：限时支付（QRIS/PawCoin）→ PawCoin 即时建单建会话 /
 *       现金返回支付信息（到账 handler 转单）。</li>
 *   <li>{@code POST /consultations/{requestToken}/pause}·{@code /resume}：跳充值暂停顺延（A-4，服务端权威计时）。</li>
 *   <li>{@code POST /consultations/{requestToken}/cancel}：用户主动取消（物理删、无痕不建单）。</li>
 * </ul>
 *
 * <p>token 寻址（不可枚举）；userId 取自 JWT（不信客户端）。归属/状态不符统一 409 防枚举。
 */
@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultPayController {

    private final ConsultPayService payService;
    private final ConsultRequestService requestService;

    public ConsultPayController(ConsultPayService payService, ConsultRequestService requestService) {
        this.payService = payService;
        this.requestService = requestService;
    }

    @PostMapping("/{requestToken}/pay")
    public ConsultPayResponse pay(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestToken,
            @Valid @RequestBody ConsultPayRequest body) {
        return payService.pay(currentUserId(jwt), requestToken, body.channel());
    }

    @PostMapping("/{requestToken}/pause")
    public void pause(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestToken) {
        requestService.pauseAcceptance(currentUserId(jwt), requestToken);
    }

    @PostMapping("/{requestToken}/resume")
    public void resume(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestToken) {
        requestService.resumeAcceptance(currentUserId(jwt), requestToken);
    }

    @PostMapping("/{requestToken}/cancel")
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestToken) {
        requestService.cancelRequest(currentUserId(jwt), requestToken);
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
