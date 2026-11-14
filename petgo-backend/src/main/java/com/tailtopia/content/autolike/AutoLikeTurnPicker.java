package com.tailtopia.content.autolike;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮里每位作者排哪几帖（V1.5.0 FR-214、A-AC43 / AC44）：纯函数。
 *
 * <p>按作者分组，组内按 {@code last_liked_at NULLS FIRST, post_created_at, post_id} 取前 {@code perAuthor} 条
 * （从没被赞过的先、再最久没被赞的——同一作者的几帖轮流）；入选的全部按 {@code post_created_at, post_id} 升序
 * （最老优先）。不设每轮上限（A-AC14）。
 */
public final class AutoLikeTurnPicker {

    private AutoLikeTurnPicker() {
    }

    /** 目标集里仍在集合的一行。 */
    public record OpenTarget(long postId, long authorId, Instant postCreatedAt, Instant lastLikedAt) {
    }

    private static final Comparator<OpenTarget> TURN_ORDER = Comparator
            .comparing(OpenTarget::lastLikedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(OpenTarget::postCreatedAt)
            .thenComparingLong(OpenTarget::postId);

    private static final Comparator<OpenTarget> OLDEST_FIRST = Comparator
            .comparing(OpenTarget::postCreatedAt)
            .thenComparingLong(OpenTarget::postId);

    public static List<OpenTarget> pick(List<OpenTarget> open, int perAuthor) {
        Map<Long, List<OpenTarget>> byAuthor = new LinkedHashMap<>();
        for (OpenTarget t : open) {
            byAuthor.computeIfAbsent(t.authorId(), k -> new ArrayList<>()).add(t);
        }
        List<OpenTarget> chosen = new ArrayList<>();
        for (List<OpenTarget> group : byAuthor.values()) {
            group.sort(TURN_ORDER);
            chosen.addAll(group.subList(0, Math.min(perAuthor, group.size())));
        }
        chosen.sort(OLDEST_FIRST);
        return chosen;
    }
}
