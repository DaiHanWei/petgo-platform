package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBadge;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询用于<b>权益判断</b>的用户等级（V1.4.0 ① Tailrent Level 对外契约，Story 0.1 地基定义，Story 1.1 换方法体）。
 *
 * <p>🔴 <b>签名是 ①②③ 的契约：① 只替换方法体，不改签名、不改包名；②③ 只调用，不做任何替身、不自写等级判断。</b>
 * 调用方：② 计算宠物名额、③ 判断专属礼物能否送、Feed 作者等级加权。
 *
 * <p>读落库的 {@code growth_accounts.level} 与等级表段位，不现算。参与口径见 {@link GrowthParticipation}：
 * 虚拟号、官方号（{@code role = ADMIN}，P-7）、查无此人、已注销、已停用 → {@link GrowthLevel#NOT_PARTICIPATING}；
 * 无账户行的真实用户 → Lv1 / {@link GrowthTier#NEWBIE}。展示用等级（含虚拟号 / 官方号的展示等级）不走这里，
 * 走 {@link #getBadges}（Story 3.1）。
 */
@Service
@Transactional(readOnly = true)
public class GrowthQueryService {

    static final String LEVELS_SQL = "SELECT u.id, " + GrowthParticipation.ACTIVE_REGULAR_USER + " AS participating, "
            + "COALESCE(a.level, 1) AS level, l.tier "
            + "FROM users u "
            + "LEFT JOIN growth_accounts a ON a.user_id = u.id "
            + "LEFT JOIN growth_levels l ON l.level = COALESCE(a.level, 1) "
            + "WHERE u.id IN (:ids)";

    /**
     * 展示用作者等级（Story 3.1）：一条 SQL，不随 id 个数增长。只取未注销、未停用的真实用户（{@code role = USER}、
     * {@code REAL}）与「展示等级」账号（虚拟号、官方号 {@code role = ADMIN}，P-7）；兽医等其他角色不出现。
     * 段位取「≤ 该等级的最高一行」的 {@code tier}，等级表追加或展示等级越过表尾时也有段位。
     */
    static final String BADGES_SQL = "SELECT b.id, b.real_user, b.level, b.display_missing, b.frame_choice, "
            + "(SELECT l.tier FROM growth_levels l WHERE l.level <= b.level ORDER BY l.level DESC LIMIT 1) AS tier "
            + "FROM (SELECT u.id, (u.role = 'USER' AND u.account_type = 'REAL') AS real_user, "
            + "CASE WHEN u.role = 'USER' AND u.account_type = 'REAL' THEN COALESCE(a.level, 1) "
            + "ELSE COALESCE(a.display_level, 1) END AS level, "
            + "(NOT (u.role = 'USER' AND u.account_type = 'REAL') AND a.display_level IS NULL) AS display_missing, "
            + "a.frame_choice "
            + "FROM users u LEFT JOIN growth_accounts a ON a.user_id = u.id "
            + "WHERE u.id IN (:ids) AND u.deleted_at IS NULL AND u.status = 'ACTIVE' "
            + "AND ((u.role = 'USER' AND u.account_type IN ('REAL', 'VIRTUAL')) OR u.role = 'ADMIN')) b";

    private static final Logger log = LoggerFactory.getLogger(GrowthQueryService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public GrowthQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 单个用户的当前等级。 */
    public GrowthLevel getLevel(long userId) {
        return getLevels(List.of(userId)).get(userId);
    }

    /** 批量版：一次查询；入参里每个 id 在返回 Map 中都有键（查无此人 → 不参与）。 */
    public Map<Long, GrowthLevel> getLevels(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, GrowthLevel> found = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(LEVELS_SQL, Map.of("ids", userIds))) {
            long id = ((Number) row.get("id")).longValue();
            if (!Boolean.TRUE.equals(row.get("participating"))) {
                found.put(id, GrowthLevel.NOT_PARTICIPATING);
                continue;
            }
            int level = ((Number) row.get("level")).intValue();
            String tier = (String) row.get("tier");
            // 等级表缺这一级（理论上不会：level 只会写成表里存在的值）→ 按最低段位兜底。
            found.put(id, GrowthLevel.of(level, tier == null ? GrowthTier.NEWBIE : GrowthTier.valueOf(tier)));
        }
        Map<Long, GrowthLevel> levels = new LinkedHashMap<>();
        for (Long id : userIds) {
            levels.put(id, found.getOrDefault(id, GrowthLevel.NOT_PARTICIPATING));
        }
        return levels;
    }

    /**
     * 批量<b>展示</b>查询（Story 3.1 · AD-14）：作者头像角标与头像框。只用于展示，权益判定一律走 {@link #getLevels}。
     *
     * <p>真实用户读落库等级（无账户行 = Lv1 / NEWBIE / 无框），框按 {@link GrowthFrameResolver} 读时推导；
     * 虚拟号与官方号读展示等级，框取该段位自动最高档（不看 {@code frame_choice}）。已注销、已停用、查无此人与其他角色
     * 不出现在返回 Map 中。空入参不发查询。
     */
    public Map<Long, GrowthBadge> getBadges(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, GrowthBadge> badges = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(BADGES_SQL, Map.of("ids", userIds))) {
            long id = ((Number) row.get("id")).longValue();
            boolean realUser = Boolean.TRUE.equals(row.get("real_user"));
            if (Boolean.TRUE.equals(row.get("display_missing"))) {
                log.warn("growth display_level missing, fallback Lv1: userId={}", id);
            }
            int level = ((Number) row.get("level")).intValue();
            String tierCode = (String) row.get("tier");
            GrowthTier tier = tierCode == null ? GrowthTier.NEWBIE : GrowthTier.valueOf(tierCode);
            FrameChoice choice = realUser ? frameChoiceOf((String) row.get("frame_choice")) : null;
            FrameChoice frame = GrowthFrameResolver.effectiveFrame(tier, choice);
            badges.put(id, new GrowthBadge(level, tier, frame == FrameChoice.NONE ? null : frame));
        }
        return badges;
    }

    private static FrameChoice frameChoiceOf(String code) {
        if (code == null) {
            return null;
        }
        try {
            return FrameChoice.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null; // 未知值按「自动」处理（CHECK 约束下理论不出现）
        }
    }
}
