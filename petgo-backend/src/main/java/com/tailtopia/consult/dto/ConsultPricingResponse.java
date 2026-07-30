package com.tailtopia.consult.dto;

/**
 * 用户侧兽医咨询当前定价（bug 20260729-417）。
 *
 * <p>{@code price} 为单次咨询价（IDR 整数），实时读 {@code pricing_config}（Story 9.2 后台可配），
 * 与 {@code ConsultPayService} 扣费口径同源——前端展示价与实际扣费价由此保持一致。
 */
public record ConsultPricingResponse(long price) {
}
