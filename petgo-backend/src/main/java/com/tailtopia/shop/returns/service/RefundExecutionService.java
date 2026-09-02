package com.tailtopia.shop.returns.service;

import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.domain.CashDestination;
import com.tailtopia.shop.returns.domain.RefundSplit;
import com.tailtopia.shop.returns.domain.ReturnLine;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
import com.tailtopia.shop.returns.domain.ShippingFeeBearer;
import com.tailtopia.shop.returns.repository.ReturnLineRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 🔴🔴 退款执行（Story 5.5，AB-12C，<b>安全攸关</b>）。
 *
 * <h2>本类唯一的资金能力是「往钱包里加钱」</h2>
 * 🔴 <b>FR-100A 规则 1（防套现）在这里是<u>能力缺席</u>，不是权限判断。</b>
 * 本类只调用 {@link PawCoinWalletService#credit}，<b>没有</b>任何把 PawCoin 段折成现金、
 * 打到银行账户或提现的方法 —— 不是「有方法但加了权限」，是那个方法根本不存在。
 * CS 即使出于安抚客户的善意也无从绕过：他手上没有那个工具。
 *
 * <h2>CS 的变通手段是补偿溢价，不是退现金</h2>
 * 退货类型为「质量问题」时，系统按 AB-6A 的<b>平台责任补偿溢价</b>比例<b>自动</b>给
 * PawCoin 段加价 —— <b>无需 CS 操作、也不允许 CS 手工调整金额</b>（避免因人而异的议价）。
 * 🔴 补偿溢价读 {@code compensationPremiumRate}，激励溢价读 {@code premiumRate}，
 * <b>两者不得共用同一数值</b>（C-9 / D-8，写成单值是静默错误）。
 *
 * <h2>两段拆分</h2>
 * 一律经 {@link RefundSplit#accumulate}（AD-2 整数累计法）。
 * 拆分比例取<b>同一行</b>的 {@code coinAmount}/{@code amount}（订单在下单时固化的那一对，
 * 与其支付意图的三列同源、天然原子），🔴 <b>绝不读 {@code coin_ratio}</b>。
 */
@Service
public class RefundExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RefundExecutionService.class);

    private final ReturnRequestRepository returns;
    private final ReturnLineRepository returnLines;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final PawCoinWalletService wallet;
    private final PawCoinConfigRepository pawcoinConfig;
    private final ReturnRequestService requests;

    public RefundExecutionService(ReturnRequestRepository returns, ReturnLineRepository returnLines,
            ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            PawCoinWalletService wallet, PawCoinConfigRepository pawcoinConfig,
            ReturnRequestService requests) {
        this.returns = returns;
        this.returnLines = returnLines;
        this.orders = orders;
        this.orderLines = orderLines;
        this.wallet = wallet;
        this.pawcoinConfig = pawcoinConfig;
        this.requests = requests;
    }

    /**
     * 试算本申请应退多少（不写库）。供后台退款单详情<b>明确列出溢价金额与其触发依据</b>，
     * 便于事后审计与客诉复盘。
     */
    @Transactional(readOnly = true)
    public Quote quote(String returnToken) {
        ReturnRequest r = returns.findByPublicToken(returnToken)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        ShopOrder order = orders.findById(r.getShopOrderId()).orElseThrow();
        return computeQuote(r, order);
    }

    /**
     * 执行退款。
     *
     * <p>🔴 <b>全部资金动作与状态迁移在同一事务内</b>：退了币却没标记已退款，
     * 或标记了却没退币，两种都是对不上的账。
     *
     * <p>🔴 <b>幂等键 {@code shop-refund:{returnToken}}</b>（AD-9 / NFR-10）：
     * 重复执行不重复到账。这不是防御性编程 —— 后台按钮被点两次是常态。
     */
    @Transactional
    public Outcome execute(String returnToken) {
        ReturnRequest r = returns.findByPublicToken(returnToken)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        if (r.getStatus() == ReturnStatus.REFUNDED) {
            // 幂等：已退过就原样返回，不再动钱
            return new Outcome(r.getRefundCoin() == null ? 0 : r.getRefundCoin(),
                    r.getRefundCash() == null ? 0 : r.getRefundCash(),
                    r.getCompensationPremium(), r.getIncentivePremium(),
                    r.getShipbackReimbursed(), false);
        }
        if (r.getStatus() != ReturnStatus.REFUNDING) {
            throw AppException.conflict("当前状态不可执行退款：" + r.getStatus());
        }
        ShopOrder order = orders.findById(r.getShopOrderId()).orElseThrow();
        Quote q = computeQuote(r, order);

        // ---------- ① PawCoin 段：只能退回 PawCoin ----------
        String idem = "shop-refund:" + r.getPublicToken();
        if (q.coinRefund() > 0) {
            wallet.credit(r.getUserId(), q.coinRefund(), PawCoinTxnType.REFUND, "SHOP_RETURN",
                    r.getId(), idem);
        }

        // ---------- ② 平台责任补偿溢价（质量问题时自动，CS 不经手） ----------
        if (q.compensationPremium() > 0) {
            wallet.credit(r.getUserId(), q.compensationPremium(), PawCoinTxnType.BONUS,
                    "SHOP_RETURN_COMPENSATION", r.getId(), idem + ":compensation");
        }

        // ---------- ③ 现金段 ----------
        // 转 PawCoin 分支：即时到账 + 【激励】溢价（C-1 反套利，读 premiumRate 而非补偿溢价）
        if (r.getCashDestination() == CashDestination.TO_PAWCOIN && q.cashRefund() > 0) {
            wallet.credit(r.getUserId(), q.cashRefund(), PawCoinTxnType.REFUND, "SHOP_RETURN_CASH",
                    r.getId(), idem + ":cash-to-coin");
            if (q.incentivePremium() > 0) {
                wallet.credit(r.getUserId(), q.incentivePremium(), PawCoinTxnType.BONUS,
                        "SHOP_RETURN_INCENTIVE", r.getId(), idem + ":incentive");
            }
        }
        // TO_BANK 分支：真钱打款由财务按 payoutChannel / payoutAccount 线下执行并回填。
        // 🔴 本类【不自建打款通道】——那是 pay/refund 的职责域（AD-10）。

        // ---------- ④ 回程运费返还（平台承担时） ----------
        // 独立于 refundAmount：它不是订单里的钱，不参与 AD-2 的两段拆分（分母是订单总额）。
        if (q.shipbackReimbursement() > 0
                && r.getCashDestination() == CashDestination.TO_PAWCOIN) {
            wallet.credit(r.getUserId(), q.shipbackReimbursement(), PawCoinTxnType.REFUND,
                    "SHOP_RETURN_SHIPFEE", r.getId(), idem + ":shipback");
        }

        // ---------- ⑤ 记账 ----------
        r.recordRefundPlan(q.refundTotal(), q.coinRefund(), q.cashRefund(),
                q.compensationPremium());
        r.recordIncentivePremium(q.incentivePremium());
        r.recordShipbackReimbursement(q.shipbackReimbursement());
        r.markRefunded();
        returns.save(r);

        // 🔴 订单侧累计字段【在同一事务内递增】（AD-2）——下一次部分退款的分母靠它
        order.recordRefund(q.refundTotal(), q.coinRefund());
        orders.save(order);

        // 🔴 订单行的已退数量累加；全部行退净后订单才转 REFUNDED（AD-5）
        for (ReturnLine rl : returnLines.findByReturnRequestIdOrderByIdAsc(r.getId())) {
            ShopOrderLine line = orderLines.findById(rl.getOrderLineId()).orElseThrow();
            if (line.getRefundedQty() < line.getQty()) {
                line.addRefundedQty(Math.min(rl.getQty(), line.getQty() - line.getRefundedQty()));
                orderLines.save(line);
            }
        }
        boolean orderFullyRefunded = requests.settleOrderIfFullyRefunded(order.getId());

        log.info("退款执行完成 return={} coin={} cash={} premium={} orderRefunded={}",
                r.getPublicToken(), q.coinRefund(), q.cashRefund(), q.compensationPremium(),
                orderFullyRefunded);
        return new Outcome(q.coinRefund(), q.cashRefund(), q.compensationPremium(),
                q.incentivePremium(), q.shipbackReimbursement(), orderFullyRefunded);
    }

    /** S-8 ③：退款执行失败 → 可重试；超 3 次转人工。 */
    @Transactional
    public void markFailed(String returnToken, String note) {
        ReturnRequest r = returns.findByPublicToken(returnToken)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        r.markRefundFailed(note);
        returns.save(r);
    }

    @Transactional
    public void retry(String returnToken) {
        ReturnRequest r = returns.findByPublicToken(returnToken)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        r.retryRefund();
        returns.save(r);
    }

    // ---------- 计算 ----------

    private Quote computeQuote(ReturnRequest r, ShopOrder order) {
        // 商品金额：按下单时的行单价快照
        long goods = returnLines.findByReturnRequestIdOrderByIdAsc(r.getId()).stream()
                .mapToLong(ReturnLine::getLineRefundAmount).sum();

        // 🔴 去程运费：整单退才退，部分退不退（C-12）。退的是【实付】的那部分：
        //    运费 + 免运抵扣（抵扣为负）—— 免运的单本来就没付运费，自然也退不出运费来。
        long outbound = r.isOutboundFeeRefundable()
                ? Math.max(0L, order.getShippingFee() + order.getShippingDiscount())
                : 0L;

        long refundTotal = goods + outbound;
        // 兜底：累计不得超过订单总额（RefundSplit 也会再拦一次）
        long room = order.getTotalAmount() - order.getRefundedTotal();
        if (refundTotal > room) {
            refundTotal = room;
        }

        // 🔴 AD-2 整数累计法。分子分母取【同一行】固化的那一对（下单时写死，不随部分退款重算）。
        long coinAmount = order.getCoinAmount() == null ? 0L : order.getCoinAmount();
        RefundSplit split = RefundSplit.accumulate(order.getTotalAmount(), coinAmount,
                order.getRefundedTotal(), order.getRefundedCoin(), refundTotal);

        PawCoinConfig config = pawcoinConfig.findAll().stream().findFirst().orElse(null);

        // 🔴 平台责任【补偿】溢价：只在平台责任（质量问题 / 拒收）时给，读独立配置项
        long compensation = 0L;
        if (r.getReturnType().isPlatformFault() && config != null) {
            compensation = premium(split.thisCoin(), config.getCompensationPremiumRate(),
                    config.getCompensationPremiumCap());
        }

        // 🔴 【激励】溢价：只在「现金段转 PawCoin」时给，读的是另一个配置项（C-1 反套利）
        long incentive = 0L;
        if (r.getCashDestination() == CashDestination.TO_PAWCOIN && config != null) {
            incentive = premium(split.thisCash(), config.getPremiumRate(), 0L);
        }
        // 🔴 **「选了转币会拿到多少」的预览**（D-11，2026-09-02 stag）。
        //    上面那个 incentive 要**已经选了** TO_PAWCOIN 才非零，而用户是在退款方式页
        //    **做选择之前**看到「Lands instantly, with a bonus」这句承诺的 ——
        //    端上拿 incentive 去判就恒为 0，等于永远藏掉；不判又变成无条件承诺（就是 D-11）。
        //    所以额外给一个与当前选择无关的预览值，端上据此决定那句话说不说。
        long incentiveIfPawcoin =
                config == null ? 0L : premium(split.thisCash(), config.getPremiumRate(), 0L);

        // 回程运费：平台承担时按用户上传的实际运单金额返还（S-7）
        long shipbackReimbursement =
                r.getReturnShipBearer() == ShippingFeeBearer.PLATFORM && r.getShipbackFee() != null
                        ? r.getShipbackFee() : 0L;

        return new Quote(refundTotal, split.thisCoin(), split.thisCash(), compensation, incentive,
                incentiveIfPawcoin, shipbackReimbursement, goods, outbound);
    }

    /** 溢价 = 基数 × 比例%，按 cap 封顶（cap = 0 表示不封顶）。整数运算，禁浮点。 */
    private static long premium(long base, int ratePercent, long cap) {
        if (base <= 0 || ratePercent <= 0) {
            return 0L;
        }
        long value = base * ratePercent / 100;
        return cap > 0 ? Math.min(value, cap) : value;
    }

    /**
     * 退款试算。
     *
     * <p>后台退款单详情<b>明确列出溢价金额与其触发依据</b>（退货类型 = 质量问题），
     * 便于事后审计与客诉复盘。
     */
    public record Quote(long refundTotal, long coinRefund, long cashRefund,
            long compensationPremium, long incentivePremium,
            /**
             * 「若选择转 PawCoin，激励溢价会是多少」——**与当前选择无关**的预览值（D-11）。
             * ⚠️ 与 {@code incentivePremium} 的区别：那个要已经选了 TO_PAWCOIN 才非零，
             * 是**结算口径**；这个是**给用户做选择前看的**。不要拿它入账。
             */
            long incentiveIfPawcoin,
            long shipbackReimbursement,
            long goodsAmount, long outboundFeeRefund) {

        /** 用户视角的「总退回（含补偿）」。 */
        public long grandTotal() {
            return refundTotal + compensationPremium + incentivePremium + shipbackReimbursement;
        }
    }

    /** 执行结果。{@code orderFullyRefunded} 为 true 时订单才被回写为 REFUNDED（AD-5）。 */
    public record Outcome(long coinRefunded, long cashRefunded, long compensationPremium,
            long incentivePremium, long shipbackReimbursed, boolean orderFullyRefunded) {
    }
}
