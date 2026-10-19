package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：V1.4.0 multi-pet Story 2.2 · code review #1 / #2 —— {@link PetSlotGranter} 在 JDBC 保存点内发放：成功释放保存点、
 * 失败回滚到保存点并原样抛（外层到账事务不受影响）；保存点内先取账号锁；RENT 新建 / RENEW 延长或改新建；
 * 会新增名额且超 5 → OVER_CAP 不写任何行；纯延长不受封顶影响。
 */
class PetSlotGranterTest {

    private static final long USER = 7L;
    private static final Instant PAID_AT = Instant.parse("2026-10-28T03:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(PAID_AT, ZoneOffset.UTC);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcTemplate plain = mock(JdbcTemplate.class);
    private final Connection con = mock(Connection.class);
    private final Savepoint sp = mock(Savepoint.class);
    private final PetSlotTokenGenerator tokens = mock(PetSlotTokenGenerator.class);
    private final AtomicLong ids = new AtomicLong(900);
    private int levelSlots;
    private PetSlotGranter granter;

    @BeforeEach
    void setUp() throws Exception {
        granter = new PetSlotGranter(jdbc, uid -> levelSlots, tokens);
        when(jdbc.getJdbcTemplate()).thenReturn(plain);
        when(con.getAutoCommit()).thenReturn(false);
        when(con.setSavepoint()).thenReturn(sp);
        when(plain.execute(any(ConnectionCallback.class))).thenAnswer(inv ->
                ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(con));
        when(tokens.generate()).thenReturn("tok");
        when(jdbc.queryForObject(eq(PetSlotGranter.INSERT_SQL), any(SqlParameterSource.class), eq(Long.class)))
                .thenAnswer(inv -> ids.incrementAndGet());
        live(0);
    }

    private void live(long n) {
        when(jdbc.queryForObject(eq(PetSlotGranter.COUNT_LIVE_SQL), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(n);
    }

    private PetSlotPurchase row(PetSlotPurchaseKind kind, int qty, List<Long> rentalIds) {
        PetSlotPurchase p = PetSlotPurchase.pending("pt", USER, kind, qty, 5000, 5000L * qty, PayChannel.QRIS, 99L,
                "k:99", rentalIds, PAID_AT);
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }

    /** 所选名额的库内现状：id → (status, expires_at)。 */
    private void selected(Object[]... rows) {
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(2);
            for (Object[] r : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("id")).thenReturn((Long) r[0]);
                when(rs.getString("status")).thenReturn((String) r[1]);
                when(rs.getObject("expires_at", OffsetDateTime.class)).thenReturn((OffsetDateTime) r[2]);
                h.processRow(rs);
            }
            return null;
        }).when(jdbc).query(eq(PetSlotGranter.SELECT_SELECTED_SQL), any(SqlParameterSource.class),
                any(RowCallbackHandler.class));
    }

    @Test
    void rentInsertsRowsInsideSavepointAfterTakingAccountLock() throws Exception {
        PetSlotGranter.Result r = granter.grant(row(PetSlotPurchaseKind.RENT, 2, null), PAID_AT);
        assertThat(r.outcome()).isEqualTo(PetSlotGranter.Outcome.GRANTED);
        assertThat(r.trigger()).isEqualTo(PetSlotTrigger.RENTAL_ADDED);
        assertThat(r.rentalIds()).containsExactly(901L, 902L);
        InOrder o = inOrder(con, jdbc);
        o.verify(con).setSavepoint();
        o.verify(jdbc).query(eq(PetSlotGranter.LOCK_SQL), argThat((SqlParameterSource p) ->
                Integer.valueOf(PetSlotLock.PET_SLOT_LOCK_NS).equals(p.getValue("ns"))
                        && Integer.valueOf(PetSlotLock.lockKey(USER)).equals(p.getValue("uid"))),
                any(RowCallbackHandler.class));
        o.verify(jdbc, times(2)).queryForObject(eq(PetSlotGranter.INSERT_SQL), argThat((SqlParameterSource p) ->
                AT.equals(p.getValue("startsAt")) && AT.plusDays(30).equals(p.getValue("expiresAt"))
                        && Long.valueOf(1L).equals(p.getValue("purchaseId"))), eq(Long.class));
        o.verify(con).releaseSavepoint(sp);
        verify(con, never()).rollback(any(Savepoint.class));
    }

    @Test
    void rentThatWouldExceedFiveWritesNothing() {
        levelSlots = 1;
        live(2); // 1 + 1 + 2 + 2 = 6 > 5
        PetSlotGranter.Result r = granter.grant(row(PetSlotPurchaseKind.RENT, 2, null), PAID_AT);
        assertThat(r.outcome()).isEqualTo(PetSlotGranter.Outcome.OVER_CAP);
        assertThat(r.granted()).isFalse();
        assertThat(r.rentalIds()).isEmpty();
        verify(jdbc, never()).queryForObject(eq(PetSlotGranter.INSERT_SQL), any(SqlParameterSource.class), eq(Long.class));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void rentExactlyToFiveIsGranted() {
        live(2); // 1 + 0 + 2 + 2 = 5
        assertThat(granter.grant(row(PetSlotPurchaseKind.RENT, 2, null), PAID_AT).granted()).isTrue();
    }

    @Test
    void renewExtendsLiveAndReissuesExpiredAsNewRent() {
        live(1);
        selected(new Object[] {11L, "ACTIVE", AT.plusDays(3)}, new Object[] {12L, "EXPIRED", AT.minusMinutes(10)});
        PetSlotGranter.Result r = granter.grant(row(PetSlotPurchaseKind.RENEW, 2, List.of(11L, 12L)), PAID_AT);
        assertThat(r.outcome()).isEqualTo(PetSlotGranter.Outcome.GRANTED);
        assertThat(r.trigger()).isEqualTo(PetSlotTrigger.RENEWED);
        assertThat(r.rentalIds()).containsExactly(11L, 901L);
        verify(jdbc).update(eq(PetSlotGranter.EXTEND_SQL), argThat((SqlParameterSource p) ->
                Long.valueOf(11L).equals(p.getValue("id")) && Integer.valueOf(30).equals(p.getValue("days"))));
        verify(jdbc, times(1)).queryForObject(eq(PetSlotGranter.INSERT_SQL), any(SqlParameterSource.class),
                eq(Long.class));
    }

    /** Story 4.3 code review：续租到账时所选名额已过期而改新建的那一行，来源仍是 RENT（只有 ADMIN_GRANT 行写 ADMIN_GRANT）。 */
    @Test
    void renewConversionRowKeepsSourceRentAndAdminGrantRowGetsAdminGrant() {
        selected(new Object[] {12L, "EXPIRED", AT.minusMinutes(10)});
        granter.grant(row(PetSlotPurchaseKind.RENEW, 1, List.of(12L)), PAID_AT);
        verify(jdbc).queryForObject(eq(PetSlotGranter.INSERT_SQL),
                argThat((SqlParameterSource p) -> "RENT".equals(p.getValue("source"))), eq(Long.class));

        PetSlotPurchase admin = PetSlotPurchase.adminGrant("pa", USER, 7, "ADMIN_GRANT:t", 900L, "r", PAID_AT);
        ReflectionTestUtils.setField(admin, "id", 2L);
        granter.grant(admin, PAID_AT);
        verify(jdbc).queryForObject(eq(PetSlotGranter.INSERT_SQL), argThat((SqlParameterSource p) ->
                "ADMIN_GRANT".equals(p.getValue("source")) && AT.plusDays(7).equals(p.getValue("expiresAt"))),
                eq(Long.class));
    }

    @Test
    void renewActiveButPastExpiryIsTreatedAsExpired() {
        selected(new Object[] {11L, "ACTIVE", AT.minusSeconds(1)}); // 扫描尚未置 EXPIRED
        PetSlotGranter.Result r = granter.grant(row(PetSlotPurchaseKind.RENEW, 1, List.of(11L)), PAID_AT);
        assertThat(r.rentalIds()).containsExactly(901L);
        verify(jdbc, never()).update(eq(PetSlotGranter.EXTEND_SQL), any(SqlParameterSource.class));
    }

    @Test
    void pureExtensionIsNeverCapped() {
        levelSlots = 2;
        live(4); // 已超（租用期间升级）：纯续不新增名额，照常延长
        selected(new Object[] {11L, "ACTIVE", AT.plusDays(1)}, new Object[] {12L, "ACTIVE", AT.plusDays(2)});
        assertThat(granter.grant(row(PetSlotPurchaseKind.RENEW, 2, List.of(11L, 12L)), PAID_AT).granted()).isTrue();
        verify(jdbc, times(2)).update(eq(PetSlotGranter.EXTEND_SQL), any(SqlParameterSource.class));
    }

    @Test
    void renewConversionOverCapIsOverCap() {
        live(4); // 1 + 0 + 4 + 1 = 6
        selected(new Object[] {11L, "EXPIRED", AT.minusDays(1)});
        PetSlotGranter.Result r = granter.grant(row(PetSlotPurchaseKind.RENEW, 1, List.of(11L)), PAID_AT);
        assertThat(r.outcome()).isEqualTo(PetSlotGranter.Outcome.OVER_CAP);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void failureRollsBackToSavepointAndPropagates() throws Exception {
        when(jdbc.queryForObject(eq(PetSlotGranter.INSERT_SQL), any(SqlParameterSource.class), eq(Long.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));
        assertThatThrownBy(() -> granter.grant(row(PetSlotPurchaseKind.RENT, 1, null), PAID_AT))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(con).rollback(sp);
        verify(con, never()).releaseSavepoint(any());
    }

    @Test
    void levelReadFailurePropagatesBeforeAnyWrite() {
        granter = new PetSlotGranter(jdbc, uid -> {
            throw new IllegalStateException("growth down");
        }, tokens);
        assertThatThrownBy(() -> granter.grant(row(PetSlotPurchaseKind.RENT, 1, null), PAID_AT))
                .isInstanceOf(IllegalStateException.class);
        verify(plain, never()).execute(any(ConnectionCallback.class));
    }
}
