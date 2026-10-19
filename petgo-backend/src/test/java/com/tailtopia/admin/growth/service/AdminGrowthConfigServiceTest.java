package com.tailtopia.admin.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService.LevelsForm;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService.RuleForm;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService.SpendForm;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthRecalcJob;
import com.tailtopia.growth.domain.GrowthRule;
import com.tailtopia.growth.domain.GrowthSpendTier;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.domain.SpendTier;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.repository.GrowthRecalcJobRepository;
import com.tailtopia.growth.repository.GrowthRuleRepository;
import com.tailtopia.growth.repository.GrowthSpendTierRepository;
import com.tailtopia.growth.service.GrowthRecalcRequestedEvent;
import com.tailtopia.growth.service.GrowthRecalcService;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 ① Story 4.1（L0）：规则 / 消费换算 / 等级表的校验、写入与审计。 */
class AdminGrowthConfigServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T05:00:00Z");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthRuleRepository rules = mock(GrowthRuleRepository.class);
    private final GrowthSpendTierRepository spendTiers = mock(GrowthSpendTierRepository.class);
    private final GrowthLevelDefRepository levels = mock(GrowthLevelDefRepository.class);
    private final GrowthRecalcJobRepository jobs = mock(GrowthRecalcJobRepository.class);
    private final GrowthRecalcService recalc = mock(GrowthRecalcService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AdminGrowthConfigService service = new AdminGrowthConfigService(jdbc, rules, spendTiers, levels,
            jobs, recalc, audit, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Map<String, GrowthRule> ruleRows = new HashMap<>();

    private void rule(RuleCode code, int points, Integer unit, Integer cap, String group, boolean enabled) {
        ruleRows.put(code.name(), GrowthRule.of(code, points, unit, cap, group, enabled));
    }

    @BeforeEach
    void seed() {
        rule(RuleCode.DAILY_LOGIN, 3, null, 1, null, true);
        rule(RuleCode.COMMENT_POSTED, 2, null, 5, null, true);
        rule(RuleCode.LIKE_RECEIVED, 1, null, null, "PASSIVE", true);
        rule(RuleCode.PASSIVE_GROUP, 0, null, 50, null, true);
        rule(RuleCode.MILESTONE_S, 5, null, null, null, true);
        rule(RuleCode.GIFT_SENT, 5, 1000, null, null, true);
        rule(RuleCode.OPS_CAMPAIGN, 0, null, null, null, true);
        rule(RuleCode.SPEND_DIGITAL, 5, 1000, null, null, true);
        rule(RuleCode.SPEND_SHOP, 5, 10000, null, null, true);
        when(rules.findAll()).thenAnswer(inv -> new ArrayList<>(ruleRows.values()));
        when(rules.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(ruleRows.get((String) inv.getArgument(0))));

        List<GrowthLevelDef> t = new ArrayList<>();
        long[] th = {0, 70, 130, 250, 500};
        GrowthTier[] tier = {GrowthTier.NEWBIE, GrowthTier.NEWBIE, GrowthTier.NEWBIE, GrowthTier.NEWBIE, GrowthTier.BUDDY};
        for (int i = 0; i < th.length; i++) {
            t.add(GrowthLevelDef.of(i + 1, th[i], tier[i]));
        }
        when(levels.findAllByOrderByLevelAsc()).thenReturn(t);
        when(recalc.preview(any())).thenReturn(new GrowthRecalcService.Preview(2, 1));
        when(jobs.saveAndFlush(any())).thenAnswer(inv -> {
            GrowthRecalcJob j = inv.getArgument(0);
            ReflectionTestUtils.setField(j, "id", 77L);
            return j;
        });
    }

    private static String code(Throwable e) {
        return ((AppException) e).getMessageCode();
    }

    // ── ① 规则 ───────────────────────────────────────────────────

    @Test
    void invalidRuleFieldsAreAllCollectedAtOnce() {
        assertThatThrownBy(() -> service.updateRule("COMMENT_POSTED", new RuleForm("0", "-1", null, null, true), 1L))
                .isInstanceOfSatisfying(GrowthConfigValidationException.class, e -> {
                    assertThat(e.errors()).extracting(GrowthConfigValidationException.FieldError::field)
                            .containsExactly("points", "dailyCap");
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(audit);
    }

    @Test
    void ruleChangeWritesAndAuditsBeforeAfter() {
        service.updateRule("COMMENT_POSTED", new RuleForm("3", "6", null, null, false), 1L);

        verify(jdbc).update(contains("UPDATE growth_rules SET points"), any(MapSqlParameterSource.class));
        verify(audit).record(eq(1L), eq(AuditActions.GROWTH_RULE_UPDATED), eq("growth_config"), eq("COMMENT_POSTED"),
                argThat(s -> s.contains("points 2→3") && s.contains("daily_cap 5→6") && s.contains("enabled true→false")));
    }

    @Test
    void blankDailyCapMeansUnlimited() {
        service.updateRule("DAILY_LOGIN", new RuleForm("3", "", null, null, true), 1L);
        verify(audit).record(eq(1L), any(), any(), eq("DAILY_LOGIN"), argThat(s -> s.contains("daily_cap 1→—")));
    }

    @Test
    void unchangedRuleWritesNothing() {
        service.updateRule("DAILY_LOGIN", new RuleForm("3", "1", null, null, true), 1L);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(audit);
    }

    @Test
    void passiveRuleGroupCapWritesThePassiveGroupRow() {
        service.updateRule("LIKE_RECEIVED", new RuleForm("1", null, "60", null, true), 1L);
        verify(jdbc).update(contains("WHERE code = 'PASSIVE_GROUP'"), anyMap());
        verify(audit).record(eq(1L), any(), any(), eq("LIKE_RECEIVED"), argThat(s -> s.contains("PASSIVE_GROUP.daily_cap 50→60")));
    }

    @Test
    void spendAndSystemRulesAreNotEditable() {
        for (String c : List.of("SPEND_DIGITAL", "SPEND_SHOP", "PASSIVE_GROUP", "LAUNCH_BACKFILL", "ADMIN_ADJUST")) {
            assertThatThrownBy(() -> service.editableRule(c)).satisfies(e -> assertThat(code(e))
                    .isEqualTo("admin.err.growth.ruleNotEditable"));
        }
        assertThatThrownBy(() -> service.editableRule("NOPE")).satisfies(e -> {
            assertThat(code(e)).isEqualTo("admin.err.growth.ruleNotFound");
            assertThat(((AppException) e).getStatus().value()).isEqualTo(404);
        });
    }

    @Test
    void rulesViewFollowsTheFixedOrderAndHidesSystemRows() {
        assertThat(service.rules().rows()).extracting(AdminGrowthConfigService.RuleRow::code)
                .containsExactly("DAILY_LOGIN", "COMMENT_POSTED", "LIKE_RECEIVED", "MILESTONE_S", "OPS_CAMPAIGN",
                        "GIFT_SENT", "SPEND_DIGITAL", "SPEND_SHOP");
        assertThat(service.rules().passiveGroupCap()).isEqualTo(50);
    }

    // ── ② 消费换算 ───────────────────────────────────────────────

    private void tiers() {
        when(spendTiers.findAll()).thenReturn(List.of(GrowthSpendTier.of("SHOP_ORDER", SpendTier.TOKO, false),
                GrowthSpendTier.of("PAWCOIN_TOPUP", SpendTier.NONE, true),
                GrowthSpendTier.of("GIFT", SpendTier.GIFT, true)));
    }

    @Test
    void lockedPurposesAreIgnoredAndMissingRowsUpsert() {
        tiers();
        service.updateSpend(new SpendForm("1000", "5", "10000", "5",
                Map.of("PAWCOIN_TOPUP", "DIGITAL", "GIFT", "NONE", "TS_MATCH", "TOKO")), 1L);

        // 锁定行提交值被忽略；TS_MATCH 无行 → upsert（WHERE locked = false 双保险）。
        verify(jdbc).update(contains("ON CONFLICT (purpose) DO UPDATE"),
                argThat((Map<String, ?> m) -> "TS_MATCH".equals(m.get("p")) && "TOKO".equals(m.get("t"))));
        verify(jdbc, never()).update(contains("ON CONFLICT"), argThat((Map<String, ?> m) -> "GIFT".equals(m.get("p"))
                || "PAWCOIN_TOPUP".equals(m.get("p"))));
        verify(audit).record(eq(1L), eq(AuditActions.GROWTH_SPEND_CONFIG_UPDATED), any(), eq("spend"),
                argThat(s -> s.contains("TS_MATCH DIGITAL→TOKO")));
    }

    @Test
    void giftTierIsRejectedForEditableRows() {
        tiers();
        assertThatThrownBy(() -> service.updateSpend(new SpendForm("1000", "5", "10000", "5",
                Map.of("VET_CONSULT", "GIFT")), 1L)).satisfies(e -> assertThat(code(e))
                .isEqualTo("admin.err.growth.spendTierInvalid"));
    }

    @Test
    void ratioMustBePositiveInteger() {
        tiers();
        assertThatThrownBy(() -> service.updateSpend(new SpendForm("0", "5", "10000", "5", Map.of()), 1L))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.intMin1"));
    }

    // ── ③ 等级表 ─────────────────────────────────────────────────

    private static LevelsForm levelsForm(Map<Integer, String> th, String appendTh, String appendTier) {
        return new LevelsForm(th, appendTh, appendTier);
    }

    @Test
    void levelTableValidation() {
        assertThatThrownBy(() -> service.previewLevels(levelsForm(Map.of(1, "5"), null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.levelLv1Fixed"));
        assertThatThrownBy(() -> service.previewLevels(levelsForm(Map.of(3, "60"), null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.levelNotIncreasing"));
        assertThatThrownBy(() -> service.previewLevels(levelsForm(Map.of(), "400", "BUDDY")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.levelNotIncreasing"));
        assertThatThrownBy(() -> service.previewLevels(levelsForm(Map.of(), "800", "NEWBIE")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.levelTierNotMonotonic"));
        assertThatThrownBy(() -> service.previewLevels(levelsForm(Map.of(2, "70"), null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.levelsNoChange"));
        assertThatThrownBy(() -> service.previewLevels(levelsForm(Map.of(2, "0"), null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.intMin1"));
    }

    @Test
    void runningRecalcBlocksPreviewAndSave() {
        when(jobs.existsByStatusIn(any())).thenReturn(true);
        assertThatThrownBy(() -> service.saveLevels(levelsForm(Map.of(2, "80"), null, null), 1L))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.recalcRunning"));
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void previewDoesNotWriteAndUsesTheCandidateTable() {
        AdminGrowthConfigService.LevelsPreview p = service.previewLevels(levelsForm(Map.of(2, "80"), "800", "STAR"));

        assertThat(p.counts()).isEqualTo(new GrowthRecalcService.Preview(2, 1));
        assertThat(p.plan().candidate()).extracting(GrowthRecalcService.LevelRow::threshold)
                .containsExactly(0L, 80L, 130L, 250L, 500L, 800L);
        verify(jdbc, never()).update(anyString(), anyMap());
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void saveWritesTableInsertsJobAuditsAndPublishesAfterCommitEvent() {
        service.saveLevels(levelsForm(Map.of(2, "80"), "800", "STAR"), 1L);

        verify(jdbc).update(eq("UPDATE growth_levels SET threshold = :t WHERE level = :l"),
                eq(Map.of("t", 80L, "l", 2)));
        verify(jdbc).update(contains("INSERT INTO growth_levels"), eq(Map.of("l", 6, "t", 800L, "tier", "STAR")));
        verify(jobs).saveAndFlush(any());
        verify(audit).record(eq(1L), eq(AuditActions.GROWTH_LEVEL_TABLE_UPDATED), eq("growth_config"), eq("levels"),
                argThat(s -> s.contains("Lv2 threshold 70→80") && s.contains("+Lv6 800 STAR")));
        verify(events).publishEvent(new GrowthRecalcRequestedEvent(77L));
    }

    @Test
    void concurrentActiveJobMapsToRecalcRunning() {
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uq_growth_recalc_jobs_active"))
                .when(jobs).saveAndFlush(any());
        assertThatThrownBy(() -> service.saveLevels(levelsForm(Map.of(2, "80"), null, null), 1L))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.recalcRunning"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void retryOnlyForFailedOrStaleJobs() {
        GrowthRecalcJob done = GrowthRecalcJob.pending(1L, NOW);
        ReflectionTestUtils.setField(done, "id", 5L);
        ReflectionTestUtils.setField(done, "status", GrowthRecalcJob.Status.DONE);
        when(jobs.findTopByOrderByIdDesc()).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.retryRecalc(1L))
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.growth.recalcRunning"));

        ReflectionTestUtils.setField(done, "status", GrowthRecalcJob.Status.FAILED);
        assertThat(service.retryRecalc(1L)).isEqualTo(77L);
        verify(jobs, never()).markFailed(anyLong(), anyString(), any());
        verify(audit).record(eq(1L), eq(AuditActions.GROWTH_RECALC_RETRIED), any(), eq("77"), any());
        verify(events).publishEvent(new GrowthRecalcRequestedEvent(77L));
    }
}
