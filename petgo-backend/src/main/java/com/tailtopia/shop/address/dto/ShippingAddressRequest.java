package com.tailtopia.shop.address.dto;

import com.tailtopia.shop.address.domain.AddressFields;

/**
 * 新建/编辑收货地址的请求体（FR-98 九字段中的八个可写项；{@code isDefault} 走独立端点）。
 *
 * <p>🔒 含 PII。🔴 <b>覆盖了默认 toString</b>——record 的默认实现会把三项 PII 全部打印，
 * 一旦有人把请求体塞进日志就是直接泄露。
 */
public record ShippingAddressRequest(
        String receiverName,
        String receiverPhone,
        String provinsi,
        String kotaKabupaten,
        String kecamatan,
        String addressLine,
        String kodePos,
        String label) {

    public AddressFields toFields() {
        return new AddressFields(receiverName, receiverPhone, provinsi, kotaKabupaten,
                kecamatan, addressLine, kodePos, label);
    }

    @Override
    public String toString() {
        return "ShippingAddressRequest[kecamatan=" + kecamatan + ", PII omitted]";
    }
}
