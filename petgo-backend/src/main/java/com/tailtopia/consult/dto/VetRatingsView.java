package com.petgo.consult.dto;

import com.petgo.consult.domain.ConsultRating;
import java.time.Instant;
import java.util.List;

/**
 * 兽医历史评分聚合（Story 5.6，AC4，<b>仅运营可见</b>）。
 */
public record VetRatingsView(long vetId, double average, int count, List<Item> items) {

    public record Item(int stars, String comment, Instant createdAt) {
        static Item of(ConsultRating r) {
            return new Item(r.getStars(), r.getComment(), r.getCreatedAt());
        }
    }

    public static VetRatingsView of(long vetId, List<ConsultRating> ratings) {
        double avg = ratings.isEmpty() ? 0.0
                : ratings.stream().mapToInt(ConsultRating::getStars).average().orElse(0.0);
        // 保留一位小数
        double rounded = Math.round(avg * 10.0) / 10.0;
        return new VetRatingsView(vetId, rounded, ratings.size(),
                ratings.stream().map(Item::of).toList());
    }
}
