package com.tailtopia.shared.logistics.biteship.dto;

import java.util.List;

/**
 * 下运单请求（v1.4.2 shop Story 1.1 定签名，Story 5.1 按 AC4.2 补全）：{@code POST /v1/orders}。
 *
 * <p>🔒 含发件人 / 收件人姓名、电话、地址（PII）：{@link #toString()} 已重写，只打印 referenceId、快递与件数；
 * 本对象<b>绝不整体进日志</b>。
 *
 * @param referenceId          本平台运单参考号（{@code display_no-序号}，每次重试换新；Biteship 侧唯一）
 * @param courierCompany       订单快照 {@code courier_code}
 * @param courierType          订单快照 {@code courier_service_code}
 * @param price                订单运费（IDR）——<b>不发给 Biteship</b>，仅桩模式用来回填 {@code price}
 * @param deliveryType         {@code now} | {@code scheduled}
 * @param insuranceValue       {@code courier_insurance}（商品实付）；未勾选保价为 {@code null}（不传）
 */
public record BiteshipCreateOrderRequest(String referenceId, String courierCompany,
        String courierType, long price, String deliveryType,
        Contact origin, Contact destination, List<Item> items, Long insuranceValue) {

    public BiteshipCreateOrderRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 1.1 签名（桩 / 旧测试用）：只有参考号、快递、运费。 */
    public BiteshipCreateOrderRequest(String referenceId, String courierCompany, String courierType, long price) {
        this(referenceId, courierCompany, courierType, price, "now", null, null, List.of(), null);
    }

    /** 发件 / 收件联系人（🔒 PII）。{@code phone} 已归一为纯数字。 */
    public record Contact(String name, String phone, String address, String postalCode) {

        @Override
        public String toString() {
            return "Contact{***}";
        }
    }

    /** 一项商品：{@code value} = 单价（IDR）；重量克、尺寸厘米。 */
    public record Item(String name, long value, int quantity, int weightG, int lengthCm, int widthCm,
            int heightCm) {

        @Override
        public String toString() {
            return "Item{qty=" + quantity + ", weightG=" + weightG + "}";
        }
    }

    @Override
    public String toString() {
        return "BiteshipCreateOrderRequest{referenceId=" + referenceId + ", courier=" + courierCompany + "/"
                + courierType + ", items=" + items.size() + ", insured=" + (insuranceValue != null) + "}";
    }
}
