package com.tailtopia.shop.order.service;

import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.dto.ShippingAddressView;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.dto.CouponViews;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.logistics.CourierRate;
import com.tailtopia.shop.logistics.PackageItem;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.logistics.QuoteResult;
import com.tailtopia.shop.logistics.Serviceability;
import com.tailtopia.shop.logistics.ShippingQuoteService;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.dto.CheckoutQuoteView;
import com.tailtopia.shop.order.pricing.CoinOptions;
import com.tailtopia.shop.order.pricing.CouponEvaluation;
import com.tailtopia.shop.order.pricing.CouponSelection;
import com.tailtopia.shop.order.pricing.PricingCoupon;
import com.tailtopia.shop.order.pricing.PricingCouponKind;
import com.tailtopia.shop.order.pricing.PricingInput;
import com.tailtopia.shop.order.pricing.PricingResult;
import com.tailtopia.shop.order.pricing.ShippingChoice;
import com.tailtopia.shop.order.pricing.ShopPricingCalculator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 结算试算编排（v1.4.2 shop Story 3.1 AC1，D-8 / D-9 / P-1 / P-7）。
 *
 * <p>🔴 <b>本类不加 {@code @Transactional}</b>：只读事务里取齐入参（{@link CheckoutLineResolver#load}）→
 * 事务<b>外</b>调 1.1 的 {@link ShippingQuoteService} 询价 → 纯函数计价器 → 组视图。
 * 询价期间不持有任何连接 / 行锁。
 *
 * <p>🔴 <b>不做任何加减乘除</b>（P-1）：金额全部来自 {@link ShopPricingCalculator}，这里只装配入参、搬运出参。
 *
 * <p>🔴 <b>试算永不因券报错</b>：手选券不可用 → 不采用、放进 {@code unavailable} 组；只有提交（3.2）才 409。
 * 询价失败同样不抛 503，转成 {@code QUOTE_FAILED}（页面要渲染「运费暂时无法获取 · 重试」）。
 *
 * <p>🔒 日志不记地址 / 邮编 / 手机号（本类不打日志）。
 */
@Service
public class CheckoutQuoteService {

    private final CheckoutLineResolver loader;
    private final ShippingQuoteService quotes;
    private final Clock clock;

    @Autowired
    public CheckoutQuoteService(CheckoutLineResolver loader, ShippingQuoteService quotes) {
        this(loader, quotes, Clock.systemUTC());
    }

    CheckoutQuoteService(CheckoutLineResolver loader, ShippingQuoteService quotes, Clock clock) {
        this.loader = loader;
        this.quotes = quotes;
        this.clock = clock;
    }

    /** {@code POST /me/checkout/preview}。 */
    public CheckoutQuoteView preview(long userId, CheckoutPreviewRequest req) {
        return toView(compute(userId, CheckoutCommand.of(req)));
    }

    // ------------------------------------------------------------------ 询价结果

    /** 询价结果（含未询价的原因）。 */
    public record QuoteOutcome(Status status, QuoteResult result) {

        public enum Status {
            /** 报价 ≥ 1 家 */
            OK,
            /** 没有收货地址：不询价 */
            NO_ADDRESS,
            /** 区域未开通或报价 0 家 */
            NOT_SERVICEABLE,
            /** Biteship 超时 / 5xx / 网络错误 / 配置不可用 */
            QUOTE_FAILED,
            /** 没有可计价的行：不询价 */
            SKIPPED
        }

        public boolean ok() {
            return status == Status.OK;
        }

        static QuoteOutcome of(Status s) {
            return new QuoteOutcome(s, null);
        }
    }

    /**
     * 一次试算的完整结果（新视图与旧结算模式共用；3.2 复用）。
     *
     * @param selectedCourier 已选快递；无报价为 null
     * @param useCoin         有效值（请求 ∧ 规则开启）
     * @param manualDiscountToken     MANUAL 手选的满减券 token（AUTO / 旧结算模式为 null）
     * @param manualFreeShippingToken MANUAL 手选的免运券 token（同上）
     */
    public record CheckoutComputation(CheckoutInputs inputs, QuoteOutcome quote,
            CourierRate selectedCourier, boolean insuranceAvailable, boolean insurance,
            boolean useCoin, PricingResult pricing, String notPlaceableReason, Instant now,
            String manualDiscountToken, String manualFreeShippingToken) {

        public boolean placeable() {
            return notPlaceableReason == null;
        }

        /** 生效退货规则最严一档（只看计价行；无行落到最严）。 */
        public ReturnPolicy strictestReturnPolicy() {
            return CheckoutPreviewView.strictest(inputs.pricedLines().stream()
                    .map(ResolvedCheckoutLine::returnPolicy).toList());
        }
    }

    /** 装配链路主体：只读取数 → 事务外询价 → 计价器。 */
    public CheckoutComputation compute(long userId, CheckoutCommand cmd) {
        Instant now = clock.instant();
        CheckoutInputs in = loader.load(userId, cmd, now);
        CheckoutPreviewRequest req = cmd.request();

        List<ResolvedCheckoutLine> priced = in.pricedLines();
        QuoteOutcome quote = quote(userId, in.address(), priced);

        // 已选快递：指定且仍在列表 → 保留；否则最便宜（1.1 已按价格升序）
        CourierRate selected = null;
        Long lowest = null;
        if (quote.ok()) {
            List<CourierRate> rates = quote.result().rates();
            selected = rates.getFirst();
            if (req.courierKey() != null) {
                for (CourierRate r : rates) {
                    if (r.courierKey().equals(req.courierKey())) {
                        selected = r;
                        break;
                    }
                }
            }
            lowest = quote.result().lowestPrice() != null ? quote.result().lowestPrice()
                    : rates.getFirst().price();
        }
        boolean insuranceAvailable = selected != null && selected.insuranceSupported();
        boolean insurance = req.insuranceValue() && insuranceAvailable;
        boolean useCoin = req.useCoinValue() && in.coinEnabled();

        List<PricingCoupon> pricingCoupons = in.candidates().stream()
                .map(CheckoutLineResolver::toPricingCoupon).toList();
        CouponSelection selection;
        if (!cmd.couponsEnabled()) {
            selection = CouponSelection.manual(null, null);   // 旧结算模式：不用券
        } else if (req.couponModeValue() == CheckoutPreviewRequest.CouponMode.MANUAL) {
            selection = CouponSelection.manual(blankToNull(req.discountCouponToken()),
                    blankToNull(req.freeShippingCouponToken()));
        } else {
            selection = CouponSelection.auto();
        }

        PricingResult pr = ShopPricingCalculator.price(
                pricingInput(in, priced, pricingCoupons, selection, selected, lowest, insurance,
                        useCoin, now));

        return new CheckoutComputation(in, quote, selected, insuranceAvailable, insurance,
                useCoin, pr, notPlaceableReason(in, quote), now,
                selection.mode() == CouponSelection.Mode.MANUAL ? selection.discountUserCouponToken() : null,
                selection.mode() == CouponSelection.Mode.MANUAL ? selection.freeShippingUserCouponToken() : null);
    }

    /**
     * 计价器入参装配（试算与 3.2 提交共用，保证两处入参同构；本方法不做任何计算）。
     *
     * @param selected 已选快递；null = 运费未知（计价器按 0 计、{@code shippingKnown=false}）
     * @param lowest   本单最低报价（{@code selected} 非 null 时必填）
     * @param useCoin  有效值（请求 ∧ 规则开启）
     */
    static PricingInput pricingInput(CheckoutInputs in, List<ResolvedCheckoutLine> priced,
            List<PricingCoupon> pricingCoupons, CouponSelection selection, CourierRate selected,
            Long lowest, boolean insurance, boolean useCoin, Instant now) {
        return new PricingInput(
                priced.stream().map(ResolvedCheckoutLine::toPricingLine).toList(),
                pricingCoupons, selection, in.rule(),
                selected == null ? null : new ShippingChoice(selected.price(), lowest),
                insurance, in.insuranceRateBp(),
                new CoinOptions(useCoin, in.coinBalance(), in.coinCap(), in.coinEnabled(),
                        in.allowShippingDeduction()),
                now);
    }

    /** 🔴 事务外。 */
    private QuoteOutcome quote(long userId, ShippingAddress addr, List<ResolvedCheckoutLine> priced) {
        if (priced.isEmpty()) {
            return QuoteOutcome.of(QuoteOutcome.Status.SKIPPED);
        }
        if (addr == null) {
            return QuoteOutcome.of(QuoteOutcome.Status.NO_ADDRESS);
        }
        List<PackageItem> items = priced.stream().map(ResolvedCheckoutLine::toPackageItem).toList();
        try {
            Serviceability sv = quotes.checkAddress(addr.getKecamatan(), addr.getKodePos(), items,
                    userId);
            return switch (sv.status()) {
                case SERVICEABLE -> sv.quote() != null && !sv.quote().rates().isEmpty()
                        ? new QuoteOutcome(QuoteOutcome.Status.OK, sv.quote())
                        : QuoteOutcome.of(QuoteOutcome.Status.NOT_SERVICEABLE);
                case ZONE_NOT_OPEN, NO_COURIER -> QuoteOutcome.of(QuoteOutcome.Status.NOT_SERVICEABLE);
            };
        } catch (QuoteFailedException e) {
            return QuoteOutcome.of(QuoteOutcome.Status.QUOTE_FAILED);
        }
    }

    /** 判序：NO_LINES → UNAVAILABLE_LINES → NO_ADDRESS → NOT_SERVICEABLE → QUOTE_FAILED。 */
    static String notPlaceableReason(CheckoutInputs in, QuoteOutcome quote) {
        if (in.lines().isEmpty()) {
            return CheckoutQuoteView.NO_LINES;
        }
        if (in.lines().stream().anyMatch(l -> l.unavailableReason() != null)) {
            return CheckoutQuoteView.UNAVAILABLE_LINES;
        }
        if (in.address() == null) {
            return CheckoutQuoteView.NO_ADDRESS;
        }
        return switch (quote.status()) {
            case NOT_SERVICEABLE -> CheckoutQuoteView.NOT_SERVICEABLE;
            case QUOTE_FAILED -> CheckoutQuoteView.QUOTE_FAILED;
            case NO_ADDRESS -> CheckoutQuoteView.NO_ADDRESS;
            case SKIPPED -> CheckoutQuoteView.NO_LINES;   // 防御：有行即有计价行
            case OK -> null;
        };
    }

    // ------------------------------------------------------------------ 视图

    CheckoutQuoteView toView(CheckoutComputation c) {
        CheckoutInputs in = c.inputs();
        PricingResult pr = c.pricing();
        boolean shippingIncluded = c.quote().ok() && pr.shippingKnown();

        List<CheckoutQuoteView.CourierOption> couriers = new ArrayList<>();
        if (c.quote().ok()) {
            List<CourierRate> rates = c.quote().result().rates();
            for (int i = 0; i < rates.size(); i++) {
                CourierRate r = rates.get(i);
                couriers.add(new CheckoutQuoteView.CourierOption(r.courierKey(), r.courierCode(),
                        r.courierServiceCode(), r.courierName(), r.courierServiceName(),
                        r.durationRange() != null ? r.durationRange() : r.durationText(),
                        r.price(), r.insuranceSupported(), i == 0));
            }
        }

        List<CheckoutQuoteView.QuoteLine> lines = in.lines().stream()
                .map(l -> new CheckoutQuoteView.QuoteLine(l.skuToken(), l.productToken(),
                        l.productName(), l.specName(), l.price(), l.qty(), l.mainImageUrl(),
                        l.returnPolicy() == null ? null : l.returnPolicy().name(),
                        l.unavailableReason(), l.availableStock()))
                .toList();

        CheckoutQuoteView.PricingView pricing = new CheckoutQuoteView.PricingView(
                pr.goodsAmount(), pr.discountCouponAmount(),
                shippingIncluded ? pr.shippingFee() : null,
                shippingIncluded ? pr.shippingDiscount() : null,
                shippingIncluded ? pr.shippingDiscountSource().name() : "NONE",
                pr.insuranceFee(), pr.payable(), pr.coinAmount(), pr.cashAmount(),
                pr.savedAmount(), shippingIncluded, pr.freeShippingProgress());

        String strictest = c.strictestReturnPolicy().name();
        ShippingAddress addr = in.address();
        return new CheckoutQuoteView(
                in.source().name(),
                addr == null ? null : ShippingAddressView.of(addr),
                c.quote().ok() ? c.quote().result().quoteToken() : null,
                couriers,
                c.selectedCourier() == null ? null : c.selectedCourier().courierKey(),
                c.insuranceAvailable(),
                c.insurance(),
                lines,
                pricing,
                couponGroups(c),
                new CheckoutQuoteView.CoinView(in.coinBalance(), in.coinCap(), in.coinEnabled(),
                        c.useCoin(), pr.coinCapped()),
                strictest,
                strictest,
                c.placeable(),
                c.notPlaceableReason());
    }

    private CheckoutQuoteView.CouponGroups couponGroups(CheckoutComputation c) {
        CheckoutInputs in = c.inputs();
        PricingResult pr = c.pricing();
        Map<String, OwnedCoupon> byToken = new LinkedHashMap<>();
        in.candidates().forEach(o -> byToken.put(o.userCoupon().getPublicToken(), o));

        List<CheckoutQuoteView.CouponOption> discAvail = new ArrayList<>();
        List<CheckoutQuoteView.CouponOption> discUnavail = new ArrayList<>();
        List<CheckoutQuoteView.CouponOption> fsAvail = new ArrayList<>();
        List<CheckoutQuoteView.CouponOption> fsUnavail = new ArrayList<>();
        for (CouponEvaluation e : pr.evaluations()) {
            OwnedCoupon o = byToken.get(e.userCouponToken());
            CheckoutQuoteView.CouponOption opt = option(o, e.userCouponToken(),
                    e.kind() == PricingCouponKind.FREE_SHIPPING ? CouponKind.FREE_SHIPPING.name()
                            : CouponKind.DISCOUNT.name(),
                    e.usable() ? e.benefit() : 0L,
                    e.usable() ? null : e.reason().name(), c.now());
            boolean fs = e.kind() == PricingCouponKind.FREE_SHIPPING;
            (e.usable() ? (fs ? fsAvail : discAvail) : (fs ? fsUnavail : discUnavail)).add(opt);
        }

        // 手选但不在候选里的券（被占用 / 已失效 / 不是你的）→ 对应类目的 unavailable 组
        addManualExtra(c, c.manualDiscountToken(), CouponKind.DISCOUNT, byToken, discUnavail);
        addManualExtra(c, c.manualFreeShippingToken(), CouponKind.FREE_SHIPPING, byToken, fsUnavail);

        Comparator<CheckoutQuoteView.CouponOption> byDeduction = Comparator
                .comparingLong(CheckoutQuoteView.CouponOption::deduction).reversed()
                .thenComparing(CheckoutQuoteView.CouponOption::validUntil,
                        Comparator.nullsLast(Comparator.naturalOrder()));
        discAvail.sort(byDeduction);
        fsAvail.sort(byDeduction);
        return new CheckoutQuoteView.CouponGroups(
                new CheckoutQuoteView.CouponGroup(pr.appliedDiscountUserCouponToken(), discAvail,
                        discUnavail),
                new CheckoutQuoteView.CouponGroup(pr.appliedFreeShippingUserCouponToken(), fsAvail,
                        fsUnavail));
    }

    /**
     * 手选 token 不在候选里 → 放进该类目 unavailable 组，原因 {@code UNAVAILABLE}。本人券（被占用 / 已失效）
     * 带券面信息；查不到的（不是你的 / 不存在）只回显 token。
     */
    private static void addManualExtra(CheckoutComputation c, String token, CouponKind kind,
            Map<String, OwnedCoupon> candidates, List<CheckoutQuoteView.CouponOption> target) {
        if (token == null) {
            return;
        }
        OwnedCoupon candidate = candidates.get(token);
        if (candidate != null) {
            // 复审修复（AC1.6）：类目错配（满减券填进免运槽或反之）→ 计价器判无效、不采用，
            // 在所填槽位的 unavailable 组回显（它在本来类目的组里照常按评估结果出现）
            if (candidate.coupon().getKind() != kind) {
                target.add(option(candidate, token, candidate.coupon().getKind().name(), 0L,
                        CheckoutQuoteView.COUPON_UNAVAILABLE, c.now()));
            }
            return;
        }
        OwnedCoupon o = c.inputs().manualExtras().get(token);
        target.add(option(o, token, kind.name(), 0L, CheckoutQuoteView.COUPON_UNAVAILABLE, c.now()));
    }

    private static CheckoutQuoteView.CouponOption option(OwnedCoupon o, String userCouponToken,
            String kind, long deduction, String reason, Instant now) {
        if (o == null) {
            return new CheckoutQuoteView.CouponOption(userCouponToken, null, kind, null, null,
                    null, null, null, false, deduction, reason);
        }
        Instant until = o.userCoupon().getValidUntil();
        boolean expiringSoon = now.isBefore(until)
                && Duration.between(now, until).compareTo(CouponViews.EXPIRING_SOON) <= 0;
        return new CheckoutQuoteView.CouponOption(userCouponToken, o.coupon().getPublicToken(),
                o.coupon().getKind().name(), o.coupon().getNameId(), o.coupon().getNameEn(),
                o.coupon().getAmount(), o.coupon().getMinSpend(), until, expiringSoon, deduction,
                reason);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
