package com.tailtopia.shop.logistics;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.pricing.CoinOptions;
import com.tailtopia.shop.order.pricing.CouponSelection;
import com.tailtopia.shop.order.pricing.FreeShippingProgress;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import com.tailtopia.shop.order.pricing.PricingInput;
import com.tailtopia.shop.order.pricing.PricingLine;
import com.tailtopia.shop.order.pricing.ShopPricingCalculator;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 商品详情运费估算（v1.4.2 shop Story 3.1 AC2，D-9 / FR-127 / FR-172.1）。
 *
 * <p>🔴 <b>本类不加事务</b>：读库各自短事务，Biteship 询价在事务外（P-7），落库与 30 分钟复用由
 * {@link ShippingQuoteService} 负责。🔒 不打含地址 / 邮编的日志。
 *
 * <p>免运进度由 {@link ShopPricingCalculator} 给（P-1，基数 = 商品实付；此处无券 → 单价 × 数量）。
 */
@Service
public class ShippingEstimateService {

    public static final int MAX_QTY = 99;

    private final ShopProductRepository products;
    private final ShopSkuRepository skus;
    private final ShippingAddressService addresses;
    private final ShippingSettingsRepository settings;
    private final ShippingQuoteService quotes;
    private final Clock clock;

    @Autowired
    public ShippingEstimateService(ShopProductRepository products, ShopSkuRepository skus,
            ShippingAddressService addresses, ShippingSettingsRepository settings,
            ShippingQuoteService quotes) {
        this(products, skus, addresses, settings, quotes, Clock.systemUTC());
    }

    ShippingEstimateService(ShopProductRepository products, ShopSkuRepository skus,
            ShippingAddressService addresses, ShippingSettingsRepository settings,
            ShippingQuoteService quotes, Clock clock) {
        this.products = products;
        this.skus = skus;
        this.addresses = addresses;
        this.settings = settings;
        this.quotes = quotes;
        this.clock = clock;
    }

    /**
     * @param viewerUserId     只认 role=USER 的登录用户；游客 / 非 USER 为 null（此时忽略 addressToken）
     * @param addressToken     本人地址（非本人 → 404）
     * @param regionKecamatanId {@code shipping_zones.id}（游客 / 无地址时）
     * @throws AppException 404 商品不存在或已下架 / 规格不属于该商品；422 参数非法 / {@code sku-not-sellable}
     */
    public ShippingEstimateView estimate(String productToken, String skuToken, Integer qty,
            Long viewerUserId, String addressToken, Long regionKecamatanId) {
        if (skuToken == null || skuToken.isBlank()) {
            throw AppException.validation("skuToken 必填");
        }
        int n = qty == null ? 1 : qty;
        if (n < 1 || n > MAX_QTY) {
            throw AppException.validation("数量须在 1 到 " + MAX_QTY + " 之间");
        }
        ShopProduct p = products.findByPublicTokenAndActiveTrue(productToken)
                .orElseThrow(() -> AppException.notFound("商品不存在"));
        ShopSku sku = skus.findByPublicToken(skuToken.trim())
                .filter(s -> s.getProductId().equals(p.getId()))
                .orElseThrow(() -> AppException.notFound("规格不存在"));
        if (!sku.isSellable()) {
            throw AppException.skuNotSellable("该规格暂不可售");
        }

        boolean useAddress = viewerUserId != null && addressToken != null && !addressToken.isBlank();
        if (!useAddress && regionKecamatanId == null) {
            return ShippingEstimateView.noDestination();
        }
        // 地址先取（非本人 → 404，在询价之前）
        ShippingAddress addr = useAddress ? addresses.require(viewerUserId, addressToken.trim()) : null;
        FreeShippingProgress progress = progress(p, sku, n);
        List<PackageItem> items = List.of(PackageItems.of(sku, p.getName(), n));
        try {
            Serviceability sv = addr != null
                    ? quotes.checkAddress(addr.getKecamatan(), addr.getKodePos(), items, viewerUserId)
                    : quotes.checkZone(regionKecamatanId, items, viewerUserId);
            if (sv.status() == Serviceability.Status.SERVICEABLE && sv.quote() != null
                    && !sv.quote().rates().isEmpty()) {
                Long lowest = sv.quote().lowestPrice() != null ? sv.quote().lowestPrice()
                        : sv.quote().rates().getFirst().price();
                return new ShippingEstimateView(true, lowest, false, progress, null);
            }
            return new ShippingEstimateView(false, null, false, progress,
                    ShippingEstimateView.NOT_SERVICEABLE);
        } catch (QuoteFailedException e) {
            return new ShippingEstimateView(null, null, true, progress,
                    ShippingEstimateView.QUOTE_FAILED);
        }
    }

    private FreeShippingProgress progress(ShopProduct p, ShopSku sku, int qty) {
        ShippingSettings s = settings.findById(ShippingSettings.SINGLETON_ID).orElse(null);
        FreeShippingRule rule = s == null ? new FreeShippingRule(0L, null)
                : new FreeShippingRule(s.getFreeShippingThreshold(), s.getFreeShippingCap());
        return ShopPricingCalculator.price(new PricingInput(
                List.of(new PricingLine(sku.getPublicToken(), p.getPublicToken(), p.getCategory(),
                        sku.getPrice(), qty)),
                List.of(), CouponSelection.manual(null, null), rule, null, false, 0,
                new CoinOptions(false, 0L, 0L, false, false), clock.instant()))
                .freeShippingProgress();
    }
}
