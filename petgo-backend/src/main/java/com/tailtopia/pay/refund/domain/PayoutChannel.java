package com.tailtopia.pay.refund.domain;

/**
 * 出款渠道 + 权威渠道费（Story 4.3，落库 varchar UPPER_SNAKE）。
 * 净额 {@code net = order_amount − fee} 由后端按渠道权威计算（FR-NFR-5，前端须一致，不可前端传费）。
 * 费率本 story 为常量；9-2 后台 pricing_config 可调则外部化（预留，本 story 不做）。
 */
public enum PayoutChannel {
    BCA(0),      // 银行转账，无手续费
    OVO(2500),   // e-wallet，Rp2,500
    GOPAY(2500); // e-wallet，Rp2,500

    private final long fee;

    PayoutChannel(long fee) {
        this.fee = fee;
    }

    /** 出款渠道费（IDR 最小单位）。 */
    public long fee() {
        return fee;
    }
}
