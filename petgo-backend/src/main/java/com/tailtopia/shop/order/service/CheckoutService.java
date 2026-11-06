package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.coupon.service.CouponInvalidException;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.logistics.CourierRate;
import com.tailtopia.shop.logistics.PackageItems;
import com.tailtopia.shop.logistics.ShippingQuoteRecord;
import com.tailtopia.shop.logistics.ShippingQuoteRecordRepository;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.PlaceOrderRequest;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.pricing.CouponEvaluation;
import com.tailtopia.shop.order.pricing.CouponSelection;
import com.tailtopia.shop.order.pricing.CouponUnavailableReason;
import com.tailtopia.shop.order.pricing.PricingCoupon;
import com.tailtopia.shop.order.pricing.PricingResult;
import com.tailtopia.shop.order.pricing.ShopPricingCalculator;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算与下单（Story 3.4 起；v1.4.2 shop Story 3.2 重写提交，D-8 / S-3 / S-4 / I7）。
 *
 * <p>🔴 <b>PawCoin 在提交订单时扣</b>（S-3，v1.4.2 起）：提交事务内锁库存、占券、扣币、固化拆分，
 * 现金段为 0（纯币足额）或应付为 0（C-4）当场转「已付款」。未付款取消（用户取消 / 超时关单）经
 * {@code ShopOrderPaymentService#releaseUnpaid} 退回币与券。此前「下单只锁库存、付款时扣币」的规则只对
 * {@code checkout_source IS NULL} 的存量单保留（过渡，{@code TODO(v1.4.3)}）。
 *
 * <p>🔴 <b>报价锁定</b>（I7）：提交不再询价（事务内零网络调用），按 {@code quoteToken} 取 1.1 落库的报价，
 * 校验未过期、邮编与地址一致、{@code items_hash} 与本单行一致、所选快递在列表中，任一不符 409 {@code quote-expired}。
 * 旧结算模式（旧版 App，S-4）由 {@link LegacyCheckoutAdapter#placeCommand} 在事务<b>外</b>先询价拿到 token，
 * 再进同一个 {@link #place} —— 预览与下单运费同为 Biteship 最低价（{@code shipping_zones.fee} 不再被读取）。
 *
 * <p>🔴 金额只来自 {@link ShopPricingCalculator}（P-1）；券状态只经 {@link CouponLedgerService}（P-2）。
 *
 * <p>🔴 <b>第二次库存校验必须逐行给出「哪个 SKU 怎么了」，不整单打回</b>（FR-95）。
 */
@Service
public class CheckoutService {

    private final CartService carts;
    private final InventoryService inventory;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final PawCoinWalletService wallet;
    private final ShopTokenGenerator tokens;
    private final ShopOrderDisplayNoGenerator displayNos;
    private final IdempotencyService idempotency;
    /** V1.4.1 商家平台：下单写归属门店（{@code shop_orders.referrer_merchant_id}）。 */
    private final com.tailtopia.merchant.binding.service.MerchantBindingService merchantBindings;
    /** v1.4.2 shop Story 3.1：旧结算预览改走新装配链路。 */
    private final CheckoutQuoteService quotesChain;
    private final LegacyCheckoutAdapter legacy;
    /** v1.4.2 shop Story 3.2：提交与试算共用的入参装配。 */
    private final CheckoutLineResolver loader;
    private final ShippingZoneRepository zones;
    private final ShippingQuoteRecordRepository quoteRecords;
    private final CouponLedgerService coupons;
    private final ShopOrderPaidTransition paidTransition;
    /** 自引用代理：无事务入口（旧模式询价、幂等占位）调事务方法 {@link #placeTransactional} 必须经它。 */
    private final ObjectProvider<CheckoutService> selfProvider;

    public CheckoutService(CartService carts, InventoryService inventory,
            ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            PawCoinWalletService wallet, ShopTokenGenerator tokens,
            ShopOrderDisplayNoGenerator displayNos, IdempotencyService idempotency,
            com.tailtopia.merchant.binding.service.MerchantBindingService merchantBindings,
            CheckoutQuoteService quotesChain, LegacyCheckoutAdapter legacy,
            CheckoutLineResolver loader, ShippingZoneRepository zones,
            ShippingQuoteRecordRepository quoteRecords, CouponLedgerService coupons,
            ShopOrderPaidTransition paidTransition, ObjectProvider<CheckoutService> selfProvider) {
        this.carts = carts;
        this.inventory = inventory;
        this.orders = orders;
        this.orderLines = orderLines;
        this.wallet = wallet;
        this.tokens = tokens;
        this.displayNos = displayNos;
        this.idempotency = idempotency;
        this.merchantBindings = merchantBindings;
        this.quotesChain = quotesChain;
        this.legacy = legacy;
        this.loader = loader;
        this.zones = zones;
        this.quoteRecords = quoteRecords;
        this.coupons = coupons;
        this.paidTransition = paidTransition;
        this.selfProvider = selfProvider;
    }

    /**
     * 旧结算试算（旧版 App 的 {@code GET /me/checkout}，S-4）。
     *
     * <p>🔴 v1.4.2 shop Story 3.1 AC4：改走与新端点 {@code POST /me/checkout/preview} <b>同一条</b>装配链路
     * （{@link CheckoutQuoteService#compute}），入参由 {@link LegacyCheckoutAdapter#previewCommand} 固定为
     * 「购物车 · 最便宜快递 · 不用券 · 用币 · 不保价」；金额只来自 {@code ShopPricingCalculator}（P-1）。
     * 运费改由 Biteship 报价，{@code shipping_zones.fee} 与按最大区域估算的无地址运费停读（D-9）。
     *
     * <p>🔴 <b>本方法不加事务</b>：链路中间要在事务外调 Biteship（P-7）。
     *
     * <p>🔴 超服务范围 / 无地址 / 询价失败 / 一件没选都<b>不抛异常</b>，回一个金额位为 null 的试算
     * （{@code serviceable=false} 当且仅当超范围）：结算页仍要渲染地址、商品清单与警示并禁用提交（FR-99）。
     * <b>下单路径 {@link #placeOrder} 仍然照抛不误</b>。
     */
    public CheckoutPreview preview(long userId, String addressToken) {
        return legacy.toLegacyPreview(quotesChain.compute(userId, legacy.previewCommand(addressToken)));
    }

    /** 无幂等键的便捷重载（既有内部调用方 / 集成测试用）。对外下单入口走带 key 的主方法。 */
    public ShopOrder placeOrder(long userId, String addressToken, String entrySource,
            String triggerType) {
        return placeOrder(userId, addressToken, entrySource, triggerType, null);
    }

    /**
     * 旧结算模式下单（旧版 App / 存量内部调用，S-4）：委托 {@link LegacyCheckoutAdapter#placeCommand}
     * （事务外询价）→ {@link #place}（幂等占位）→ {@link #placeTransactional}（事务）。签名不变。
     *
     * <p>🔴 <b>本方法不加事务</b>（询价在提交事务之前，P-7）；同 key 重放先取回既有订单，不再询价。
     */
    public ShopOrder placeOrder(long userId, String addressToken, String entrySource,
            String triggerType, String idempotencyKey) {
        Optional<ShopOrder> replay = replayOf(userId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        PlaceCommand cmd = legacy.placeCommand(userId,
                PlaceOrderRequest.legacy(addressToken, entrySource, triggerType));
        return place(userId, cmd, idempotencyKey);
    }

    /** 同 key 处理中占位的 TTL：须长于一次提交事务的最长耗时（事务内零网络调用，2 分钟足够）。 */
    static final java.time.Duration CLAIM_TTL = java.time.Duration.ofMinutes(2);

    /**
     * 提交订单入口（新旧两种结算模式共用）：<b>本方法不加事务</b>，先在事务外处理幂等，再进
     * {@link #placeTransactional}。
     *
     * <p>🔴 复审修复（资金）：同一 {@code Idempotency-Key} 并发提交时，两边都会在 Redis 映射写入（提交后才写）之前
     * 通过重放检查，各建一单、各扣一次币。这里先 {@code SET NX} 抢占 {@code idem-claim:shop-checkout:{userId}:{key}}：
     * <ul>
     *   <li>已有映射 → 取回原单（重放）；</li>
     *   <li>抢不到 → 再查一次映射：有则取回原单，无则 409 {@code conflict}「订单正在提交中」（前一个请求仍在处理）；</li>
     *   <li>抢到 → 提交（映射在事务提交后写入），无论成败 finally 释放占位（失败回滚后同 key 可重试）。</li>
     * </ul>
     * 无 key 不做幂等（与改动前一致）。Redis 不可用时与 {@link IdempotencyService} 现有行为一致：异常上抛、请求失败，
     * 不降级放行。
     */
    public ShopOrder place(long userId, PlaceCommand cmd, String idempotencyKey) {
        String scopedKey = scopedKey(userId, idempotencyKey);
        if (scopedKey == null) {
            return selfProvider.getObject().placeTransactional(userId, cmd, null);
        }
        Optional<ShopOrder> replay = replayOf(userId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!idempotency.tryClaim(scopedKey, CLAIM_TTL)) {
            return replayOf(userId, idempotencyKey)
                    .orElseThrow(() -> AppException.conflict("订单正在提交中，请稍候"));
        }
        try {
            return selfProvider.getObject().placeTransactional(userId, cmd, idempotencyKey);
        } finally {
            idempotency.releaseClaim(scopedKey);
        }
    }

    /**
     * 提交订单主方法（v1.4.2 shop Story 3.2 AC3.2；新旧两种结算模式共用）。
     *
     * <p>由 {@link #place} 在完成幂等占位后经自引用代理调用，不直接对外。
     *
     * <p>🔴 <b>同一个事务</b>，任一步失败整体回滚：券、币、库存、订单、购物车全部不落。<b>事务内零网络调用</b>。
     *
     * <p>🔴 {@code Idempotency-Key} 去重（键按用户隔离 {@code shop-checkout:{userId}:{key}}）：同 key 重放取回既有订单，
     * 不重复建单、不重复锁库存、不重复扣币。
     *
     * @throws CheckoutUnavailableException 有行不可买（409 {@code unavailable-lines}，逐行明细）
     * @throws CouponInvalidException       所选券不可用或被并发占用（409 {@code coupon-invalid}）
     * @throws AppException                 422 {@code validation} / {@code not-placeable}；409 {@code quote-expired}；
     *                                      409 {@code pawcoin-insufficient}（余额被并发花掉）
     */
    @Transactional
    public ShopOrder placeTransactional(long userId, PlaceCommand cmd, String idempotencyKey) {
        // ① 幂等重放
        String scopedKey = scopedKey(userId, idempotencyKey);
        Optional<ShopOrder> replay = replayOf(userId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        // ② 退货规则同意（旧结算模式视为已同意）
        if (!cmd.agreeReturnPolicy()) {
            throw AppException.validation("请先同意退货规则");
        }
        boolean legacyMode = cmd.checkoutSource() == CheckoutSource.LEGACY;
        // 🔴 now 只取一次：展示号日期段、created_at、券有效期判断、扣币 / 占券时刻共用（Story 4-3）。
        Instant now = Instant.now();

        // ③ 行解析（复用 3.1 CheckoutLineResolver；CART 含库内勾着的失效行，用于逐行报错）
        CheckoutInputs in = loader.load(userId, cmd.checkout(), now);
        requirePlaceableLines(in, legacyMode);
        List<ResolvedCheckoutLine> lines = in.lines();

        // ④ 地址与区域
        ShippingAddress addr = in.address();
        if (addr == null) {
            throw AppException.notPlaceable("请选择收货地址");
        }
        if (addr.getKecamatan() == null
                || zones.findByKecamatanAndActiveTrue(addr.getKecamatan()).isEmpty()) {
            throw AppException.notPlaceable(notServiceableDetail(addr));
        }

        // ⑤ 报价锁定与绑定（I7，零网络调用）
        CheckoutPreviewRequest req = cmd.checkout().request();
        ShippingQuoteRecord quote = requireBoundQuote(cmd.quoteToken(), addr, lines, now);
        List<CourierRate> rates = quote.toResult().rates();
        CourierRate courier = rates.stream()
                .filter(r -> r.courierKey().equals(req.courierKey()))
                .findFirst()
                .orElseThrow(() -> AppException.quoteExpired("所选快递已不可用，请重新确认运费"));
        long lowest = quote.getLowestPrice() != null ? quote.getLowestPrice() : rates.getFirst().price();

        // ⑥⑦ 券 + 计价（服务端重新计价，不信任客户端金额；价格变动不比对、不提示，FR-128.6）
        boolean insurance = req.insuranceValue() && courier.insuranceSupported();
        boolean useCoin = req.useCoinValue() && in.coinEnabled();
        List<PricingCoupon> pricingCoupons = in.candidates().stream()
                .map(CheckoutLineResolver::toPricingCoupon).toList();
        String discountToken = cmd.checkout().couponsEnabled() ? blankToNull(req.discountCouponToken()) : null;
        String freeShippingToken = cmd.checkout().couponsEnabled()
                ? blankToNull(req.freeShippingCouponToken()) : null;
        PricingResult pr = ShopPricingCalculator.price(CheckoutQuoteService.pricingInput(in, lines,
                pricingCoupons, CouponSelection.manual(discountToken, freeShippingToken), courier,
                lowest, insurance, useCoin, now));
        if (pr.manualSelectionInvalid()) {
            throw new CouponInvalidException(invalidCouponTokens(pr, discountToken, freeShippingToken));
        }
        // 🔴 只占用【实际采用】的券：免运券输给满额免运（RULE_BETTER）→ 不报错、不占用（拍板点 ②）
        String appliedDiscount = pr.appliedDiscountUserCouponToken();
        String appliedFreeShipping = pr.appliedFreeShippingUserCouponToken();
        Map<String, Long> userCouponIds = new java.util.HashMap<>();
        for (OwnedCoupon o : in.candidates()) {
            userCouponIds.put(o.userCoupon().getPublicToken(), o.userCoupon().getId());
        }

        // ⑧ 建单
        String displayNo = displayNos.generateUnique(now, orders::existsByDisplayNo);
        CheckoutSource source = cmd.checkoutSource();
        ShopOrder order = orders.save(ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot(
                tokens.generate(), displayNo, now, userId, snapshotOf(addr),
                // 🔴 归属门店取下单这一刻的绑定，只看绑定不看门店状态（AD-19）
                merchantBindings.referrerOf(userId), source,
                pr.goodsAmount(), pr.discountCouponAmount(), pr.shippingFee(), pr.shippingDiscount(),
                pr.shippingDiscountSource(), pr.insuranceFee(), insurance, pr.payable(),
                appliedDiscount == null ? null : userCouponIds.get(appliedDiscount),
                appliedFreeShipping == null ? null : userCouponIds.get(appliedFreeShipping),
                cap(courier.courierCode(), 32), cap(courier.courierServiceCode(), 32),
                cap(courier.courierName(), 64), cap(courier.courierServiceName(), 64),
                cap(courier.durationRange() != null ? courier.durationRange() : courier.durationText(), 32),
                quote.getId(), req.useCoinValue())));

        // ⑨⑩ 订单行（单价为原单价、不分摊券 —— NFR7 返佣基数）+ 原子锁库存
        PricingCoupon discountCoupon = appliedDiscount == null ? null : pricingCoupons.stream()
                .filter(c -> c.userCouponToken().equals(appliedDiscount)).findFirst().orElse(null);
        for (ResolvedCheckoutLine l : lines) {
            ShopOrderLine ol = ShopOrderLine.of(order.getId(), l.sku().getId(), l.productName(),
                    l.specName(), l.price(), l.qty(), l.returnPolicy());
            // 🔴 归因优先取购物车行上记下的来源（Story 3.10）；请求值 / buyNow.entrySource 只作兜底
            ol.attributeTo(l.entrySource() != null ? l.entrySource() : cmd.fallbackEntrySource(),
                    l.triggerType() != null ? l.triggerType() : cmd.fallbackTriggerType());
            ol.markDiscountEligible(discountCoupon != null
                    && ShopPricingCalculator.inScope(discountCoupon, l.toPricingLine()));
            orderLines.save(ol);
            // 🔴 影响 0 行即抛，整个事务回滚 —— 并发下只有库存数量的订单能建成
            inventory.lock(l.sku().getId(), l.qty());
        }

        // ⑪ 券占用（一次调用锁全部；被别单抢占 → CouponInvalidException 409，整单回滚）
        List<String> lockTokens = new ArrayList<>(2);
        if (appliedDiscount != null) {
            lockTokens.add(appliedDiscount);
        }
        if (appliedFreeShipping != null) {
            lockTokens.add(appliedFreeShipping);
        }
        if (!lockTokens.isEmpty()) {
            coupons.lock(userId, order.getId(), lockTokens, now);
        }

        // ⑬ 拆分固化（取计价器出参，不随后续部分退款重算）
        order.applyPaymentSplit(channelOf(pr.coinAmount(), pr.cashAmount()), pr.coinAmount(),
                pr.cashAmount());
        // ⑫ 扣币（S-3）：refType 必须是 SHOP_ORDER（成长值不重复返），键 shop-order:{token} 与存量付款时扣同一个
        settlePawCoinSegment(order);

        // ⑭ 应付为 0（C-4，先判）/ 现金段为 0 → 当场「已付款」，不出码
        if (pr.payable() == 0L) {
            paidTransition.markPaid(order, PaidSource.ZERO_PAY);
        } else if (pr.cashAmount() == 0L) {
            paidTransition.markPaid(order, PaidSource.PURE_COIN);
        }

        // ⑮ 购物车：只删已下单行（Story 4-1）；BUY_NOW 不碰购物车
        if (source != CheckoutSource.BUY_NOW) {
            for (ResolvedCheckoutLine l : lines) {
                carts.remove(userId, l.skuToken());
            }
        }

        // ⑯ 幂等映射（key 为空时 store 为 no-op）
        idempotency.store(scopedKey, order.getId());
        return order;
    }

    /**
     * 扣减 PawCoin 段。
     *
     * <p>v1.4.2 shop Story 3.2（S-3）：新单在<b>提交事务内</b>调用；到账 / {@code /pay} 只对存量单
     * （{@code checkout_source IS NULL}）调用（过渡规则，{@code TODO(v1.4.3)}）。
     *
     * <p>🔴 <b>走既有 {@code PawCoinWalletService.debit}，不另造幂等机制</b>（AD-9 / NFR-10）——
     * 它自带 Redis 前置 + 跨 TTL 的 DB 兜底。幂等键 {@code shop-order:{orderToken}}
     * 照既有 {@code id-hd:{petProfileId}} 范式：<b>键稳定 = 同一单无论提交时还是到账时都只扣一次</b>。
     * {@code refType="SHOP_ORDER"}：{@code GrowthSpendListener.COIN_EXCLUDED} 据此不重复返成长值。
     */
    @Transactional
    public void settlePawCoinSegment(ShopOrder order) {
        long coin = order.getCoinAmount() == null ? 0L : order.getCoinAmount();
        if (coin <= 0) {
            return;     // 纯现金单没有 Coin 段
        }
        wallet.debit(order.getUserId(), coin, PawCoinTxnType.SPEND, "SHOP_ORDER", order.getId(),
                "shop-order:" + order.getPublicToken());
    }

    // ---------- 内部 ----------

    private static String scopedKey(long userId, String idempotencyKey) {
        // 🔴 键按用户隔离：客户端 key 是任意字符串，不隔离则 B 重放 A 用过的 key 会拿到 A 的订单。
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? null : "shop-checkout:" + userId + ":" + idempotencyKey;
    }

    private Optional<ShopOrder> replayOf(long userId, String idempotencyKey) {
        Optional<Long> existing = idempotency.findResourceId(scopedKey(userId, idempotencyKey));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(orders.findById(existing.get())
                .filter(o -> o.getUserId() != null && o.getUserId() == userId)
                .orElseThrow(() -> AppException.notFound("订单不存在")));
    }

    /**
     * 可结算行校验。🔴 逐行收集、不遇第一个就抛 —— 用户要一次看清全部问题。
     *
     * <p>旧结算模式（S-4）保留旧版 App 认识的文案与判序：空车 → 422「购物车为空」；车里有东西但一件没勾
     * （有效行没选中，且失效行里也没有库内勾着的）→ 422「请至少选择一件商品」；勾着的失效行 / 库存不够 → 409 逐行明细。
     * 新结算模式：无可结算行 → 422 {@code not-placeable}；有不可买行 → 409 {@code unavailable-lines}。
     */
    private static void requirePlaceableLines(CheckoutInputs in, boolean legacyMode) {
        if (legacyMode && in.cart() != null) {
            CartView cart = in.cart();
            // 🔴 「空车」只在【有效行与失效行都为空】时成立；整车失效要的是逐行明细
            if (cart.lines().isEmpty() && cart.invalidLines().isEmpty()) {
                throw AppException.validation("购物车为空");
            }
            // v1.4.2 Story 3.1 复审修复：失效行对外 selected 恒 false，这里按库内勾选位判（旧契约 S-4）
            if (cart.selectedLines().isEmpty()
                    && cart.invalidLines().stream().noneMatch(CartView.CartLine::selectedInDb)) {
                throw AppException.validation("请至少选择一件商品");
            }
        } else if (in.lines().isEmpty()) {
            throw AppException.notPlaceable("没有可结算的商品");
        }
        List<UnavailableLine> unavailable = new ArrayList<>();
        for (ResolvedCheckoutLine l : in.lines()) {
            if (l.unavailableReason() != null) {
                unavailable.add(unavailableOf(l, legacyMode));
            }
        }
        if (!unavailable.isEmpty()) {
            throw new CheckoutUnavailableException(unavailable);
        }
        if (in.lines().isEmpty()) {
            // 防御：旧模式下有选中行却一行都解析不出（SKU 被物理删除）
            throw AppException.notPlaceable("没有可结算的商品");
        }
    }

    /**
     * 结算行原因 → 对外逐行明细。下架 → {@code DELISTED}；售罄 / 库存不足 → {@code INSUFFICIENT_STOCK}；
     * 不可售 → 新模式 {@code NOT_SELLABLE}，旧模式降级为 {@code INSUFFICIENT_STOCK} + {@code available=0}
     * （旧版 App 只认前两个值，「Sisa 0」可让用户移除后继续，AC5.3）。
     */
    static UnavailableLine unavailableOf(ResolvedCheckoutLine l, boolean legacyMode) {
        long available = l.availableStock() == null ? 0L : l.availableStock();
        String reason;
        if (ResolvedCheckoutLine.REASON_DELISTED.equals(l.unavailableReason())) {
            reason = UnavailableLine.REASON_DELISTED;
        } else if (ResolvedCheckoutLine.REASON_NOT_SELLABLE.equals(l.unavailableReason())) {
            if (legacyMode) {
                reason = UnavailableLine.REASON_INSUFFICIENT_STOCK;
                available = 0L;
            } else {
                reason = UnavailableLine.REASON_NOT_SELLABLE;
            }
        } else {
            reason = UnavailableLine.REASON_INSUFFICIENT_STOCK;
        }
        return new UnavailableLine(l.skuToken(), l.productName(), l.specName(), reason, available,
                l.qty());
    }

    /**
     * 报价锁定与绑定（I7）：存在、未过期、目的邮编 = 地址邮编、{@code items_hash} = 由本单行重算的哈希。
     * 哈希调用 1.1 的<b>同一个</b> {@link PackageItems#itemsHash}，不另写一份。
     */
    private ShippingQuoteRecord requireBoundQuote(String quoteToken, ShippingAddress addr,
            List<ResolvedCheckoutLine> lines, Instant now) {
        if (quoteToken == null || quoteToken.isBlank()) {
            throw AppException.quoteExpired("运费报价已失效，请重新确认运费");
        }
        ShippingQuoteRecord q = quoteRecords.findByPublicToken(quoteToken.trim())
                .orElseThrow(() -> AppException.quoteExpired("运费报价已失效，请重新确认运费"));
        if (!q.getExpiresAt().isAfter(now)) {
            throw AppException.quoteExpired("运费报价已过期，请重新确认运费");
        }
        String kodePos = addr.getKodePos() == null ? null : addr.getKodePos().trim();
        if (!Objects.equals(q.getDestPostalCode(), kodePos)) {
            throw AppException.quoteExpired("收货地址已变更，请重新确认运费");
        }
        String hash = PackageItems.itemsHash(lines.stream()
                .map(ResolvedCheckoutLine::toPackageItem).toList());
        if (!hash.equals(q.getItemsHash())) {
            throw AppException.quoteExpired("商品已变更，请重新确认运费");
        }
        return q;
    }

    /** MANUAL 指定的券里哪几张不可用（不在候选 / 类目错配 / 未达门槛 / 范围 / 过期；输给满额免运不算）。 */
    private static List<String> invalidCouponTokens(PricingResult pr, String discountToken,
            String freeShippingToken) {
        List<String> out = new ArrayList<>(2);
        if (discountToken != null && !usable(pr, discountToken, false)) {
            out.add(discountToken);
        }
        if (freeShippingToken != null && !usable(pr, freeShippingToken, true)) {
            out.add(freeShippingToken);
        }
        return out.isEmpty() ? List.of(discountToken != null ? discountToken : freeShippingToken) : out;
    }

    private static boolean usable(PricingResult pr, String token, boolean freeShipping) {
        for (CouponEvaluation e : pr.evaluations()) {
            if (!token.equals(e.userCouponToken())) {
                continue;
            }
            boolean kindOk = freeShipping
                    == (e.kind() == com.tailtopia.shop.order.pricing.PricingCouponKind.FREE_SHIPPING);
            return kindOk && (e.usable() || e.reason() == CouponUnavailableReason.RULE_BETTER);
        }
        return false;
    }

    /** 🔴 纯 QRIS / 纯 Coin 时 channel 取原值；两者都有才是 MIXED（AD-1）。零元单（两段皆 0）沿用存量口径 QRIS。 */
    static PayChannel channelOf(long coinAmount, long cashAmount) {
        if (coinAmount > 0 && cashAmount > 0) {
            return PayChannel.MIXED;
        }
        return coinAmount > 0 ? PayChannel.PAWCOIN : PayChannel.QRIS;
    }

    /** 沿用旧文案「暂不配送至 X，请更换收货地址」（旧版 App 直接展示 detail）。 */
    static String notServiceableDetail(ShippingAddress a) {
        String kec = a == null || a.getKecamatan() == null ? "" : a.getKecamatan().trim();
        return kec.isEmpty() ? "暂不配送至该地址，请更换收货地址" : "暂不配送至 %s，请更换收货地址".formatted(kec);
    }

    private static AddressSnapshot snapshotOf(ShippingAddress a) {
        return new AddressSnapshot(a.getReceiverName(), a.getReceiverPhone(), a.getProvinsi(),
                a.getKotaKabupaten(), a.getKecamatan(), a.getAddressLine(), a.getKodePos());
    }

    /** 快照列按列宽截断（Biteship 文案不可控，超长不该让提交 500）。 */
    private static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 旧结算页试算结果（v1.4.2 Story 3.1 起金额由新装配链路给出，见 {@link LegacyCheckoutAdapter}）。
     *
     * @param address        未传地址时为 {@code null}
     * @param shippingFee    最便宜快递运费；无报价（无地址 / 超范围 / 询价失败 / 一件没选）为 null
     * @param shippingDiscount 免运抵扣，<b>≤ 0</b>（旧口径）；同上为 null
     * @param payableTotal   应付；同上为 null
     * @param coinCapped     PawCoin 段被单笔上限截断（UX-DR14 要求多出一行提示）
     * @param serviceable    false 当且仅当超服务范围
     */
    public record CheckoutPreview(CartView cart, ShippingAddress address, Long shippingFee,
            Long shippingDiscount, Long payableTotal, Long coinAmount, Long cashAmount,
            long coinBalance, long maxCoinPerOrder, boolean coinCapped,
            boolean serviceable, Map<String, ReturnPolicy> returnPolicies) {
    }
}
