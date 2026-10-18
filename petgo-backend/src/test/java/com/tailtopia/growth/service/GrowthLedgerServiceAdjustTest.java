package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.GrowthRule;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthDailyCounterRepository;
import com.tailtopia.growth.repository.GrowthLedgerRepository;
import com.tailtopia.growth.repository.GrowthRuleRepository;
import com.tailtopia.growth.service.AdminAdjustCommand.AdjustType;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** V1.4.0 ① Story 4.2 · AC5（L0）：后台单个调整 {@link GrowthLedgerService#adjust} 的判定顺序、幂等与定级收口。 */
class GrowthLedgerServiceAdjustTest {

    private static final long U = 7L;
    private static final long ADMIN = 99L;
    private static final String TOKEN = "2f1c7d1e-0000-4000-8000-000000000001";
    /** 2026-10-18 23:30 WIB = 16:30 UTC（WIB 日期与 UTC 日期相同，取 WIB 口径）。 */
    private static final Instant AT = Instant.parse("2026-10-18T16:30:00Z");

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
        account = GrowthAccount.of(U, 1420, 7);
        when(accounts.lockById(U)).thenReturn(Optional.of(account));
        campaign(true);
    }

    private void participating(boolean yes) {
        when(jdbc.queryForObject(eq(GrowthLedgerService.PARTICIPATING_SQL), anyMap(), eq(Long.class)))
                .thenReturn(yes ? 1L : 0L);
    }

    private void accountType(String type) {
        when(jdbc.queryForList(eq(GrowthLedgerService.ACCOUNT_TYPE_SQL), anyMap(), eq(String.class)))
                .thenReturn(type == null ? List.of() : List.of(type));
    }

    private void campaign(boolean enabled) {
        when(rules.findById(RuleCode.OPS_CAMPAIGN.name())).thenReturn(Optional.of(
                GrowthRule.of(RuleCode.OPS_CAMPAIGN, 0, null, null, null, enabled)));
    }

    private static AdminAdjustCommand cmd(AdjustType type, int delta) {
        return new AdminAdjustCommand(U, type, delta, " 补发漏账 ", ADMIN, TOKEN);
    }

    private SqlParameterSource insertedRow() {
        ArgumentCaptor<SqlParameterSource> p = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(GrowthLedgerService.INSERT_ADJUST_SQL), p.capture());
        return p.getValue();
    }

    private static String code(Throwable t) {
        return ((AppException) t).getMessageCode();
    }

    @Test
    void positiveAdjustWritesLedgerRowAndRoutesThroughLevelHandler() {
        AdminAdjustCommand.Result r = service.adjust(cmd(AdjustType.ADMIN_ADJUST, 50));

        assertThat(r).isEqualTo(new AdminAdjustCommand.Result(1420, 1470, 7, 7, false));
        assertThat(account.getLifetimeTotal()).isEqualTo(1470);
        SqlParameterSource row = insertedRow();
        assertThat(row.getValue("ruleCode")).isEqualTo("ADMIN_ADJUST");
        assertThat(row.getValue("delta")).isEqualTo(50);
        assertThat(row.getValue("reason")).isEqualTo("ADMIN_ADJUST");
        assertThat(row.getValue("note")).isEqualTo("补发漏账");
        assertThat(row.getValue("operatorAdminId")).isEqualTo(ADMIN);
        assertThat(row.getValue("idemKey")).isEqualTo(GrowthIdemKeys.admin(TOKEN)).isEqualTo("ADMIN:" + TOKEN);
        assertThat(row.getValue("wibDate")).isEqualTo(LocalDate.of(2026, 10, 18));
        verify(accounts).insertIfAbsent(U);
        verify(levelChange).onLifetimeChanged(account, RuleCode.ADMIN_ADJUST);
    }

    @Test
    void campaignRecordsOpsCampaignWithoutReason() {
        service.adjust(cmd(AdjustType.OPS_CAMPAIGN, 30));

        SqlParameterSource row = insertedRow();
        assertThat(row.getValue("ruleCode")).isEqualTo("OPS_CAMPAIGN");
        assertThat(row.getValue("reason")).isNull();
        verify(levelChange).onLifetimeChanged(account, RuleCode.OPS_CAMPAIGN);
    }

    @Test
    void levelUpAcrossLevelsCallsHandlerOnceAndReportsNewLevel() {
        doAnswer(inv -> {
            account.changeLevel(9, AT);
            return null;
        }).when(levelChange).onLifetimeChanged(any(), any());

        AdminAdjustCommand.Result r = service.adjust(cmd(AdjustType.OPS_CAMPAIGN, 2000));

        assertThat(r.oldLevel()).isEqualTo(7);
        assertThat(r.newLevel()).isEqualTo(9);
        verify(levelChange, times(1)).onLifetimeChanged(any(), any());
    }

    @Test
    void negativeToExactlyZeroIsAllowed() {
        AdminAdjustCommand.Result r = service.adjust(cmd(AdjustType.ADMIN_ADJUST, -1420));

        assertThat(r.newTotal()).isZero();
        assertThat(insertedRow().getValue("delta")).isEqualTo(-1420);
    }

    @Test
    void negativeBelowZeroIsRejectedNotTruncated() {
        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.ADMIN_ADJUST, -1421)))
                .isInstanceOf(AppException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.belowZero"))
                .satisfies(t -> assertThat(((AppException) t).getMessageArgs()).containsExactly(1420L));
        verify(jdbc, never()).update(eq(GrowthLedgerService.INSERT_ADJUST_SQL), any(SqlParameterSource.class));
        assertThat(account.getLifetimeTotal()).isEqualTo(1420);
        verifyNoInteractions(levelChange);
    }

    private void alreadyPosted(long ownerId) {
        when(ledger.findByIdemKey("ADMIN:" + TOKEN)).thenReturn(Optional.of(
                GrowthLedgerEntry.of(1L, ownerId, "ADMIN_ADJUST", 50, null, null, "ADMIN:" + TOKEN)));
        when(accounts.findById(U)).thenReturn(Optional.of(account));
    }

    @Test
    void sameTokenTwiceIsReplayedWithoutTouchingTotal() {
        alreadyPosted(U);

        AdminAdjustCommand.Result r = service.adjust(cmd(AdjustType.ADMIN_ADJUST, 50));

        assertThat(r).isEqualTo(new AdminAdjustCommand.Result(1420, 1420, 7, 7, true));
        assertThat(account.getLifetimeTotal()).isEqualTo(1420);
        verify(jdbc, never()).update(eq(GrowthLedgerService.INSERT_ADJUST_SQL), any(SqlParameterSource.class));
        verifyNoInteractions(levelChange);
    }

    @Test
    void replayStillSucceedsAfterUserDeactivatedOrCampaignDisabled() {
        alreadyPosted(U);
        participating(false);
        campaign(false);

        assertThat(service.adjust(cmd(AdjustType.OPS_CAMPAIGN, 50)).replayed()).isTrue();
        verify(accounts, never()).lockById(anyLong());
    }

    @Test
    void anotherUsersTokenIsRejectedNotReplayed() {
        alreadyPosted(8L);

        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.ADMIN_ADJUST, 50)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.missingToken"));
    }

    @Test
    void deletionSubmittedIsRejectedEvenWhilePending() {
        when(jdbc.queryForObject(eq(GrowthLedgerService.DELETION_SUBMITTED_SQL), anyMap(), eq(Long.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.ADMIN_ADJUST, 5)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.userInactive"));
    }

    @Test
    void officialAccountGetsNotParticipatingMessage() {
        participating(false);
        accountType("REAL");

        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.ADMIN_ADJUST, 5)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.notParticipating"));
    }

    @Test
    void virtualAccountRejectedWithItsOwnMessage() {
        participating(false);
        accountType("VIRTUAL");

        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.ADMIN_ADJUST, 5)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.virtualNotParticipating"));
        verify(accounts, never()).insertIfAbsent(anyLong());
    }

    @Test
    void deletedOrDeactivatedRejected() {
        participating(false);
        accountType("REAL");
        when(jdbc.queryForObject(eq(GrowthLedgerService.INACTIVE_SQL), anyMap(), eq(Long.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.OPS_CAMPAIGN, 5)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.userInactive"));
        verify(accounts, never()).insertIfAbsent(anyLong());
    }

    @Test
    void unknownUserIsNotFound() {
        participating(false);
        accountType(null);

        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.ADMIN_ADJUST, 5)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.user.notFound"));
    }

    @Test
    void disabledCampaignRejectsCampaignButNotManualAdjust() {
        campaign(false);

        assertThatThrownBy(() -> service.adjust(cmd(AdjustType.OPS_CAMPAIGN, 5)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.campaignDisabled"));
        assertThat(service.adjust(cmd(AdjustType.ADMIN_ADJUST, 5)).replayed()).isFalse();
    }

    @Test
    void neverTouchesDailyCounters() {
        service.adjust(cmd(AdjustType.ADMIN_ADJUST, 5));
        service.adjust(new AdminAdjustCommand(U, AdjustType.OPS_CAMPAIGN, 5, "活动", ADMIN, "t-2"));

        verifyNoInteractions(counters);
    }

    @Test
    void inputValidation() {
        assertThatThrownBy(() -> GrowthLedgerService.validateAdjust(null, 5, "x", TOKEN))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.typeRequired"));
        assertThatThrownBy(() -> GrowthLedgerService.validateAdjust(AdjustType.ADMIN_ADJUST, 0, "x", TOKEN))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.deltaZero"));
        assertThatThrownBy(() -> GrowthLedgerService.validateAdjust(AdjustType.ADMIN_ADJUST, null, "x", TOKEN))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.deltaZero"));
        assertThatThrownBy(() -> GrowthLedgerService.validateAdjust(AdjustType.ADMIN_ADJUST, 5, "  ", TOKEN))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.noteRequired"));
        assertThatThrownBy(() -> GrowthLedgerService.validateAdjust(AdjustType.ADMIN_ADJUST, 5, "x".repeat(201),
                TOKEN)).satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.noteTooLong"));
        assertThatThrownBy(() -> GrowthLedgerService.validateAdjust(AdjustType.ADMIN_ADJUST, 5, "x", " "))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.missingToken"));
        assertThatThrownBy(() -> GrowthLedgerService.validateAdjust(AdjustType.ADMIN_ADJUST, 5, "x", "t".repeat(65)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.missingToken"));
        // 恰好 200 字放行
        GrowthLedgerService.validateAdjust(AdjustType.ADMIN_ADJUST, -5, "x".repeat(200), TOKEN);
    }

    @Test
    void adjustTypeParsing() {
        assertThat(AdjustType.fromOrNull("OPS_CAMPAIGN")).isEqualTo(AdjustType.OPS_CAMPAIGN);
        assertThat(AdjustType.fromOrNull(" ADMIN_ADJUST ")).isEqualTo(AdjustType.ADMIN_ADJUST);
        assertThat(AdjustType.fromOrNull("DAILY_LOGIN")).isNull();
        assertThat(AdjustType.fromOrNull(null)).isNull();
        assertThat(AdjustType.OPS_CAMPAIGN.rule()).isEqualTo(RuleCode.OPS_CAMPAIGN);
    }
}
