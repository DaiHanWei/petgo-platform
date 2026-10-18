package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBenefit;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthRule;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.repository.GrowthRuleRepository;
import com.tailtopia.growth.web.dto.GrowthPageResponse;
import com.tailtopia.growth.web.dto.GrowthPageResponse.FrameOption;
import com.tailtopia.growth.web.dto.GrowthPageResponse.Frames;
import com.tailtopia.growth.web.dto.GrowthPageResponse.LevelRow;
import com.tailtopia.growth.web.dto.GrowthPageResponse.PassiveGroup;
import com.tailtopia.growth.web.dto.GrowthPageResponse.RuleRow;
import com.tailtopia.growth.web.dto.GrowthPageResponse.TierBenefits;
import com.tailtopia.growth.web.dto.MeGrowthBrief;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 等级页首屏聚合（V1.4.0 ① Story 1.4 · AD-16 / V-2）与 {@code /me} 成长值摘要。只读、不写库（无账户行也不建）。
 *
 * <p>查询次数为常数：账户 1、等级表 1、规则 1、当日计数 1、当日流水汇总 1、最近 3 条 1（+ 帖子类型补充 ≤ 1）。
 * 「今日已得」= 当天（WIB）该规则正数流水之和（扣回不减）；「今日已满」读 {@code growth_daily_counters}。
 */
@Service
@Transactional(readOnly = true)
public class GrowthPageQuery {

    static final String ACCOUNT_SQL = "SELECT " + GrowthParticipation.ACTIVE_REGULAR_USER + " AS participating, "
            + "a.lifetime_total, a.level, a.frame_choice "
            + "FROM users u LEFT JOIN growth_accounts a ON a.user_id = u.id WHERE u.id = :u";

    static final String COUNTERS_SQL =
            "SELECT counter_key, used FROM growth_daily_counters WHERE user_id = :u AND wib_date = :d";

    static final String TODAY_EARNED_SQL = "SELECT rule_code, COALESCE(SUM(delta) FILTER (WHERE delta > 0), 0) AS earned "
            + "FROM growth_ledger WHERE user_id = :u AND wib_date = :d GROUP BY rule_code";

    static final int RECENT = 3;

    /** 等级页规则顺序（UI 稿 B2 自上而下）；系统代码与伪行不在其中。 */
    static final List<RuleCode> RULE_ORDER = List.of(RuleCode.DAILY_LOGIN, RuleCode.POST_PUBLISHED,
            RuleCode.COMMENT_POSTED, RuleCode.LIKE_GIVEN, RuleCode.PLACE_CHECKIN, RuleCode.LIKE_RECEIVED,
            RuleCode.COMMENT_RECEIVED, RuleCode.COLLECT_RECEIVED, RuleCode.MILESTONE_S, RuleCode.MILESTONE_M,
            RuleCode.MILESTONE_L, RuleCode.GIFT_SENT, RuleCode.GIFT_RECEIVED, RuleCode.SPEND_DIGITAL,
            RuleCode.SPEND_SHOP);

    /** 各段位权益（PRD AC20：不含优惠券）。 */
    static final Map<GrowthTier, List<GrowthBenefit>> BENEFITS = Map.of(
            GrowthTier.NEWBIE, List.of(GrowthBenefit.LEVEL_BADGE),
            GrowthTier.BUDDY, List.of(GrowthBenefit.PET_SLOT_PLUS_ONE, GrowthBenefit.FRAME_BASIC,
                    GrowthBenefit.EXCLUSIVE_GIFTS_BATCH1),
            GrowthTier.GUARDIAN, List.of(GrowthBenefit.PET_SLOT_PLUS_ONE, GrowthBenefit.FRAME_UPGRADED),
            GrowthTier.STAR, List.of(GrowthBenefit.FEED_BOOST, GrowthBenefit.FRAME_ANIMATED,
                    GrowthBenefit.EXCLUSIVE_GIFTS_BATCH2));

    /** 头像框档 → 解锁段位（AD-10）。 */
    private static final Map<FrameChoice, GrowthTier> FRAME_TIER = Map.of(
            FrameChoice.BASIC, GrowthTier.BUDDY,
            FrameChoice.UPGRADED, GrowthTier.GUARDIAN,
            FrameChoice.ANIMATED, GrowthTier.STAR);

    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthLevelDefRepository levels;
    private final GrowthRuleRepository rules;
    private final GrowthLedgerPageQuery ledger;
    private final Clock clock;

    @Autowired
    public GrowthPageQuery(NamedParameterJdbcTemplate jdbc, GrowthLevelDefRepository levels,
            GrowthRuleRepository rules, GrowthLedgerPageQuery ledger) {
        this(jdbc, levels, rules, ledger, Clock.systemUTC());
    }

    GrowthPageQuery(NamedParameterJdbcTemplate jdbc, GrowthLevelDefRepository levels, GrowthRuleRepository rules,
            GrowthLedgerPageQuery ledger, Clock clock) {
        this.jdbc = jdbc;
        this.levels = levels;
        this.rules = rules;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** 等级页首屏。 */
    public GrowthPageResponse page(long userId) {
        Account a = account(userId);
        if (a == null) {
            return GrowthPageResponse.notParticipating();
        }
        List<GrowthLevelDef> table = levels.findAllByOrderByLevelAsc();
        LevelPosition pos = position(table, a.level);
        LocalDate today = clock.instant().atZone(ScheduleWindow.WIB).toLocalDate();
        Map<String, Integer> counters = counters(userId, today);
        Map<String, Long> earned = todayEarned(userId, today);
        Map<String, GrowthRule> ruleByCode = new HashMap<>();
        for (GrowthRule r : rules.findAll()) {
            ruleByCode.put(r.getCode(), r);
        }
        GrowthRule passiveCfg = ruleByCode.get(RuleCode.PASSIVE_GROUP.name());
        Integer passiveCap = passiveCfg == null ? null : passiveCfg.getDailyCap();
        int passiveUsed = counters.getOrDefault(RuleCode.PASSIVE_CAP_GROUP, 0);
        boolean passiveMaxed = passiveCap != null && passiveUsed >= passiveCap;

        List<RuleRow> ruleRows = new ArrayList<>();
        for (RuleCode code : RULE_ORDER) {
            GrowthRule r = ruleByCode.get(code.name());
            if (r == null || !r.isEnabled()) {
                continue;
            }
            Integer todayCount = null;
            boolean maxed = false;
            if (r.isPassive()) {
                maxed = passiveMaxed;
            } else if (r.getDailyCap() != null) {
                todayCount = counters.getOrDefault(r.getCode(), 0);
                maxed = todayCount >= r.getDailyCap();
            }
            ruleRows.add(new RuleRow(r.getCode(), r.getPoints(), r.getUnitAmount(), r.getDailyCap(),
                    r.getCapGroup(), todayCount, earned.getOrDefault(r.getCode(), 0L), maxed));
        }

        return new GrowthPageResponse(true, a.level, pos.tier, a.lifetimeTotal, pos.currentThreshold,
                pos.nextLevel, pos.nextThreshold,
                table.stream().map(d -> new LevelRow(d.getLevel(), d.getThreshold(), d.getTier())).toList(),
                frames(table, pos.tier, a.frameChoice), ruleRows,
                new PassiveGroup(passiveCap, passiveUsed, passiveMaxed), tierBenefits(table),
                a.hasAccount ? ledger.recent(userId, RECENT) : List.of());
    }

    /** 是否参与成长值（明细接口对不参与者返回空列表）。 */
    public boolean participating(long userId) {
        return account(userId) != null;
    }

    /** {@code /me} 摘要；不参与者返回 null。 */
    public MeGrowthBrief brief(long userId) {
        Account a = account(userId);
        if (a == null) {
            return null;
        }
        LevelPosition pos = position(levels.findAllByOrderByLevelAsc(), a.level);
        return new MeGrowthBrief(a.level, pos.tier, GrowthFrameResolver.effectiveFrame(pos.tier, a.frameChoice),
                a.lifetimeTotal, pos.nextThreshold);
    }

    /** 参与者的账户快照（无账户行 = Lv1 / 0 / 自动）；不参与 / 查无此人 → null。 */
    private Account account(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(ACCOUNT_SQL, Map.of("u", userId));
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("participating"))) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        if (r.get("level") == null) {
            return new Account(1, 0L, null, false);
        }
        String frame = (String) r.get("frame_choice");
        return new Account(((Number) r.get("level")).intValue(), ((Number) r.get("lifetime_total")).longValue(),
                frame == null ? null : FrameChoice.valueOf(frame), true);
    }

    private Map<String, Integer> counters(long userId, LocalDate today) {
        Map<String, Integer> m = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(COUNTERS_SQL, Map.of("u", userId, "d", today))) {
            m.put((String) r.get("counter_key"), ((Number) r.get("used")).intValue());
        }
        return m;
    }

    private Map<String, Long> todayEarned(long userId, LocalDate today) {
        Map<String, Long> m = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(TODAY_EARNED_SQL, Map.of("u", userId, "d", today))) {
            m.put((String) r.get("rule_code"), ((Number) r.get("earned")).longValue());
        }
        return m;
    }

    static LevelPosition position(List<GrowthLevelDef> table, int level) {
        GrowthLevelDef current = null;
        GrowthLevelDef next = null;
        for (GrowthLevelDef d : table) {
            if (d.getLevel() == level) {
                current = d;
            } else if (d.getLevel() > level && (next == null || d.getLevel() < next.getLevel())) {
                next = d;
            }
        }
        GrowthTier tier = current == null ? GrowthTier.NEWBIE : current.getTier();
        long threshold = current == null ? 0L : current.getThreshold();
        return new LevelPosition(tier, threshold, next == null ? null : next.getLevel(),
                next == null ? null : next.getThreshold());
    }

    static Frames frames(List<GrowthLevelDef> table, GrowthTier tier, FrameChoice choice) {
        Set<FrameChoice> available = GrowthFrameResolver.availableFrames(tier);
        Map<GrowthTier, Integer> firstLevel = firstLevelByTier(table);
        int minLevel = table.isEmpty() ? 1 : table.get(0).getLevel();
        List<FrameOption> options = new ArrayList<>();
        options.add(new FrameOption(FrameChoice.NONE, true, minLevel));
        for (FrameChoice f : List.of(FrameChoice.BASIC, FrameChoice.UPGRADED, FrameChoice.ANIMATED)) {
            Integer unlock = firstLevel.get(FRAME_TIER.get(f));
            options.add(new FrameOption(f, available.contains(f), unlock == null ? 0 : unlock));
        }
        return new Frames(GrowthFrameResolver.effectiveFrame(tier, choice), GrowthFrameResolver.isManual(choice),
                options);
    }

    static List<TierBenefits> tierBenefits(List<GrowthLevelDef> table) {
        Map<GrowthTier, int[]> range = new EnumMap<>(GrowthTier.class);
        for (GrowthLevelDef d : table) {
            range.merge(d.getTier(), new int[] {d.getLevel(), d.getLevel()},
                    (x, y) -> new int[] {Math.min(x[0], y[0]), Math.max(x[1], y[1])});
        }
        GrowthTier top = table.isEmpty() ? null : table.get(table.size() - 1).getTier();
        List<TierBenefits> out = new ArrayList<>();
        for (GrowthTier t : GrowthTier.values()) {
            int[] r = range.get(t);
            if (r == null) {
                continue;
            }
            out.add(new TierBenefits(t, r[0], t == top ? null : r[1], BENEFITS.getOrDefault(t, List.of())));
        }
        return out;
    }

    private static Map<GrowthTier, Integer> firstLevelByTier(List<GrowthLevelDef> table) {
        Map<GrowthTier, Integer> m = new EnumMap<>(GrowthTier.class);
        for (GrowthLevelDef d : table) {
            m.merge(d.getTier(), d.getLevel(), Math::min);
        }
        return m;
    }

    private record Account(int level, long lifetimeTotal, FrameChoice frameChoice, boolean hasAccount) {
    }

    record LevelPosition(GrowthTier tier, long currentThreshold, Integer nextLevel, Long nextThreshold) {
    }
}
