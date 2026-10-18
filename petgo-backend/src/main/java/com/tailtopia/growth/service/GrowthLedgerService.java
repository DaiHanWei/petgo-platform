package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.GrowthRule;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthDailyCounterRepository;
import com.tailtopia.growth.repository.GrowthLedgerRepository;
import com.tailtopia.growth.repository.GrowthRuleRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成长值入账引擎（V1.4.0 ① 架构 AD-5）。🔴 <b>写成长值的唯一入口</b>（§5.4）：其他模块只发事件，监听只拼命令。
 *
 * <h2>accrue 顺序（同一事务）</h2>
 * ① 参与判定（{@link GrowthParticipation}）→ ② 规则启用 → ③ 懒建并锁账户行（同一用户从此串行）→
 * ④ 幂等键已存在 = 已处理（不消耗额度）→ ⑤ 领每日额度 → ⑥ 插流水 → ⑦ 累计变化。
 * 幂等命中与额度不足都以「不入账」结束（返回 false），不抛给监听方。
 *
 * <h2>reverse</h2>
 * 按原幂等键找原流水；不存在（当初因额度 / 不参与没入账）则不扣（PRD AC14）。只扣原流水<b>实际入账</b>且尚未扣回的部分，
 * 累计截断到 0、流水记实际扣减值；只追加反向流水，<b>不改删原流水、不返还当日额度</b>（AD-3）。
 */
@Service
public class GrowthLedgerService {

    static final String PARTICIPATING_SQL =
            "SELECT count(*) FROM users u WHERE u.id = :id AND " + GrowthParticipation.ACTIVE_REGULAR_USER;

    static final String INSERT_LEDGER_SQL = "INSERT INTO growth_ledger (user_id, rule_code, delta, ref_type, ref_id, "
            + "pet_ids, idem_key, reverses_id, reason, wib_date, created_at) VALUES (:userId, :ruleCode, :delta, "
            + ":refType, :refId, CAST(:petIds AS BIGINT[]), :idemKey, :reversesId, :reason, :wibDate, :createdAt)";

    private static final List<String> SPEND_RULES = List.of(RuleCode.SPEND_DIGITAL.name(), RuleCode.SPEND_SHOP.name());

    private static final List<String> MILESTONE_RULES = List.of(
            RuleCode.MILESTONE_S.name(), RuleCode.MILESTONE_M.name(), RuleCode.MILESTONE_L.name());

    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthAccountRepository accounts;
    private final GrowthLedgerRepository ledger;
    private final GrowthRuleRepository rules;
    private final GrowthDailyCounterRepository counters;
    private final GrowthLevelChangeHandler levelChange;
    private final Clock clock;

    @Autowired
    public GrowthLedgerService(NamedParameterJdbcTemplate jdbc, GrowthAccountRepository accounts,
            GrowthLedgerRepository ledger, GrowthRuleRepository rules, GrowthDailyCounterRepository counters,
            GrowthLevelChangeHandler levelChange) {
        this(jdbc, accounts, ledger, rules, counters, levelChange, Clock.systemUTC());
    }

    GrowthLedgerService(NamedParameterJdbcTemplate jdbc, GrowthAccountRepository accounts,
            GrowthLedgerRepository ledger, GrowthRuleRepository rules, GrowthDailyCounterRepository counters,
            GrowthLevelChangeHandler levelChange, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledger = ledger;
        this.rules = rules;
        this.counters = counters;
        this.levelChange = levelChange;
        this.clock = clock;
    }

    /** 入账。返回是否真的写了流水。 */
    @Transactional
    public boolean accrue(AccrualCommand cmd) {
        long userId = cmd.userId();
        if (!isParticipating(userId)) {
            return false;
        }
        GrowthRule rule = rules.findById(cmd.rule().name()).orElse(null);
        if (rule == null || !rule.isEnabled()) {
            return false;
        }
        int points = cmd.pointsOverride() != null ? cmd.pointsOverride() : rule.getPoints();
        if (points <= 0) {
            return false;
        }
        accounts.insertIfAbsent(userId);
        GrowthAccount account = accounts.lockById(userId).orElseThrow();
        if (ledger.existsByIdemKey(cmd.idemKey())) {
            return false;
        }
        if (cmd.rule().isMilestone() && ledger.countPositiveByRef(userId, MILESTONE_RULES, cmd.refId())
                >= RuleCode.MILESTONE_MAX_PER_CODE) {
            return false;
        }
        LocalDate wibDate = cmd.occurredAt().atZone(ScheduleWindow.WIB).toLocalDate();
        int delta = claimQuota(userId, wibDate, rule, points);
        if (delta <= 0) {
            return false;
        }
        // 撞 idem_key 唯一约束（理论上已被行锁 + ④ 挡住）→ 异常抛出，整笔事务连同额度一起回滚。
        insertLedger(userId, cmd.rule().name(), delta, cmd.refType(), cmd.refId(), cmd.petIds(), cmd.idemKey(),
                null, null, wibDate);
        applyDelta(account, delta, cmd.rule());
        return true;
    }

    /** 扣回。返回是否真的写了反向流水。 */
    @Transactional
    public boolean reverse(ReverseCommand cmd) {
        GrowthLedgerEntry original = ledger.findByIdemKey(cmd.originalIdemKey()).orElse(null);
        if (original == null || original.getDelta() <= 0) {
            return false;
        }
        GrowthAccount account = accounts.lockById(original.getUserId()).orElse(null);
        if (account == null) {
            return false;
        }
        String idemKey = GrowthIdemKeys.reverse(original.getId(), cmd.sourceKey());
        if (ledger.existsByIdemKey(idemKey)) {
            return false;
        }
        long remaining = original.getDelta() - ledger.sumReversed(original.getId());
        if (remaining <= 0) {
            return false;
        }
        long wanted = cmd.amountOverride() != null ? cmd.amountOverride() : original.getDelta();
        int amount = (int) Math.min(Math.min(wanted, remaining), account.getLifetimeTotal());
        if (amount <= 0) {
            return false;
        }
        LocalDate wibDate = clock.instant().atZone(ScheduleWindow.WIB).toLocalDate();
        insertLedger(original.getUserId(), original.getRuleCode(), -amount, original.getRefType(),
                original.getRefId(), null, idemKey, original.getId(), cmd.reason().name(), wibDate);
        applyDelta(account, -amount, RuleCode.valueOf(original.getRuleCode()));
        return true;
    }

    /** 按幂等键读一条流水（只读；Story 1.3 退款扣回定位原消费）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<GrowthLedgerEntry> findByIdemKey(String idemKey) {
        return ledger.findByIdemKey(idemKey);
    }

    /** 按业务单反查原消费流水（{@code rule_code IN (SPEND_DIGITAL, SPEND_SHOP)} 且 {@code delta > 0}）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<GrowthLedgerEntry> findSpendByRef(String refType, String refId) {
        return ledger.findFirstByRefTypeAndRefIdAndRuleCodeInAndDeltaGreaterThanOrderByIdAsc(refType, refId,
                SPEND_RULES, 0);
    }

    /** 按金额比例的规则（送礼 / 消费）换算成长值；规则不存在或非比例规则 → 0。 */
    @Transactional(readOnly = true)
    public int pointsForAmount(RuleCode rule, long amount) {
        return rules.findById(rule.name())
                .map(r -> GrowthPoints.ofAmount(amount, r.getPoints(), r.getUnitAmount()))
                .orElse(0);
    }

    private boolean isParticipating(long userId) {
        Long n = jdbc.queryForObject(PARTICIPATING_SQL, Map.of("id", userId), Long.class);
        return n != null && n > 0;
    }

    /**
     * 领每日额度，返回本次可入账值（0 = 不入账）。主动规则按「次」领 1；被动三项按「分值」领、合计截断到恰好上限
     * （最后一笔可能只入一部分）；无上限规则原值返回。账户行锁已持有，同一用户在此串行；条件 UPDATE 再兜一层。
     */
    private int claimQuota(long userId, LocalDate wibDate, GrowthRule rule, int points) {
        if (rule.isPassive()) {
            Integer cap = rules.findById(RuleCode.PASSIVE_GROUP.name()).map(GrowthRule::getDailyCap).orElse(null);
            if (cap == null) {
                return points;
            }
            counters.insertIfAbsent(userId, wibDate, RuleCode.PASSIVE_CAP_GROUP);
            int grant = Math.min(points, cap - counters.used(userId, wibDate, RuleCode.PASSIVE_CAP_GROUP));
            if (grant <= 0) {
                return 0;
            }
            return counters.tryGrant(userId, wibDate, RuleCode.PASSIVE_CAP_GROUP, grant, cap) == 1 ? grant : 0;
        }
        if (rule.getDailyCap() != null) {
            counters.insertIfAbsent(userId, wibDate, rule.getCode());
            return counters.tryGrant(userId, wibDate, rule.getCode(), 1, rule.getDailyCap()) == 1 ? points : 0;
        }
        return points;
    }

    private void insertLedger(long userId, String ruleCode, int delta, String refType, String refId,
            List<Long> petIds, String idemKey, Long reversesId, String reason, LocalDate wibDate) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleCode", ruleCode)
                .addValue("delta", delta)
                .addValue("refType", refType)
                .addValue("refId", refId)
                .addValue("petIds", toPgArray(petIds), Types.VARCHAR)
                .addValue("idemKey", idemKey)
                .addValue("reversesId", reversesId)
                .addValue("reason", reason)
                .addValue("wibDate", wibDate)
                .addValue("createdAt", java.sql.Timestamp.from(clock.instant()));
        jdbc.update(INSERT_LEDGER_SQL, p);
    }

    /** 累计变化的唯一收口：改累计后经 {@link GrowthLevelChangeHandler}（单一连锁出口）重算等级（Story 1.2）。 */
    private void applyDelta(GrowthAccount account, long delta, RuleCode source) {
        account.addLifetime(delta, clock.instant());
        levelChange.onLifetimeChanged(account, source);
    }

    /** {@code [1, 2]} → {@code "{1,2}"}；空 / null → null。 */
    static String toPgArray(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }
}
