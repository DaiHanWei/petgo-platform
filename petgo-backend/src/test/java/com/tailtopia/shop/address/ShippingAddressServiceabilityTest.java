package com.tailtopia.shop.address;

import static com.tailtopia.shop.support.CheckoutFixtures.address;
import static com.tailtopia.shop.support.CheckoutFixtures.quote;
import static com.tailtopia.shop.support.CheckoutFixtures.rate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.address.dto.ShippingAddressView;
import com.tailtopia.shop.address.service.AddressServiceabilityService;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.logistics.ShippingQuoteService;
import com.tailtopia.shop.shipping.domain.ShippingZone;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** L0：地址簿 serviceable 三态（v1.4.2 shop Story 3.1 AC6）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShippingAddressServiceabilityTest {

    private static final long UID = 3L;

    @Mock private ShippingAddressService addresses;
    @Mock private ShippingZoneRepository zones;
    @Mock private ShippingQuoteService quotes;

    private AddressServiceabilityService service;

    @BeforeEach
    void setUp() {
        service = new AddressServiceabilityService(addresses, zones, quotes);
        when(zones.findByKecamatanAndActiveTrue("Open"))
                .thenReturn(Optional.of(Mockito.mock(ShippingZone.class)));
        when(zones.findByKecamatanAndActiveTrue("Closed")).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("未开通 → false 且不询价")
    void zoneClosedIsFalseWithoutQuote() {
        when(addresses.list(UID)).thenReturn(List.of(address(UID, "a1", "Closed", "12160")));
        List<ShippingAddressView> out = service.listWithServiceability(UID);
        assertThat(out).singleElement().extracting(ShippingAddressView::serviceable).isEqualTo(false);
        verify(quotes, never()).probe(anyString(), any());
    }

    @Test
    @DisplayName("已开通：报价 ≥ 1 家 → true，0 家 → false；同邮编只询价一次")
    void probeOncePerPostal() {
        when(addresses.list(UID)).thenReturn(List.of(address(UID, "a1", "Open", "12160"),
                address(UID, "a2", "Open", "12160"), address(UID, "a3", "Open", "99999")));
        when(quotes.probe(eq("12160"), eq(UID))).thenReturn(quote("q", rate("jne", "reg", 9_000L, true)));
        when(quotes.probe(eq("99999"), eq(UID))).thenReturn(quote("q0"));

        List<ShippingAddressView> out = service.listWithServiceability(UID);

        assertThat(out).extracting(ShippingAddressView::serviceable).containsExactly(true, true, false);
        verify(quotes, times(1)).probe("12160", UID);
        verify(quotes, times(1)).probe("99999", UID);
    }

    @Test
    @DisplayName("询价失败 → null（App 不标注）")
    void failureIsNull() {
        when(addresses.list(UID)).thenReturn(List.of(address(UID, "a1", "Open", "12160")));
        when(quotes.probe(anyString(), any())).thenThrow(new QuoteFailedException("x"));
        assertThat(service.listWithServiceability(UID)).singleElement()
                .extracting(ShippingAddressView::serviceable).isNull();
    }

    @Test
    @DisplayName("其他出口（of(a)）serviceable 为 null，行为不变")
    void otherExitsLeaveItNull() {
        assertThat(ShippingAddressView.of(address(UID, "a1", "Open", "12160")).serviceable()).isNull();
    }
}
