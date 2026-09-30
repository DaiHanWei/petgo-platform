package com.tailtopia.place.service;

import com.tailtopia.place.repository.PlacePhotoRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final PlacePhotoRepository photos;
    private final PlacePhotoService photoService;

    public PlaceIdentityQuery(NamedParameterJdbcTemplate jdbc, PlaceStampQueryService stamps,
            PlacePhotoRepository photos, PlacePhotoService photoService) {
        this.jdbc = jdbc;
        this.stamps = stamps;
        this.photos = photos;
        this.photoService = photoService;
    }

    /**
     * 按 token 取场所 id（V1.3.2 Story 3.5）：<b>不看状态、不看软删</b> —— 登机牌 / 章是历史事实，下架 / 软删的卡仍在；
     * MERGED 由调用方再 {@link #resolveFinal} 到保留方。未知 token → empty。
     */
    @Transactional(readOnly = true)
    public Optional<Long> findIdByToken(String publicToken) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM places WHERE public_token = :t", Map.of("t", publicToken),
                Long.class);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 在调用方事务里对场所行加 {@code FOR SHARE} 锁并复核它仍是最终场所（V1.3.2 Story 3.5 复审）：
     * 与 {@code PlaceMergeService} 的 {@code FOR UPDATE} 互斥 —— 合并进行中则等它提交；返回 false = 已被并掉（调用方重解析）。
     * 🔴 不加 {@code @Transactional(readOnly)}：只读事务里 PG 不允许行锁；须在调用方的读写事务内调用。
     */
    public boolean lockIfFinal(long placeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT merged_into_id FROM places WHERE id = :id FOR SHARE", Map.of("id", placeId));
        return !rows.isEmpty() && rows.get(0).get("merged_into_id") == null;
    }

    /** 场所卡面信息（Story 3.5 登机牌详情）：token、城市、首张可见照片 URL（无 → null）。 */
    @Transactional(readOnly = true)
    public Optional<PlaceCardInfo> cardInfoOf(long placeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT public_token, city FROM places WHERE id = :id", Map.of("id", placeId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var ps = photos.findVisibleForPlaces(List.of(placeId));
        String photo = ps.isEmpty() ? null : photoService.publicUrlOf(ps.get(0));
        Map<String, Object> r = rows.get(0);
        return Optional.of(new PlaceCardInfo((String) r.get("public_token"), (String) r.get("city"), photo));
    }

    public record PlaceCardInfo(String publicToken, String city, String firstPhotoUrl) {
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
