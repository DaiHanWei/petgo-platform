package com.tailtopia.shared.pay;

import java.util.Map;
import java.util.Optional;

/**
 * 支付网关抽象（Story 1.1 / V1.1 资金地基）。收款聚合商（Midtrans）的可替换契约——一切收费场景
 * （AI 解锁、兽医咨询、PawCoin 充值、身份证高清）都建在同一基座上（FR-43 / FR-NFR-2）。
 *
 * <p>三个能力：
 * <ol>
 *   <li>{@link #createCharge(ChargeRequest)}：发起收款，返回网关订单号 + 付款载荷（二维码/deeplink）。</li>
 *   <li>{@link #verifyCallback(Map)}：验签（Midtrans SHA-512 {@code signature_key}）——失败即拒。</li>
 *   <li>{@link #parseCallback(Map)}：把网关回调正文归一化为 {@link PaymentCallback}（供意图状态机）。</li>
 * </ol>
 *
 * <p>护栏：实现只 log 异常类名，<b>绝不打印 body / 凭证 / 签名</b>；凭证 env 注入不入库。
 * 桩实现（{@code mode=stub}，默认）无凭证可跑 L0/L1；真实 Midtrans（{@code mode=live}）属 L2。
 */
public interface PaymentGateway {

    /** 发起收款（QRIS / e-wallet）。失败抛 {@link PayException}（仅携安全文案）。 */
    ChargeResult createCharge(ChargeRequest request);

    /**
     * 发起出款（Story 4.6，退款真钱打款，Midtrans Iris/Disbursement，与收款侧独立）。返回网关出款单号 + 归一化状态。
     * 失败抛 {@link PayException}（仅携安全文案）。实现<b>绝不 log body / 凭证 / PII（账号/户名）</b>。
     * 桩实现（{@code mode=stub}）返确定性 ref 供 L0/L1；真实 Iris（{@code mode=live}）属 L2（sandbox 真出款）。
     */
    DisburseResult disburse(DisburseRequest request);

    /** 验签回调正文；非法（签名不匹配 / 缺字段）返回 {@code false}，调用方转 403。 */
    boolean verifyCallback(Map<String, Object> body);

    /** 归一化回调/轮询正文为内部结果（已假定 {@link #verifyCallback} 通过）。 */
    PaymentCallback parseCallback(Map<String, Object> body);

    /**
     * 主动查询收款结果（GemPay {@code /history}，<b>轮询通道</b>）。回调缺失时对账用：按网关订单号
     * {@code gatewayRef} 查最新状态，归一化为 {@link PaymentCallback} 交同一 {@code applyCallback} 单一收口
     * （与回调双通道去重、只推进一次）。查无结果 / 尚未终态由调用方按 {@code status} 处理。
     *
     * <p>默认 {@link Optional#empty()}（不支持主动查询）；仅 {@link GemPayGateway} 覆盖。<b>只查收款，不涉及 payout。</b>
     */
    default Optional<PaymentCallback> queryCharge(String gatewayRef) {
        return Optional.empty();
    }
}
