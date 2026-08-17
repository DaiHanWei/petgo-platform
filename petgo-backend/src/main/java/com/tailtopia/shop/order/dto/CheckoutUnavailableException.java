package com.tailtopia.shop.order.dto;

import java.util.List;

/**
 * 下单被不可用行阻断（Story 3.4）。
 *
 * <p>🔴 携带<b>逐行明细</b>而不是一句笼统的错误 —— 见 {@link UnavailableLine} 的说明。
 * 控制器把它映射为 RFC 9457 ProblemDetail 并把明细放进扩展字段。
 */
public class CheckoutUnavailableException extends RuntimeException {

    private final transient List<UnavailableLine> lines;

    public CheckoutUnavailableException(List<UnavailableLine> lines) {
        super("部分商品不可购买");
        this.lines = List.copyOf(lines);
    }

    public List<UnavailableLine> getLines() {
        return lines;
    }
}
