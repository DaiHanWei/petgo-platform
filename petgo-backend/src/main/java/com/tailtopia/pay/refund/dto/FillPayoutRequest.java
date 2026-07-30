package com.tailtopia.pay.refund.dto;

import com.tailtopia.pay.refund.domain.PayoutChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 用户填真钱收款账户请求（Story 4.5，QRIS 退款专属）。**只传渠道 + 账户 + 户名，绝不传费/净额**
 * （净额后端按 {@link PayoutChannel} 权威算，前端传值一律忽略，FR-NFR-5/D-4）。PII 由 service 加密落库。
 *
 * @param channel           出款渠道（{@code BCA}/{@code OVO}/{@code GOPAY}；枚举反序列化即拒非法值）
 * @param payoutAccount     收款账号（PII，加密落库）
 * @param accountHolderName 户名（PII，加密落库）
 */
public record FillPayoutRequest(
        @NotNull(message = "出款渠道必填") PayoutChannel channel,
        @NotBlank(message = "收款账号必填") @Size(max = 64, message = "收款账号过长") String payoutAccount,
        @NotBlank(message = "户名必填") @Size(max = 64, message = "户名过长") String accountHolderName) {
}
