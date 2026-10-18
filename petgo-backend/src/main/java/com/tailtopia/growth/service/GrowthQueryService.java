package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 无账户行的真实用户 → Lv1 / {@link GrowthTier#NEWBIE}。展示用等级（含虚拟号 / 官方号的展示等级）不走这里。
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
}
