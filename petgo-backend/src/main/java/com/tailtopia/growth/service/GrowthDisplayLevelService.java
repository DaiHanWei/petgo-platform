package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.shared.error.AppException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 虚拟号 / 官方号的展示等级（V1.4.0 ① Story 2.2 · AD-13 · P-7）。展示等级只用于对外展示（角标 / 头像框），
 * 不参与成长值、不享任何权益。
 *
 * <p>分工：{@link #assignInitial}（Story 2.2，新建虚拟号时随机 6 / 7 / 8）；运营修改 {@link #set}、列表批量读
 * {@link #displayLevels}、下拉上限 {@link #maxLevel}（Story 4.4；审计由后台调用方写）。存量虚拟号 / 官方号由迁移
 * {@code V20261018_1503} 一次性分配。
 *
 * <p>写入纪律（§5.4）：只写 {@code display_level}，不碰累计、等级与流水。
 */
@Service
public class GrowthDisplayLevelService {

    static final int MIN_INITIAL = 6;
    static final int SPAN = 3;

    static final String ASSIGN_SQL = "INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, "
            + "display_level, backfill_popup_pending, version, created_at, updated_at) "
            + "VALUES (:u, 0, 0, 1, :d, false, 0, now(), now()) ON CONFLICT (user_id) DO NOTHING";

    /** 只写 display_level（无行补建；有行只改 display_level 与 updated_at，不碰累计 / 等级 / 待弹）。 */
    static final String SET_SQL = "INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, "
            + "display_level, backfill_popup_pending, version, created_at, updated_at) "
            + "VALUES (:u, 0, 0, 1, :d, false, 0, now(), now()) "
            + "ON CONFLICT (user_id) DO UPDATE SET display_level = EXCLUDED.display_level, updated_at = now()";

    static final String CURRENT_SQL = "SELECT display_level FROM growth_accounts WHERE user_id = :u";

    static final String MAX_LEVEL_SQL = "SELECT COALESCE(MAX(level), 1) FROM growth_levels";

    static final String LEVEL_OPTIONS_SQL = "SELECT level, tier FROM growth_levels ORDER BY level";

    /** 段位 = 不高于该级的最高一行的段位（同 {@code GrowthQueryService.BADGES_SQL}）。 */
    static final String DISPLAY_LEVELS_SQL = "SELECT a.user_id, a.display_level, (SELECT l.tier FROM growth_levels l "
            + "WHERE l.level <= a.display_level ORDER BY l.level DESC LIMIT 1) AS tier FROM growth_accounts a "
            + "WHERE a.user_id IN (:ids) AND a.display_level IS NOT NULL";

    /** 一个展示等级 + 其段位。 */
    public record DisplayLevel(int level, GrowthTier tier) {
    }

    /** 一次修改的结果。{@code changed = false}：值未变，没写库。 */
    public record Change(Integer oldLevel, int newLevel, boolean changed) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final IntSupplier random;

    @Autowired
    public GrowthDisplayLevelService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, () -> ThreadLocalRandom.current().nextInt(SPAN));
    }

    /** {@code random} 返回 [0, 3) 的整数。 */
    GrowthDisplayLevelService(NamedParameterJdbcTemplate jdbc, IntSupplier random) {
        this.jdbc = jdbc;
        this.random = random;
    }

    /** 在调用方事务内为新建的虚拟号建账户行，随机展示等级 6–8；已有行不覆盖。 */
    @Transactional
    public void assignInitial(long virtualUserId) {
        int level = MIN_INITIAL + Math.floorMod(random.getAsInt(), SPAN);
        jdbc.update(ASSIGN_SQL, Map.of("u", virtualUserId, "d", level));
    }

    /**
     * 运营修改展示等级（Story 4.4 · AD-1）：{@code 1 ≤ level ≤ max(growth_levels.level)}，越界 422；值未变不写。
     * 🔴 只写 {@code display_level}：不写流水、不调 {@code GrowthLevelChangeHandler}、不发事件 / 通知（AC12）。
     * 目标是否允许有展示等级（虚拟号 / 官方号）由调用方判定。
     */
    @Transactional
    public Change set(long userId, int level) {
        int max = maxLevel();
        if (level < 1 || level > max) {
            throw AppException.validation("展示等级须在 Lv1 ~ Lv" + max + " 之间")
                    .code("admin.err.growth.display.outOfRange", max);
        }
        Integer old = current(userId);
        if (old != null && old == level) {
            return new Change(old, level, false);
        }
        jdbc.update(SET_SQL, Map.of("u", userId, "d", level));
        return new Change(old, level, true);
    }

    /** 当前展示等级（无行 / 未初始化 = null）。 */
    @Transactional(readOnly = true)
    public Integer current(long userId) {
        List<Integer> v = jdbc.queryForList(CURRENT_SQL, Map.of("u", userId), Integer.class);
        return v.isEmpty() ? null : v.get(0);
    }

    /** 一批账号的展示等级与段位（一次查询）；无行 / 为空的不在结果里。 */
    @Transactional(readOnly = true)
    public Map<Long, DisplayLevel> displayLevels(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DisplayLevel> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(DISPLAY_LEVELS_SQL, Map.of("ids", List.copyOf(userIds)))) {
            String tier = (String) r.get("tier");
            out.put(((Number) r.get("user_id")).longValue(), new DisplayLevel(((Number) r.get("display_level")).intValue(),
                    tier == null ? GrowthTier.NEWBIE : GrowthTier.valueOf(tier)));
        }
        return out;
    }

    /** 当前等级表最高级（下拉随等级表追加自动变长）。 */
    @Transactional(readOnly = true)
    public int maxLevel() {
        Integer m = jdbc.queryForObject(MAX_LEVEL_SQL, Map.of(), Integer.class);
        return m == null ? 1 : m;
    }

    /** 下拉选项：等级表全部级 + 段位（Lv1 ~ 最高级）。 */
    @Transactional(readOnly = true)
    public List<DisplayLevel> levelOptions() {
        return jdbc.query(LEVEL_OPTIONS_SQL, Map.of(),
                (rs, i) -> new DisplayLevel(rs.getInt("level"), GrowthTier.valueOf(rs.getString("tier"))));
    }
}
