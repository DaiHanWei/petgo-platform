package com.tailtopia.admin.growth.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.service.AdminAdjustCommand;
import com.tailtopia.growth.service.AdminAdjustCommand.AdjustType;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户抽屉「成长值」页签（V1.4.0 ① Story 4.2 · AB-33 · UI I1 ~ I7）：读模型 + 单个调整。
 *
 * <p>读：概览（无账户行 = Lv1 / 0，AD-1）、虚拟号只读展示等级、已注销只给一句提示；明细 {@code created_at DESC, id DESC}
 * 每页 20，一页的操作人 / 宠物 / 支付 token <b>各一次</b>批量查询（禁 N+1）。库里只有代码，显示串全在模板按代码取文案（§5.6）。
 *
 * <p>写：唯一入口 {@link GrowthLedgerService#adjust}（幂等键 {@code ADMIN:{token}}、定级与连锁都在那边）；本类只在同一事务里补审计，
 * 幂等命中（重复提交）不写审计。
 */
@Service
public class AdminGrowthUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminGrowthUserService.class);

    public static final int PAGE_SIZE = 20;

    static final String USER_SQL = "SELECT u.account_type, u.status, u.deleted_at IS NOT NULL AS deleted, "
            + "COALESCE(u.nickname, u.display_name) AS name, a.lifetime_total, a.level, a.display_level "
            + "FROM users u LEFT JOIN growth_accounts a ON a.user_id = u.id WHERE u.id = :id";

    /** 扣回行的「来源幂等键」取原流水的（认出支付单要看原入账的键）。 */
    static final String PAGE_SELECT = "SELECT l.id, l.rule_code, l.delta, l.reason, l.note, l.operator_admin_id, "
            + "l.ref_type, l.ref_id, l.reverses_id, l.pet_ids, COALESCE(o.idem_key, l.idem_key) AS src_idem, "
            + "l.created_at FROM growth_ledger l LEFT JOIN growth_ledger o ON o.id = l.reverses_id";

    static final String COUNT_SELECT = "SELECT count(*) FROM growth_ledger l";

    static final String EVER_SQL = "SELECT count(*) FROM growth_ledger WHERE user_id = :u";

    static final String PET_NAMES_SQL = "SELECT id, name FROM pet_profiles WHERE id IN (:ids)";

    static final String INTENT_TOKENS_SQL = "SELECT id, public_token FROM payment_intents WHERE id IN (:ids)";

    /** 有专属类型名的 ref_type（其余：支付用途走 {@code admin.payments.purpose.*}，未登记的原样显示代码）。 */
    static final Set<String> KNOWN_REF_TYPES = Set.of("CONTENT_POST", "PLACE_CHECKIN", "MILESTONE", "GIFT_ORDER");

    static final String REF_CONTENT_POST = "CONTENT_POST";

    static final String REF_GIFT_ORDER = "GIFT_ORDER";

    /** 明细里显示为「××扣回」的原因（{@code ADMIN_ADJUST} 不是扣回，走运营调整文案）。 */
    private static final Set<String> REVERSAL_REASONS = Set.of(ReverseReason.REFUND.name(),
            ReverseReason.UNLIKE.name(), ReverseReason.UNCOLLECT.name(), ReverseReason.TAKEDOWN.name());

    private static final Set<String> PURPOSES = Arrays.stream(PaymentPurpose.values()).map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** 规则下拉：全部规则代码（伪行 PASSIVE_GROUP 不产生流水，不列）。 */
    public static final List<String> RULE_OPTIONS = Arrays.stream(RuleCode.values())
            .filter(r -> r != RuleCode.PASSIVE_GROUP).map(Enum::name).toList();

    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthLedgerService ledgerService;
    private final GrowthLevelDefRepository levels;
    private final AdminAccountRepository adminAccounts;
    private final AdminAuditService audit;

    public AdminGrowthUserService(NamedParameterJdbcTemplate jdbc, GrowthLedgerService ledgerService,
            GrowthLevelDefRepository levels, AdminAccountRepository adminAccounts, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.ledgerService = ledgerService;
        this.levels = levels;
        this.adminAccounts = adminAccounts;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 视图模型

    /** 页签三态。停用是 REAL 的一个标记（概览与明细照常、调整卡置灰）。 */
    public enum State { REAL, VIRTUAL, DELETED }

    /**
     * 页签视图。
     *
     * @param adjustBlockedKey 调整卡置灰时的提示文案键（null = 账号可调整；权限另由模板 sec:authorize 判）
     * @param nextLevel        下一级（满级 = null）
     * @param remaining        距下一级还差（满级 = null）
     * @param displayLevel     虚拟号展示等级（未初始化 = null）
     */
    public record Tab(long userId, State state, boolean deactivated, String adjustBlockedKey, int level,
            GrowthTier tier, long lifetimeTotal, Integer nextLevel, Long remaining, Integer displayLevel,
            GrowthTier displayTier, Ledger ledger) {

        public boolean virtual() {
            return state == State.VIRTUAL;
        }

        public boolean deleted() {
            return state == State.DELETED;
        }

        public boolean maxed() {
            return nextLevel == null;
        }

        public List<String> ruleOptions() {
            return RULE_OPTIONS;
        }
    }

    /**
     * 明细一页。{@code page} 从 0 起；{@code everAny} 区分「从无记录」与「筛选无结果」两种空态。
     */
    public record Ledger(long userId, List<LedgerRow> rows, int page, int totalPages, long totalCount,
            boolean everAny, String rule, String from, String to) {

        public boolean hasPrevious() {
            return page > 0;
        }

        public boolean hasNext() {
            return page + 1 < totalPages;
        }

        public boolean filtered() {
            return rule != null || from != null || to != null;
        }
    }

    /**
     * 明细一行。规则列：{@code reversalReason} 非空 = 扣回；否则按 {@code ruleCode}（运营调整 / 运营活动带 note 与操作人）。
     *
     * @param refTypeKey 关联对象类型名的文案键（null = 原样显示 {@code refType}）
     * @param refShown   关联对象的展示标识（支付单显示 token，不显示数字 id）
     * @param refHref    可点开的后台地址（帖子 / 支付）；其余 null
     * @param petNames   关联宠物名，「、」连接；无 / 已删 = null
     */
    public record LedgerRow(long id, Instant createdAt, String ruleCode, String reversalReason, String note,
            String operator, int delta, String refType, String refTypeKey, String refShown, String refHref,
            String petNames) {

        public String signedDelta() {
            return signed(delta);
        }
    }

    /** 调整预览（确认弹窗 I4 用）：四个数都按库里当前值算，不信任页面。 */
    public record Preview(long userId, String displayName, AdjustType type, int delta, String note,
            String grantToken, long oldTotal, long newTotal, int oldLevel, int newLevel) {
    }

    // ------------------------------------------------------------------ 读

    /** 页签首屏（概览 + 明细第 1 页）。用户不存在 → 404。 */
    @Transactional(readOnly = true)
    public Tab tab(long userId) {
        UserRow u = user(userId);
        List<GrowthLevelDef> defs = levels.findAllByOrderByLevelAsc();
        if (u.deleted) {
            return new Tab(userId, State.DELETED, false, null, 1, tierOf(defs, 1), 0, null, null, null, null, null);
        }
        if ("VIRTUAL".equals(u.accountType)) {
            GrowthTier dt = u.displayLevel == null ? null : tierOf(defs, u.displayLevel);
            return new Tab(userId, State.VIRTUAL, false, null, 1, null, 0, null, null, u.displayLevel, dt, null);
        }
        boolean deactivated = !"ACTIVE".equals(u.status);
        String blocked = null;
        if (!ledgerService.participates(userId)) {
            blocked = deactivated ? "admin.v140.growth.adjust.deactivated" : "admin.err.growth.notParticipating";
        }
        int level = u.level == null ? 1 : u.level;
        long total = u.lifetimeTotal == null ? 0 : u.lifetimeTotal;
        GrowthLevelDef next = defs.stream().filter(d -> d.getLevel() == level + 1).findFirst().orElse(null);
        Integer nextLevel = next == null ? null : next.getLevel();
        Long remaining = next == null ? null : Math.max(0, next.getThreshold() - total);
        return new Tab(userId, State.REAL, deactivated, blocked, level, tierOf(defs, level), total, nextLevel,
                remaining, null, null, query(userId, null, null, null, null));
    }

    /**
     * 明细一页。参数全按字符串收、宽松解析（手改 URL 的非法日期 / 页码 / 规则 = 不筛，同算法参数变更记录）；
     * 起止 = WIB 自然日半开区间 {@code [from 00:00, to + 1 天 00:00)}。虚拟号 / 已注销 = 空页。
     */
    @Transactional(readOnly = true)
    public Ledger ledger(long userId, String ruleRaw, String fromRaw, String toRaw, String pageRaw) {
        UserRow u = user(userId);
        if (u.deleted || "VIRTUAL".equals(u.accountType)) {
            return new Ledger(userId, List.of(), 0, 1, 0, false, null, null, null);
        }
        return query(userId, ruleRaw, fromRaw, toRaw, pageRaw);
    }

    private Ledger query(long userId, String ruleRaw, String fromRaw, String toRaw, String pageRaw) {
        String rule = RULE_OPTIONS.contains(trimToNull(ruleRaw)) ? ruleRaw.trim() : null;
        LocalDate from = parseDate(fromRaw);
        LocalDate to = parseDate(toRaw);
        String fromS = from == null ? null : from.toString();
        String toS = to == null ? null : to.toString();
        int requested = parsePage(pageRaw);

        MapSqlParameterSource p = new MapSqlParameterSource("u", userId);
        StringBuilder where = new StringBuilder(" WHERE l.user_id = :u");
        if (rule != null) {
            where.append(" AND l.rule_code = :rule");
            p.addValue("rule", rule);
        }
        if (from != null) {
            where.append(" AND l.created_at >= :from");
            p.addValue("from", Timestamp.from(from.atStartOfDay(ScheduleWindow.WIB).toInstant()));
        }
        if (to != null) {
            where.append(" AND l.created_at < :to");
            p.addValue("to", Timestamp.from(to.plusDays(1).atStartOfDay(ScheduleWindow.WIB).toInstant()));
        }
        Long countObj = jdbc.queryForObject(COUNT_SELECT + where, p, Long.class);
        long count = countObj == null ? 0 : countObj;
        boolean filtered = rule != null || from != null || to != null;
        boolean everAny = count > 0 || (filtered && everAny(userId));
        int totalPages = (int) Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(requested, totalPages - 1);
        if (count == 0) {
            return new Ledger(userId, List.of(), 0, 1, 0, everAny, rule, fromS, toS);
        }
        p.addValue("limit", PAGE_SIZE).addValue("offset", (long) page * PAGE_SIZE);
        List<Raw> raws = jdbc.query(PAGE_SELECT + where + " ORDER BY l.created_at DESC, l.id DESC "
                + "LIMIT :limit OFFSET :offset", p, (rs, i) -> raw(rs));
        return new Ledger(userId, toRows(raws, userId), page, totalPages, count, everAny, rule, fromS, toS);
    }

    private boolean everAny(long userId) {
        Long n = jdbc.queryForObject(EVER_SQL, new MapSqlParameterSource("u", userId), Long.class);
        return n != null && n > 0;
    }

    private List<LedgerRow> toRows(List<Raw> raws, long userId) {
        Map<Long, String> operators = operatorNames(raws);
        Map<Long, String> pets = petNames(raws);
        Map<Long, String> tokens = intentTokens(raws);
        List<LedgerRow> rows = new ArrayList<>(raws.size());
        for (Raw r : raws) {
            String reversal = r.reversesId != null && r.reason != null && REVERSAL_REASONS.contains(r.reason)
                    ? r.reason : null;
            String operator = r.operatorAdminId == null ? null
                    : operators.getOrDefault(r.operatorAdminId, "#" + r.operatorAdminId);
            String refTypeKey = null;
            String refShown = r.refId;
            String refHref = null;
            if (r.refType != null) {
                if (KNOWN_REF_TYPES.contains(r.refType)) {
                    refTypeKey = "admin.v140.growth.ledger.ref." + r.refType;
                } else if (PURPOSES.contains(r.refType)) {
                    refTypeKey = "admin.payments.purpose." + r.refType;
                }
                Long intentId = intentId(r);
                if (intentId != null) {
                    // 🛡 支付单对外只用 public_token：展示与链接都不出现数字 intentId；换不到 token 则不给链接、不显示 id。
                    String token = tokens.get(intentId);
                    refShown = token;
                    refHref = token == null ? null : "/admin/payments/" + token + "/drawer";
                } else if (REF_CONTENT_POST.equals(r.refType) && parseLong(r.refId) != null) {
                    refHref = "/admin/content/" + parseLong(r.refId) + "/drawer";
                } else if (REF_GIFT_ORDER.equals(r.refType)) {
                    // UI I1：送礼单没有单条抽屉 → 跳「送礼记录」按本用户（送礼人 / 收礼主人）筛好的列表，该单就在其中。
                    if ("GIFT_SENT".equals(r.ruleCode)) {
                        refHref = "/admin/gift-orders?senderId=" + userId;
                    } else if ("GIFT_RECEIVED".equals(r.ruleCode)) {
                        refHref = "/admin/gift-orders?ownerId=" + userId;
                    }
                }
            }
            String petNames = null;
            if (!r.petIds.isEmpty()) {
                String joined = r.petIds.stream().map(pets::get).filter(n -> n != null)
                        .collect(Collectors.joining("、"));
                petNames = joined.isEmpty() ? null : joined;
            }
            rows.add(new LedgerRow(r.id, r.createdAt, r.ruleCode, reversal, r.note, operator, r.delta, r.refType,
                    refTypeKey, refShown, refHref, petNames));
        }
        return rows;
    }

    private Map<Long, String> operatorNames(List<Raw> raws) {
        Set<Long> ids = raws.stream().map(Raw::operatorAdminId).filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (AdminAccount a : adminAccounts.findAllById(ids)) {
            names.put(a.getId(), a.getDisplayName());
        }
        return names;
    }

    private Map<Long, String> petNames(List<Raw> raws) {
        Set<Long> ids = raws.stream().flatMap(r -> r.petIds.stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(PET_NAMES_SQL, new MapSqlParameterSource("ids", ids))) {
            names.put(((Number) row.get("id")).longValue(), (String) row.get("name"));
        }
        return names;
    }

    private Map<Long, String> intentTokens(List<Raw> raws) {
        Set<Long> ids = raws.stream().map(AdminGrowthUserService::intentId).filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> tokens = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(INTENT_TOKENS_SQL,
                new MapSqlParameterSource("ids", ids))) {
            tokens.put(((Number) row.get("id")).longValue(), (String) row.get("public_token"));
        }
        return tokens;
    }

    /** 现金 / QRIS 消费（及其扣回）才关联支付单：按（原）入账幂等键 {@code SPEND:PI:{intentId}} 认，不猜 ref_id。 */
    private static Long intentId(Raw r) {
        if (r.srcIdem == null || !r.srcIdem.startsWith(GrowthIdemKeys.SPEND_INTENT_PREFIX)) {
            return null;
        }
        return parseLong(r.srcIdem.substring(GrowthIdemKeys.SPEND_INTENT_PREFIX.length()));
    }

    // ------------------------------------------------------------------ 写

    /**
     * 调整预览（只校验 + 算前后值，不写库）：入参 → 参与判定 → 运营活动规则启用 → 累计不得 &lt; 0。失败 422（文案码）。
     */
    @Transactional(readOnly = true)
    public Preview previewAdjust(long userId, String typeRaw, String deltaRaw, String note, String grantToken) {
        AdjustType type = AdjustType.fromOrNull(typeRaw);
        Integer delta = parseDelta(deltaRaw);
        GrowthLedgerService.validateAdjust(type, delta, note, grantToken);
        ledgerService.requireAdjustable(userId);
        if (type == AdjustType.OPS_CAMPAIGN && !ledgerService.campaignEnabled()) {
            throw AppException.validation("「运营活动」规则已停用").code("admin.err.growth.campaignDisabled");
        }
        UserRow u = user(userId);
        long oldTotal = u.lifetimeTotal == null ? 0 : u.lifetimeTotal;
        int oldLevel = u.level == null ? 1 : u.level;
        long newTotal = oldTotal + delta;
        if (newTotal < 0) {
            throw AppException.validation("调整后累计不能小于 0").code("admin.err.growth.belowZero", oldTotal);
        }
        int newLevel = levelFor(levels.findAllByOrderByLevelAsc(), newTotal);
        return new Preview(userId, u.name, type, delta, note.trim(), grantToken.trim(), oldTotal, newTotal, oldLevel,
                newLevel);
    }

    /** 入账 + 审计（同一事务；重复提交不写审计）。 */
    @Transactional
    public AdminAdjustCommand.Result adjust(long userId, String typeRaw, String deltaRaw, String note,
            String grantToken, long adminId) {
        AdjustType type = AdjustType.fromOrNull(typeRaw);
        Integer delta = parseDelta(deltaRaw);
        GrowthLedgerService.validateAdjust(type, delta, note, grantToken);
        AdminAdjustCommand.Result r = ledgerService.adjust(
                new AdminAdjustCommand(userId, type, delta, note, adminId, grantToken));
        if (r.replayed()) {
            return r;
        }
        audit.record(adminId, AuditActions.GROWTH_USER_ADJUSTED, "USER", String.valueOf(userId),
                "类型=" + type.name() + "；增减=" + signed(delta) + "；累计 " + r.oldTotal() + "→" + r.newTotal()
                        + "；等级 " + r.oldLevel() + "→" + r.newLevel() + "；活动/原因=" + note.trim()
                        + "；幂等键=" + GrowthIdemKeys.admin(grantToken.trim()));
        // 🛡 日志只记 userId / delta / 升降级（note 是运营手填的文字，可能误含隐私，不进日志）。
        log.info("growth admin adjust userId={} delta={} levelChange={}", userId, delta,
                Integer.compare(r.newLevel(), r.oldLevel()));
        return r;
    }

    // ------------------------------------------------------------------ 工具

    /** 带符号的增减值：{@code +5} / {@code -1}。 */
    public static String signed(long delta) {
        return delta > 0 ? "+" + delta : String.valueOf(delta);
    }

    private UserRow user(long userId) {
        List<UserRow> rows = jdbc.query(USER_SQL, new MapSqlParameterSource("id", userId), (rs, i) -> new UserRow(
                rs.getString("account_type"), rs.getString("status"), rs.getBoolean("deleted"), rs.getString("name"),
                (Long) rs.getObject("lifetime_total", Long.class), (Integer) rs.getObject("level", Integer.class),
                (Integer) rs.getObject("display_level", Integer.class)));
        if (rows.isEmpty()) {
            throw AppException.notFound("用户不存在").code("admin.err.user.notFound");
        }
        return rows.get(0);
    }

    /** 段位 = 不高于该级的最高一行的段位（与 {@code GrowthQueryService.BADGES_SQL} 同口径；展示等级可能超出当前表）。 */
    static GrowthTier tierOf(List<GrowthLevelDef> defs, int level) {
        GrowthTier t = GrowthTier.NEWBIE;
        int best = Integer.MIN_VALUE;
        for (GrowthLevelDef d : defs) {
            if (d.getLevel() <= level && d.getLevel() > best) {
                best = d.getLevel();
                t = d.getTier();
            }
        }
        return t;
    }

    /** 与 {@code GrowthLevelChangeHandler.levelFor} 同口径：{@code max(level) where threshold <= total}，无 = 1。 */
    static int levelFor(List<GrowthLevelDef> defs, long total) {
        return defs.stream().filter(d -> d.getThreshold() <= total).mapToInt(GrowthLevelDef::getLevel).max()
                .orElse(1);
    }

    /** 非整数 / 溢出 → 422（不让 400 ProblemDetail JSON 被 htmx 换进错误槽）。空 → null（交给「不能为 0」）。 */
    static Integer parseDelta(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw AppException.validation("增减值须为整数").code("admin.err.growth.deltaInvalid");
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static int parsePage(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static Raw raw(ResultSet rs) throws SQLException {
        return new Raw(rs.getLong("id"), rs.getString("rule_code"), rs.getInt("delta"), rs.getString("reason"),
                rs.getString("note"), (Long) rs.getObject("operator_admin_id", Long.class), rs.getString("ref_type"),
                rs.getString("ref_id"), (Long) rs.getObject("reverses_id", Long.class), petIds(rs.getArray("pet_ids")),
                rs.getString("src_idem"), rs.getTimestamp("created_at").toInstant());
    }

    private static List<Long> petIds(Array arr) throws SQLException {
        if (arr == null) {
            return List.of();
        }
        Object[] values = (Object[]) arr.getArray();
        List<Long> ids = new ArrayList<>(values.length);
        for (Object v : values) {
            if (v instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        return ids;
    }

    private record UserRow(String accountType, String status, boolean deleted, String name, Long lifetimeTotal,
            Integer level, Integer displayLevel) {
    }

    /** 包级可见：L0 测试直接喂行。 */
    record Raw(long id, String ruleCode, int delta, String reason, String note, Long operatorAdminId,
            String refType, String refId, Long reversesId, List<Long> petIds, String srcIdem, Instant createdAt) {
    }
}
