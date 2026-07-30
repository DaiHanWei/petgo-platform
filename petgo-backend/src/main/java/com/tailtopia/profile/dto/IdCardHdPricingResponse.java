package com.tailtopia.profile.dto;

/**
 * 身份证 HD 下载当前定价（417 同类修复）。
 *
 * <p>{@code price} 为单次解锁价（IDR 整数），实时读 {@code pricing_config.id_hd_download_price}
 * （Story 9.2 后台可配），与 {@code IdCardHdService} 扣费口径同源——前端展示价与实际扣费价由此一致。
 */
public record IdCardHdPricingResponse(long price) {
}
