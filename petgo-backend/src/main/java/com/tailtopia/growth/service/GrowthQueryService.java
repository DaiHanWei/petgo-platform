package com.tailtopia.growth.service;

import com.tailtopia.auth.domain.AccountType;
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
 * 查询用于<b>权益判断</b>的用户等级（V1.4.0 ① Tailrent Level 对外契约，Story 0.1 地基定义）。
 *
 * <p>🔴 <b>签名是 ①②③ 的契约：① 只替换方法体，不改签名、不改包名；②③ 只调用，不做任何替身、不自写等级判断。</b>
 * 调用方：② 计算宠物名额、③ 判断专属礼物能否送。
 *
 * <p><b>当前为占位实现</b>（① 合并前）：虚拟号（{@code account_type = VIRTUAL}）与查无此人（含已注销）→
 * {@link GrowthLevel#NOT_PARTICIPATING}；其余一律 Lv1 / {@link GrowthTier#NEWBIE}。
 * 因此 ① 合并前，② 的名额恒为「默认 1 + 月租」，③ 的专属礼物对所有人锁住 —— 与新用户的真实表现一致。
 */
@Service
@Transactional(readOnly = true)
public class GrowthQueryService {

    static final String ACCOUNT_TYPES_SQL =
            "SELECT id, account_type FROM users WHERE id IN (:ids) AND deleted_at IS NULL";

    private static final GrowthLevel PLACEHOLDER_LEVEL = GrowthLevel.of(1, GrowthTier.NEWBIE);

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
        Map<Long, String> accountTypes = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(ACCOUNT_TYPES_SQL, Map.of("ids", userIds))) {
            accountTypes.put(((Number) row.get("id")).longValue(), (String) row.get("account_type"));
        }
        Map<Long, GrowthLevel> levels = new LinkedHashMap<>();
        for (Long id : userIds) {
            String type = accountTypes.get(id);
            levels.put(id, type == null || AccountType.VIRTUAL.name().equals(type)
                    ? GrowthLevel.NOT_PARTICIPATING
                    : PLACEHOLDER_LEVEL);
        }
        return levels;
    }
}
