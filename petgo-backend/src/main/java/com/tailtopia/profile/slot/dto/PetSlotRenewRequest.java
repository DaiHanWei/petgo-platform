package com.tailtopia.profile.slot.dto;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 续租请求（V1.4.0 multi-pet Story 2.2 · AC8.3）：{@code {"rentalTokens":["…"],"channel":"PAWCOIN"|"QRIS"}}，
 * 勾选多个名额一次付款。名额按 {@code GET /pet-slots/me} 下发的 {@code rentals[].token} 寻址。
 */
public record PetSlotRenewRequest(@NotNull List<String> rentalTokens, @NotNull PayChannel channel) {
}
