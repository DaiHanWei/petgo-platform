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
        long shipbackReimbursed = 0L;
        if (q.shipbackReimbursement() > 0
                && r.getCashDestination() == CashDestination.TO_PAWCOIN) {
            wallet.credit(r.getUserId(), q.shipbackReimbursement(), PawCoinTxnType.REFUND,
                    "SHOP_RETURN_SHIPFEE", r.getId(), idem + ":shipback");
            shipbackReimbursed = q.shipbackReimbursement();
        }
        // 🔴 TO_BANK：本版回程运费【没有】银行报销通道（财务线下打款单只含现金段本金），
        //    故 shipback_reimbursed 只记实际到账的那笔（TO_PAWCOIN 路径），不得预标 —— 预标
        //    等于账上写了一笔从未支付的报销。TODO：运费并入银行打款流程后，在财务回填处补记。

        // ---------- ⑤ 记账 ----------
        r.recordRefundPlan(q.refundTotal(), q.coinRefund(), q.cashRefund(),
                q.compensationPremium());
        r.recordIncentivePremium(q.incentivePremium());
        r.recordShipbackReimbursement(shipbackReimbursed);
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
        // ⚠️ Outcome 里的 shipbackReimbursed 是【实际到账】的报销额（TO_BANK 时为 0），与库中记账同源。
        return new Outcome(q.coinRefund(), q.cashRefund(), q.compensationPremium(),
                q.incentivePremium(), shipbackReimbursed, orderFullyRefunded);
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

        // 🔴 【激励】溢价：只在「**未交付** + 现金段转 PawCoin」时给（C-1 反套利）。
        //
        // ⚠️ **「未交付」这道门是 2026-09-02 补的**（D-16）。此前这里对**任何** TO_PAWCOIN
        //    退货都给 —— 而 premium_rate/premium_fixed 这对配置，
        //    迁移 V20260817_2330 的头注释与 PawCoinConfig 的 javadoc **两处都写明**
        //    是「未交付+转币」分支专用的反套利激励。代码漏了这道门。
        //    已交付的退货也给，等于付钱请人「买 → 收货 → 退 → 转币」；
        //    质量问题那一档尤其糟：平台还要承担回程运费并另给补偿溢价。
        //    当时 premiumRate=0 把这件事掩盖着 —— 而那是个后台随时可改的值。
        //
        // 🔴 金额改走**领域方法** `refundPawcoinPremium`（= base × rate% + **premiumFixed**），
        //    不再用本类那个私有 premium()。此前两套公式并存，
        //    运营改「退款转币固定溢价」只有 pay/refund 那条链路会变，这条不变。
        //    ⚠️ 补偿溢价**仍走私有 premium()**：它读的是另一对配置
        //    （compensation_premium_rate/cap）且带上限，迁移注释明确警告
        //    「两条溢价必须是两个独立配置项，写成同一个数值会**静默**毁掉
        //     AB-13A 售后成本口径与 AB-6C 浮存归因」。两者不可合并。
        //
        // ⚠️ 还要求 `thisCash > 0`：领域公式在 base=0 时仍会加上 premiumFixed，
        //    而没有现金段就没有「转币」这回事，不该凭空给一笔固定溢价。
        boolean incentiveEligible =
                incentiveApplies(r.getReturnType(), split.thisCash(), config != null);
        // 「选了转币会拿到多少」的预览（D-11）：与**当前选择**无关，但同样受上面这道门约束 ——
        // 门外的退货本来就拿不到激励，端上再承诺 bonus 就又变回 D-11 那种空头支票。
        long incentiveIfPawcoin =
                incentiveEligible ? config.refundPawcoinPremium(split.thisCash()) : 0L;
        long incentive = incentiveEligible
                && r.getCashDestination() == CashDestination.TO_PAWCOIN
                        ? incentiveIfPawcoin : 0L;

        // 回程运费：平台承担时按用户上传的实际运单金额返还（S-7）
        long shipbackReimbursement =
                r.getReturnShipBearer() == ShippingFeeBearer.PLATFORM && r.getShipbackFee() != null
                        ? r.getShipbackFee() : 0L;

        return new Quote(refundTotal, split.thisCoin(), split.thisCash(), compensation, incentive,
                incentiveIfPawcoin, shipbackReimbursement, goods, outbound);
    }

    /**
     * 转币【激励】溢价是否适用 —— <b>C-1 反套利那道门</b>（D-16，2026-09-02）。
     *
     * <p>抽成纯函数是为了让这道门<b>可被 L0 直接断言</b>：它守的是资损，
     * 而金额计算本身埋在需要 DB 的集成链路里，那一层跑得慢、也不便穷举真值表。
     *
     * <p>三个条件缺一不可：
     * <ul>
     *   <li><b>未交付</b>（{@link com.tailtopia.shop.returns.domain.ReturnType#isUndelivered()}）—— 已交付的退货也给激励，
     *       等于付钱请人「买 → 收货 → 退 → 转币」；</li>
     *   <li><b>有现金段可转</b> —— 领域公式在 base=0 时仍会加上 {@code premiumFixed}，
     *       而没有现金段就没有「转币」这回事，不该凭空给一笔固定溢价；</li>
     *   <li>配置存在。</li>
     * </ul>
     *
     * <p>⚠️ 「是否真的选了 TO_PAWCOIN」<b>不在这里判</b>：本判据同时服务于
     * 「选了拿多少」（结算）与「若选会拿多少」（端上展示预览，D-11），
     * 后者正是在用户做选择<b>之前</b>要回答的。
     */
    static boolean incentiveApplies(com.tailtopia.shop.returns.domain.ReturnType type, long cashSegment, boolean hasConfig) {
        return type != null && type.isUndelivered() && cashSegment > 0 && hasConfig;
    }

    /**
     * 溢价 = 基数 × 比例%，按 cap 封顶（cap = 0 表示不封顶）。整数运算，禁浮点。
     *
     * <h2>⚠️ 只给**补偿**溢价用（D-16 处理后，2026-09-02）</h2>
     * 激励溢价已改走领域方法 {@link com.tailtopia.config.domain.PawCoinConfig#refundPawcoinPremium}
     * （那套带 {@code premiumFixed}）。本方法只剩补偿溢价一个调用方 ——
     * 它读的是**另一对配置**（{@code compensation_premium_rate/cap}）且**带上限**，
     * 与激励溢价是两个独立配置项，不可合并（迁移 V20260817_2330 的头注释写明：
     * 写成同一个数值会**静默**毁掉 AB-13A 售后成本口径与 AB-6C 浮存归因）。
     *
     * <h2>历史（保留以防再被"统一"掉）</h2>
     * {@link com.tailtopia.config.domain.PawCoinConfig#refundPawcoinPremium} 写的是
     * <pre>溢价 = 基数 × premiumRate% + <b>premiumFixed</b></pre>
     * 而本方法**不加 premiumFixed**。于是同一个后台配置项在两条退款链路上行为不同：
     * <ul>
     *   <li>{@code pay/refund/RefundService}（问诊/充值退款）：走领域方法，<b>吃 premiumFixed</b>；</li>
     *   <li>本类（电商退货转币激励）：走这里，<b>不吃 premiumFixed</b>。</li>
     * </ul>
     *
     * <p>📌 报告 D-16 记的是「premiumFixed 全仓没人读、是个空开关」——**不准确**：
     * RefundService 一直在读。真正的问题是<b>两套公式并存</b>，
     * 运营在后台改「退款转币固定溢价」时，只有问诊/充值那条链路会变。
     *
     * <p>🔴 <b>另有一处代码与注释不符，一并记在这里</b>：
     * {@code PawCoinConfig#refundPawcoinPremium} 的注释写「仅『未交付 + 转币』分支给
     * （反套利 C-1，由 RefundService 门控）」——那句话对 RefundService 成立，
     * 但**本类对任何 TO_PAWCOIN 退货都给激励**，没有「未交付」这道门。
     * 即：电商退货这条链路是**第二个、且未被 C-1 门控的**溢价出口。
     * 当前 staging {@code premiumRate = 0} 把这两件事都掩盖着，一旦调非 0 就会同时暴露
     * （买 → 收货 → 退 → 转币赚溢价）。
     *
     * <p>⚠️ <b>本次不改行为</b>：统一公式与补 C-1 门控都直接改钱，属产品/风控决策，
     * 不由实现侧顺手定。
     */
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
