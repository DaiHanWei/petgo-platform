package com.tailtopia.shop.order.domain;

/**
 * 下单时的收货地址快照（AD-13）。
 *
 * <p>🔴 <b>是快照不是引用</b>：用户改地址簿不得改写历史订单的履约地址。
 * 订单上的地址是<b>履约凭证</b>，不是<b>当前偏好</b>——两者被混为一谈时，
 * 纠纷里没人说得清货到底该寄到哪。
 *
 * <p>🔒 含三项 PII，已覆盖默认 toString。
 */
public record AddressSnapshot(
        String receiverName,
        String receiverPhone,
        String provinsi,
        String kotaKabupaten,
        String kecamatan,
        String addressLine,
        String kodePos) {

    @Override
    public String toString() {
        return "AddressSnapshot[kecamatan=" + kecamatan + ", PII omitted]";
    }
}
