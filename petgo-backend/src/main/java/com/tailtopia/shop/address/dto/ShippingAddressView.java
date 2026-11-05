package com.tailtopia.shop.address.dto;

import com.tailtopia.shop.address.domain.ShippingAddress;

/**
 * 收货地址对外视图（Story 2.1）。
 *
 * <p>🔴 只暴露 {@code token}，绝不暴露自增 id（NFR-3）。
 * 🔒 本视图<b>确实包含三项 PII</b>——它是地址簿页面必需的数据。
 * 关键在于：它只经 HTTPS 响应体下发给<b>地址主人自己</b>，
 * 而 {@code ApiAccessLoggingFilter} 会经 {@code LogSanitizer} 把这三个字段打码后才落盘。
 */
public record ShippingAddressView(
        String token,
        String receiverName,
        String receiverPhone,
        String provinsi,
        String kotaKabupaten,
        String kecamatan,
        String addressLine,
        String kodePos,
        String label,
        boolean isDefault,
        /*
         * v1.4.2 shop Story 3.1 AC6（D-9 地址簿标注）：只有 GET 列表填它 ——
         * false = 不可配送（区域未开通 / 无快递）；true = 可配送；null = 未判定（询价失败 / 其他出口），NON_NULL 不下发。
         */
        Boolean serviceable) {

    /** 不带可配送标注（create / update / setDefault / 结算视图等出口，行为不变）。 */
    public static ShippingAddressView of(ShippingAddress a) {
        return of(a, null);
    }

    public static ShippingAddressView of(ShippingAddress a, Boolean serviceable) {
        return new ShippingAddressView(
                a.getPublicToken(), a.getReceiverName(), a.getReceiverPhone(),
                a.getProvinsi(), a.getKotaKabupaten(), a.getKecamatan(),
                a.getAddressLine(), a.getKodePos(), a.getLabel(), a.isDefault(), serviceable);
    }
}
