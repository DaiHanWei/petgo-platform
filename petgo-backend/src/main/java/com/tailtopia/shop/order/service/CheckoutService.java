package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.PaymentSplit;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopPawcoinRules;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.repository.ShopPawcoinRulesRepository;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.shop.shipping.dto.ShippingQuote;
import com.tailtopia.shop.shipping.service.ShippingQuoteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算与下单（Story 3.4，FR-95 / FR-99 / FR-100A / AD-1 / AD-8）。
 *
 * <p>这是把前面所有 Epic 串起来的地方：<b>购物车 → 地址 → 服务范围 → 运费 → 库存锁定 → 支付拆分</b>。
 *
 * <p>🔴 <b>下单只锁库存，不扣款。</b>订单落 {@code PENDING_PAYMENT}，
 * PawCoin 段的实际扣减在支付执行时（Story 3.8）经 {@link #settlePawCoinSegment} 完成。
 * 分开的理由：下单与付款之间有 60 分钟窗口（AD-8），
 * 在这个窗口里就把币扣掉，等于用户还没决定付钱就先被扣了。
 *
 * <p>🔴 <b>第二次库存校验必须逐行给出「哪个 SKU 怎么了」，不整单打回</b>（FR-95）。
 */
@Service
public class CheckoutService {

    private final CartService carts;
    private final ShippingAddressService addresses;
    private final ShippingQuoteService quotes;
    private final InventoryService inventory;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShopPawcoinRulesRepository rules;
    private final PawCoinWalletService wallet;
    private final ShopTokenGenerator tokens;
    private final IdempotencyService idempotency;

    public CheckoutService(CartService carts, ShippingAddressService addresses,
            ShippingQuoteService quotes, InventoryService inventory, ShopSkuRepository skus,
            ShopProductRepository products,
            ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ShopPawcoinRulesRepository rules, PawCoinWalletService wallet,
            ShopTokenGenerator tokens, IdempotencyService idempotency) {
        this.carts = carts;
        this.addresses = addresses;
        this.quotes = quotes;
        this.inventory = inventory;
        this.skus = skus;
        this.products = products;
        this.orders = orders;
        this.orderLines = orderLines;
        this.rules = rules;
        this.wallet = wallet;
        this.tokens = tokens;
        this.idempotency = idempotency;
    }

    /**
     * 结算试算（结算页展示用，不产生任何副作用）。
     *
     * <p>与 {@link #placeOrder} 走<b>同一套</b>运费与拆分计算 ——
     * 两处各算一遍必然漂移，表现为「结算页显示要付 285.000，提交后变成 305.000」。
     *
     * <p>🔴 <b>超服务范围时不抛异常，而是回一个 {@code serviceable=false} 的试算</b>（Story 3.7）：
     * 结算页此时仍要渲染地址、商品清单与「暂不配送至该区域」的警示并禁用提交（FR-99）。
     * 抛异常会让整页变成错误态 —— 用户看不到自己选的地址是哪一个，也就不知道该改哪里。
     * <b>下单路径 {@link #placeOrder} 仍然照抛不误</b>，阻断能力一点没少。
     */
    @Transactional(readOnly = true)
    public CheckoutPreview preview(long userId, String addressToken) {
        CartView cart = carts.view(userId);
        ShippingAddress addr = addresses.require(userId, addressToken);
        if (!quotes.isServiceable(addr.getKecamatan())) {
            return new CheckoutPreview(cart, addr, null, null, wallet.balanceOf(userId),
                    maxCoinPerOrder(), false, false, policiesOf(cart));
        }
        ShippingQuote quote = quotes.quote(addr.getKecamatan(), cart.subtotal());
        PaymentSplit split = splitFor(userId, cart.subtotal(), quote);
        return new CheckoutPreview(cart, addr, quote, split, wallet.balanceOf(userId),
                maxCoinPerOrder(), coinCapped(userId, cart.subtotal(), quote, split), true,
                policiesOf(cart));
    }

    /**
     * 每个有效行的<b>生效</b>退货规则（FR-104 第 2 处明示 / S-6）。
     *
     * <p>🔴 与 {@link #placeOrder} 落库用的是<b>同一个</b> {@link #effectiveReturnPolicy} ——
     * 结算页承诺的与订单行记下的必须是同一件事，否则退货时对不上账（而这正是 FR-104 存在的理由）。
     */
    private Map<String, ReturnPolicy> policiesOf(CartView cart) {
        Map<String, ShopSku> skuByToken = skusOf(cart);
        Map<String, ReturnPolicy> out = new java.util.LinkedHashMap<>();
        for (CartView.CartLine l : cart.lines()) {
            ShopSku sku = skuByToken.get(l.skuToken());
            if (sku != null) {
                out.put(l.skuToken(), effectiveReturnPolicy(sku));
            }
        }
        return out;
    }

    /**
     * PawCoin 段是否<b>被单笔上限截断</b>（C-16 / UX-DR14）。
     *
     * <p>🔴 判定是「若没有上限，本可以抵扣得更多」，<b>不是「coinAmount == 上限」</b>：
     * 余额恰好等于上限时也会相等，但那不是被截断，多出一行「本单最多可用 …」只会让用户困惑。
     * 不明示真被截断的情况同样有害 —— 用户会以为系统算错了。
     */
    private boolean coinCapped(long userId, long goodsSubtotal, ShippingQuote quote,
            PaymentSplit split) {
        ShopPawcoinRules r = requireRules();
        if (!r.isEnabled()) {
            return false;
        }
        long total = goodsSubtotal + quote.total();
        long deductible = r.isAllowShippingDeduction() ? total : Math.max(total - quote.total(), 0);
        long wouldUse = Math.min(deductible, wallet.balanceOf(userId));
        return wouldUse > r.getMaxCoinPerOrder() && split.coinAmount() == r.getMaxCoinPerOrder();
    }

    private long maxCoinPerOrder() {
        return requireRules().getMaxCoinPerOrder();
    }

    /** 无幂等键的便捷重载（既有内部调用方 / 集成测试用）。对外下单入口走带 key 的主方法。 */
    @Transactional
    public ShopOrder placeOrder(long userId, String addressToken, String entrySource,
            String triggerType) {
        return placeOrder(userId, addressToken, entrySource, triggerType, null);
    }

    /**
     * 下单。
     *
     * <p>🔴 {@code Idempotency-Key} 去重（照 {@code ContentService.publish} 范式，复用
     * {@link IdempotencyService}）：同 key 重放取回既有订单，<b>不重复建单、不重复锁库存</b> ——
     * 并发双击 / 网络重试不再产生两笔完整重复订单。key 为空则跳过（与 pay 端点同款，头可选）。
     *
     * @throws CheckoutUnavailableException 有行不可购买 —— 携带逐行明细，前端据此让用户移除后重试
     * @throws AppException 购物车为空 / 地址超范围 / 地址不存在
     */
    @Transactional
    public ShopOrder placeOrder(long userId, String addressToken, String entrySource,
            String triggerType, String idempotencyKey) {
        // 幂等重放：同 key 已落一单则取回，不重复创建。
        // 🔴 键按用户隔离：客户端 key 是任意字符串，不隔离则 B 重放 A 用过的 key 会拿到 A 的订单。
        String scopedKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : "shop-checkout:" + userId + ":" + idempotencyKey;
        Optional<Long> existing = idempotency.findResourceId(scopedKey);
        if (existing.isPresent()) {
            return orders.findById(existing.get())
                    .filter(o -> o.getUserId() != null && o.getUserId() == userId)
                    .orElseThrow(() -> AppException.notFound("订单不存在"));
        }
        CartView cart = carts.view(userId);
        // 🔴 「空车」只在【有效行与失效行都为空】时成立。
        //    若整车都是失效行，用户明明加过东西，报「购物车为空」是答非所问 ——
        //    他需要的是逐行明细（哪个下架了、哪个没货了），而不是一句与他的操作对不上的话。
        if (cart.lines().isEmpty() && cart.invalidLines().isEmpty()) {
            throw AppException.validation("购物车为空");
        }

        // ① 地址与服务范围。🔴 超范围在这里阻断（保存地址时不校验，FR-99）
        ShippingAddress addr = addresses.require(userId, addressToken);
        ShippingQuote quote = quotes.quote(addr.getKecamatan(), cart.subtotal());

        // ② 🔴 第二次库存校验（第一次在加购时）。逐行收集问题，不遇到第一个就抛
        List<UnavailableLine> unavailable = collectUnavailable(cart);
        if (!unavailable.isEmpty()) {
            throw new CheckoutUnavailableException(unavailable);
        }

        // ③ 建单
        ShopOrder order = orders.save(ShopOrder.place(tokens.generate(), userId,
                cart.subtotal(), quote.fee(), quote.discount(), snapshotOf(addr)));

        Map<String, ShopSku> skuByToken = skusOf(cart);
        for (CartView.CartLine line : cart.lines()) {
            ShopSku sku = skuByToken.get(line.skuToken());
            ShopOrderLine ol = ShopOrderLine.of(order.getId(), sku.getId(),
                    line.productName(), line.specName(), line.price(), line.qty(),
                    effectiveReturnPolicy(sku));
            // 🔴 归因随订单行落库（IR 前移）：AB-13B 的服务端权威依据。
            //    🔴 **优先取购物车行上记下的来源**（Story 3.10）—— 商品「从哪个入口进的车」
            //    只有加购那一刻知道；调用方传入的值只作兜底（如后台代下单）。
            //    两者都没有就写 null：诚实的「未知」好过编一个「从购物车结算」。
            ol.attributeTo(
                    line.entrySource() != null ? line.entrySource() : entrySource,
                    line.triggerType() != null ? line.triggerType() : triggerType);
            orderLines.save(ol);

            // ④ 🔴 原子锁定库存（Story 1.2 的原语）。影响 0 行即抛，整个事务回滚 ——
            //    并发下只有库存数量的订单能建成，多出来的那些一件也建不成。
            inventory.lock(sku.getId(), line.qty());
        }

        // ⑤ 支付拆分在建单时【固化】，不随后续部分退款重算
        PaymentSplit split = splitFor(userId, cart.subtotal(), quote);
        order.applyPaymentSplit(channelOf(split), split);

        // ⑥ 清掉已下单的行（失效行留在车里，用户还需要看到它们）
        for (CartView.CartLine line : cart.lines()) {
            carts.remove(userId, line.skuToken());
        }

        // ⑦ 记录幂等映射：重放取回本单（key 为空时 store 为 no-op）
        idempotency.store(scopedKey, order.getId());
        return order;
    }

    /**
     * 扣减 PawCoin 段（Story 3.8 支付执行时调用）。
     *
     * <p>🔴 <b>走既有 {@code PawCoinWalletService.debit}，不另造幂等机制</b>（AD-9 / NFR-10）——
     * 它自带 Redis 前置 + 跨 TTL 的 DB 兜底。幂等键 {@code shop-order:{orderToken}}
     * 照既有 {@code id-hd:{petProfileId}} 范式：<b>键稳定 = 重复投递的支付回调不会двойно扣币</b>。
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

    /** 🔴 逐行收集，不遇到第一个就抛 —— 用户要一次看清全部问题。 */
    private List<UnavailableLine> collectUnavailable(CartView cart) {
        List<UnavailableLine> out = new ArrayList<>();
        // 失效行（已下架/已售罄）直接进列表
        for (CartView.CartLine l : cart.invalidLines()) {
            out.add(new UnavailableLine(l.skuToken(), l.productName(), l.specName(),
                    CartView.REASON_DELISTED.equals(l.invalidReason())
                            ? UnavailableLine.REASON_DELISTED
                            : UnavailableLine.REASON_INSUFFICIENT_STOCK,
                    l.availableStock() == null ? 0L : l.availableStock(), l.qty()));
        }
        // 有效行再核一次可售量：加购到结算之间可能已被别人买走
        Map<String, ShopSku> skuByToken = skusOf(cart);
        for (CartView.CartLine l : cart.lines()) {
            ShopSku sku = skuByToken.get(l.skuToken());
            if (sku == null) {
                continue;
            }
            long available = inventory.availableBySkuId(List.of(sku.getId()))
                    .getOrDefault(sku.getId(), 0L);
            if (available < l.qty()) {
                out.add(new UnavailableLine(l.skuToken(), l.productName(), l.specName(),
                        UnavailableLine.REASON_INSUFFICIENT_STOCK, available, l.qty()));
            }
        }
        return out;
    }

    /**
     * 🔴 <b>退货规则取「生效值」</b>：SKU 自己没设时<b>继承商品级规则</b>
     * （后台表单的「Ikut produk」就是这个语义，Story 1.3）。
     *
     * <p>直接取 {@code sku.getReturnPolicy()} 会在继承场景下拿到 null，
     * 而订单行的该列 NOT NULL —— 表现是下单直接 500，且只在「运营用了继承」的商品上发生。
     * 更糟的是若该列可空，退货时就不知道当初承诺的是什么（FR-104）。
     */
    private ReturnPolicy effectiveReturnPolicy(ShopSku sku) {
        if (sku.getReturnPolicy() != null) {
            return sku.getReturnPolicy();
        }
        return products.findById(sku.getProductId())
                .map(ShopProduct::getReturnPolicy)
                // 商品也没有时落到最保守的一档：宁可少承诺，不可多承诺
                .orElse(ReturnPolicy.NON_RETURNABLE);
    }

    private Map<String, ShopSku> skusOf(CartView cart) {
        List<String> tokens = new ArrayList<>();
        cart.lines().forEach(l -> tokens.add(l.skuToken()));
        cart.invalidLines().forEach(l -> tokens.add(l.skuToken()));
        return skus.findAll().stream()
                .filter(s -> tokens.contains(s.getPublicToken()))
                .collect(Collectors.toMap(ShopSku::getPublicToken, s -> s, (a, b) -> a));
    }

    private ShopPawcoinRules requireRules() {
        return rules.findById(ShopPawcoinRules.SINGLETON_ID)
                .orElseThrow(() -> AppException.serviceUnavailable("PawCoin 规则未初始化"));
    }

    private PaymentSplit splitFor(long userId, long goodsSubtotal, ShippingQuote quote) {
        ShopPawcoinRules r = requireRules();
        long total = goodsSubtotal + quote.total();
        return PaymentSplit.compute(total, quote.total(), wallet.balanceOf(userId),
                r.getMaxCoinPerOrder(), r.isEnabled(), r.isAllowShippingDeduction());
    }

    /** 🔴 纯 QRIS / 纯 Coin 时 channel 取原值；两者都有才是 MIXED（AD-1）。 */
    private static PayChannel channelOf(PaymentSplit split) {
        if (split.isMixed()) {
            return PayChannel.MIXED;
        }
        return split.coinAmount() > 0 ? PayChannel.PAWCOIN : PayChannel.QRIS;
    }

    private static AddressSnapshot snapshotOf(ShippingAddress a) {
        return new AddressSnapshot(a.getReceiverName(), a.getReceiverPhone(), a.getProvinsi(),
                a.getKotaKabupaten(), a.getKecamatan(), a.getAddressLine(), a.getKodePos());
    }

    /**
     * 结算页试算结果。
     *
     * @param shipping 超服务范围时为 {@code null}（此时 {@code serviceable=false}）
     * @param split    同上
     * @param coinCapped PawCoin 段被单笔上限截断（UX-DR14 要求多出一行提示）
     */
    public record CheckoutPreview(CartView cart, ShippingAddress address, ShippingQuote shipping,
            PaymentSplit split, long coinBalance, long maxCoinPerOrder, boolean coinCapped,
            boolean serviceable, Map<String, ReturnPolicy> returnPolicies) {
    }
}
