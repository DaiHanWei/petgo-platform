package com.tailtopia.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.order.dto.OrderDetailView;
import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.dto.OrderSummaryView;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderCardService;
import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.domain.StarterPackTier;
import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import com.tailtopia.starterpack.service.StarterPackSlotPetNameResolver;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.5.0 gift-pack Story 4-4 AC1 —— 第 6 源闸门默认值、卡片映射与详情（Mockito）。 */
class OrderCenterStarterPackTest {

    private static final long USER = 7L;

    private final StarterPackPurchaseRepository packs = mock(StarterPackPurchaseRepository.class);
    private final OrderCenterService service = new OrderCenterService(mock(ConsultOrderRepository.class),
            mock(AiConsultOrderRepository.class), mock(PaymentIntentRepository.class), mock(PawCoinWalletService.class),
            mock(RefundRequestRepository.class), mock(PetProfileRepository.class), mock(ShopOrderRepository.class),
            mock(ShopOrderCardService.class), mock(KeepsakePurchaseRepository.class), List.of());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "starterPacks", packs);
        ReflectionTestUtils.setField(service, "starterPackPetNames", mock(StarterPackSlotPetNameResolver.class));
    }

    private static StarterPackPurchase paid() {
        StarterPackPurchase p = StarterPackPurchase.pending("sp-tok", USER, StarterPackTier.TIER_3, 13_000, 23_000,
                PayChannel.QRIS, "STARTER_PACK:7:r", Instant.parse("2026-11-14T03:00:00Z"));
        ReflectionTestUtils.setField(p, "id", 9L);
        p.markPaid(Instant.parse("2026-11-14T03:01:00Z"), 1);
        return p;
    }

    @Test
    void oldOverloadsNeverQueryStarterPacks() {
        service.listOrders(USER, null, null, 20, true, true);
        service.listOrders(USER, null, null, 20, true);
        verify(packs, never()).findOrderCenterPageBefore(anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void gateTrueOrTypeFilterIncludesStarterPackCard() {
        when(packs.findOrderCenterPageBefore(eq(USER), any(), any(), anyLong(), any())).thenReturn(List.of(paid()));
        OrderPage page = service.listOrders(USER, null, null, 20, true, true, true);
        assertThat(page.items()).hasSize(1);
        OrderSummaryView v = page.items().get(0);
        assertThat(v.orderType()).isEqualTo("STARTER_PACK");
        assertThat(v.statusCode()).isEqualTo("PAID");
        assertThat(v.statusColor()).isEqualTo("SUCCESS");
        assertThat(v.amount()).isEqualTo(13_000L);
        assertThat(v.payChannel()).isEqualTo("QRIS");
        assertThat(v.starterPackTier()).isEqualTo(3);
        assertThat(v.displayNo()).startsWith("PAKET-");
        assertThat(service.listOrders(USER, "STARTER_PACK", null, 20, false, false).items()).hasSize(1);
    }

    @Test
    void detailCarriesStarterPackBlock() {
        when(packs.findByPublicToken("sp-tok")).thenReturn(Optional.of(paid()));
        OrderDetailView d = service.getDetail(USER, "sp-tok");
        assertThat(d.orderType()).isEqualTo("STARTER_PACK");
        assertThat(d.starterPack().tier()).isEqualTo(3);
        assertThat(d.starterPack().contents()).containsExactly("KTP_HD_8", "TAILSONALITY_1", "PET_SLOT_30D",
                "GIFT_1K_5");
        assertThat(d.purchaseToken()).isNull();
        assertThatThrownBy(() -> service.getDetail(8L, "sp-tok")).isInstanceOf(AppException.class);
    }
}
