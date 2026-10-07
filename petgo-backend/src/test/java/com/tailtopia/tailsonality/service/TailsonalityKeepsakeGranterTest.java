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

/** V1.3.2 Story 3.2 · AC2（L0）：三种结果、异常不外抛、失败回滚到保存点（外层事务可继续）。 */
class TailsonalityKeepsakeGranterTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcTemplate jt = mock(JdbcTemplate.class);
    private final Connection con = mock(Connection.class);
    private final Savepoint sp = mock(Savepoint.class);
    private final TailsonalityKeepsakeGranter granter = new TailsonalityKeepsakeGranter(jdbc);

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
    void skuIsTailsonality() {
        assertThat(granter.sku()).isEqualTo(KeepsakeSku.TAILSONALITY);
    }

    @Test
    void oneRowUpdatedIsGrantedAndSavepointReleased() throws Exception {
        when(jdbc.update(startsWith("UPDATE tailsonality_results"), anyMap())).thenReturn(1);
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.GRANTED);
        verify(con).releaseSavepoint(sp);
        verify(con, never()).rollback(sp);
        // Story 3.3 · AC1.2：GRANTED 分支内首次自动佩戴（同一保存点）。
        verify(jdbc).update(eq(TailsonalityKeepsakeGranter.AUTO_EQUIP), anyMap());
    }

    @Test
    void zeroRowsButRowExistsIsAlreadyUnlocked() {
        when(jdbc.update(startsWith("UPDATE"), anyMap())).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT count"), anyMap(), eq(Integer.class))).thenReturn(1);
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.ALREADY_UNLOCKED);
        verify(jdbc, never()).update(eq(TailsonalityKeepsakeGranter.AUTO_EQUIP), anyMap());
    }

    @Test
    void missingRowIsRefMissing() {
        when(jdbc.update(startsWith("UPDATE"), anyMap())).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT count"), anyMap(), eq(Integer.class))).thenReturn(0);
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.REF_MISSING);
        verify(jdbc, never()).update(eq(TailsonalityKeepsakeGranter.AUTO_EQUIP), anyMap());
    }

    /** 自动佩戴 SQL 的三条规则写在语句里（真库行为见 L1 IT）：仅首次解锁、不替换、并发不撞主键。 */
    @Test
    void autoEquipSqlEncodesFirstUnlockOnlyAndNeverReplaces() {
        String sql = TailsonalityKeepsakeGranter.AUTO_EQUIP;
        assertThat(sql).contains("unlocked_at IS NOT NULL) = 1").contains("ON CONFLICT (pet_profile_id) DO NOTHING")
                .doesNotContain("DO UPDATE");
    }

    @Test
    void dbFailureRollsBackToSavepointAndIsSwallowed() throws Exception {
        when(jdbc.update(anyString(), anyMap())).thenThrow(new QueryTimeoutException("boom"));
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.REF_MISSING);
        verify(con).rollback(sp);
        verify(con, never()).releaseSavepoint(sp);
    }

    @Test
    void autocommitConnectionSkipsSavepoint() throws Exception {
        when(con.getAutoCommit()).thenReturn(true);
        when(jdbc.update(startsWith("UPDATE"), anyMap())).thenReturn(1);
        assertThat(granter.grant(5L, 9L)).isEqualTo(GrantOutcome.GRANTED);
        verify(con, never()).setSavepoint();
    }
}
