package com.tailtopia.notify.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

/**
 * L0：V1.4.0 ④ Story 1.1 · AC3 / AC5 —— {@link PushPreferenceService}（mock DAO）。
 */
@ExtendWith(MockitoExtension.class)
class PushPreferenceServiceTest {

    @Mock UserPushMuteDao dao;
    @Mock AccountQueryService accounts;

    private PushPreferenceService service() {
        return new PushPreferenceService(dao, accounts);
    }

    private void activeUser(long id) {
        User u = org.mockito.Mockito.mock(User.class);
        when(u.getDeletedAt()).thenReturn(null);
        when(accounts.findUserById(id)).thenReturn(Optional.of(u));
    }

    // ---------- isEnabled ----------

    @Test
    void orderAccountAlwaysEnabledWithoutQuery() {
        assertThat(service().isEnabled(7L, NotificationType.SHOP_ORDER_SHIPPED)).isTrue();
        assertThat(service().isEnabled(7L, NotificationType.TICKET_RESOLVED)).isTrue();
        verifyNoInteractions(dao);
    }

    @Test
    void mutedCategoryIsDisabled() {
        when(dao.isMuted(7L, PushCategory.LIKE)).thenReturn(true);
        assertThat(service().isEnabled(7L, NotificationType.CONTENT_LIKED)).isFalse();
    }

    @Test
    void noRowMeansEnabled() {
        when(dao.isMuted(7L, PushCategory.PROMO)).thenReturn(false);
        assertThat(service().isEnabled(7L, NotificationType.OPS_ANNOUNCEMENT)).isTrue();
    }

    @Test
    void queryFailureFallsBackToEnabled() {
        when(dao.isMuted(7L, PushCategory.FOLLOWER)).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(service().isEnabled(7L, NotificationType.USER_FOLLOWED)).isTrue();
    }

    // ---------- mutedAmong ----------

    @Test
    void mutedAmongEmptyOrLockedSkipsQuery() {
        assertThat(service().mutedAmong(List.of(), PushCategory.PROMO)).isEmpty();
        assertThat(service().mutedAmong(List.of(1L, 2L), PushCategory.ORDER_ACCOUNT)).isEmpty();
        verifyNoInteractions(dao);
    }

    @Test
    void mutedAmongPassesThroughDao() {
        when(dao.mutedAmong(List.of(1L, 2L, 3L), PushCategory.PROMO)).thenReturn(Set.of(2L));
        assertThat(service().mutedAmong(List.of(1L, 2L, 3L), PushCategory.PROMO)).containsExactly(2L);
    }

    // ---------- get ----------

    @Test
    void getReturnsNineItemsInOrder() {
        when(dao.mutedCategories(7L)).thenReturn(List.of("LIKE", "FOLLOWER"));
        PushSettingsView v = service().get(7L);
        assertThat(v.categories()).extracting(PushSettingsView.Item::code)
                .containsExactly(PushCategory.values());
        assertThat(v.categories()).filteredOn(i -> !i.enabled()).extracting(PushSettingsView.Item::code)
                .containsExactlyInAnyOrder(PushCategory.LIKE, PushCategory.FOLLOWER);
        assertThat(v.categories()).filteredOn(i -> !i.lockable()).extracting(PushSettingsView.Item::code)
                .containsExactly(PushCategory.ORDER_ACCOUNT);
    }

    @Test
    void getIgnoresUnknownRows() {
        when(dao.mutedCategories(7L)).thenReturn(List.of("SOMETHING_ELSE"));
        assertThat(service().get(7L).categories()).allMatch(PushSettingsView.Item::enabled);
    }

    // ---------- update ----------

    @Test
    void updateRejectsDeletedOrMissingUser() {
        User deleted = org.mockito.Mockito.mock(User.class);
        when(deleted.getDeletedAt()).thenReturn(java.time.Instant.now());
        when(accounts.findUserById(7L)).thenReturn(Optional.of(deleted));
        when(accounts.findUserById(8L)).thenReturn(Optional.empty());
        for (long id : new long[] {7L, 8L}) {
            AppException e = catchThrowableOfType(AppException.class,
                    () -> service().update(id, Map.of(PushCategory.LIKE, false)));
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        verifyNoInteractions(dao);
    }

    @Test
    void updateMutesAndUnmutes() {
        activeUser(7L);
        when(dao.mutedCategories(7L)).thenReturn(List.of("LIKE"));
        Map<PushCategory, Boolean> changes = new LinkedHashMap<>();
        changes.put(PushCategory.LIKE, false);
        changes.put(PushCategory.GIFT, true);
        PushSettingsView v = service().update(7L, changes);
        verify(dao).mute(7L, PushCategory.LIKE);
        verify(dao).unmute(7L, PushCategory.GIFT);
        assertThat(v.categories()).hasSize(9);
    }

    @Test
    void lockingOrderAccountRejectsWholeRequest() {
        activeUser(7L);
        Map<PushCategory, Boolean> changes = new LinkedHashMap<>();
        changes.put(PushCategory.LIKE, false);
        changes.put(PushCategory.ORDER_ACCOUNT, false);
        AppException e = catchThrowableOfType(AppException.class, () -> service().update(7L, changes));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getType()).isEqualTo(ErrorTypes.PUSH_CATEGORY_LOCKED);
        verify(dao, never()).mute(anyLong(), ArgumentMatchers.any());
        verify(dao, never()).unmute(anyLong(), ArgumentMatchers.any());
    }

    @Test
    void orderAccountTrueIsNoOp() {
        activeUser(7L);
        service().update(7L, Map.of(PushCategory.ORDER_ACCOUNT, true));
        verify(dao, never()).mute(anyLong(), ArgumentMatchers.any());
        verify(dao, never()).unmute(anyLong(), ArgumentMatchers.any());
    }

    @Test
    void nullValueIsValidationError() {
        activeUser(7L);
        Map<PushCategory, Boolean> changes = new HashMap<>();
        changes.put(PushCategory.LIKE, false);
        changes.put(PushCategory.GIFT, null);
        AppException e = catchThrowableOfType(AppException.class, () -> service().update(7L, changes));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getType()).isEqualTo(ErrorTypes.VALIDATION);
        verify(dao, never()).mute(anyLong(), ArgumentMatchers.any());
        verify(dao, never()).unmute(anyLong(), ArgumentMatchers.any());
    }

    @Test
    void emptyMapWritesNothingAndReturnsCurrent() {
        activeUser(7L);
        when(dao.mutedCategories(7L)).thenReturn(List.of("PROMO"));
        PushSettingsView v = service().update(7L, Map.of());
        verify(dao, never()).mute(anyLong(), ArgumentMatchers.any());
        verify(dao, never()).unmute(anyLong(), ArgumentMatchers.any());
        assertThat(v.categories()).filteredOn(i -> !i.enabled()).extracting(PushSettingsView.Item::code)
                .containsExactly(PushCategory.PROMO);
    }

    // ---------- deleteByUserId ----------

    @Test
    void deleteByUserIdDelegates() {
        service().deleteByUserId(7L);
        verify(dao).deleteByUserId(7L);
    }
}
