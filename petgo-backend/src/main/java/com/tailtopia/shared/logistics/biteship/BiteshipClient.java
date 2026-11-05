package com.tailtopia.shared.logistics.biteship;

import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;

/**
 * Biteship 客户端（v1.4.2 shop Story 1.1，D-9 / P-4 / P-7）。实现由 {@link BiteshipConfig} 按
 * {@code petgo.shop.biteship.mode} 装配。
 *
 * <p>🔴 调用方不得在持有行锁的事务内调用（外部调用可能耗时 8 s）。
 */
public interface BiteshipClient {

    /** 落库用的提供方模式：{@code STUB} | {@code SANDBOX} | {@code LIVE}（进报价复用键）。 */
    String providerMode();

    /**
     * 是否可用于询价 / 下运单。{@code false}（如生产缺 key，决策 C-5）时调用方必须直接按
     * 「询价失败」处理，<b>不得复用任何历史报价</b>。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 询价。
     *
     * @return 有报价或不可配送
     * @throws BiteshipException 询价失败（鉴权 / 配额 / 限流 / 5xx / 网络 / 解析）
     */
    BiteshipRatesResult rates(BiteshipRatesRequest req);

    /** 下运单（Story 5.1 实现）。 */
    CreateOutcome createOrder(BiteshipCreateOrderRequest req);

    /** 取消运单（Story 5.1 实现）。 */
    CancelOutcome cancelOrder(String biteshipOrderId, String reason);

    /** 回查运单（Story 5.1 实现）。 */
    BiteshipOrderSnapshot getOrder(String biteshipOrderId);
}
