package com.tailtopia.growth.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V1.4.0 ① Story 2.1 · AC2（L0）：升级才发、参数逐字、targetRef = {toLevel}:{TIER}。 */
class GrowthNotifyListenerTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final GrowthLevelDefRepository levels = mock(GrowthLevelDefRepository.class);
    private final GrowthNotifyListener listener = new GrowthNotifyListener(notifications, accounts, levels);

    @BeforeEach
    void setUp() {
        when(accounts.isActive(7L)).thenReturn(true);
        when(accounts.isVirtual(7L)).thenReturn(false);
        when(levels.findById(5)).thenReturn(Optional.of(GrowthLevelDef.of(5, 500, GrowthTier.BUDDY)));
        when(levels.findById(7)).thenReturn(Optional.of(GrowthLevelDef.of(7, 1250, GrowthTier.BUDDY)));
    }

    @Test
    void tierUpSendsLevelUpWithCodesOnly() {
        listener.onLevelChanged(new GrowthLevelChangedEvent(7L, 4, 5));

        verify(notifications).sendWithCopy(eq(7L), eq(NotificationType.GROWTH_LEVEL_UP), eq("GROWTH_LEVEL_UP.BUDDY"),
                aryEq(new Object[] {"5"}), eq("Naik ke Lv5!"), eq("Kamu sekarang Sahabat. Lihat keuntungan barumu."),
                eq("5:BUDDY"));
    }

    @Test
    void inTierLevelUpAlsoSends() {
        listener.onLevelChanged(new GrowthLevelChangedEvent(7L, 5, 7));

        verify(notifications).sendWithCopy(eq(7L), eq(NotificationType.GROWTH_LEVEL_UP), eq("GROWTH_LEVEL_UP.BUDDY"),
                aryEq(new Object[] {"7"}), anyString(), anyString(), eq("7:BUDDY"));
    }

    @Test
    void levelDownOrEqualSendsNothing() {
        listener.onLevelChanged(new GrowthLevelChangedEvent(7L, 10, 9));
        listener.onLevelChanged(new GrowthLevelChangedEvent(7L, 5, 5));

        verify(notifications, never()).sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(),
                anyString());
    }

    @Test
    void inactiveOrVirtualSendsNothing() {
        when(accounts.isActive(8L)).thenReturn(false);
        when(accounts.isActive(9L)).thenReturn(true);
        when(accounts.isVirtual(9L)).thenReturn(true);

        listener.onLevelChanged(new GrowthLevelChangedEvent(8L, 4, 5));
        listener.onLevelChanged(new GrowthLevelChangedEvent(9L, 4, 5));

        verify(notifications, never()).sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(),
                anyString());
    }
}
