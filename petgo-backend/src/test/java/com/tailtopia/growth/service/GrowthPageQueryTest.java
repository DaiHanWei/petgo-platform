package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBenefit;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthRule;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.repository.GrowthRuleRepository;
import com.tailtopia.growth.web.dto.GrowthPageResponse;
import com.tailtopia.growth.web.dto.GrowthPageResponse.RuleRow;
import com.tailtopia.growth.web.dto.MeGrowthBrief;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.4.0 ① Story 1.4 · AC1 / AC2 / AC5（L0）：等级页聚合口径与 /me 摘要。 */
class GrowthPageQueryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthLevelDefRepository levels = mock(GrowthLevelDefRepository.class);
    private final GrowthRuleRepository rules = mock(GrowthRuleRepository.class);
    private final GrowthLedgerPageQuery ledger = mock(GrowthLedgerPageQuery.class);
    private final GrowthPageQuery query = new GrowthPageQuery(jdbc, levels, rules, ledger,
            Clock.fixed(Instant.parse("2026-10-18T05:00:00Z"), ZoneOffset.UTC));

    private static final long[] TH = {0, 70, 130, 250, 500, 800, 1250, 1800, 2500, 3300, 4500, 5800, 7400, 9500,
        12400, 16500, 21500, 27200, 33800, 41300};

    private static List<GrowthLevelDef> table() {
        List<GrowthLevelDef> t = new ArrayList<>();
        for (int i = 0; i < TH.length; i++) {
            int lv = i + 1;
            GrowthTier tier = lv <= 4 ? GrowthTier.NEWBIE : lv <= 9 ? GrowthTier.BUDDY
                    : lv <= 14 ? GrowthTier.GUARDIAN : GrowthTier.STAR;
            t.add(GrowthLevelDef.of(lv, TH[i], tier));
        }
        return t;
    }

    private static List<GrowthRule> seedRules() {
        return List.of(
                GrowthRule.of(RuleCode.DAILY_LOGIN, 3, null, 1, null, true),
                GrowthRule.of(RuleCode.POST_PUBLISHED, 5, null, 3, null, true),
                GrowthRule.of(RuleCode.COMMENT_POSTED, 2, null, 5, null, true),
                GrowthRule.of(RuleCode.LIKE_GIVEN, 1, null, 5, null, true),
                GrowthRule.of(RuleCode.PLACE_CHECKIN, 5, null, 3, null, true),
                GrowthRule.of(RuleCode.LIKE_RECEIVED, 1, null, null, "PASSIVE", true),
                GrowthRule.of(RuleCode.COMMENT_RECEIVED, 3, null, null, "PASSIVE", true),
                GrowthRule.of(RuleCode.COLLECT_RECEIVED, 2, null, null, "PASSIVE", true),
                GrowthRule.of(RuleCode.PASSIVE_GROUP, 0, null, 50, null, true),
                GrowthRule.of(RuleCode.MILESTONE_S, 5, null, null, null, true),
                GrowthRule.of(RuleCode.MILESTONE_M, 15, null, null, null, true),
                GrowthRule.of(RuleCode.MILESTONE_L, 25, null, null, null, true),
                GrowthRule.of(RuleCode.OPS_CAMPAIGN, 0, null, null, null, true),
                GrowthRule.of(RuleCode.GIFT_SENT, 5, 1000, null, null, true),
                GrowthRule.of(RuleCode.GIFT_RECEIVED, 4, 1000, null, null, true),
                GrowthRule.of(RuleCode.SPEND_DIGITAL, 5, 1000, null, null, true),
                GrowthRule.of(RuleCode.SPEND_SHOP, 5, 10000, null, null, true),
                GrowthRule.of(RuleCode.LAUNCH_BACKFILL, 0, null, null, null, true),
                GrowthRule.of(RuleCode.ADMIN_ADJUST, 0, null, null, null, true));
    }

    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<String, Long> earned = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(levels.findAllByOrderByLevelAsc()).thenReturn(table());
        when(rules.findAll()).thenReturn(seedRules());
        when(jdbc.queryForList(eq(GrowthPageQuery.COUNTERS_SQL), anyMap())).thenAnswer(inv -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            counters.forEach((k, v) -> rows.add(Map.of("counter_key", k, "used", v)));
            return rows;
        });
        when(jdbc.queryForList(eq(GrowthPageQuery.TODAY_EARNED_SQL), anyMap())).thenAnswer(inv -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            earned.forEach((k, v) -> rows.add(Map.of("rule_code", k, "earned", v)));
            return rows;
        });
    }

    private void account(boolean participating, Integer level, Long total, String frame) {
        Map<String, Object> row = new HashMap<>();
        row.put("participating", participating);
        row.put("level", level);
        row.put("lifetime_total", total);
        row.put("frame_choice", frame);
        when(jdbc.queryForList(eq(GrowthPageQuery.ACCOUNT_SQL), anyMap())).thenReturn(List.of(row));
    }

    private static RuleRow rule(GrowthPageResponse p, RuleCode code) {
        return p.rules().stream().filter(r -> r.code().equals(code.name())).findFirst().orElseThrow();
    }

    @Test
    void midLevelPositionAndFrames() {
        account(true, 7, 1420L, null);

        GrowthPageResponse p = query.page(7L);

        assertThat(p.participating()).isTrue();
        assertThat(p.level()).isEqualTo(7);
        assertThat(p.tier()).isEqualTo(GrowthTier.BUDDY);
        assertThat(p.lifetimeTotal()).isEqualTo(1420L);
        assertThat(p.currentThreshold()).isEqualTo(1250L);
        assertThat(p.nextLevel()).isEqualTo(8);
        assertThat(p.nextThreshold()).isEqualTo(1800L);
        assertThat(p.levels()).hasSize(20);
        assertThat(p.frames().effective()).isEqualTo(FrameChoice.BASIC);
        assertThat(p.frames().manual()).isFalse();
        assertThat(p.frames().options()).extracting(o -> o.frame() + ":" + o.unlocked() + ":" + o.unlockLevel())
                .containsExactly("NONE:true:1", "BASIC:true:5", "UPGRADED:false:10", "ANIMATED:false:15");
    }

    @Test
    void maxLevelHasNoNext() {
        account(true, 20, 50_000L, "UPGRADED");

        GrowthPageResponse p = query.page(7L);

        assertThat(p.nextLevel()).isNull();
        assertThat(p.nextThreshold()).isNull();
        assertThat(p.frames().effective()).isEqualTo(FrameChoice.UPGRADED);
        assertThat(p.frames().manual()).isTrue();
    }

    @Test
    void noAccountRowIsLevelOneAndDoesNotTouchLedger() {
        account(true, null, null, null);

        GrowthPageResponse p = query.page(7L);

        assertThat(p.level()).isEqualTo(1);
        assertThat(p.lifetimeTotal()).isZero();
        assertThat(p.frames().effective()).isEqualTo(FrameChoice.NONE);
        assertThat(p.recentLedger()).isEmpty();
        verify(ledger, never()).recent(anyLong(), anyInt());
        verify(jdbc, never()).update(anyString(), anyMap()); // 只读
    }

    @Test
    void rulesExcludeSystemCodesInFixedOrder() {
        account(true, 1, 0L, null);

        GrowthPageResponse p = query.page(7L);

        assertThat(p.rules()).extracting(RuleRow::code).containsExactly("DAILY_LOGIN", "POST_PUBLISHED",
                "COMMENT_POSTED", "LIKE_GIVEN", "PLACE_CHECKIN", "LIKE_RECEIVED", "COMMENT_RECEIVED",
                "COLLECT_RECEIVED", "MILESTONE_S", "MILESTONE_M", "MILESTONE_L", "GIFT_SENT", "GIFT_RECEIVED",
                "SPEND_DIGITAL", "SPEND_SHOP");
    }

    @Test
    void todayCountersAndEarnedPerRuleKind() {
        account(true, 3, 200L, null);
        counters.put("LIKE_GIVEN", 3);
        counters.put("PASSIVE", 18);
        earned.put("LIKE_GIVEN", 3L);
        earned.put("LIKE_RECEIVED", 10L);
        earned.put("GIFT_SENT", 63L);

        GrowthPageResponse p = query.page(7L);

        RuleRow like = rule(p, RuleCode.LIKE_GIVEN);
        assertThat(like.todayCount()).isEqualTo(3);
        assertThat(like.todayEarned()).isEqualTo(3);
        assertThat(like.maxedToday()).isFalse();
        RuleRow received = rule(p, RuleCode.LIKE_RECEIVED);
        assertThat(received.todayCount()).isNull();
        assertThat(received.todayEarned()).isEqualTo(10);
        assertThat(received.maxedToday()).isFalse();
        RuleRow gift = rule(p, RuleCode.GIFT_SENT);
        assertThat(gift.todayCount()).isNull();
        assertThat(gift.maxedToday()).isFalse();
        assertThat(gift.todayEarned()).isEqualTo(63);
        assertThat(p.passiveGroup().dailyCap()).isEqualTo(50);
        assertThat(p.passiveGroup().todayEarned()).isEqualTo(18);
        assertThat(p.passiveGroup().maxedToday()).isFalse();
    }

    @Test
    void fiveLikesMaxesTheRuleAndFiftyMaxesPassive() {
        account(true, 3, 200L, null);
        counters.put("LIKE_GIVEN", 5);
        counters.put("PASSIVE", 50);

        GrowthPageResponse p = query.page(7L);

        assertThat(rule(p, RuleCode.LIKE_GIVEN).maxedToday()).isTrue();
        assertThat(rule(p, RuleCode.COMMENT_RECEIVED).maxedToday()).isTrue();
        assertThat(p.passiveGroup().maxedToday()).isTrue();
        assertThat(rule(p, RuleCode.SPEND_SHOP).maxedToday()).isFalse();
    }

    @Test
    void tierBenefitsHaveNoCouponAndStarIsOpenEnded() {
        account(true, 1, 0L, null);

        GrowthPageResponse p = query.page(7L);

        assertThat(p.tierBenefits()).extracting(t -> t.tier() + ":" + t.fromLevel() + "-" + t.toLevel())
                .containsExactly("NEWBIE:1-4", "BUDDY:5-9", "GUARDIAN:10-14", "STAR:15-null");
        assertThat(p.tierBenefits()).flatExtracting(t -> t.benefits())
                .allSatisfy(b -> assertThat(b.name()).doesNotContain("COUPON"));
        assertThat(p.tierBenefits().get(3).benefits()).containsExactly(GrowthBenefit.FEED_BOOST,
                GrowthBenefit.FRAME_ANIMATED, GrowthBenefit.EXCLUSIVE_GIFTS_BATCH2);
    }

    @Test
    void constantNumberOfQueries() {
        account(true, 7, 1420L, null);

        query.page(7L);

        verify(jdbc, times(1)).queryForList(eq(GrowthPageQuery.ACCOUNT_SQL), anyMap());
        verify(jdbc, times(1)).queryForList(eq(GrowthPageQuery.COUNTERS_SQL), anyMap());
        verify(jdbc, times(1)).queryForList(eq(GrowthPageQuery.TODAY_EARNED_SQL), anyMap());
        verify(levels, times(1)).findAllByOrderByLevelAsc();
        verify(rules, times(1)).findAll();
        verify(ledger, times(1)).recent(7L, 3);
    }

    @Test
    void nonParticipantGetsOnlyTheFlag() {
        account(false, 8, 900L, null);

        GrowthPageResponse p = query.page(7L);

        assertThat(p.participating()).isFalse();
        assertThat(p.level()).isNull();
        assertThat(p.rules()).isNull();
        assertThat(query.brief(7L)).isNull();
    }

    @Test
    void briefCarriesEffectiveFrameAndNext() {
        account(true, 10, 3300L, null);

        MeGrowthBrief b = query.brief(7L);

        assertThat(b).isEqualTo(new MeGrowthBrief(10, GrowthTier.GUARDIAN, FrameChoice.UPGRADED, 3300L, 4500L));
    }
}
