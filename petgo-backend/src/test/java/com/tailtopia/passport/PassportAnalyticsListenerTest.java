package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.passport.domain.PassportSource;
import com.tailtopia.passport.event.PassportIssuedEvent;
import com.tailtopia.passport.service.PassportAnalyticsListener;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.event.PlaceCheckedInEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsEventGuard;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** V1.3.2 Story 1.2 · L0：三个服务端事件的属性（AC7.2），且全部过得了双白名单。 */
class PassportAnalyticsListenerTest {

    private final AnalyticsClient analytics = mock(AnalyticsClient.class);
    private final PassportAnalyticsListener listener = new PassportAnalyticsListener(analytics);
    private final AnalyticsEventGuard guard = new AnalyticsEventGuard();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void newStampEmitsCheckinAndStamped() {
        listener.onCheckedIn(new PlaceCheckedInEvent(7L, "tok".repeat(10), PlaceType.PARK, true, 3));

        ArgumentCaptor<Map> checkin = ArgumentCaptor.forClass(Map.class);
        verify(analytics).capture(anyString(), eq("place_checkin"), checkin.capture());
        assertThat(checkin.getValue()).containsEntry("place_id", "tok".repeat(10))
                .containsEntry("place_type", "PARK").containsEntry("is_new_stamp", true);
        ArgumentCaptor<Map> stamped = ArgumentCaptor.forClass(Map.class);
        verify(analytics).capture(anyString(), eq("passport_stamped"), stamped.capture());
        assertThat(stamped.getValue()).containsEntry("place_type", "PARK").containsEntry("stamp_count", 3);
        // 全部键都在白名单里（漏登记 = 静默丢键）。
        assertThat(guard.filterProperties(checkin.getValue())).isEqualTo(checkin.getValue());
        assertThat(guard.filterProperties(stamped.getValue())).isEqualTo(stamped.getValue());
    }

    @Test
    void repeatVisitDoesNotEmitStamped() {
        listener.onCheckedIn(new PlaceCheckedInEvent(7L, "t".repeat(32), PlaceType.CAFE, false, 3));
        verify(analytics, never()).capture(anyString(), eq("passport_stamped"), anyMap());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void issuedCarriesOnlyTheSource() {
        listener.onIssued(new PassportIssuedEvent(7L, PassportSource.KTP));
        ArgumentCaptor<Map> p = ArgumentCaptor.forClass(Map.class);
        verify(analytics).capture(anyString(), eq("passport_issued"), p.capture());
        assertThat(p.getValue()).isEqualTo(Map.of("passport_source", "KTP"));
    }

    @Test
    void allThreeEventsAreWhitelisted() {
        assertThat(guard.allowsEvent("place_checkin")).isTrue();
        assertThat(guard.allowsEvent("passport_issued")).isTrue();
        assertThat(guard.allowsEvent("passport_stamped")).isTrue();
    }
}
