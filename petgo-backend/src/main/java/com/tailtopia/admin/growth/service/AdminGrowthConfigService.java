package com.tailtopia.admin.growth.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
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
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台「成长体系」配置（V1.4.0 ① AB-32 · Story 4.1 · AD-4 / AD-12 / AD-17）：成长值规则、消费换算、等级表。
 *
 * <p>校验全在这里（Lv1 门槛 0、严格递增、段位单调、只能末尾追加、整数 ≥ 1）；每次真改动写操作审计（改前 → 改后，
 * 同一事务）；不写 {@code config_change_logs}（其 CHECK 无 GROWTH 类型）。改等级表 → 同一事务入库一条重算任务，
 * <b>提交后</b>由 {@link GrowthRecalcService} 异步执行。规则改动从下一次入账起生效（{@code accrue} 每次直读规则表）。
 */
@Service
public class AdminGrowthConfigService {

    /** 规则表页签的固定顺序（UI G1）；{@code PASSIVE_GROUP / LAUNCH_BACKFILL / ADMIN_ADJUST} 不显示。 */
    public static final List<RuleCode> RULE_ORDER = List.of(RuleCode.DAILY_LOGIN, RuleCode.POST_PUBLISHED,
            RuleCode.COMMENT_POSTED, RuleCode.LIKE_GIVEN, RuleCode.PLACE_CHECKIN, RuleCode.LIKE_RECEIVED,
            RuleCode.COMMENT_RECEIVED, RuleCode.COLLECT_RECEIVED, RuleCode.MILESTONE_S, RuleCode.MILESTONE_M,
            RuleCode.MILESTONE_L, RuleCode.OPS_CAMPAIGN, RuleCode.GIFT_SENT, RuleCode.GIFT_RECEIVED,
            RuleCode.SPEND_DIGITAL, RuleCode.SPEND_SHOP);

    /** 支付用途归档表的固定顺序（UI G4）。 */
    public static final List<String> PURPOSE_ORDER = List.of("VET_CONSULT", "AI_UNLOCK", "ID_HD", "TAILSONALITY",
            "PASSPORT_SNAP", "BOARDING_PASS", "TS_MATCH", "PET_SLOT_RENT", "SHOP_ORDER", "PAWCOIN_TOPUP", "GIFT");

    /** 后台可选的档位（{@code GIFT} 只属于锁定行）。 */
    public static final Set<SpendTier> EDITABLE_TIERS = EnumSet.of(SpendTier.DIGITAL, SpendTier.TOKO, SpendTier.NONE);

    /** 抽屉分型（只渲染该型有的字段）。 */
    public enum RuleKind {
        ACTIVE,
        PASSIVE,
        MILESTONE,
        PER_AMOUNT,
        CAMPAIGN,
        SPEND
    }

    public static RuleKind kindOf(RuleCode code) {
        return switch (code) {
            case DAILY_LOGIN, POST_PUBLISHED, COMMENT_POSTED, LIKE_GIVEN, PLACE_CHECKIN -> RuleKind.ACTIVE;
            case LIKE_RECEIVED, COMMENT_RECEIVED, COLLECT_RECEIVED -> RuleKind.PASSIVE;
            case MILESTONE_S, MILESTONE_M, MILESTONE_L -> RuleKind.MILESTONE;
            case GIFT_SENT, GIFT_RECEIVED -> RuleKind.PER_AMOUNT;
            case OPS_CAMPAIGN -> RuleKind.CAMPAIGN;
            default -> RuleKind.SPEND; // SPEND_* 与系统代码：不在抽屉编辑
        };
    }

    // ── 视图模型 ────────────────────────────────────────────────

    public record RuleRow(String code, RuleKind kind, int points, Integer unitAmount, Integer dailyCap,
            String capGroup, boolean enabled) {
        public boolean editable() {
            return kind != RuleKind.SPEND;
        }
    }

    public record RulesView(List<RuleRow> rows, Integer passiveGroupCap) {
    }

    public record PurposeRow(String purpose, SpendTier tier, boolean locked) {
    }

    public record SpendView(int digitalUnit, int digitalPoints, int tokoUnit, int tokoPoints,
            List<PurposeRow> purposes) {
    }

    public record LevelRowView(int level, long threshold, GrowthTier tier) {
    }

    public record LevelsView(List<LevelRowView> rows, int nextLevel, GrowthTier lastTier,
            Optional<GrowthRecalcJob> latestJob) {
    }

    // ── 表单 ────────────────────────────────────────────────────

    /** 规则抽屉表单（数字以原始字符串收，服务端判「≥ 1 的整数」）。 */
    public record RuleForm(String points, String dailyCap, String groupCap, String unitAmount, boolean enabled) {
    }

    public record SpendForm(String digitalUnit, String digitalPoints, String tokoUnit, String tokoPoints,
            Map<String, String> tiers) {
        public SpendForm {
            tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
        }
    }

    /**
     * 等级表表单：{@code thresholds} = 等级 → 门槛原始值（Lv1 可缺省 = 0）；{@code appendThreshold} 为空 = 不追加。
     */
    public record LevelsForm(Map<Integer, String> thresholds, String appendThreshold, String appendTier) {
        public LevelsForm {
            thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        }
    }

    /** 等级表改动计划（预检与保存同一份）。 */
    public record LevelChange(int level, long oldThreshold, long newThreshold) {
    }

    public record LevelAppend(int level, long threshold, GrowthTier tier) {
    }

    public record LevelsPlan(List<LevelChange> changes, LevelAppend append,
            List<GrowthRecalcService.LevelRow> candidate) {
    }

    public record LevelsPreview(LevelsPlan plan, GrowthRecalcService.Preview counts) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthRuleRepository rules;
    private final GrowthSpendTierRepository spendTiers;
    private final GrowthLevelDefRepository levels;
    private final GrowthRecalcJobRepository jobs;
    private final GrowthRecalcService recalc;
    private final AdminAuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public AdminGrowthConfigService(NamedParameterJdbcTemplate jdbc, GrowthRuleRepository rules,
            GrowthSpendTierRepository spendTiers, GrowthLevelDefRepository levels, GrowthRecalcJobRepository jobs,
            GrowthRecalcService recalc, AdminAuditService audit, ApplicationEventPublisher events) {
        this(jdbc, rules, spendTiers, levels, jobs, recalc, audit, events, Clock.systemUTC());
    }

    AdminGrowthConfigService(NamedParameterJdbcTemplate jdbc, GrowthRuleRepository rules,
            GrowthSpendTierRepository spendTiers, GrowthLevelDefRepository levels, GrowthRecalcJobRepository jobs,
            GrowthRecalcService recalc, AdminAuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.spendTiers = spendTiers;
        this.levels = levels;
        this.jobs = jobs;
        this.recalc = recalc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ── ① 成长值规则 ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public RulesView rules() {
        Map<String, GrowthRule> byCode = rulesByCode();
        List<RuleRow> rows = new ArrayList<>();
        for (RuleCode code : RULE_ORDER) {
            GrowthRule r = byCode.get(code.name());
            if (r != null) {
                rows.add(row(code, r));
            }
        }
        GrowthRule passive = byCode.get(RuleCode.PASSIVE_GROUP.name());
        return new RulesView(rows, passive == null ? null : passive.getDailyCap());
    }

    /** 抽屉用：不存在 → 404；不可在此编辑（SPEND_* / 系统代码）→ 422。 */
    @Transactional(readOnly = true)
    public RuleRow editableRule(String code) {
        RuleCode rc = parseCode(code);
        if (kindOf(rc) == RuleKind.SPEND) {
            throw notEditable();
        }
        GrowthRule r = rules.findById(rc.name()).orElseThrow(AdminGrowthConfigService::ruleNotFound);
        return row(rc, r);
    }

    @Transactional
    public void updateRule(String code, RuleForm form, long adminId) {
        RuleRow current = editableRule(code);
        RuleKind kind = current.kind();
        List<GrowthConfigValidationException.FieldError> errors = new ArrayList<>();
        Integer points = current.points();
        Integer dailyCap = current.dailyCap();
        Integer unitAmount = current.unitAmount();
        Integer groupCap = null;
        if (kind != RuleKind.CAMPAIGN) {
            points = positiveInt(form.points(), "points", errors);
        }
        if (kind == RuleKind.ACTIVE) {
            dailyCap = blank(form.dailyCap()) ? null : positiveInt(form.dailyCap(), "dailyCap", errors);
        }
        if (kind == RuleKind.PASSIVE) {
            groupCap = positiveInt(form.groupCap(), "groupCap", errors);
        }
        if (kind == RuleKind.PER_AMOUNT) {
            unitAmount = positiveInt(form.unitAmount(), "unitAmount", errors);
        }
        if (!errors.isEmpty()) {
            throw new GrowthConfigValidationException(errors);
        }

        List<String> diffs = new ArrayList<>();
        diff(diffs, "points", current.points(), points);
        diff(diffs, "daily_cap", current.dailyCap(), dailyCap);
        diff(diffs, "unit_amount", current.unitAmount(), unitAmount);
        diff(diffs, "enabled", current.enabled(), form.enabled());
        Integer oldGroupCap = null;
        if (kind == RuleKind.PASSIVE) {
            oldGroupCap = rules.findById(RuleCode.PASSIVE_GROUP.name()).map(GrowthRule::getDailyCap).orElse(null);
            diff(diffs, "PASSIVE_GROUP.daily_cap", oldGroupCap, groupCap);
        }
        if (diffs.isEmpty()) {
            return; // 无变化：不写库、不写审计
        }
        jdbc.update("UPDATE growth_rules SET points = :points, daily_cap = :cap, unit_amount = :unit, "
                + "enabled = :enabled, updated_at = now(), updated_by = :by WHERE code = :code",
                new MapSqlParameterSource().addValue("points", points).addValue("cap", dailyCap, Types.INTEGER)
                        .addValue("unit", unitAmount, Types.INTEGER).addValue("enabled", form.enabled())
                        .addValue("by", adminId).addValue("code", current.code()));
        if (kind == RuleKind.PASSIVE && !Objects.equals(oldGroupCap, groupCap)) {
            jdbc.update("UPDATE growth_rules SET daily_cap = :cap, updated_at = now(), updated_by = :by "
                    + "WHERE code = 'PASSIVE_GROUP'", Map.of("cap", groupCap, "by", adminId));
        }
        audit.record(adminId, AuditActions.GROWTH_RULE_UPDATED, "growth_config", current.code(),
                current.code() + " " + String.join("; ", diffs));
    }

    // ── ② 消费换算 ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SpendView spend() {
        Map<String, GrowthRule> byCode = rulesByCode();
        GrowthRule d = byCode.get(RuleCode.SPEND_DIGITAL.name());
        GrowthRule t = byCode.get(RuleCode.SPEND_SHOP.name());
        Map<String, GrowthSpendTier> tiers = spendTiers.findAll().stream()
                .collect(Collectors.toMap(GrowthSpendTier::getPurpose, Function.identity()));
        List<PurposeRow> purposes = new ArrayList<>();
        for (String p : PURPOSE_ORDER) {
            GrowthSpendTier row = tiers.get(p);
            // 无行的用途按 DIGITAL 显示（AD-4「新用途默认数字服务」）。
            purposes.add(row == null ? new PurposeRow(p, SpendTier.DIGITAL, false)
                    : new PurposeRow(p, row.getTier(), row.isLocked()));
        }
        return new SpendView(unit(d), points(d), unit(t), points(t), purposes);
    }

    @Transactional
    public void updateSpend(SpendForm form, long adminId) {
        SpendView current = spend();
        int dUnit = requirePositive(form.digitalUnit());
        int dPoints = requirePositive(form.digitalPoints());
        int tUnit = requirePositive(form.tokoUnit());
        int tPoints = requirePositive(form.tokoPoints());

        Map<String, SpendTier> changes = new LinkedHashMap<>();
        for (PurposeRow row : current.purposes()) {
            String raw = form.tiers().get(row.purpose());
            if (row.locked() || raw == null) {
                continue; // 锁定行：提交值忽略、不报错；未提交 = 不改
            }
            SpendTier next;
            try {
                next = SpendTier.valueOf(raw);
            } catch (IllegalArgumentException e) {
                throw tierInvalid();
            }
            if (!EDITABLE_TIERS.contains(next)) {
                throw tierInvalid();
            }
            if (next != row.tier()) {
                changes.put(row.purpose(), next);
            }
        }

        List<String> diffs = new ArrayList<>();
        diff(diffs, "SPEND_DIGITAL.unit_amount", current.digitalUnit(), dUnit);
        diff(diffs, "SPEND_DIGITAL.points", current.digitalPoints(), dPoints);
        diff(diffs, "SPEND_SHOP.unit_amount", current.tokoUnit(), tUnit);
        diff(diffs, "SPEND_SHOP.points", current.tokoPoints(), tPoints);
        Map<String, SpendTier> old = current.purposes().stream()
                .collect(Collectors.toMap(PurposeRow::purpose, PurposeRow::tier));
        changes.forEach((p, next) -> diffs.add(p + " " + old.get(p) + "→" + next));
        if (diffs.isEmpty()) {
            return;
        }
        updateSpendRule(RuleCode.SPEND_DIGITAL, dUnit, dPoints, adminId);
        updateSpendRule(RuleCode.SPEND_SHOP, tUnit, tPoints, adminId);
        for (Map.Entry<String, SpendTier> e : changes.entrySet()) {
            // 双保险：库里是锁定行的绝不写（WHERE locked = false）。
            jdbc.update("INSERT INTO growth_spend_tiers (purpose, tier, locked, updated_at, updated_by) "
                    + "VALUES (:p, :t, false, now(), :by) ON CONFLICT (purpose) DO UPDATE "
                    + "SET tier = EXCLUDED.tier, updated_at = now(), updated_by = EXCLUDED.updated_by "
                    + "WHERE growth_spend_tiers.locked = false",
                    Map.of("p", e.getKey(), "t", e.getValue().name(), "by", adminId));
        }
        audit.record(adminId, AuditActions.GROWTH_SPEND_CONFIG_UPDATED, "growth_config", "spend",
                String.join("; ", diffs));
    }

    private void updateSpendRule(RuleCode code, int unit, int points, long adminId) {
        jdbc.update("UPDATE growth_rules SET unit_amount = :unit, points = :points, updated_at = now(), "
                + "updated_by = :by WHERE code = :code AND (unit_amount IS DISTINCT FROM :unit OR points <> :points)",
                Map.of("unit", unit, "points", points, "by", adminId, "code", code.name()));
    }

    // ── ③ 等级表 ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LevelsView levels() {
        List<LevelRowView> rows = levels.findAllByOrderByLevelAsc().stream()
                .map(d -> new LevelRowView(d.getLevel(), d.getThreshold(), d.getTier())).toList();
        LevelRowView last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        return new LevelsView(rows, last == null ? 1 : last.level() + 1, last == null ? null : last.tier(),
                jobs.findTopByOrderByIdDesc());
    }

    /** 预检：只校验 + 按候选新表算人数，不写库。 */
    @Transactional(readOnly = true)
    public LevelsPreview previewLevels(LevelsForm form) {
        LevelsPlan plan = plan(form);
        return new LevelsPreview(plan, recalc.preview(plan.candidate()));
    }

    /** 保存：同一事务写等级表 + 入库重算任务 + 审计；提交后异步重算。返回预检同口径的计划。 */
    @Transactional
    public LevelsPlan saveLevels(LevelsForm form, long adminId) {
        LevelsPlan plan = plan(form);
        for (LevelChange c : plan.changes()) {
            jdbc.update("UPDATE growth_levels SET threshold = :t WHERE level = :l",
                    Map.of("t", c.newThreshold(), "l", c.level()));
        }
        LevelAppend a = plan.append();
        if (a != null) {
            jdbc.update("INSERT INTO growth_levels (level, threshold, tier) VALUES (:l, :t, :tier)",
                    Map.of("l", a.level(), "t", a.threshold(), "tier", a.tier().name()));
        }
        GrowthRecalcJob job = insertJob(adminId);
        audit.record(adminId, AuditActions.GROWTH_LEVEL_TABLE_UPDATED, "growth_config", "levels",
                summary(plan) + "; recalcJob=" + job.getId());
        events.publishEvent(new GrowthRecalcRequestedEvent(job.getId()));
        return plan;
    }

    /** 最近一次失败或僵死 → 置 FAILED 后再入库一条 PENDING 并触发。 */
    @Transactional
    public long retryRecalc(long adminId) {
        GrowthRecalcJob latest = jobs.findTopByOrderByIdDesc().orElse(null);
        if (latest == null || !latest.isRetryable(clock.instant())) {
            throw recalcRunning();
        }
        if (latest.isActive()) {
            jobs.markFailed(latest.getId(), "Stale", clock.instant());
        }
        GrowthRecalcJob job = insertJob(adminId);
        audit.record(adminId, AuditActions.GROWTH_RECALC_RETRIED, "growth_config", String.valueOf(job.getId()),
                "retry of job " + latest.getId() + " (" + latest.getStatus() + ")");
        events.publishEvent(new GrowthRecalcRequestedEvent(job.getId()));
        return job.getId();
    }

    private GrowthRecalcJob insertJob(long adminId) {
        try {
            return jobs.saveAndFlush(GrowthRecalcJob.pending(adminId, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw recalcRunning(); // 部分唯一索引：同时只能有一个进行中的任务
        }
    }

    /** 预检与保存共用的校验 + 计划。 */
    LevelsPlan plan(LevelsForm form) {
        if (jobs.existsByStatusIn(List.of(GrowthRecalcJob.Status.PENDING, GrowthRecalcJob.Status.RUNNING))) {
            throw recalcRunning();
        }
        List<GrowthLevelDef> table = levels.findAllByOrderByLevelAsc();
        TreeMap<Integer, Long> next = new TreeMap<>();
        TreeMap<Integer, GrowthTier> tiers = new TreeMap<>();
        List<LevelChange> changes = new ArrayList<>();
        for (GrowthLevelDef d : table) {
            int lv = d.getLevel();
            tiers.put(lv, d.getTier());
            String raw = form.thresholds().get(lv);
            long value;
            if (lv == 1) {
                if (raw != null && !blank(raw) && parseLong(raw) != 0L) {
                    throw validation("admin.err.growth.levelLv1Fixed");
                }
                value = 0L;
            } else if (raw == null) {
                value = d.getThreshold(); // 未提交 = 不改
            } else {
                value = requirePositiveLong(raw);
            }
            next.put(lv, value);
            if (value != d.getThreshold()) {
                changes.add(new LevelChange(lv, d.getThreshold(), value));
            }
        }
        LevelAppend append = null;
        if (!blank(form.appendThreshold())) {
            int lv = table.isEmpty() ? 1 : table.get(table.size() - 1).getLevel() + 1;
            long threshold = requirePositiveLong(form.appendThreshold());
            GrowthTier tier;
            try {
                tier = GrowthTier.valueOf(form.appendTier());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw validation("admin.err.growth.levelTierNotMonotonic");
            }
            GrowthTier prev = tiers.isEmpty() ? null : tiers.lastEntry().getValue();
            if (prev != null && tier.ordinal() < prev.ordinal()) {
                throw validation("admin.err.growth.levelTierNotMonotonic");
            }
            append = new LevelAppend(lv, threshold, tier);
            next.put(lv, threshold);
        }
        // 严格递增（含追加级 > 当前最高级）。
        Map.Entry<Integer, Long> prev = null;
        for (Map.Entry<Integer, Long> e : next.entrySet()) {
            if (prev != null && e.getValue() <= prev.getValue()) {
                throw validation("admin.err.growth.levelNotIncreasing", e.getKey(), prev.getKey(), prev.getValue());
            }
            prev = e;
        }
        if (changes.isEmpty() && append == null) {
            throw validation("admin.err.growth.levelsNoChange");
        }
        List<GrowthRecalcService.LevelRow> candidate = next.entrySet().stream()
                .map(e -> new GrowthRecalcService.LevelRow(e.getKey(), e.getValue())).toList();
        return new LevelsPlan(List.copyOf(changes), append, candidate);
    }

    static String summary(LevelsPlan plan) {
        List<String> parts = new ArrayList<>();
        for (LevelChange c : plan.changes()) {
            parts.add("Lv" + c.level() + " threshold " + c.oldThreshold() + "→" + c.newThreshold());
        }
        if (plan.append() != null) {
            parts.add("+Lv" + plan.append().level() + " " + plan.append().threshold() + " " + plan.append().tier());
        }
        return String.join("; ", parts);
    }

    // ── helpers ─────────────────────────────────────────────────

    private Map<String, GrowthRule> rulesByCode() {
        return rules.findAll().stream().collect(Collectors.toMap(GrowthRule::getCode, Function.identity()));
    }

    private static RuleRow row(RuleCode code, GrowthRule r) {
        return new RuleRow(code.name(), kindOf(code), r.getPoints(), r.getUnitAmount(), r.getDailyCap(),
                r.getCapGroup(), r.isEnabled());
    }

    private static RuleCode parseCode(String code) {
        RuleCode rc;
        try {
            rc = RuleCode.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ruleNotFound();
        }
        if (!RULE_ORDER.contains(rc)) {
            throw notEditable(); // PASSIVE_GROUP / LAUNCH_BACKFILL / ADMIN_ADJUST
        }
        return rc;
    }

    private static int unit(GrowthRule r) {
        return r == null || r.getUnitAmount() == null ? 0 : r.getUnitAmount();
    }

    private static int points(GrowthRule r) {
        return r == null ? 0 : r.getPoints();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** ≥ 1 的整数；不合法记一条字段错误并返回 null。 */
    private static Integer positiveInt(String raw, String field, List<GrowthConfigValidationException.FieldError> errors) {
        Integer v = parsePositiveInt(raw);
        if (v == null) {
            errors.add(new GrowthConfigValidationException.FieldError(field, "admin.err.growth.intMin1"));
        }
        return v;
    }

    private static Integer parsePositiveInt(String raw) {
        if (blank(raw)) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int requirePositive(String raw) {
        Integer v = parsePositiveInt(raw);
        if (v == null) {
            throw validation("admin.err.growth.intMin1");
        }
        return v;
    }

    private static long requirePositiveLong(String raw) {
        long v = parseLong(raw);
        if (v < 1) {
            throw validation("admin.err.growth.intMin1");
        }
        return v;
    }

    private static long parseLong(String raw) {
        if (blank(raw)) {
            return -1;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void diff(List<String> out, String field, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            out.add(field + " " + (oldValue == null ? "—" : oldValue) + "→" + (newValue == null ? "—" : newValue));
        }
    }

    private static AppException validation(String code, Object... args) {
        return AppException.validation(code).code(code, args);
    }

    private static AppException ruleNotFound() {
        return new AppException(HttpStatus.NOT_FOUND, com.tailtopia.shared.error.ErrorTypes.NOT_FOUND,
                "规则不存在").code("admin.err.growth.ruleNotFound");
    }

    private static AppException notEditable() {
        return validation("admin.err.growth.ruleNotEditable");
    }

    private static AppException tierInvalid() {
        return validation("admin.err.growth.spendTierInvalid");
    }

    private static AppException recalcRunning() {
        return validation("admin.err.growth.recalcRunning");
    }
}
