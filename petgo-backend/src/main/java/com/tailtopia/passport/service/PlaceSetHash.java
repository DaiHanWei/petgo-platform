package com.tailtopia.passport.service;

import com.tailtopia.place.service.PlaceIdentityQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 章集合 hash —— <b>唯一定义</b>（V1.3.2 Story 3.4 · AC2）。版本 = 场所集合：不看次数、不看章面图。
 *
 * <p>① 每个 placeId 沿 {@code merged_into_id} 解析到最终场所（DELISTED / 软删照常计入原 id）；
 * ② 去重 → 升序 → {@code ,} 连接十进制 id → SHA-256 → 小写 hex（64 位）。
 *
 * <p>🔴 购买判定与读取判定调用<b>同一个函数现算</b>；落库的 {@code place_set_hash} 只作记录、不参与比较 ——
 * 合并场所会改 place_id，落库值会过期。
 */
@Component
public class PlaceSetHash {

    private final PlaceIdentityQuery places;

    public PlaceSetHash(PlaceIdentityQuery places) {
        this.places = places;
    }

    /** 现算。空集合 → {@link IllegalArgumentException}（无章不出购买，也不该有版本）。 */
    public String of(Collection<Long> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            throw new IllegalArgumentException("empty place set");
        }
        Map<Long, Long> finalOf = places.resolveFinal(placeIds);
        return digest(placeIds.stream().map(id -> finalOf.getOrDefault(id, id)).toList());
    }

    /** 已解析到最终场所的 id → hash（纯函数）。 */
    static String digest(Collection<Long> finalIds) {
        if (finalIds.isEmpty()) {
            throw new IllegalArgumentException("empty place set");
        }
        String joined = finalIds.stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
