package com.tailtopia.ops.popup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.domain.OpsPopupImpression;
import com.tailtopia.ops.popup.repository.OpsPopupImpressionRepository;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：曝光上报（V1.3.4 Story 5.1 · AC4.2）。 */
class OpsPopupImpressionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final OpsPopupRepository popups = mock(OpsPopupRepository.class);
    private final OpsPopupImpressionRepository impressions = mock(OpsPopupImpressionRepository.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final OpsPopupImpressionService service =
            new OpsPopupImpressionService(popups, impressions, accounts, Clock.fixed(NOW, ZoneOffset.UTC));

    private void userExists(long id, Instant deletedAt) {
        User u = mock(User.class);
        when(u.getDeletedAt()).thenReturn(deletedAt);
        when(accounts.findUserById(id)).thenReturn(Optional.of(u));
    }

    @Test
    void unknownOrDeletedUserIs404AndWritesNothing() {
        when(accounts.findUserById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.record("any", 7L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        userExists(8L, NOW.minusSeconds(60));
        assertThatThrownBy(() -> service.record("any", 8L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(impressions, never()).save(any());
    }

    @Test
    void unknownTokenIs404AndWritesNothing() {
        userExists(7L, null);
        when(popups.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.record("nope", 7L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(impressions, never()).save(any());
    }

    @Test
    void disabledPopupStillRecordsWithServerTime() {
        OpsPopup p = OpsPopup.create("n", NOW, null, 0, OpsPopupAudience.ALL, 7, OpsPopupFrequency.ONCE,
                "https://x/a.jpg", null, null, null, null, null, "Ok", null, OpsTargetType.NONE, null);
        ReflectionTestUtils.setField(p, "id", 9L);
        when(popups.findByPublicToken(p.getPublicToken())).thenReturn(Optional.of(p));
        userExists(7L, null);

        service.record(p.getPublicToken(), 7L);

        ArgumentCaptor<OpsPopupImpression> captor = ArgumentCaptor.forClass(OpsPopupImpression.class);
        verify(impressions).save(captor.capture());
        assertThat(captor.getValue().getPopupId()).isEqualTo(9L);
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getShownAt()).isEqualTo(NOW);
    }
}
