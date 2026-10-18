package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.GrowthRule;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthDailyCounterRepository;
import com.tailtopia.growth.repository.GrowthLedgerRepository;
import com.tailtopia.growth.repository.GrowthRuleRepository;
import com.tailtopia.shared.error.AppException;
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

    /** 后台调整（Story 4.2）：比入账多 {@code note / operator_admin_id} 两列。 */
    static final String INSERT_ADJUST_SQL = "INSERT INTO growth_ledger (user_id, rule_code, delta, idem_key, reason, "
            + "note, operator_admin_id, batch_no, wib_date, created_at) VALUES (:userId, :ruleCode, :delta, :idemKey, "
            + ":reason, :note, :operatorAdminId, :batchNo, :wibDate, :createdAt)";

    /** 与 {@code growth_ledger.batch_no VARCHAR(32)} 一致。 */
    public static final int BATCH_NO_MAX_LENGTH = 32;

    /** 批量参与归类（Story 4.3）：一次 SQL，参与判定与 {@link #adjust} 同口径（{@link GrowthParticipation} + 注销已提交）。 */
    static final String CLASSIFY_SQL = "SELECT u.id, u.account_type, u.status, "
            + "(u.deleted_at IS NOT NULL OR EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)) "
            + "AS deleted, " + GrowthParticipation.ACTIVE_REGULAR_USER + " AS eligible FROM users u WHERE u.id IN (:ids)";

    static final int CLASSIFY_CHUNK = 1000;

    static final String BATCH_SIGNATURE_SQL = "SELECT DISTINCT note, delta FROM growth_ledger WHERE batch_no = :b";

    /** 批量归类结果。{@code NOT_PARTICIPATING} = 未注销、未停用的非普通用户（官方号等）。 */
    public enum Eligibility { ELIGIBLE, NOT_FOUND, DELETED, DEACTIVATED, VIRTUAL, NOT_PARTICIPATING }

    static final String ACCOUNT_TYPE_SQL = "SELECT account_type FROM users WHERE id = :id";

    static final String DELETION_SUBMITTED_SQL = "SELECT count(*) FROM account_deletions WHERE user_id = :id";

    /** 已注销 / 已停用 / 注销作业已开始（与「不参与」的其余原因——官方号——区分文案）。 */
    static final String INACTIVE_SQL = "SELECT count(*) FROM users u WHERE u.id = :id AND (u.deleted_at IS NOT NULL "
            + "OR u.status <> 'ACTIVE' OR EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id))";

    /** 与 {@code growth_ledger.note VARCHAR(200)} 一致。 */
    public static final int NOTE_MAX_LENGTH = 200;

    /** {@code ADMIN:} + token 不得超过幂等键列宽；页面给的是 36 位 UUID，这里放宽到 64。 */
    static final int GRANT_TOKEN_MAX_LENGTH = 64;

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

    /**
     * 后台对单个用户调整 EXP / 发运营活动（V1.4.0 ① Story 4.2 · AD-5 adjust）。同一事务：
     * ① 入参 → ② 参与判定（虚拟号 / 注销 / 停用 / 官方号 422）→ ③ {@code OPS_CAMPAIGN} 须启用 → ④ 懒建并锁账户行 →
     * ⑤ 幂等键已在 = 重复提交（{@code replayed}，不改任何东西）→ ⑥ 累计 + delta &lt; 0 拒绝（AD-1：运营调整不截断）→
     * ⑦ 插流水 → ⑧ 改累计并经 {@link GrowthLevelChangeHandler} 定级与连锁。不占每日额度（{@code growth_daily_counters}）。
     *
     * <p>调用方（后台服务）在<b>同一事务</b>里按 {@code replayed} 决定是否写审计。
     */
    @Transactional
    public AdminAdjustCommand.Result adjust(AdminAdjustCommand cmd) {
        if (cmd.batchNo() == null) {
            validateAdjust(cmd.type(), cmd.delta(), cmd.note(), cmd.grantToken());
        } else {
            validateBatchAdjust(cmd);
        }
        long userId = cmd.userId();
        String idemKey = cmd.idemKey();
        // 重复提交先认（code-review）：入账成功后用户被停用 / 运营活动被停用，回退重提同一 token 仍应是「已处理」而非 422。
        GrowthLedgerEntry prior = ledger.findByIdemKey(idemKey).orElse(null);
        if (prior != null) {
            return replayed(prior, userId);
        }
        requireAdjustable(userId);
        if (cmd.type() == AdminAdjustCommand.AdjustType.OPS_CAMPAIGN && !campaignEnabled()) {
            throw AppException.validation("「运营活动」规则已停用").code("admin.err.growth.campaignDisabled");
        }
        accounts.insertIfAbsent(userId);
        GrowthAccount account = accounts.lockById(userId).orElseThrow();
        long oldTotal = account.getLifetimeTotal();
        int oldLevel = account.getLevel();
        // 并发的同 token 两次提交：行锁串行后，后到的一次在这里看见先到的那条流水。
        prior = ledger.findByIdemKey(idemKey).orElse(null);
        if (prior != null) {
            return replayed(prior, userId);
        }
        if (oldTotal + cmd.delta() < 0) {
            throw AppException.validation("调整后累计不能小于 0").code("admin.err.growth.belowZero", oldTotal);
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleCode", cmd.type().name())
                .addValue("delta", cmd.delta())
                .addValue("idemKey", idemKey)
                // 架构 AD-2：reason 只给「运营调整」记 ADMIN_ADJUST；运营活动的说明只在 note（活动名）。
                .addValue("reason", cmd.type() == AdminAdjustCommand.AdjustType.ADMIN_ADJUST
                        ? ReverseReason.ADMIN_ADJUST.name() : null)
                .addValue("note", cmd.note().trim())
                .addValue("operatorAdminId", cmd.operatorAdminId())
                .addValue("batchNo", cmd.batchNo())
                .addValue("wibDate", clock.instant().atZone(ScheduleWindow.WIB).toLocalDate())
                .addValue("createdAt", java.sql.Timestamp.from(clock.instant()));
        jdbc.update(INSERT_ADJUST_SQL, p);
        applyDelta(account, cmd.delta(), cmd.type().rule());
        return new AdminAdjustCommand.Result(oldTotal, account.getLifetimeTotal(), oldLevel, account.getLevel(),
                false);
    }

    /** 幂等命中：token 必须属于同一用户（别人的 token 被拿来重放 → 422，不假装成功）；四个数都是当前值。 */
    private AdminAdjustCommand.Result replayed(GrowthLedgerEntry prior, long userId) {
        if (prior.getUserId() != userId) {
            throw AppException.validation("缺少幂等标识，请刷新页面后重试").code("admin.err.growth.missingToken");
        }
        GrowthAccount acc = accounts.findById(userId).orElse(null);
        long total = acc == null ? 0 : acc.getLifetimeTotal();
        int level = acc == null ? 1 : acc.getLevel();
        return new AdminAdjustCommand.Result(total, total, level, level, true);
    }

    /**
     * 调整的入参校验（预览与入账共用一份，422 + 文案码）：类型必选、增减 ≠ 0、原因 / 活动名必填且 ≤ 200、token 必有。
     */
    public static void validateAdjust(AdminAdjustCommand.AdjustType type, Integer delta, String note,
            String grantToken) {
        if (type == null) {
            throw AppException.validation("请选择调整类型").code("admin.err.growth.typeRequired");
        }
        if (delta == null || delta == 0) {
            throw AppException.validation("增减值不能为 0").code("admin.err.growth.deltaZero");
        }
        if (note == null || note.isBlank()) {
            throw AppException.validation("请填写原因或活动名称").code("admin.err.growth.noteRequired");
        }
        if (note.trim().length() > NOTE_MAX_LENGTH) {
            throw AppException.validation("原因或活动名称不能超过 200 字").code("admin.err.growth.noteTooLong");
        }
        if (grantToken == null || grantToken.isBlank() || grantToken.trim().length() > GRANT_TOKEN_MAX_LENGTH) {
            throw AppException.validation("缺少幂等标识，请刷新页面后重试").code("admin.err.growth.missingToken");
        }
    }

    /** 参与判定（与 {@link #accrue} 同一口径）；不参与时按原因给 422：虚拟号单列一句，其余（注销 / 停用 / 官方号）一句。 */
    public void requireAdjustable(long userId) {
        if (isParticipating(userId)) {
            // 后台比入账更严（code-review）：注销已提交（作业还在 PENDING）就不许再手动调整，不只靠模板藏卡片。
            Long pending = jdbc.queryForObject(DELETION_SUBMITTED_SQL, Map.of("id", userId), Long.class);
            if (pending == null || pending == 0) {
                return;
            }
            throw AppException.validation("该账号已注销或已停用，不可调整").code("admin.err.growth.userInactive");
        }
        List<String> type = jdbc.queryForList(ACCOUNT_TYPE_SQL, Map.of("id", userId), String.class);
        if (type.isEmpty()) {
            throw AppException.notFound("用户不存在").code("admin.err.user.notFound");
        }
        if ("VIRTUAL".equals(type.get(0))) {
            throw AppException.validation("虚拟号不参与成长值").code("admin.err.growth.virtualNotParticipating");
        }
        Long inactive = jdbc.queryForObject(INACTIVE_SQL, Map.of("id", userId), Long.class);
        if (inactive == null || inactive == 0) {
            // 未注销、未停用但仍不参与 = 官方号等 role ≠ USER 的账号
            throw AppException.validation("该账号不参与成长值").code("admin.err.growth.notParticipating");
        }
        throw AppException.validation("该账号已注销或已停用，不可调整").code("admin.err.growth.userInactive");
    }

    /** {@code growth_rules.OPS_CAMPAIGN.enabled}；行不存在视作停用。 */
    @Transactional(readOnly = true)
    public boolean campaignEnabled() {
        return rules.findById(RuleCode.OPS_CAMPAIGN.name()).map(GrowthRule::isEnabled).orElse(false);
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

    /**
     * 批量参与归类（Story 4.3 · 与单个调整同一判定，不在后台另写一套）：分块 IN 查询，每块一条 SQL。
     * 顺序：注销（含注销已提交）→ 虚拟号 → 停用 → 参与 → 其余（官方号等）；不在 users 里 = NOT_FOUND。
     */
    @Transactional(readOnly = true)
    public Map<Long, Eligibility> classify(java.util.Collection<Long> userIds) {
        Map<Long, Eligibility> out = new java.util.LinkedHashMap<>();
        List<Long> ids = List.copyOf(new java.util.LinkedHashSet<>(userIds));
        for (Long id : ids) {
            out.put(id, Eligibility.NOT_FOUND);
        }
        for (int from = 0; from < ids.size(); from += CLASSIFY_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CLASSIFY_CHUNK));
            jdbc.query(CLASSIFY_SQL, Map.of("ids", chunk), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                long id = rs.getLong("id");
                Eligibility e;
                if (rs.getBoolean("deleted")) {
                    e = Eligibility.DELETED;
                } else if ("VIRTUAL".equals(rs.getString("account_type"))) {
                    e = Eligibility.VIRTUAL;
                } else if (!"ACTIVE".equals(rs.getString("status"))) {
                    e = Eligibility.DEACTIVATED;
                } else if (rs.getBoolean("eligible")) {
                    e = Eligibility.ELIGIBLE;
                } else {
                    e = Eligibility.NOT_PARTICIPATING;
                }
                out.put(id, e);
            });
        }
        return out;
    }

    /**
     * 某批次已入账流水的（活动名, 每人值）是否与本次一致（Story 4.3 code-review）：同一批次号只能对应一个活动与一个数额，
     * 否则「按批次号查」就不再能还原一次发放。批次尚无流水 = 一致。
     */
    @Transactional(readOnly = true)
    public boolean batchMatches(String batchNo, String campaign, int expPerUser) {
        List<Map<String, Object>> rows = jdbc.queryForList(BATCH_SIGNATURE_SQL, Map.of("b", batchNo));
        for (Map<String, Object> r : rows) {
            if (!campaign.equals(r.get("note")) || ((Number) r.get("delta")).intValue() != expPerUser) {
                return false;
            }
        }
        return true;
    }

    /** 批量形态的入参：只增、活动名 ≤ 200、批次号 ≤ 32。 */
    private static void validateBatchAdjust(AdminAdjustCommand cmd) {
        if (cmd.type() != AdminAdjustCommand.AdjustType.OPS_CAMPAIGN || cmd.delta() <= 0) {
            throw AppException.validation("每人 EXP 须为正整数").code("admin.err.growth.batch.expInvalid");
        }
        if (cmd.note() == null || cmd.note().isBlank()) {
            throw AppException.validation("请填写活动名称").code("admin.err.growth.batch.campaignRequired");
        }
        if (cmd.note().trim().length() > NOTE_MAX_LENGTH) {
            throw AppException.validation("活动名称不能超过 200 字").code("admin.err.growth.batch.campaignTooLong");
        }
        if (cmd.batchNo().isBlank() || cmd.batchNo().length() > BATCH_NO_MAX_LENGTH) {
            throw AppException.validation("批次号无效").code("admin.err.growth.batch.batchNoInvalid");
        }
    }

    /** 是否参与成长值（{@link GrowthParticipation} 口径；后台成长值页签据此决定调整卡是否可用）。 */
    @Transactional(readOnly = true)
    public boolean participates(long userId) {
        return isParticipating(userId);
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
