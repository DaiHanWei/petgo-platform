package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.profile.event.IdHdPawcoinUnlockedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shared.analytics.AnalyticsEventGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：KTP（身份证高清图）付费漏斗服务端埋点（2026-09-25）。 */
class KtpUnlockAnalyticsListenerTest {

    private record Captured(String distinctId, String event, Map<String, Object> props) {
    }

    private final List<Captured> captured = new ArrayList<>();
    private final AnalyticsClient recording =
            (distinctId, event, props) -> captured.add(new Captured(distinctId, event, props));
    private final KtpUnlockAnalyticsListener listener = new KtpUnlockAnalyticsListener(recording);

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
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L));

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
        listener.onPawcoinUnlocked(new IdHdPawcoinUnlockedEvent(UID, 10_000L));
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
                .containsExactlyInAnyOrder("method", "price_idr", "failure_reason");
    }
}
