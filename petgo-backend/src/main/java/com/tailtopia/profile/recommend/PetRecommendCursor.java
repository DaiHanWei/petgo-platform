package com.tailtopia.profile.recommend;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 推荐集合页的翻页游标（V1.3.0 batch-b1 Story 4.3 · AC3）。
 *
 * <h2>🔴 游标必须是**整个排序键**，不是「最后那只宠物的 id」</h2>
 * AC1 的排序是三层：{@code (WIB 日期桶 DESC, 互动量 DESC, 最后发帖时刻 DESC, petId DESC)}。
 * 只带 petId 翻页等于假设按 id 排序 —— 那会让第二页从一个**与排序无关**的位置开始，
 * 表现是「第二页里重复出现第一页看过的宠物，同时另一批永远刷不到」。
 * 这正是 {@code KeysetCursor} 类注释里记下的那两次事故（NOTIFY-CURSOR-TIE / ORDER-CENTER-CURSOR-TIE）
 * 的同一形状，只不过这里的排序键更长。
 *
 * <h2>为什么不复用 {@link com.tailtopia.shared.paging.KeysetCursor}</h2>
 * 那个是 {@code (createdAt, id)} 二元组，**排序键不一样**（这里多了日期桶与互动量两层）。
 * 硬塞进去的唯一办法是把互动量丢掉，而丢掉的那一层正是 AC1 排序的一半。
 * 🔴 <b>日期桶不入游标</b>：它是 {@code lastPostedAt} 的函数（{@code date(… AT TIME ZONE 'Asia/Jakarta')}），
 * 由 SQL 从 {@link #lastPostedAt} 现算 —— 存两份的表现是「桶与时刻对不上时翻页跳一大段」。
 *
 * <p>⚠️ 微秒精度、用 {@code long} 存：与 {@code KeysetCursor} 同一条教训 ——
 * {@code Instant} 能表达纳秒而库里存不下，截断到毫秒会让「同刻」的判定连自己都对不上。
 *
 * <p>🔒 对外是 base64url 不可枚举串（与 {@code FeedCursor} / {@code KeysetCursor} 同一形态）：
 * 客户端<b>原样回传，不要解析、不要自己拼</b>。
 *
 * <p>⚠️ 推荐池是**实时算的**，两次请求之间排名会变（有人刚发帖、有人刚被点赞）。
 * keyset 分页在这种列表上的固有行为是「极少数宠物可能跨页重复或漏掉一次」——
 * 这是**已接受的代价**：替代方案是把整份排名快照下来，而那就是 AC2 禁止的缓存。
 *
 * <h2>V1.5.0 onboarding-recall Story 1.1 · OR-3：追加段号 {@link #tier}</h2>
 * 推荐改为三段 keyset（同品种 → 同物种其他品种 → 全站），游标须带上「最后看过的那一行在哪一段」。
 * 编码把 tier 追加在<b>末尾</b>（{@code <interactions>:<micros>:<petId>:<tier>}）：旧客户端手里的三段旧串
 * 照样能解（缺 tier → 段 0）；坏串仍按第一页。
 *
 * @param interactions 该行的互动量（点赞总数）
 * @param lastPostedAt 该行最后一条公开成长帖的时刻（<b>微秒</b>精度，UTC）
 * @param petId        宠物 id（最后一层 tie-breaker）
 * @param tier         该行所在段：0 同品种 / 1 同物种 / 2 全站（见 {@code PetRecommendationService}）
 */
public record PetRecommendCursor(long interactions, Instant lastPostedAt, long petId, int tier) {

    /** 段号上限（含）。 */
    static final int MAX_TIER = 2;

    /** 旧形态（V1.5.0 之前无段号）—— 等价于段 0。 */
    public PetRecommendCursor(long interactions, Instant lastPostedAt, long petId) {
        this(interactions, lastPostedAt, petId, 0);
    }

    /** 编码为对外 token：{@code base64url("<interactions>:<epochMicros>:<petId>:<tier>")}。 */
    public String encode() {
        long micros = lastPostedAt.getEpochSecond() * 1_000_000L + lastPostedAt.getNano() / 1_000L;
        String raw = interactions + ":" + micros + ":" + petId + ":" + tier;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码；任何非法形态返回 {@code null}。
     *
     * <p>🔴 <b>不抛异常</b>（同 {@code KeysetCursor}）：游标是客户端传回来的，坏值让整个列表
     * 400/500 是**把用户锁在门外**。调用方拿到 {@code null} 就当第一页处理 ——
     * 用户至少还看得到一页宠物。
     */
    public static PetRecommendCursor decodeOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 3 && parts.length != 4) {
                return null;
            }
            long interactions = Long.parseLong(parts[0]);
            long micros = Long.parseLong(parts[1]);
            long petId = Long.parseLong(parts[2]);
            // 旧串（V1.5.0 之前）无段号 → 段 0。
            int tier = parts.length == 4 ? Integer.parseInt(parts[3]) : 0;
            if (tier < 0 || tier > MAX_TIER) {
                return null;
            }
            return new PetRecommendCursor(interactions,
                    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                            Math.floorMod(micros, 1_000_000L) * 1_000L),
                    petId, tier);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
