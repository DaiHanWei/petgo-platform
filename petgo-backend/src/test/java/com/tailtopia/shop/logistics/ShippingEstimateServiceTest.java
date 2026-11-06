package com.tailtopia.shop.logistics;

import static com.tailtopia.shop.support.CheckoutFixtures.NOW;
import static com.tailtopia.shop.support.CheckoutFixtures.address;
import static com.tailtopia.shop.support.CheckoutFixtures.product;
import static com.tailtopia.shop.support.CheckoutFixtures.quote;
import static com.tailtopia.shop.support.CheckoutFixtures.rate;
import static com.tailtopia.shop.support.CheckoutFixtures.settings;
import static com.tailtopia.shop.support.CheckoutFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** L0：商品详情运费估算（v1.4.2 shop Story 3.1 AC2）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShippingEstimateServiceTest {

    private static final long UID = 7L;

    @Mock private ShopProductRepository products;
    @Mock private ShopSkuRepository skus;
    @Mock private ShippingAddressService addresses;
    @Mock private ShippingSettingsRepository settingsRepo;
    @Mock private ShippingQuoteService quotes;

    private ShippingEstimateService service;

    @BeforeEach
    void setUp() {
        service = new ShippingEstimateService(products, skus, addresses, settingsRepo, quotes,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(products.findByPublicTokenAndActiveTrue("p1"))
                .thenReturn(Optional.of(product(1, "p1", true, ReturnPolicy.RETURNABLE)));
        when(skus.findByPublicToken("s1")).thenReturn(Optional.of(sku(11, "s1", 1, 100_000L, true)));
        when(skus.findByPublicToken("bad")).thenReturn(Optional.of(sku(12, "bad", 1, 100_000L, false)));
        when(skus.findByPublicToken("alien")).thenReturn(Optional.of(sku(13, "alien", 99, 1L, true)));
        when(settingsRepo.findById(ShippingSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings(300_000L, null, 50)));
    }

    @Test
    @DisplayName("无收货地 → NO_DESTINATION，不询价")
    void noDestination() {
        ShippingEstimateView v = service.estimate("p1", "s1", 1, null, null, null);
        assertThat(v.reason()).isEqualTo(ShippingEstimateView.NO_DESTINATION);
        assertThat(v.serviceable()).isNull();
        assertThat(v.lowestFee()).isNull();
        assertThat(v.quoteFailed()).isFalse();
        assertThat(v.freeShippingProgress()).isNull();
        verifyNoInteractions(quotes);
    }

    @Test
    @DisplayName("游客带 addressToken（viewer=null）→ 忽略，按无收货地处理，不读地址")
    void guestAddressTokenIgnored() {
        ShippingEstimateView v = service.estimate("p1", "s1", 1, null, "addr", null);
        assertThat(v.reason()).isEqualTo(ShippingEstimateView.NO_DESTINATION);
        verifyNoInteractions(addresses);
    }

    @Test
    @DisplayName("区域未开通 / 无代表邮编 → serviceable=false（1.1 checkZone 不调 Biteship）")
    void zoneNotServiceable() {
        when(quotes.checkZone(eq(5L), anyList(), any()))
                .thenReturn(new Serviceability(Serviceability.Status.ZONE_NOT_OPEN, null));
        when(quotes.checkZone(eq(6L), anyList(), any()))
                .thenReturn(new Serviceability(Serviceability.Status.NO_COURIER, null));

        assertThat(service.estimate("p1", "s1", 1, null, null, 5L).serviceable()).isFalse();
        ShippingEstimateView noPostal = service.estimate("p1", "s1", 1, null, null, 6L);
        assertThat(noPostal.serviceable()).isFalse();
        assertThat(noPostal.reason()).isEqualTo(ShippingEstimateView.NOT_SERVICEABLE);
        verify(quotes, never()).quote(anyString(), anyList(), any());
    }

    @Test
    @DisplayName("询价失败 → quoteFailed=true、serviceable=null、reason=QUOTE_FAILED（不抛）")
    void quoteFailed() {
        when(quotes.checkZone(anyLong(), anyList(), any())).thenThrow(new QuoteFailedException("x"));
        ShippingEstimateView v = service.estimate("p1", "s1", 1, null, null, 5L);
        assertThat(v.quoteFailed()).isTrue();
        assertThat(v.serviceable()).isNull();
        assertThat(v.reason()).isEqualTo(ShippingEstimateView.QUOTE_FAILED);
    }

    @Test
    @DisplayName("lowestFee 取最低价；免运进度由计价器给（基数 = 单价 × 数量）")
    void lowestFeeAndProgress() {
        when(quotes.checkZone(anyLong(), anyList(), any())).thenReturn(Serviceability.of(
                quote("q", rate("jne", "reg", 9_000L, true), rate("sicepat", "reg", 12_000L, false))));
        ShippingEstimateView v = service.estimate("p1", "s1", 2, null, null, 5L);
        assertThat(v.serviceable()).isTrue();
        assertThat(v.lowestFee()).isEqualTo(9_000L);
        assertThat(v.reason()).isNull();
        assertThat(v.freeShippingProgress().threshold()).isEqualTo(300_000L);
        assertThat(v.freeShippingProgress().remaining()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("登录 USER 带 addressToken → 本人地址询价（非本人 404 由 require 保证）")
    void loggedInAddress() {
        when(addresses.require(UID, "a1")).thenReturn(address(UID, "a1", "Kebayoran", "12160"));
        when(quotes.checkAddress(eq("Kebayoran"), eq("12160"), anyList(), eq(UID)))
                .thenReturn(Serviceability.of(quote("q", rate("jne", "reg", 9_000L, true))));
        assertThat(service.estimate("p1", "s1", 1, UID, "a1", 5L).lowestFee()).isEqualTo(9_000L);
        verify(quotes, never()).checkZone(anyLong(), anyList(), any());

        when(addresses.require(UID, "x")).thenThrow(AppException.notFound("地址不存在"));
        assertThatThrownBy(() -> service.estimate("p1", "s1", 1, UID, "x", null))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("SKU 不可售 → 422 sku-not-sellable；商品下架 / 规格不属该商品 → 404；qty 越界 → 422")
    void validation() {
        assertThatThrownBy(() -> service.estimate("p1", "bad", 1, null, null, 5L))
                .extracting(e -> ((AppException) e).getType().toString())
                .asString().endsWith("sku-not-sellable");
        assertThatThrownBy(() -> service.estimate("gone", "s1", 1, null, null, 5L))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(404);
        assertThatThrownBy(() -> service.estimate("p1", "alien", 1, null, null, 5L))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(404);
        assertThatThrownBy(() -> service.estimate("p1", "s1", 0, null, null, 5L))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
        assertThatThrownBy(() -> service.estimate("p1", null, 1, null, null, 5L))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
    }
}
