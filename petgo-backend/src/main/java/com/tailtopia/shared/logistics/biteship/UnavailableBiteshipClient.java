package com.tailtopia.shared.logistics.biteship;

import com.tailtopia.shared.logistics.biteship.BiteshipException.Kind;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;

/**
 * 生产环境 Biteship 配置不可用时的 fail-closed 客户端（v1.4.2 shop 决策 C-5）。
 *
 * <p>C-5：生产缺 key（或 mode / key 前缀不符）<b>不拒绝启动</b>（避免卡住同版本其他线发布），
 * 启动时 ERROR 日志；询价一律失败（→ {@code quote-failed}，结算不可提交）。
 * 🔴 绝不在生产回退到桩报价——那会让用户按假运费付款。
 */
public class UnavailableBiteshipClient implements BiteshipClient {

    @Override
    public String providerMode() {
        return "LIVE";
    }

    /** C-5：不可用——{@code ShippingQuoteService} 在查复用之前即抛 {@code quote-failed}，旧报价不会被复用。 */
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public BiteshipRatesResult rates(BiteshipRatesRequest req) {
        throw new BiteshipException(Kind.AUTH, null, null);
    }

    @Override
    public CreateOutcome createOrder(BiteshipCreateOrderRequest req) {
        return new CreateOutcome.Unknown("BITESHIP_NOT_CONFIGURED");
    }

    @Override
    public CancelOutcome cancelOrder(String biteshipOrderId, String reason) {
        return new CancelOutcome.Unknown("BITESHIP_NOT_CONFIGURED");
    }

    @Override
    public BiteshipOrderSnapshot getOrder(String biteshipOrderId) {
        throw new BiteshipException(Kind.AUTH, null, null);
    }
}
