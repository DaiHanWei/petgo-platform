package com.tailtopia.shop.logistics;

import com.tailtopia.shared.logistics.biteship.BiteshipClient;
import com.tailtopia.shared.logistics.biteship.BiteshipException;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRate;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.domain.ShippingZone;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Biteship 报价与复用（v1.4.2 shop Story 1.1，D-9 / NFR2 / P-7）。
 *
 * <p>架构 §5.1 里它「替换」旧 {@code shop.shipping.service.ShippingQuoteService}（按 Kecamatan 固定运费）；
 * 本 story 只新增、不删旧类——旧类改用显式 bean 名 {@code zoneShippingQuoteService}，Story 3.2 删除。
 *
 * <p>🔴 <b>本类不加 {@code @Transactional}</b>：先查复用 → 事务外调 Biteship → 仓库 {@code save}
 * 自带的短事务落库。外部调用期间不持有任何行锁。并发下同键可能两次真调（各插一行），可接受，
 * 不加唯一约束、不加锁。
 *
 * <p>「询价失败」抛 {@link QuoteFailedException}（503 {@code quote-failed}），<b>不落库、不回退固定运费</b>；
 * 「不可配送」是正常结果，照样落库复用。
 *
 * <p>🔒 日志只记目的邮编前 2 位、区域 id；不记地址、完整邮编、手机号。
 */
@Service
public class ShippingQuoteService {

    private static final Logger log = LoggerFactory.getLogger(ShippingQuoteService.class);

    /** 默认复用 30 分钟（D-9）。 */
    public static final Duration DEFAULT_REUSE = Duration.ofMinutes(30);
    /** 地址簿探测包裹复用 24 小时（D-9，3.1 用）。 */
    public static final Duration PROBE_REUSE = Duration.ofHours(24);
    /** stub 模式下未配发货地邮编时的占位。 */
    static final String STUB_ORIGIN_PLACEHOLDER = "00000";

    private static final Pattern POSTAL = Pattern.compile("^[1-9][0-9]{4}$");

    /** 价格升序，并列按快递名、服务码升序（结果确定）。 */
    static final Comparator<CourierRate> RATE_ORDER = Comparator
            .comparingLong(CourierRate::price)
            .thenComparing(CourierRate::courierName, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CourierRate::courierServiceCode,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    private final BiteshipClient client;
    private final ShippingQuoteRecordRepository quotes;
    private final ShippingSettingsRepository settings;
    private final ShippingZoneRepository zones;
    private final ShopTokenGenerator tokens;
    private final ShippingProbeProperties probe;
    private final Clock clock;

    @Autowired
    public ShippingQuoteService(BiteshipClient client, ShippingQuoteRecordRepository quotes,
            ShippingSettingsRepository settings, ShippingZoneRepository zones,
            ShopTokenGenerator tokens, ShippingProbeProperties probe) {
        this(client, quotes, settings, zones, tokens, probe, Clock.systemUTC());
    }

    ShippingQuoteService(BiteshipClient client, ShippingQuoteRecordRepository quotes,
            ShippingSettingsRepository settings, ShippingZoneRepository zones,
            ShopTokenGenerator tokens, ShippingProbeProperties probe, Clock clock) {
        this.client = client;
        this.quotes = quotes;
        this.settings = settings;
        this.zones = zones;
        this.tokens = tokens;
        this.probe = probe;
        this.clock = clock;
    }

    /** 询价，默认复用 30 分钟。 */
    public QuoteResult quote(String destPostalCode, List<PackageItem> items, Long userId) {
        return quote(destPostalCode, items, userId, DEFAULT_REUSE);
    }

    /**
     * 询价。
     *
     * @param reuseFor 新报价的有效期（也是复用窗口）
     * @throws QuoteFailedException 询价失败或 live / sandbox 下未配发货地邮编
     */
    public QuoteResult quote(String destPostalCode, List<PackageItem> items, Long userId,
            Duration reuseFor) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        String hash = PackageItems.itemsHash(items);
        String dest = destPostalCode == null ? null : destPostalCode.trim();
        if (dest == null || !POSTAL.matcher(dest).matches()) {
            // 邮编格式不对：直接不可配送，不调 Biteship、不落库
            return new QuoteResult(null, false, List.of(), null, dest, hash, null);
        }
        if (!client.isAvailable()) {
            // C-5：Biteship 配置不可用（如生产缺 key）→ 一律 quote-failed，不复用任何历史报价
            log.error("Biteship client unavailable; shipping quote refused");
            throw new QuoteFailedException("运费询价暂不可用");
        }
        String mode = client.providerMode();
        String origin = originPostalCode(mode);
        Instant now = clock.instant();

        Optional<ShippingQuoteRecord> hit = quotes
                .findFirstByDestPostalCodeAndItemsHashAndOriginPostalCodeAndProviderModeAndExpiresAtAfterOrderByExpiresAtDesc(
                        dest, hash, origin, mode, now);
        if (hit.isPresent()) {
            return hit.get().toResult();
        }

        BiteshipRatesResult result;
        try {
            // 🔴 事务外调用
            result = client.rates(new BiteshipRatesRequest(origin, dest, items.stream()
                    .map(i -> new BiteshipRatesRequest.Item(i.name(), i.value(), i.quantity(),
                            i.weightG(), i.lengthCm(), i.widthCm(), i.heightCm()))
                    .toList()));
        } catch (BiteshipException e) {
            log.warn("shipping quote failed dest={}*** kind={} http={} code={}", dest.substring(0, 2),
                    e.getKind(), e.getHttpStatus(), e.getBiteshipCode());
            throw new QuoteFailedException("运费询价失败，请稍后重试");
        }

        List<CourierRate> rates = normalize(result);
        Instant fetchedAt = clock.instant();
        ShippingQuoteRecord saved = quotes.save(ShippingQuoteRecord.create(tokens.generate(), userId,
                mode, origin, dest, hash, rates, result.noCourierCode(), fetchedAt,
                fetchedAt.plus(reuseFor)));
        return saved.toResult();
    }

    /** 标准探测包裹询价（24 小时复用），3.1 地址簿 {@code serviceable} 用。 */
    public QuoteResult probe(String destPostalCode, Long userId) {
        return quote(destPostalCode, List.of(probe.toItem()), userId, PROBE_REUSE);
    }

    /** 按 token 取未过期报价（3.2 提交用；过期由 3.2 映射 409 {@code quote-expired}）。 */
    public Optional<QuoteResult> findValid(String quoteToken, Instant now) {
        if (quoteToken == null || quoteToken.isBlank()) {
            return Optional.empty();
        }
        return quotes.findByPublicToken(quoteToken)
                .filter(q -> q.getExpiresAt().isAfter(now))
                .map(ShippingQuoteRecord::toResult);
    }

    /**
     * 登录用户按地址判定可配送：Kecamatan 未开通 → {@code ZONE_NOT_OPEN}（不调 Biteship）；
     * 否则按 {@code kodePos} 询价。
     *
     * @throws QuoteFailedException 询价失败
     */
    public Serviceability checkAddress(String kecamatan, String kodePos, List<PackageItem> items,
            Long userId) {
        Optional<ShippingZone> zone = kecamatan == null ? Optional.empty()
                : zones.findByKecamatanAndActiveTrue(kecamatan);
        if (zone.isEmpty()) {
            return new Serviceability(Serviceability.Status.ZONE_NOT_OPEN, null);
        }
        return Serviceability.of(quote(kodePos, items, userId));
    }

    /**
     * 游客按地区判定可配送：区不存在 / 未开通 → {@code ZONE_NOT_OPEN}；无代表邮编 → {@code NO_COURIER}
     * （均不调 Biteship）；否则按区代表邮编询价。
     */
    public Serviceability checkZone(long zoneId, List<PackageItem> items, Long userId) {
        Optional<ShippingZone> zone = zones.findById(zoneId).filter(ShippingZone::isActive);
        if (zone.isEmpty()) {
            return new Serviceability(Serviceability.Status.ZONE_NOT_OPEN, null);
        }
        String pc = zone.get().getPostalCode();
        if (pc == null || pc.isBlank()) {
            log.info("shipping zone without postal code zoneId={}", zoneId);
            return new Serviceability(Serviceability.Status.NO_COURIER, null);
        }
        return Serviceability.of(quote(pc, items, userId));
    }

    private String originPostalCode(String mode) {
        String origin = settings.findById(ShippingSettings.SINGLETON_ID)
                .map(ShippingSettings::getOriginPostalCode)
                .filter(s -> !s.isBlank())
                .orElse(null);
        if (origin != null) {
            return origin;
        }
        if ("STUB".equals(mode)) {
            return STUB_ORIGIN_PLACEHOLDER;
        }
        log.error("shipping_settings.origin_postal_code not configured; Biteship quote unavailable");
        throw new QuoteFailedException("运费询价暂不可用");
    }

    static List<CourierRate> normalize(BiteshipRatesResult result) {
        return result.rates().stream()
                .map(ShippingQuoteService::toCourierRate)
                .sorted(RATE_ORDER)
                .toList();
    }

    private static CourierRate toCourierRate(BiteshipRate r) {
        return new CourierRate(CourierRate.keyOf(r.courierCode(), r.courierServiceCode()),
                r.courierCode(), r.courierServiceCode(), r.courierName(), r.courierServiceName(),
                r.duration(), r.shipmentDurationRange(), r.shipmentDurationUnit(), r.price(),
                r.availableForInsurance());
    }
}
