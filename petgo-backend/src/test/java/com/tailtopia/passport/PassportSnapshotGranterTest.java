package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.passport.service.PassportSnapshotAnalyticsListener;
import com.tailtopia.passport.service.PassportSnapshotGranter;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsEventGuard;
import java.sql.Connection;
import java.sql.Savepoint;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.3.2 Story 3.4 · AC4 / AC8（L0）：发放三结局、异常回滚到保存点；埋点属性与白名单。 */
class PassportSnapshotGranterTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcTemplate jt = mock(JdbcTemplate.class);
    private final Connection con = mock(Connection.class);
    private final Savepoint sp = mock(Savepoint.class);
    private final PassportSnapshotGranter granter = new PassportSnapshotGranter(jdbc);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        when(jdbc.getJdbcTemplate()).thenReturn(jt);
        when(jt.execute(any(ConnectionCallback.class)))
                .thenAnswer(inv -> ((ConnectionCallback) inv.getArgument(0)).doInConnection(con));
        when(con.setSavepoint()).thenReturn(sp);
    }

    @Test
    void outcomes() throws Exception {
        assertThat(granter.sku()).isEqualTo(KeepsakeSku.PASSPORT_SNAP);
        when(jdbc.update(startsWith("UPDATE passport_snapshots SET paid_at"), anyMap())).thenReturn(1);
        assertThat(granter.grant(1L, 2L)).isEqualTo(GrantOutcome.GRANTED);
        when(jdbc.update(startsWith("UPDATE"), anyMap())).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT count"), anyMap(), eq(Integer.class))).thenReturn(1);
        assertThat(granter.grant(1L, 2L)).isEqualTo(GrantOutcome.ALREADY_UNLOCKED);
        when(jdbc.queryForObject(startsWith("SELECT count"), anyMap(), eq(Integer.class))).thenReturn(0);
        assertThat(granter.grant(1L, 2L)).isEqualTo(GrantOutcome.REF_MISSING);
    }

    @Test
    void failureRollsBackToSavepointAndNeverThrows() throws Exception {
        when(jdbc.update(anyString(), anyMap())).thenThrow(new QueryTimeoutException("x"));
        assertThat(granter.grant(1L, 2L)).isEqualTo(GrantOutcome.REF_MISSING);
        verify(con).rollback(sp);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void analyticsCarriesStampCountAndPriceAndIsWhitelisted() {
        AnalyticsClient analytics = mock(AnalyticsClient.class);
        var repo = mock(com.tailtopia.passport.repository.PassportSnapshotRepository.class);
        when(repo.findById(5L)).thenReturn(Optional.of(PassportVersionQueryTest.paid(1, 2, 3)));
        new PassportSnapshotAnalyticsListener(analytics, repo)
                .onUnlocked(new KeepsakeUnlockedEvent(7L, KeepsakeSku.PASSPORT_SNAP, 5L, 2000L, PayChannel.QRIS));
        ArgumentCaptor<Map> p = ArgumentCaptor.forClass(Map.class);
        verify(analytics).capture(anyString(), eq("passport_snapshot_unlocked"), p.capture());
        assertThat(p.getValue()).isEqualTo(Map.of("stamp_count", 3, "price", 2000L));
        AnalyticsEventGuard guard = new AnalyticsEventGuard();
        assertThat(guard.allowsEvent("passport_snapshot_unlocked")).isTrue();
        assertThat(guard.filterProperties(p.getValue())).isEqualTo(p.getValue());
    }
}
