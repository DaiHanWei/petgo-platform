package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.domain.IdCardHdPurchase;
import com.tailtopia.profile.event.IdHdPawcoinUnlockedEvent;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shared.analytics.AnalyticsEventGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** L0：KTP（身份证高清图）付费漏斗服务端埋点（2026-09-25）。 */
class KtpUnlockAnalyticsListenerTest {

    private record Captured(String distinctId, String event, Map<String, Object> props) {
    }

    private final List<Captured> captured = new ArrayList<>();
    private final AnalyticsClient recording =
            (distinctId, event, props) -> captured.add(new Captured(distinctId, event, props));
    private final IdCardRepository idCards = mock(IdCardRepository.class);
    private final IdCardHdPurchaseRepository purchases = mock(IdCardHdPurchaseRepository.class);
    private final KtpUnlockAnalyticsListener listener =
            new KtpUnlockAnalyticsListener(recording, idCards, purchases);

    private static IdCard card(String type, String variant) {
        return IdCard.snapshot(UID, 1L, "Momo", "CAT", null, null, null, null, "FEMALE", null, null,
                null, null, null, null, type, variant, null, null);
    }

    private static final long UID = 42L;

    private PaymentIntentPaidEvent paid(PaymentPurpose purpose) {
        return new PaymentIntentPaidEvent(1L, "pi-1", UID, purpose, PayChannel.QRIS, 10_000L, "IDR");
    }

    private PaymentIntentFailedEvent failed(PaymentPurpose purpose, PaymentFailureCategory c) {
        return new PaymentIntentFailedEvent(1L, "pi-1", UID, purpose, PayChannel.QRIS, 10_000L, "IDR", c);
    }

    @Test
    @DisplayName("QRIS 到账 → ktp_unlock_succeeded（method=QRIS, price_idr）")
    void qrisPaid() {
        listener.onPaid(paid(PaymentPurpose.ID_HD));

        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.distinctId()).isEqualTo(AnalyticsDistinctId.of(UID));
            assertThat(c.event()).isEqualTo("ktp_unlock_succeeded");
            assertThat(c.props()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("method", "QRIS", "price_idr", 10_000L));
        });
    }

    @Test
    @DisplayName("PawCoin 扣款成功 → ktp_unlock_succeeded（method=PAWCOIN）")
    void pawcoinUnlocked() {
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L, null));

        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.event()).isEqualTo("ktp_unlock_succeeded");
            assertThat(c.props()).containsEntry("method", "PAWCOIN").containsEntry("price_idr", 10_000L);
        });
    }

    @Test
    @DisplayName("二维码超时未付 → ktp_unlock_failed（failure_reason=EXPIRED）")
    void expired() {
        listener.onFailed(failed(PaymentPurpose.ID_HD, PaymentFailureCategory.EXPIRED));

        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.event()).isEqualTo("ktp_unlock_failed");
            assertThat(c.props()).containsEntry("failure_reason", "EXPIRED")
                    .containsEntry("method", "QRIS").containsEntry("price_idr", 10_000L);
        });
    }

    @Test
    @DisplayName("网关下单失败 / 拒付 → failure_reason=GATEWAY_DECLINED")
    void gatewayDeclined() {
        listener.onFailed(failed(PaymentPurpose.ID_HD, PaymentFailureCategory.GATEWAY_DECLINED));

        assertThat(captured).singleElement()
                .satisfies(c -> assertThat(c.props()).containsEntry("failure_reason", "GATEWAY_DECLINED"));
    }

    @Test
    @DisplayName("🔴 其它用途（电商 / 充值 / AI 解锁）的到账与失败一律不进 KTP 口径")
    void ignoresOtherPurposes() {
        for (PaymentPurpose p : PaymentPurpose.values()) {
            if (p == PaymentPurpose.ID_HD) {
                continue;
            }
            listener.onPaid(paid(p));
            listener.onFailed(failed(p, PaymentFailureCategory.EXPIRED));
        }
        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("失败类别为 null → 不发（归不了类的事件会搅浑分母）")
    void nullCategoryDropped() {
        listener.onFailed(failed(PaymentPurpose.ID_HD, null));
        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("🔒 事件名与全部属性键都在服务端白名单里（漏登记 = 静默关停）")
    void everythingPassesTheGuard() {
        listener.onPaid(paid(PaymentPurpose.ID_HD));
        when(idCards.findById(7L)).thenReturn(Optional.of(card("STUDENT", "BINTANG")));
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L, 7L));
        listener.onFailed(failed(PaymentPurpose.ID_HD, PaymentFailureCategory.EXPIRED));

        AnalyticsEventGuard guard = new AnalyticsEventGuard();
        for (Captured c : captured) {
            assertThat(guard.allowsEvent(c.event())).as(c.event()).isTrue();
            assertThat(guard.filterProperties(c.props())).as("属性被白名单丢弃了")
                    .containsExactlyInAnyOrderEntriesOf(c.props());
        }
        assertThat(captured).extracting(Captured::event)
                .containsOnly("ktp_unlock_succeeded", "ktp_unlock_failed");
        assertThat(Set.copyOf(captured.stream().flatMap(c -> c.props().keySet().stream()).toList()))
                .containsExactlyInAnyOrder("method", "price_idr", "failure_reason", "card_type", "card_variant");
    }

    // ---- V1.3.4 ktp Story 1.8（AD-12）：ktp_unlock_succeeded 补 card_type / card_variant ----

    @Test
    @DisplayName("PawCoin 多卡：学生卡带 card_type=STUDENT + card_variant=BINTANG")
    void pawcoinStudentCarriesTypeAndVariant() {
        when(idCards.findById(7L)).thenReturn(Optional.of(card("STUDENT", "BINTANG")));
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L, 7L));
        assertThat(captured).singleElement().satisfies(c -> assertThat(c.props())
                .containsEntry("card_type", "STUDENT").containsEntry("card_variant", "BINTANG")
                .containsEntry("method", "PAWCOIN"));
    }

    @Test
    @DisplayName("PawCoin 非学生卡只带 card_type")
    void pawcoinNonStudentOnlyType() {
        when(idCards.findById(8L)).thenReturn(Optional.of(card("SIM", null)));
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L, 8L));
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.props()).containsEntry("card_type", "SIM");
            assertThat(c.props()).doesNotContainKey("card_variant");
        });
    }

    @Test
    @DisplayName("PawCoin 旧版单卡端点（cardId=null）不带两键、不查库")
    void pawcoinLegacyEndpointNoStyle() {
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L, null));
        assertThat(captured).singleElement().satisfies(c ->
                assertThat(c.props()).doesNotContainKeys("card_type", "card_variant"));
        org.mockito.Mockito.verifyNoInteractions(idCards);
    }

    @Test
    @DisplayName("QRIS 到账：由意图反查购买行 → 卡，带两键")
    void qrisResolvesCardByIntent() {
        IdCardHdPurchase attempt = IdCardHdPurchase.of(UID, null, 9L, PayChannel.QRIS, 1L);
        when(purchases.findFirstByPaymentIntentId(1L)).thenReturn(Optional.of(attempt));
        when(idCards.findById(9L)).thenReturn(Optional.of(card("STUDENT", "MALAM")));
        listener.onPaid(paid(PaymentPurpose.ID_HD));
        assertThat(captured).singleElement().satisfies(c -> assertThat(c.props())
                .containsEntry("card_type", "STUDENT").containsEntry("card_variant", "MALAM")
                .containsEntry("method", "QRIS"));
    }

    @Test
    @DisplayName("QRIS 无购买行：不带两键，事件照发")
    void qrisWithoutAttemptRowNoStyle() {
        when(purchases.findFirstByPaymentIntentId(1L)).thenReturn(Optional.empty());
        listener.onPaid(paid(PaymentPurpose.ID_HD));
        assertThat(captured).singleElement().satisfies(c ->
                assertThat(c.props()).doesNotContainKeys("card_type", "card_variant"));
    }

    @Test
    @DisplayName("🔴 查库抛异常：事件照发、不带卡种、监听器不抛")
    void repositoryFailureStillCaptures() {
        when(idCards.findById(7L)).thenThrow(new IllegalStateException("db down"));
        when(purchases.findFirstByPaymentIntentId(1L)).thenThrow(new IllegalStateException("db down"));
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L, 7L));
        listener.onPaid(paid(PaymentPurpose.ID_HD));
        assertThat(captured).hasSize(2).allSatisfy(c -> {
            assertThat(c.event()).isEqualTo("ktp_unlock_succeeded");
            assertThat(c.props()).doesNotContainKeys("card_type", "card_variant");
        });
    }
}
