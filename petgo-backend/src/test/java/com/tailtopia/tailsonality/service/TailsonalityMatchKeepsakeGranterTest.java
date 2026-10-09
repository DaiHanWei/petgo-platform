package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeSku;
import java.sql.Connection;
import java.sql.Savepoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 2026-10-09 配型单独解锁发放（L0）：三种结果；完整解读已解锁也算已解锁；不碰徽章。 */
class TailsonalityMatchKeepsakeGranterTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcTemplate jt = mock(JdbcTemplate.class);
    private final Connection con = mock(Connection.class);
    private final Savepoint sp = mock(Savepoint.class);
    private final TailsonalityMatchKeepsakeGranter granter = new TailsonalityMatchKeepsakeGranter(jdbc);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        when(jdbc.getJdbcTemplate()).thenReturn(jt);
        when(jt.execute(any(ConnectionCallback.class)))
                .thenAnswer(inv -> ((ConnectionCallback) inv.getArgument(0)).doInConnection(con));
        when(con.getAutoCommit()).thenReturn(false);
        when(con.setSavepoint()).thenReturn(sp);
    }

    @Test
    void skuIsTsMatch() {
        assertThat(granter.sku()).isEqualTo(KeepsakeSku.TS_MATCH);
    }

    @Test
    void oneRowUpdatedIsGrantedWithoutTouchingBadges() throws Exception {
        when(jdbc.update(startsWith("UPDATE tailsonality_results"), anyMap())).thenReturn(1);
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.GRANTED);
        verify(con).releaseSavepoint(sp);
        verify(jdbc, never()).update(eq(TailsonalityKeepsakeGranter.AUTO_EQUIP), anyMap());
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("match_unlocked_at IS NULL AND unlocked_at IS NULL"),
                anyMap());
    }

    @Test
    void zeroRowsButRowExistsIsAlreadyUnlocked() {
        when(jdbc.update(startsWith("UPDATE"), anyMap())).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT count"), anyMap(), eq(Integer.class))).thenReturn(1);
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.ALREADY_UNLOCKED);
    }

    @Test
    void missingRowIsRefMissingAndExceptionsNeverEscape() throws Exception {
        when(jdbc.update(startsWith("UPDATE"), anyMap())).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT count"), anyMap(), eq(Integer.class))).thenReturn(0);
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.REF_MISSING);

        when(jdbc.update(startsWith("UPDATE"), anyMap())).thenThrow(new QueryTimeoutException("boom"));
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.REF_MISSING);
        verify(con).rollback(sp);
    }
}
