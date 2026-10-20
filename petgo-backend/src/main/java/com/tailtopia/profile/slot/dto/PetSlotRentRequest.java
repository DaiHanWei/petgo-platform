package com.tailtopia.profile.slot.dto;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.validation.constraints.NotNull;

/**
 * 新租请求（V1.4.0 multi-pet Story 2.2 · AC8.2）：{@code {"quantity":2,"channel":"PAWCOIN"|"QRIS"}}。
 * 个数是否可租由服务端判（超出 → 409 {@code pet-slot-rent-unavailable}）；MIXED 可反序列化，由服务拒成 422。
 */
public record PetSlotRentRequest(@NotNull Integer quantity, @NotNull PayChannel channel) {
}
