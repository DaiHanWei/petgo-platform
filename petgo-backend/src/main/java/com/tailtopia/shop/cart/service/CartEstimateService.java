package com.tailtopia.shop.cart.service;

import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.cart.dto.CartEstimate;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.logistics.PackageItem;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.logistics.Serviceability;
import com.tailtopia.shop.logistics.ShippingQuoteService;
import com.tailtopia.shop.order.pricing.CoinOptions;
import com.tailtopia.shop.order.pricing.CouponSelection;
import com.tailtopia.shop.order.pricing.PricingInput;
import com.tailtopia.shop.order.pricing.PricingResult;
import com.tailtopia.shop.order.pricing.ShippingChoice;
import com.tailtopia.shop.order.pricing.ShopPricingCalculator;
import com.tailtopia.shop.order.service.CheckoutLineResolver;
import com.tailtopia.shop.order.service.ResolvedCheckoutLine;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 购物车底栏预估（v1.4.2 shop Story 3.1 AC3，D-9 / FR-133.8）。
 *
 * <p>🔴 <b>只在 {@code MeCartController} 出口调用</b>，不放进 {@link CartService#view}：view() 被结算 / 下单
 * 内部调用，放进去会让每次结算都多打一次计费询价。
 *
 * <p>🔴 <b>本类不加事务</b>：controller 先拿到 {@link CartView}（购物车写事务已提交），这里再事务外询价（P-7）。
 *
 * <p>🔴 金额全部来自 {@link ShopPricingCalculator}（P-1），入参固定「最便宜快递 + AUTO 券 + useCoin=false +
 * insurance=false」。收货地：默认地址 → 否则 {@code regionKecamatanId}（App 传详情页选过的地区）→ 都没有不含运费。
 */
@Service
public class CartEstimateService {

    private final CheckoutLineResolver resolver;
    private final ShippingQuoteService quotes;
    private final Clock clock;

    @Autowired
    public CartEstimateService(CheckoutLineResolver resolver, ShippingQuoteService quotes) {
        this(resolver, quotes, Clock.systemUTC());
    }

    CartEstimateService(CheckoutLineResolver resolver, ShippingQuoteService quotes, Clock clock) {
        this.resolver = resolver;
        this.quotes = quotes;
        this.clock = clock;
    }

    public CartEstimate estimate(long userId, CartView cart, Long regionKecamatanId) {
        List<ResolvedCheckoutLine> priced = cart.selectedLines().isEmpty() ? List.of()
                : resolver.cartLines(cart.selectedLines()).stream()
                        .filter(ResolvedCheckoutLine::priced).toList();
        if (priced.isEmpty()) {
            // 一件没选：金额全 0、不询价
            return new CartEstimate(null, 0L, 0L, 0L, 0L, 0L, null, CartEstimate.NO_SELECTION);
        }
        Instant now = clock.instant();
        List<PackageItem> items = priced.stream().map(ResolvedCheckoutLine::toPackageItem).toList();

        String reason = null;
        Long lowest = null;
        ShippingAddress addr = resolver.defaultAddress(userId);
        try {
            Serviceability sv;
            if (addr != null) {
                sv = quotes.checkAddress(addr.getKecamatan(), addr.getKodePos(), items, userId);
            } else if (regionKecamatanId != null) {
                sv = quotes.checkZone(regionKecamatanId, items, userId);
            } else {
                sv = null;
                reason = CartEstimate.NO_DESTINATION;
            }
            if (sv != null) {
                if (sv.status() == Serviceability.Status.SERVICEABLE && sv.quote() != null
                        && !sv.quote().rates().isEmpty()) {
                    lowest = sv.quote().lowestPrice() != null ? sv.quote().lowestPrice()
                            : sv.quote().rates().getFirst().price();
                } else {
                    reason = CartEstimate.NOT_SERVICEABLE;
                }
            }
        } catch (QuoteFailedException e) {
            reason = CartEstimate.QUOTE_FAILED;
        }

        PricingResult pr = ShopPricingCalculator.price(new PricingInput(
                priced.stream().map(ResolvedCheckoutLine::toPricingLine).toList(),
                resolver.couponCandidates(userId, now).stream()
                        .map(CheckoutLineResolver::toPricingCoupon).toList(),
                CouponSelection.auto(),
                resolver.freeShippingRule(),
                // 最便宜快递：所选 = 最低
                lowest == null ? null : new ShippingChoice(lowest, lowest),
                false, 0,
                // 不用币：PawCoin 规则与余额不参与（也不因规则未初始化让购物车 503）
                new CoinOptions(false, 0L, 0L, false, false),
                now));
        return new CartEstimate(
                pr.shippingKnown() ? pr.shippingFee() : null,
                pr.goodsAmount(), pr.discountCouponAmount(), pr.shippingDiscount(), pr.payable(),
                pr.savedAmount(), pr.freeShippingProgress(), reason);
    }
}
