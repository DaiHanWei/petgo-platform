package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.GrowthRule;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthDailyCounterRepository;
import com.tailtopia.growth.repository.GrowthLedgerRepository;
import com.tailtopia.growth.repository.GrowthRuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** V1.4.0 ① Story 1.1 · AC3（L0）：入账引擎的判定顺序、额度、幂等与扣回（事务回滚语义由 L1 验）。 */
class GrowthLedgerServiceTest {

    private static final long U = 7L;
    /** 2026-10-18 12:00 WIB。 */
    private static final Instant AT = Instant.parse("2026-10-18T05:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthAccountRepository accounts = mock(GrowthAccountRepository.class);
    private final GrowthLedgerRepository ledger = mock(GrowthLedgerRepository.class);
    private final GrowthRuleRepository rules = mock(GrowthRuleRepository.class);
    private final GrowthDailyCounterRepository counters = mock(GrowthDailyCounterRepository.class);
    private final GrowthLevelChangeHandler levelChange = mock(GrowthLevelChangeHandler.class);
    private final GrowthLedgerService service = new GrowthLedgerService(jdbc, accounts, ledger, rules, counters,
            levelChange, Clock.fixed(AT, ZoneOffset.UTC));

    private GrowthAccount account;

    @BeforeEach
    void setUp() {
        participating(true);
        account = GrowthAccount.of(U, 100, 1);
        when(accounts.lockById(U)).thenReturn(Optional.of(account));
        rule(RuleCode.POST_PUBLISHED, 5, null, 3, null, true);
        rule(RuleCode.LIKE_RECEIVED, 1, null, null, "PASSIVE", true);
        rule(RuleCode.COMMENT_RECEIVED, 3, null, null, "PASSIVE", true);
        rule(RuleCode.PASSIVE_GROUP, 0, null, 50, null, true);
        rule(RuleCode.MILESTONE_S, 5, null, null, null, true);
        rule(RuleCode.GIFT_SENT, 5, 1000, null, null, true);
        when(counters.tryGrant(anyLong(), any(), anyString(), anyInt(), anyInt())).thenReturn(1);
    }

    private void participating(boolean yes) {
        when(jdbc.queryForObject(eq(GrowthLedgerService.PARTICIPATING_SQL), anyMap(), eq(Long.class)))
                .thenReturn(yes ? 1L : 0L);
    }

    private void rule(RuleCode code, int points, Integer unit, Integer cap, String group, boolean enabled) {
        when(rules.findById(code.name())).thenReturn(Optional.of(GrowthRule.of(code, points, unit, cap, group,
                enabled)));
    }

    private static AccrualCommand cmd(RuleCode rule, String key) {
        return new AccrualCommand(U, rule, null, key, "CONTENT_POST", "5", List.of(3L), AT);
    }

    private SqlParameterSource insertedRow() {
        ArgumentCaptor<SqlParameterSource> p = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(GrowthLedgerService.INSERT_LEDGER_SQL), p.capture());
        return p.getValue();
    }

    private void verifyNothingWritten() {
        verify(jdbc, never()).update(eq(GrowthLedgerService.INSERT_LEDGER_SQL), any(SqlParameterSource.class));
    }

    @Test
    void nonParticipantReturnsWithoutCreatingAccount() {
        participating(false);

        assertThat(service.accrue(cmd(RuleCode.POST_PUBLISHED, "POST:5"))).isFalse();

        verify(accounts, never()).insertIfAbsent(anyLong());
        verifyNothingWritten();
    }

    @Test
    void disabledRuleReturns() {
        rule(RuleCode.POST_PUBLISHED, 5, null, 3, null, false);

        assertThat(service.accrue(cmd(RuleCode.POST_PUBLISHED, "POST:5"))).isFalse();

        verify(accounts, never()).insertIfAbsent(anyLong());
        verifyNothingWritten();
    }

    @Test
    void activeRuleClaimsOneCountAndWritesLedger() {
        assertThat(service.accrue(cmd(RuleCode.POST_PUBLISHED, "POST:5"))).isTrue();

        verify(accounts).insertIfAbsent(U);
        verify(counters).insertIfAbsent(U, DAY, "POST_PUBLISHED");
        verify(counters).tryGrant(U, DAY, "POST_PUBLISHED", 1, 3);
        SqlParameterSource row = insertedRow();
        assertThat(row.getValue("delta")).isEqualTo(5);
        assertThat(row.getValue("ruleCode")).isEqualTo("POST_PUBLISHED");
        assertThat(row.getValue("idemKey")).isEqualTo("POST:5");
        assertThat(row.getValue("petIds")).isEqualTo("{3}");
        assertThat(row.getValue("wibDate")).isEqualTo(DAY);
        assertThat(row.getValue("reversesId")).isNull();
        assertThat(account.getLifetimeTotal()).isEqualTo(105);
        // Story 1.2：累计变化后经单一连锁出口重算等级，source = 本次规则。
        verify(levelChange).onLifetimeChanged(account, RuleCode.POST_PUBLISHED);
    }

    @Test
    void idempotentReplayDoesNotClaimQuota() {
        when(ledger.existsByIdemKey("POST:5")).thenReturn(true);

        assertThat(service.accrue(cmd(RuleCode.POST_PUBLISHED, "POST:5"))).isFalse();

        verify(counters, never()).tryGrant(anyLong(), any(), anyString(), anyInt(), anyInt());
        verifyNothingWritten();
        assertThat(account.getLifetimeTotal()).isEqualTo(100);
        verify(levelChange, never()).onLifetimeChanged(any(), any());
    }

    @Test
    void activeRuleOverDailyCountIsNotWritten() {
        when(counters.tryGrant(U, DAY, "POST_PUBLISHED", 1, 3)).thenReturn(0);

        assertThat(service.accrue(cmd(RuleCode.POST_PUBLISHED, "POST:6"))).isFalse();

        verifyNothingWritten();
        assertThat(account.getLifetimeTotal()).isEqualTo(100);
    }

    @Test
    void passiveIsTruncatedToExactlyTheGroupCap() {
        when(counters.used(U, DAY, "PASSIVE")).thenReturn(48);

        assertThat(service.accrue(cmd(RuleCode.COMMENT_RECEIVED, "COMMENT_RECEIVED:1"))).isTrue();

        verify(counters).tryGrant(U, DAY, "PASSIVE", 2, 50);
        assertThat(insertedRow().getValue("delta")).isEqualTo(2);
        assertThat(account.getLifetimeTotal()).isEqualTo(102);
    }

    @Test
    void passiveFullIsNotWritten() {
        when(counters.used(U, DAY, "PASSIVE")).thenReturn(50);

        assertThat(service.accrue(cmd(RuleCode.LIKE_RECEIVED, "LIKE_RECEIVED:3:5"))).isFalse();

        verify(counters, never()).tryGrant(anyLong(), any(), anyString(), anyInt(), anyInt());
        verifyNothingWritten();
    }

    @Test
    void uncappedRuleDoesNotTouchCounters() {
        AccrualCommand gift = new AccrualCommand(U, RuleCode.GIFT_SENT, 63, "GIFT_SENT:4", "GIFT_ORDER", "4",
                List.of(3L), AT);

        assertThat(service.accrue(gift)).isTrue();

        verify(counters, never()).insertIfAbsent(anyLong(), any(), anyString());
        assertThat(insertedRow().getValue("delta")).isEqualTo(63);
    }

    @Test
    void milestoneSixthTimeIsNotWritten() {
        when(ledger.countPositiveByRef(eq(U), any(), eq("C-S1"))).thenReturn(5L);
        AccrualCommand m = new AccrualCommand(U, RuleCode.MILESTONE_S, null, "MILESTONE:7:C-S1:9", "MILESTONE",
                "C-S1", List.of(9L), AT);

        assertThat(service.accrue(m)).isFalse();

        verifyNothingWritten();
    }

    @Test
    void milestoneFifthTimeIsWritten() {
        when(ledger.countPositiveByRef(eq(U), any(), eq("C-S1"))).thenReturn(4L);
        AccrualCommand m = new AccrualCommand(U, RuleCode.MILESTONE_S, null, "MILESTONE:7:C-S1:9", "MILESTONE",
                "C-S1", List.of(9L), AT);

        assertThat(service.accrue(m)).isTrue();
    }

    @Test
    void wibDayBoundaryDecidesTheQuotaDay() {
        AccrualCommand lateUtc = new AccrualCommand(U, RuleCode.POST_PUBLISHED, null, "POST:8", null, null, null,
                Instant.parse("2026-10-18T17:00:00Z")); // = 10-19 00:00 WIB

        service.accrue(lateUtc);

        verify(counters).tryGrant(U, LocalDate.of(2026, 10, 19), "POST_PUBLISHED", 1, 3);
    }

    @Test
    void uniqueViolationPropagatesSoTheTransactionRollsBack() {
        when(jdbc.update(eq(GrowthLedgerService.INSERT_LEDGER_SQL), any(SqlParameterSource.class)))
                .thenThrow(new DuplicateKeyException("uq"));

        assertThatThrownBy(() -> service.accrue(cmd(RuleCode.POST_PUBLISHED, "POST:5")))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(account.getLifetimeTotal()).isEqualTo(100);
    }

    // ---------- reverse ----------

    private GrowthLedgerEntry original(int delta) {
        GrowthLedgerEntry e = GrowthLedgerEntry.of(42L, U, "LIKE_RECEIVED", delta, "CONTENT_POST", "5",
                "LIKE_RECEIVED:3:5");
        when(ledger.findByIdemKey("LIKE_RECEIVED:3:5")).thenReturn(Optional.of(e));
        return e;
    }

    private static ReverseCommand unlike() {
        return new ReverseCommand("LIKE_RECEIVED:3:5", ReverseReason.UNLIKE, "UNLIKE", null);
    }

    @Test
    void reverseWithoutOriginalDoesNothing() {
        when(ledger.findByIdemKey(anyString())).thenReturn(Optional.empty());

        assertThat(service.reverse(unlike())).isFalse();

        verify(accounts, never()).lockById(anyLong());
        verifyNothingWritten();
    }

    @Test
    void reverseAppendsNegativeRowPointingAtOriginal() {
        original(1);

        assertThat(service.reverse(unlike())).isTrue();

        SqlParameterSource row = insertedRow();
        assertThat(row.getValue("delta")).isEqualTo(-1);
        assertThat(row.getValue("reversesId")).isEqualTo(42L);
        assertThat(row.getValue("reason")).isEqualTo("UNLIKE");
        assertThat(row.getValue("ruleCode")).isEqualTo("LIKE_RECEIVED");
        assertThat(row.getValue("idemKey")).isEqualTo("REV:42:UNLIKE");
        assertThat(account.getLifetimeTotal()).isEqualTo(99);
        // Story 1.2：扣回同样经 handler，source = 原流水规则代码。
        verify(levelChange).onLifetimeChanged(account, RuleCode.LIKE_RECEIVED);
        // 不返还额度、不改原流水：counters 未被触碰，原流水只读实体。
        verify(counters, never()).tryGrant(anyLong(), any(), anyString(), anyInt(), anyInt());
    }

    @Test
    void reverseAlreadyFullyReversedDoesNothing() {
        original(3);
        when(ledger.sumReversed(42L)).thenReturn(3L);

        assertThat(service.reverse(unlike())).isFalse();

        verifyNothingWritten();
    }

    @Test
    void reverseReplayIsIdempotent() {
        original(3);
        when(ledger.existsByIdemKey("REV:42:UNLIKE")).thenReturn(true);

        assertThat(service.reverse(unlike())).isFalse();

        verifyNothingWritten();
    }

    @Test
    void reverseIsTruncatedSoLifetimeNeverGoesNegative() {
        original(5);
        account = GrowthAccount.of(U, 2, 1);
        when(accounts.lockById(U)).thenReturn(Optional.of(account));

        assertThat(service.reverse(unlike())).isTrue();

        assertThat(insertedRow().getValue("delta")).isEqualTo(-2);
        assertThat(account.getLifetimeTotal()).isZero();
    }

    @Test
    void partialReverseNeverExceedsWhatRemains() {
        original(10);
        when(ledger.sumReversed(42L)).thenReturn(7L);

        assertThat(service.reverse(new ReverseCommand("LIKE_RECEIVED:3:5", ReverseReason.REFUND, "REFUND:r1", 5)))
                .isTrue();

        assertThat(insertedRow().getValue("delta")).isEqualTo(-3);
    }

    @Test
    void pointsForAmountReadsTheRuleRatio() {
        assertThat(service.pointsForAmount(RuleCode.GIFT_SENT, 12_500)).isEqualTo(63);
        assertThat(service.pointsForAmount(RuleCode.POST_PUBLISHED, 12_500)).isZero(); // 非比例规则
    }

    @Test
    void pgArrayFormatting() {
        assertThat(GrowthLedgerService.toPgArray(List.of(1L, 2L))).isEqualTo("{1,2}");
        assertThat(GrowthLedgerService.toPgArray(List.of())).isNull();
        assertThat(GrowthLedgerService.toPgArray(null)).isNull();
    }
}
