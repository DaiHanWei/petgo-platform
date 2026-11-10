package com.tailtopia.shop.order.service;

import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.service.CouponQueryService;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.domain.ShopPawcoinRules;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import com.tailtopia.shop.order.pricing.PricingCoupon;
import com.tailtopia.shop.order.pricing.PricingCouponKind;
import com.tailtopia.shop.order.pricing.PricingScopeType;
import com.tailtopia.shop.order.repository.ShopPawcoinRulesRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算入参装配（v1.4.2 shop Story 3.1）：行、地址、券候选、规则、余额 —— 在<b>一个只读事务</b>里取齐，
 * 事务结束后调用方再去事务外询价（P-7）。3.2 下单复用同一组件，不在 preview 方法里写死。
 *
 * <p>🔴 不做任何金额计算（P-1）；🔴 券只读不改（P-2）。
 */
@Component
public class CheckoutLineResolver {

    private final CartService carts;
    private final ShippingAddressService addresses;
    private final ShippingAddressRepository addressRepo;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final InventoryService inventory;
    private final ShopImageUrlResolver imageUrls;
    private final CouponQueryService coupons;
    private final ShippingSettingsRepository settings;
    private final ShopPawcoinRulesRepository coinRules;
    private final PawCoinWalletService wallet;

    public CheckoutLineResolver(CartService carts, ShippingAddressService addresses,
            ShippingAddressRepository addressRepo, ShopSkuRepository skus,
            ShopProductRepository products, InventoryService inventory,
            ShopImageUrlResolver imageUrls, CouponQueryService coupons,
            ShippingSettingsRepository settings, ShopPawcoinRulesRepository coinRules,
            PawCoinWalletService wallet) {
        this.carts = carts;
        this.addresses = addresses;
        this.addressRepo = addressRepo;
        this.skus = skus;
        this.products = products;
        this.inventory = inventory;
        this.imageUrls = imageUrls;
        this.coupons = coupons;
        this.settings = settings;
        this.coinRules = coinRules;
        this.wallet = wallet;
    }

    /** 一次取齐结算入参（只读事务）。 */
    @Transactional(readOnly = true)
    public CheckoutInputs load(long userId, CheckoutCommand cmd, Instant now) {
        CheckoutPreviewRequest req = cmd.request();
        CheckoutPreviewRequest.Source source = req.sourceValue();
        CheckoutPreviewRequest.CouponMode mode = req.couponModeValue();

        CartView cart = null;
        List<ResolvedCheckoutLine> lines;
        if (source == CheckoutPreviewRequest.Source.BUY_NOW) {
            // 🔴 不读、不写购物车
            CheckoutPreviewRequest.BuyNow b = req.requireBuyNow();
            lines = List.of(buyNowLine(b.skuToken(), b.qty()));
        } else {
            // 🔴 复审修复：只读读车，无购物车按空车、不 INSERT shop_carts（只读事务里会被拒）
            cart = carts.viewReadOnly(userId);
            lines = new ArrayList<>(cartLines(cart.selectedLines()));
            // 🔴 复审修复（S-4 同口径）：库内勾着的失效行照样进 lines[]、带原因 → UNAVAILABLE_LINES，
            //    与旧下单「勾着的失效行 409 逐行明细」一致，绝不静默少买
            //    V1.4.2 验收拍板（DEC-142-01）：只对旧结算模式保留这条；新端点跳过——新版 App 已把失效行
            //    显示为未勾选、不计入合计，「绝不静默少买」在新版里由购物车界面本身告知，不该再拦整单。
            if (!cmd.skipInvalidCartLines()) {
                lines.addAll(selectedInvalidLines(cart.invalidLines()));
            }
        }

        ShippingAddress address = resolveAddress(userId, req.addressTokenOrNull(),
                cmd.defaultAddressFallback());

        List<OwnedCoupon> candidates = cmd.couponsEnabled()
                ? coupons.checkoutCandidates(userId, now) : List.of();
        Map<String, OwnedCoupon> extras = new LinkedHashMap<>();
        if (cmd.couponsEnabled() && mode == CheckoutPreviewRequest.CouponMode.MANUAL) {
            Set<String> known = candidates.stream().map(c -> c.userCoupon().getPublicToken())
                    .collect(Collectors.toSet());
            for (String t : new String[] {req.discountCouponToken(), req.freeShippingCouponToken()}) {
                if (t != null && !t.isBlank() && !known.contains(t)) {
                    coupons.owned(userId, t).ifPresent(o -> extras.put(t, o));
                }
            }
        }

        ShopPawcoinRules rules = requireCoinRules();
        ShippingSettings s = settings.findById(ShippingSettings.SINGLETON_ID).orElse(null);
        return new CheckoutInputs(source, cart, lines, address, candidates, extras,
                freeShippingRule(s), insuranceRateBp(s), rules.isEnabled(),
                rules.isAllowShippingDeduction(), rules.getMaxCoinPerOrder(),
                wallet.balanceOf(userId));
    }

    // ---------------------------------------------------------------- 行

    /**
     * 购物车选中有效行 → 结算行。可售量不够本次数量 → {@code INSUFFICIENT_STOCK}（与下单的第二次库存校验同口径）。
     */
    public List<ResolvedCheckoutLine> cartLines(List<CartView.CartLine> selected) {
        if (selected.isEmpty()) {
            return List.of();
        }
        Map<String, ShopSku> skuByToken = skus.findByPublicTokenIn(
                selected.stream().map(CartView.CartLine::skuToken).toList()).stream()
                .collect(Collectors.toMap(ShopSku::getPublicToken, Function.identity(), (a, b) -> a));
        Map<Long, ShopProduct> productById = productsOf(skuByToken.values());
        List<ResolvedCheckoutLine> out = new ArrayList<>(selected.size());
        for (CartView.CartLine l : selected) {
            ShopSku sku = skuByToken.get(l.skuToken());
            if (sku == null) {
                continue;   // SKU 已被物理删除（罕见）
            }
            ShopProduct p = productById.get(sku.getProductId());
            long available = l.availableStock() == null ? 0L : l.availableStock();
            String reason = available < l.qty() ? ResolvedCheckoutLine.REASON_INSUFFICIENT_STOCK : null;
            out.add(new ResolvedCheckoutLine(l.skuToken(), l.productToken(), l.productName(),
                    l.specName(), l.price(), l.qty(), l.mainImageUrl(), effectivePolicy(sku, p),
                    reason, l.availableStock(), p == null ? null : p.getCategory(),
                    l.entrySource(), l.triggerType(), sku));
        }
        return out;
    }

    /** 库内勾着的失效行 → 带原因的结算行（不计价、不询价）。 */
    List<ResolvedCheckoutLine> selectedInvalidLines(List<CartView.CartLine> invalid) {
        List<CartView.CartLine> picked = invalid.stream().filter(CartView.CartLine::selectedInDb).toList();
        if (picked.isEmpty()) {
            return List.of();
        }
        Map<String, ShopSku> skuByToken = skus.findByPublicTokenIn(
                picked.stream().map(CartView.CartLine::skuToken).toList()).stream()
                .collect(Collectors.toMap(ShopSku::getPublicToken, Function.identity(), (a, b) -> a));
        Map<Long, ShopProduct> productById = productsOf(skuByToken.values());
        List<ResolvedCheckoutLine> out = new ArrayList<>(picked.size());
        for (CartView.CartLine l : picked) {
            ShopSku sku = skuByToken.get(l.skuToken());
            ShopProduct p = sku == null ? null : productById.get(sku.getProductId());
            out.add(new ResolvedCheckoutLine(l.skuToken(), l.productToken(), l.productName(),
                    l.specName(), l.price(), l.qty(), l.mainImageUrl(),
                    sku == null ? ReturnPolicy.NON_RETURNABLE : effectivePolicy(sku, p),
                    l.invalidReason() == null ? ResolvedCheckoutLine.REASON_DELISTED : l.invalidReason(),
                    l.availableStock(), p == null ? null : p.getCategory(),
                    l.entrySource(), l.triggerType(), sku));
        }
        return out;
    }

    /** 立即购买行。SKU 不存在 → 404；下架 / 不可售 / 售罄 / 库存不足 → 带原因的行（不抛错）。 */
    ResolvedCheckoutLine buyNowLine(String skuToken, int qty) {
        ShopSku sku = skus.findByPublicTokenIn(List.of(skuToken)).stream().findFirst()
                .orElseThrow(() -> AppException.notFound("规格不存在"));
        ShopProduct p = products.findById(sku.getProductId()).orElse(null);
        long available = inventory.availableBySkuId(List.of(sku.getId()))
                .getOrDefault(sku.getId(), 0L);
        String reason = null;
        if (p == null || !p.isActive()) {
            reason = ResolvedCheckoutLine.REASON_DELISTED;
        } else if (!sku.isSellable()) {
            reason = ResolvedCheckoutLine.REASON_NOT_SELLABLE;
        } else if (available <= 0) {
            reason = ResolvedCheckoutLine.REASON_OUT_OF_STOCK;
        } else if (available < qty) {
            reason = ResolvedCheckoutLine.REASON_INSUFFICIENT_STOCK;
        }
        return new ResolvedCheckoutLine(sku.getPublicToken(),
                p == null ? null : p.getPublicToken(), p == null ? null : p.getName(),
                sku.getSpecName(), sku.getPrice(), qty,
                p == null ? null : imageUrls.publicUrl(p.getMainImageKey()),
                effectivePolicy(sku, p), reason, available,
                p == null ? null : p.getCategory(), null, null, sku);
    }

    private Map<Long, ShopProduct> productsOf(java.util.Collection<ShopSku> skuList) {
        Set<Long> ids = new HashSet<>();
        skuList.forEach(s -> ids.add(s.getProductId()));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return products.findAllById(ids).stream()
                .collect(Collectors.toMap(ShopProduct::getId, Function.identity()));
    }

    /** 生效退货规则：SKU 未设继承商品，都没有落到最严（与 {@code CheckoutService.effectiveReturnPolicy} 同口径）。 */
    static ReturnPolicy effectivePolicy(ShopSku sku, ShopProduct p) {
        ReturnPolicy policy = sku.effectiveReturnPolicy(p == null ? null : p.getReturnPolicy());
        return policy == null ? ReturnPolicy.NON_RETURNABLE : policy;
    }

    // ---------------------------------------------------------------- 地址

    private ShippingAddress resolveAddress(long userId, String token, boolean defaultFallback) {
        if (token != null) {
            return addresses.require(userId, token);   // 不是本人 → 404
        }
        return defaultFallback ? addressRepo.findByUserIdAndIsDefaultTrue(userId).orElse(null) : null;
    }

    /** 默认地址（购物车预估用）。 */
    @Transactional(readOnly = true)
    public ShippingAddress defaultAddress(long userId) {
        return addressRepo.findByUserIdAndIsDefaultTrue(userId).orElse(null);
    }

    // ---------------------------------------------------------------- 规则

    public FreeShippingRule freeShippingRule() {
        return freeShippingRule(settings.findById(ShippingSettings.SINGLETON_ID).orElse(null));
    }

    private static FreeShippingRule freeShippingRule(ShippingSettings s) {
        return s == null ? new FreeShippingRule(0L, null)
                : new FreeShippingRule(s.getFreeShippingThreshold(), s.getFreeShippingCap());
    }

    private static int insuranceRateBp(ShippingSettings s) {
        return s == null ? ShippingSettings.DEFAULT_INSURANCE_RATE_BP : s.getInsuranceRateBp();
    }

    public ShopPawcoinRules requireCoinRules() {
        return coinRules.findById(ShopPawcoinRules.SINGLETON_ID)
                .orElseThrow(() -> AppException.serviceUnavailable("PawCoin 规则未初始化"));
    }

    public List<OwnedCoupon> couponCandidates(long userId, Instant now) {
        return coupons.checkoutCandidates(userId, now);
    }

    // ---------------------------------------------------------------- 券映射

    /** 用户券 → 计价器值对象（计价器不依赖券领域类，m6）。 */
    public static PricingCoupon toPricingCoupon(OwnedCoupon o) {
        ShopCoupon c = o.coupon();
        PricingScopeType scope = c.getScopeType() == null ? PricingScopeType.ALL
                : PricingScopeType.valueOf(c.getScopeType().name());
        Set<String> values = c.getScopeValues() == null ? Set.of() : Set.copyOf(c.getScopeValues());
        return new PricingCoupon(o.userCoupon().getPublicToken(), c.getPublicToken(),
                c.getKind() == CouponKind.FREE_SHIPPING ? PricingCouponKind.FREE_SHIPPING
                        : PricingCouponKind.DISCOUNT,
                c.getAmount(), c.getMinSpend(), scope, values,
                o.userCoupon().getValidFrom(), o.userCoupon().getValidUntil());
    }
}
