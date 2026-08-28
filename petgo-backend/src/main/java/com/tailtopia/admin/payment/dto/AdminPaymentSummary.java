package com.tailtopia.admin.payment.dto;

/**
 * 支付记录筛选结果的汇总（2026-08-28）。摆在表格上方，回答「这一屏筛出来的到底是多少钱、多少笔」。
 *
 * <p>🔴 **两个数的口径刻意不同，而且必须在界面上写明**，否则它是一个看着权威的错数：
 * <ul>
 *   <li>{@code orderCount} = 筛选结果的**全部**条数（含待支付 / 失败 / 已过期）——
 *       运营问"这段时间有多少笔"时，问的就是全部。</li>
 *   <li>{@code cashIncome} = 其中**只有已支付（PAID）且真正收到钱**的部分。</li>
 * </ul>
 *
 * <h2>为什么不能直接 SUM(amount)</h2>
 * 那会得到一个偏大且无人察觉的数字，三处失真：
 * <ol>
 *   <li>**未支付也被算进去** —— PENDING / FAILED / EXPIRED 的意图同样有 amount。</li>
 *   <li>**PawCoin 支付不是现金收入** —— {@code PAWCOIN} 渠道是站内余额扣减，
 *       钱早在充值那一笔（{@code PAWCOIN_TOPUP} + QRIS）就已经收过了。
 *       两笔都算 = 同一笔钱记两遍。</li>
 *   <li>**混合支付只有现金段是收入** —— {@code MIXED} 的 amount 含 coin 段，
 *       只有 {@code cash_amount} 是网关真收到的（见 PaymentIntent 的不变式说明）。</li>
 * </ol>
 *
 * @param orderCount  筛选结果条数（全部状态）
 * @param paidCount   其中已支付的条数
 * @param cashIncome  现金收入合计（PAID 的 QRIS 全额 + MIXED 的现金段），单位与 currency 一致
 * @param coinSpent   同一批里用 PawCoin 抵扣掉的金额（PAID 的 PAWCOIN 全额 + MIXED 的 coin 段）。
 *                    🛡 **单独列出而不是丢掉**：不列的话运营会拿"现金收入"去对"订单金额总和"，
 *                    发现对不上又找不到差额去哪了。两个数加起来正好是已支付的订单金额。
 * @param currency    币种。⚠️ 多币种混在一起时**不做换算**，见 {@code MIXED_CURRENCY}。
 */
public record AdminPaymentSummary(long orderCount, long paidCount, long cashIncome,
        long coinSpent, String currency) {

    /**
     * 筛选结果里出现了不止一种币种时的占位。
     *
     * <p>🔴 此时把不同币种的数字**加在一起是错的**，而错得看不出来。目前系统只用 IDR，
     * 这一档实际到不了 —— 留着是为了哪天真加了第二种币，界面会直说"币种不一致"，
     * 而不是安静地给一个假总额。
     */
    public static final String MIXED_CURRENCY = "MIXED";

    public boolean currencyIsMixed() {
        return MIXED_CURRENCY.equals(currency);
    }
}
