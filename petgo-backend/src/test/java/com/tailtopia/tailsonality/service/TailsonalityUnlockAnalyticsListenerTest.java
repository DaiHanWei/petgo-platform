package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsEventGuard;
import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.2 · AC3（L0）：只对 TAILSONALITY 发、属性三键且全过白名单、不外抛。 */
class TailsonalityUnlockAnalyticsListenerTest {

    private final AnalyticsClient analytics = mock(AnalyticsClient.class);
    private final TailsonalityResultRepository results = mock(TailsonalityResultRepository.class);
    private final TailsonalityResultService resultService = mock(TailsonalityResultService.class);
    private final TailsonalityUnlockAnalyticsListener listener =
            new TailsonalityUnlockAnalyticsListener(analytics, results, resultService);
    private final AnalyticsEventGuard guard = new AnalyticsEventGuard();

    private KeepsakeUnlockedEvent event(KeepsakeSku sku) {
        return new KeepsakeUnlockedEvent(7L, sku, 42L, 5000L, PayChannel.QRIS);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void emitsRoleCodePriceAndIndex() {
        TailsonalityResult r = TailsonalityResult.create("tok", 3L, 7L, TailsonalityQuestionSet.CAT, Map.of(),
                new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        ReflectionTestUtils.setField(r, "id", 42L);
        when(results.findById(42L)).thenReturn(Optional.of(r));
        when(resultService.indexOf(3L, 42L)).thenReturn(2);

        listener.onUnlocked(event(KeepsakeSku.TAILSONALITY));
        ArgumentCaptor<Map> p = ArgumentCaptor.forClass(Map.class);
        verify(analytics).capture(anyString(), eq("tailsonality_unlocked"), p.capture());
        assertThat(p.getValue()).isEqualTo(Map.of("role_code", "ENTJ-H", "price", 5000L, "result_index", 2));
        assertThat(guard.filterProperties(p.getValue())).isEqualTo(p.getValue());
        assertThat(guard.allowsEvent("tailsonality_unlocked")).isTrue();
    }

    @Test
    void otherSkusAreIgnored() {
        listener.onUnlocked(event(KeepsakeSku.PASSPORT_SNAP));
        verify(analytics, never()).capture(anyString(), anyString(), anyMap());
    }

    @Test
    void failuresNeverPropagate() {
        when(results.findById(42L)).thenThrow(new IllegalStateException("db down"));
        listener.onUnlocked(event(KeepsakeSku.TAILSONALITY));
        doThrow(new RuntimeException("x")).when(analytics).capture(anyString(), anyString(), anyMap());
        org.mockito.Mockito.doReturn(Optional.empty()).when(results).findById(42L);
        listener.onUnlocked(event(KeepsakeSku.TAILSONALITY));
    }
}
