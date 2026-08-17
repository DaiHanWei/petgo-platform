package com.tailtopia.shop.address.domain;

/**
 * 地址的九个可写字段（FR-98）。手机号<b>进来时已是归一化后的 E.164</b>。
 *
 * <p>🔒 含三项 PII（{@code receiverName} / {@code receiverPhone} / {@code addressLine}），
 * 🔴 <b>不要给本 record 写 toString 或把它整体塞进日志</b>——record 的默认 toString 会打印全部字段。
 */
public record AddressFields(
        String receiverName,
        String receiverPhone,
        String provinsi,
        String kotaKabupaten,
        String kecamatan,
        String addressLine,
        String kodePos,
        String label) {

    /** 🔒 覆盖默认 toString：record 默认实现会把三项 PII 全部打印出来。 */
    @Override
    public String toString() {
        return "AddressFields[kecamatan=" + kecamatan + ", kodePos=" + kodePos + ", PII omitted]";
    }
}
