package com.tailtopia.place.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所身份只读口（V1.3.2 Story 3.4）：沿 {@code places.merged_into_id} 解析到最终场所、按 id 取当前专属章 key。
 *
 * <p>{@code PlaceMergeService} 经 {@code repointMergedInto} 保证合并链恒为单跳；这里仍循环到
 * {@code merged_into_id IS NULL}（最多 {@link #MAX_HOPS} 跳）防御异常数据。
 * DELISTED / 软删的场所照常返回原 id（章仍在，1-2 口径）。
 */
@Service
public class PlaceIdentityQuery {

    static final int MAX_HOPS = 8;

    private final NamedParameterJdbcTemplate jdbc;
    private final PlaceStampQueryService stamps;

    public PlaceIdentityQuery(NamedParameterJdbcTemplate jdbc, PlaceStampQueryService stamps) {
        this.jdbc = jdbc;
        this.stamps = stamps;
    }

    /** placeId → 最终场所 id。库里不存在的 id 映射为自己（不丢章）。 */
    @Transactional(readOnly = true)
    public Map<Long, Long> resolveFinal(Collection<Long> placeIds) {
        Map<Long, Long> finalOf = new HashMap<>();
        for (Long id : placeIds) {
            finalOf.put(id, id);
        }
        Set<Long> pending = new HashSet<>(finalOf.values());
        for (int hop = 0; hop < MAX_HOPS && !pending.isEmpty(); hop++) {
            Map<Long, Long> next = mergedInto(pending);
            if (next.isEmpty()) {
                break;
            }
            for (Map.Entry<Long, Long> e : finalOf.entrySet()) {
                Long target = next.get(e.getValue());
                if (target != null) {
                    e.setValue(target);
                }
            }
            pending = new HashSet<>(next.values());
        }
        return finalOf;
    }

    /** placeId → 当前专属章公开 URL（无专属章的不在结果里；客户端按类型用默认章）。 */
    @Transactional(readOnly = true)
    public Map<Long, String> stampUrlsOf(Collection<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        jdbc.query("SELECT id, stamp_object_key FROM places WHERE id IN (:ids) AND stamp_object_key IS NOT NULL",
                Map.of("ids", placeIds), rs -> {
                    String url = stamps.stampUrlOf(rs.getString("stamp_object_key"));
                    if (url != null) {
                        out.put(rs.getLong("id"), url);
                    }
                });
        return out;
    }

    private Map<Long, Long> mergedInto(Collection<Long> ids) {
        Map<Long, Long> out = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, merged_into_id FROM places WHERE id IN (:ids) AND merged_into_id IS NOT NULL",
                Map.of("ids", ids));
        for (Map<String, Object> r : rows) {
            out.put(((Number) r.get("id")).longValue(), ((Number) r.get("merged_into_id")).longValue());
        }
        return out;
    }
}
