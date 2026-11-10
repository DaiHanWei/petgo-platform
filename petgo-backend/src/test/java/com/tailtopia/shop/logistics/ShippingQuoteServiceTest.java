package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.ErrorTypes;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 1.1 · 报价复用、失败不落库、可配送判定（Mockito，无 DB）。 */
class ShippingQuoteServiceTest {

    /** 可拨动的时钟。 */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final BiteshipClient client = mock(BiteshipClient.class);
    private final ShippingQuoteRecordRepository repo = mock(ShippingQuoteRecordRepository.class);
    private final ShippingSettingsRepository settingsRepo = mock(ShippingSettingsRepository.class);
    private final ShippingZoneRepository zones = mock(ShippingZoneRepository.class);
    private final ShippingSettings settings = mock(ShippingSettings.class);
    private final MutableClock clock = new MutableClock();
    private final List<ShippingQuoteRecord> saved = new ArrayList<>();
    private ShippingQuoteService service;

    private static final List<PackageItem> ITEMS =
            List.of(new PackageItem("Royal Canin 3 kg", 285_000L, 1, 1000, 20, 15, 10));

    @BeforeEach
    void setUp() {
        service = new ShippingQuoteService(client, repo, settingsRepo, zones,
                new ShopTokenGenerator(), new ShippingProbeProperties(), clock);
        when(client.providerMode()).thenReturn("LIVE");
        when(client.isAvailable()).thenReturn(true);   // mock 不走 default 方法，须显式打桩
        when(settingsRepo.findById(ShippingSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        when(settings.getOriginPostalCode()).thenReturn("12440");
        when(repo.save(any(ShippingQuoteRecord.class))).thenAnswer(inv -> {
            ShippingQuoteRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", (long) saved.size() + 1);
            saved.add(r);
            return r;
        });
        when(repo.findFirstByDestPostalCodeAndItemsHashAndOriginPostalCodeAndProviderModeAndExpiresAtAfterOrderByExpiresAtDesc(
                anyString(), anyString(), anyString(), anyString(), any(Instant.class)))
                .thenAnswer(inv -> saved.stream()
                        .filter(r -> r.getDestPostalCode().equals(inv.getArgument(0))
                                && r.getItemsHash().equals(inv.getArgument(1))
                                && r.getOriginPostalCode().equals(inv.getArgument(2))
                                && r.getProviderMode().equals(inv.getArgument(3))
                                && r.getExpiresAt().isAfter(inv.getArgument(4)))
                        .max(Comparator.comparing(ShippingQuoteRecord::getExpiresAt)));
        when(repo.findByPublicToken(anyString())).thenAnswer(inv -> saved.stream()
                .filter(r -> r.getPublicToken().equals(inv.getArgument(0))).findFirst());
    }

    private static BiteshipRate rate(String code, String name, String svc, long price, boolean ins) {
        return new BiteshipRate(code, name, svc, svc.toUpperCase(), "1 - 2 days", "1 - 2", "days",
                price, ins);
    }

    private void ratesReturn(BiteshipRate... rates) {
        when(client.rates(any())).thenReturn(BiteshipRatesResult.of(List.of(rates)));
    }

    @Test
    @DisplayName("30 分钟内同键复用，不再调 Biteship；token 相同")
    void reuseWithin30Minutes() {
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        QuoteResult first = service.quote("40115", ITEMS, 7L);
        clock.now = clock.now.plus(Duration.ofMinutes(29));
        QuoteResult second = service.quote("40115", ITEMS, 8L);

        verify(client, times(1)).rates(any());
        assertThat(second.quoteToken()).isEqualTo(first.quoteToken());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(7L);
        assertThat(first.expiresAt()).isEqualTo(Instant.parse("2026-11-05T08:30:00Z"));
    }

    @Test
    @DisplayName("过期后重新询价并落新行")
    void expiredRequotes() {
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        QuoteResult first = service.quote("40115", ITEMS, 7L);
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        QuoteResult second = service.quote("40115", ITEMS, 7L);

        verify(client, times(2)).rates(any());
        assertThat(saved).hasSize(2);
        assertThat(second.quoteToken()).isNotEqualTo(first.quoteToken());
    }

    @Test
    @DisplayName("reuseFor=24h 重载写入 expires_at = now + 24h；probe 用标准探测包裹")
    void reuseFor24h() {
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        QuoteResult r = service.quote("40115", ITEMS, null, Duration.ofHours(24));
        assertThat(r.expiresAt()).isEqualTo(clock.now.plus(Duration.ofHours(24)));

        ArgumentCaptor<BiteshipRatesRequest> req = ArgumentCaptor.forClass(BiteshipRatesRequest.class);
        QuoteResult p = service.probe("40116", 1L);
        verify(client, times(2)).rates(req.capture());
        assertThat(p.expiresAt()).isEqualTo(clock.now.plus(Duration.ofHours(24)));
        assertThat(req.getValue().items()).containsExactly(
                new BiteshipRatesRequest.Item("probe", 100_000L, 1, 1000, 20, 15, 10));
    }

    @Test
    @DisplayName("询价失败 → QuoteFailedException（503 quote-failed），不落库")
    void failureNotPersisted() {
        when(client.rates(any())).thenThrow(new BiteshipException(BiteshipException.Kind.SERVER, 500, null));
        assertThatThrownBy(() -> service.quote("40115", ITEMS, 7L))
                .isInstanceOfSatisfying(QuoteFailedException.class, e -> {
                    assertThat(e.getType()).isEqualTo(ErrorTypes.QUOTE_FAILED);
                    assertThat(e.getStatus().value()).isEqualTo(503);
                });
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("不可配送也落库：serviceable=false、lowest_price=null、记原码；复用")
    void noCourierPersisted() {
        when(client.rates(any())).thenReturn(BiteshipRatesResult.noCourier(40001010));
        QuoteResult r = service.quote("40115", ITEMS, 7L);
        assertThat(r.serviceable()).isFalse();
        assertThat(r.lowestPrice()).isNull();
        assertThat(r.rates()).isEmpty();
        assertThat(saved).singleElement().satisfies(rec -> {
            assertThat(rec.isServiceable()).isFalse();
            assertThat(rec.getLowestPrice()).isNull();
            assertThat(rec.getResponse().noCourierCode()).isEqualTo(40001010);
        });
        service.quote("40115", ITEMS, 7L);
        verify(client, times(1)).rates(any());
    }

    @Test
    @DisplayName("结果按价格升序，并列按快递名、服务码升序；lowest = 最低价；courierKey = code:service")
    void deterministicOrder() {
        ratesReturn(rate("sicepat", "SiCepat", "reg", 12_000, false),
                rate("jne", "JNE", "yes", 10_000, true),
                rate("anteraja", "AnterAja", "reg", 10_000, false),
                rate("jne", "JNE", "reg", 10_000, true));
        QuoteResult r = service.quote("40115", ITEMS, 7L);
        assertThat(r.rates()).extracting(CourierRate::courierKey)
                .containsExactly("anteraja:reg", "jne:reg", "jne:yes", "sicepat:reg");
        assertThat(r.lowestPrice()).isEqualTo(10_000L);
        assertThat(r.serviceable()).isTrue();
        assertThat(saved.get(0).getLowestPrice()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("邮编格式不对 → 直接不可配送，不调 Biteship、不落库")
    void badPostalCode() {
        for (String pc : new String[] {"0115", "01150", "4011a", "", null, "401155"}) {
            QuoteResult r = service.quote(pc, ITEMS, 7L);
            assertThat(r.serviceable()).isFalse();
            assertThat(r.quoteToken()).isNull();
        }
        verify(client, never()).rates(any());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("live 模式无发货地邮编 → QuoteFailedException，不调 Biteship")
    void liveWithoutOrigin() {
        when(settings.getOriginPostalCode()).thenReturn(null);
        assertThatThrownBy(() -> service.quote("40115", ITEMS, 7L)).isInstanceOf(QuoteFailedException.class);
        verify(client, never()).rates(any());
    }

    @Test
    @DisplayName("stub 模式无发货地邮编 → 占位 00000 继续询价")
    void stubWithoutOrigin() {
        when(client.providerMode()).thenReturn("STUB");
        when(settings.getOriginPostalCode()).thenReturn(null);
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        service.quote("40115", ITEMS, 7L);
        assertThat(saved.get(0).getOriginPostalCode()).isEqualTo("00000");
        assertThat(saved.get(0).getProviderMode()).isEqualTo("STUB");
    }

    @Test
    @DisplayName("复用键含 provider_mode：STUB 报价不会在 LIVE 下复用")
    void providerModeInReuseKey() {
        when(client.providerMode()).thenReturn("STUB");
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        service.quote("40115", ITEMS, 7L);
        when(client.providerMode()).thenReturn("LIVE");
        service.quote("40115", ITEMS, 7L);
        verify(client, times(2)).rates(any());
    }

    @Test
    @DisplayName("C-5：客户端不可用 → 查复用之前即抛 quote-failed，已有未过期报价也不复用")
    void unavailableClientNeverReusesOldQuote() {
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        service.quote("40115", ITEMS, 7L);           // 先落一条有效报价
        when(client.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.quote("40115", ITEMS, 7L))
                .isInstanceOfSatisfying(QuoteFailedException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.QUOTE_FAILED));
        verify(client, times(1)).rates(any());
        // 复用查找只发生在第一次（可用时）；不可用后未再查
        verify(repo, times(1))
                .findFirstByDestPostalCodeAndItemsHashAndOriginPostalCodeAndProviderModeAndExpiresAtAfterOrderByExpiresAtDesc(
                        anyString(), anyString(), anyString(), anyString(), any(Instant.class));
        assertThat(saved).hasSize(1);
    }

    @Test
    @DisplayName("C-5：真实 UnavailableBiteshipClient 装进服务 → 询价 / probe 一律 quote-failed，不落库")
    void realUnavailableClient() {
        ShippingQuoteService s2 = new ShippingQuoteService(
                new com.tailtopia.shared.logistics.biteship.UnavailableBiteshipClient(), repo, settingsRepo,
                zones, new ShopTokenGenerator(), new ShippingProbeProperties(), clock);
        assertThatThrownBy(() -> s2.quote("40115", ITEMS, 7L)).isInstanceOf(QuoteFailedException.class);
        assertThatThrownBy(() -> s2.probe("40115", 7L)).isInstanceOf(QuoteFailedException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("findValid：未过期返回，过期返回空")
    void findValid() {
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        QuoteResult r = service.quote("40115", ITEMS, 7L);
        assertThat(service.findValid(r.quoteToken(), clock.now.plus(Duration.ofMinutes(29)))).isPresent();
        assertThat(service.findValid(r.quoteToken(), clock.now.plus(Duration.ofMinutes(30)))).isEmpty();
        assertThat(service.findValid("nope", clock.now)).isEmpty();
    }

    @Test
    @DisplayName("checkAddress：区未开通 → ZONE_NOT_OPEN，不调客户端")
    void checkAddressZoneNotOpen() {
        when(zones.findByKecamatanAndActiveTrue("Coblong")).thenReturn(Optional.empty());
        Serviceability s = service.checkAddress("Coblong", "40132", ITEMS, 7L);
        assertThat(s.status()).isEqualTo(Serviceability.Status.ZONE_NOT_OPEN);
        verify(client, never()).rates(any());
    }

    @Test
    @DisplayName("checkAddress：已开通 → 按 kodePos 询价，SERVICEABLE / NO_COURIER")
    void checkAddressQuotes() {
        ShippingZone z = ShippingZone.create("Coblong", "Bandung", "Jawa Barat", 10_000);
        when(zones.findByKecamatanAndActiveTrue("Coblong")).thenReturn(Optional.of(z));
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        Serviceability s = service.checkAddress("Coblong", "40132", ITEMS, 7L);
        assertThat(s.status()).isEqualTo(Serviceability.Status.SERVICEABLE);
        assertThat(s.quote().destPostalCode()).isEqualTo("40132");

        when(client.rates(any())).thenReturn(BiteshipRatesResult.noCourier(40001010));
        assertThat(service.checkAddress("Coblong", "40133", ITEMS, 7L).status())
                .isEqualTo(Serviceability.Status.NO_COURIER);
    }

    @Test
    @DisplayName("checkZone：区不存在 / 未开通 → ZONE_NOT_OPEN；无 postal_code → NO_COURIER，均不调客户端")
    void checkZone() {
        when(zones.findById(anyLong())).thenReturn(Optional.empty());
        assertThat(service.checkZone(1L, ITEMS, null).status()).isEqualTo(Serviceability.Status.ZONE_NOT_OPEN);

        ShippingZone inactive = ShippingZone.create("A", "B", "C", 1);
        inactive.applyPostalCode("40132");
        inactive.setActive(false);
        when(zones.findById(2L)).thenReturn(Optional.of(inactive));
        assertThat(service.checkZone(2L, ITEMS, null).status()).isEqualTo(Serviceability.Status.ZONE_NOT_OPEN);

        ShippingZone noPostal = ShippingZone.create("D", "E", "F", 1);
        when(zones.findById(3L)).thenReturn(Optional.of(noPostal));
        Serviceability s = service.checkZone(3L, ITEMS, null);
        assertThat(s.status()).isEqualTo(Serviceability.Status.NO_COURIER);
        assertThat(s.quote()).isNull();
        verify(client, never()).rates(any());

        ShippingZone ok = ShippingZone.create("G", "H", "I", 1);
        ok.applyPostalCode("40132");
        when(zones.findById(4L)).thenReturn(Optional.of(ok));
        ratesReturn(rate("jne", "JNE", "reg", 10_000, true));
        assertThat(service.checkZone(4L, ITEMS, null).status()).isEqualTo(Serviceability.Status.SERVICEABLE);
    }
}
