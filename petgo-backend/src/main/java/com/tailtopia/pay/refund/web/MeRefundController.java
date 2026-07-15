package com.tailtopia.pay.refund.web;

import com.tailtopia.pay.refund.dto.FillPayoutRequest;
import com.tailtopia.pay.refund.dto.MyRefundView;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端退款方式选择与填收款（Story 4.5，AB-5B/A-2/C-1）。{@code role=user}，<b>仅作用当前 JWT sub，绝不接受任意 userId</b>
 * （照 {@code PawCoinController}，决策 C1）。退款方式由订单原支付渠道决定：PawCoin 付→即时退币（无手续费、不经 4-6）；
 * QRIS 付→填真钱收款账户（净额后端权威、不可逆、进 PENDING_APPROVAL 等 4-6）。
 */
@RestController
@RequestMapping("/api/v1")
public class MeRefundController {

    private final RefundService refundService;

    public MeRefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 我的退款列表（解锁「选方式」入口；零 PII 回显）。 */
    @GetMapping("/me/refund-requests")
    public List<MyRefundView> myRefunds(@AuthenticationPrincipal Jwt jwt) {
        return refundService.listMyRefunds(currentUserId(jwt));
    }

    /** PawCoin 订单即时退币（原路、无手续费、幂等；订单→REFUNDED）。 */
    @PostMapping("/refund-requests/{refundToken}/refund-pawcoin")
    public void refundPawCoin(@AuthenticationPrincipal Jwt jwt, @PathVariable String refundToken) {
        refundService.refundToPawCoin(refundToken, currentUserId(jwt));
    }

    /** QRIS 订单填真钱收款账户（不可逆；净额后端权威；进 PENDING_APPROVAL 等 4-6）。 */
    @PostMapping("/refund-requests/{refundToken}/payout-info")
    public void fillPayout(@AuthenticationPrincipal Jwt jwt, @PathVariable String refundToken,
            @Valid @RequestBody FillPayoutRequest req) {
        refundService.fillPayoutByUser(refundToken, currentUserId(jwt), req.channel(),
                req.payoutAccount(), req.accountHolderName());
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
