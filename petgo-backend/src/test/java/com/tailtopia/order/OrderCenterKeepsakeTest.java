package com.tailtopia.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.order.dto.OrderDetailView;
import com.tailtopia.order.dto.OrderDisplayNo;
import com.tailtopia.order.dto.OrderType;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.domain.KeepsakeTargetResolver;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderCardService;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.6 · AC1–AC3（L0，mock 仓库）：闸门、sku 过滤、卡片 / 详情映射、状态码、查看目标、404 口径。 */
class OrderCenterKeepsakeTest {

    private static final long USER = 7L;

    private final KeepsakePurchaseRepository keepsakes = mock(KeepsakePurchaseRepository.class);
    private final PetProfileRepository pets = mock(PetProfileRepository.class);
    private final KeepsakeTargetResolver tsResolver = new KeepsakeTargetResolver() {
        @Override
        public KeepsakeSku sku() {
            return KeepsakeSku.TAILSONALITY;
        }

        @Override
        public String targetKind() {
            return "TAILSONALITY_RESULT";
        }

        @Override
        public Optional<String> targetToken(long refId) {
            return refId == 42L ? Optional.of("res-token") : Optional.empty();
        }
    };
    private final OrderCenterService service = new OrderCenterService(mock(ConsultOrderRepository.class),
            mock(AiConsultOrderRepository.class), mock(PaymentIntentRepository.class), mock(PawCoinWalletService.class),
            mock(RefundRequestRepository.class), pets, mock(ShopOrderRepository.class), mock(ShopOrderCardService.class),
            keepsakes, List.of(tsResolver));

    private static KeepsakePurchase purchase(KeepsakeSku sku, KeepsakePurchaseStatus status, long refId) {
        KeepsakePurchase k = KeepsakePurchase.paidPawcoin("kp-" + refId, new KeepsakeRef(sku, refId, "ref", null, false),
                USER, 5000, Instant.parse("2026-09-30T08:00:00Z"));
        ReflectionTestUtils.setField(k, "id", 11L);
        ReflectionTestUtils.setField(k, "status", status);
        return k;
    }

    @Test
    @SuppressWarnings("unchecked")
    void gateDefaultsOffAndSkuFilterNarrows() {
        service.listOrders(USER, null, null, 20, true);
        service.listOrders(USER, null, null, 20, true, false);
        verify(keepsakes, never()).findOrderCenterPageBefore(anyLong(), anyCollection(), anyCollection(), any(), anyLong(),
                any());

        when(keepsakes.findOrderCenterPageBefore(anyLong(), anyCollection(), anyCollection(), any(), anyLong(), any()))
                .thenReturn(List.of(purchase(KeepsakeSku.TAILSONALITY, KeepsakePurchaseStatus.PAID, 42L)));
        var page = service.listOrders(USER, null, null, 20, false, true);
        ArgumentCaptor<Collection<KeepsakePurchaseStatus>> st = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<KeepsakeSku>> skus = ArgumentCaptor.forClass(Collection.class);
        verify(keepsakes).findOrderCenterPageBefore(eq(USER), st.capture(), skus.capture(), any(), anyLong(), any());
        assertThat(st.getValue()).containsExactlyInAnyOrder(KeepsakePurchaseStatus.PAID,
                KeepsakePurchaseStatus.DUPLICATE_PAID, KeepsakePurchaseStatus.ORPHAN_PAID);
        assertThat(skus.getValue()).containsExactlyInAnyOrder(KeepsakeSku.values());
        var card = page.items().get(0);
        assertThat(card.orderType()).isEqualTo("TAILSONALITY");
        assertThat(card.orderToken()).isEqualTo("kp-42");
        assertThat(card.displayNo()).isEqualTo(OrderDisplayNo.of("TSL", 11L, Instant.parse("2026-09-30T08:00:00Z")));
        assertThat(card.amount()).isEqualTo(5000L);
        assertThat(card.statusCode()).isEqualTo("PAID");
        assertThat(card.statusColor()).isEqualTo("SUCCESS");
        assertThat(card.payChannel()).isEqualTo("PAWCOIN");

        // type=BOARDING_PASS 不受闸门约束，只查该 sku。
        service.listOrders(USER, "BOARDING_PASS", null, 20, false, false);
        verify(keepsakes, org.mockito.Mockito.times(2)).findOrderCenterPageBefore(eq(USER), any(), skus.capture(), any(),
                anyLong(), any());
        assertThat(skus.getValue()).containsExactly(KeepsakeSku.BOARDING_PASS);
    }

    @Test
    void duplicateAndOrphanAreUnderReviewInfo() {
        when(keepsakes.findOrderCenterPageBefore(anyLong(), anyCollection(), anyCollection(), any(), anyLong(), any()))
                .thenReturn(List.of(purchase(KeepsakeSku.PASSPORT_SNAP, KeepsakePurchaseStatus.DUPLICATE_PAID, 1L)));
        var card = service.listOrders(USER, "PASSPORT_SNAP", null, 20, false, false).items().get(0);
        assertThat(card.statusCode()).isEqualTo("UNDER_REVIEW");
        assertThat(card.statusColor()).isEqualTo("INFO");
        assertThat(card.displayNo()).startsWith("PASPOR-");
    }

    @Test
    void detailCarriesTargetAndPetDeletedAndGuardsOwnerAndStatus() {
        KeepsakePurchase k = purchase(KeepsakeSku.TAILSONALITY, KeepsakePurchaseStatus.PAID, 42L);
        when(keepsakes.findByPublicToken("kp-42")).thenReturn(Optional.of(k));
        OrderDetailView d = service.getDetail(USER, "kp-42");
        assertThat(d.orderType()).isEqualTo(OrderType.TAILSONALITY.name());
        assertThat(d.targetKind()).isEqualTo("TAILSONALITY_RESULT");
        assertThat(d.targetToken()).isEqualTo("res-token");
        assertThat(d.petDeleted()).isTrue(); // pet_profile_id 为 null（删档置空）
        assertThat(d.paidAt()).isNotNull();
        assertThat(d.amount()).isEqualTo(5000L);

        assertThatThrownBy(() -> service.getDetail(8L, "kp-42")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        ReflectionTestUtils.setField(k, "status", KeepsakePurchaseStatus.PENDING);
        assertThatThrownBy(() -> service.getDetail(USER, "kp-42")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void enumAppendedAtTheEndAndPrefixesDistinctFromPaymentNos() {
        assertThat(List.of(OrderType.values()).stream().map(Enum::name).toList()).containsExactly(
                "VET_CONSULT", "AI_UNLOCK", "PAWCOIN_TOPUP", "ID_HD", "ECOMMERCE",
                "TAILSONALITY", "PASSPORT_SNAP", "BOARDING_PASS");
        assertThat(List.of(OrderDisplayNo.TAILSONALITY, OrderDisplayNo.PASSPORT_SNAP, OrderDisplayNo.BOARDING_PASS))
                .doesNotContain("PAYTS", "PAYPASS", "PAYBP").doesNotHaveDuplicates();
    }

    @Test
    void resolverGapStillRendersWithoutTarget() {
        KeepsakePurchase k = purchase(KeepsakeSku.BOARDING_PASS, KeepsakePurchaseStatus.ORPHAN_PAID, 5L);
        k.markStatus(KeepsakePurchaseStatus.ORPHAN_PAID);
        when(keepsakes.findByPublicToken("kp-5")).thenReturn(Optional.of(k));
        OrderDetailView d = service.getDetail(USER, "kp-5");
        assertThat(d.targetKind()).isNull();
        assertThat(d.targetToken()).isNull();
        assertThat(d.statusCode()).isEqualTo("UNDER_REVIEW");
        assertThat(PayChannel.PAWCOIN.name()).isEqualTo(d.payChannel());
    }
}
