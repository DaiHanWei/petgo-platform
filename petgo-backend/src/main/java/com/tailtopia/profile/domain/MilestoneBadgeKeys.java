package com.tailtopia.profile.domain;

import java.util.Map;
import java.util.Optional;

/**
 * 里程碑徽章：完整 code → 语义键（V1.3.2 Story 5.2 · AD-15）。**以 App {@code kMilestoneBadgeKeys} 为准**
 * （{@code petgo_app/lib/features/profile/domain/milestone_badge_assets.dart}），跨库测试
 * {@code MilestoneBadgeMappingTest} 逐条比对两边，走散即红。
 *
 * <p>H5 分享页是服务端渲染、拿不到 App 的 Dart 表，所以这里各存一份（同「三处文案同步」先例）。
 *
 * <p>🔴 <b>只按完整 code 精确查表</b>：通用套编号与猫狗不对齐 —— {@code G-S6} 是零食、{@code G-S8} 是点赞。
 */
public final class MilestoneBadgeKeys {

    private MilestoneBadgeKeys() {
    }

    /** 78 个 code → 40 个语义键。 */
    public static final Map<String, String> KEYS = Map.ofEntries(
        // ===== CAT (C) =====
        Map.entry("C-S1", "profile_created"),
        Map.entry("C-S2", "first_calendar_photo"),
        Map.entry("C-S3", "first_card_shared"),
        Map.entry("C-S4", "first_vet_note"),
        Map.entry("C-S5", "first_daily_post"),
        Map.entry("C-S6", "first_bath"),
        Map.entry("C-S7", "cat_first_nail_trim"),
        Map.entry("C-S8", "first_treat"),
        Map.entry("C-S9", "first_sleep_beside"),
        Map.entry("C-S10", "cat_first_purr"),
        Map.entry("C-S11", "cat_window_sunbath"),
        Map.entry("C-S12", "cat_wand_play"),
        Map.entry("C-S13", "cat_box_dive"),
        Map.entry("C-S14", "first_comment"),
        Map.entry("C-S15", "first_like"),
        Map.entry("C-S16", "lulus_pemula"),
        Map.entry("C-M1", "cat_first_adventure"),
        Map.entry("C-M2", "first_car_ride"),
        Map.entry("C-M3", "first_vaccination"),
        Map.entry("C-M4", "first_deworming"),
        Map.entry("C-M5", "first_vet_visit"),
        Map.entry("C-M6", "cat_met_another_cat"),
        Map.entry("C-M7", "cat_knows_name"),
        Map.entry("C-M8", "together_30_days"),
        Map.entry("C-M9", "spay_neuter"),
        Map.entry("C-M10", "growth_records_10"),
        Map.entry("C-L1", "first_birthday"),
        Map.entry("C-L2", "together_100_days"),
        Map.entry("C-L3", "together_365_days"),
        Map.entry("C-L4", "all_health_milestones"),
        Map.entry("C-L5", "growth_records_30"),
        // ===== DOG (D) =====
        Map.entry("D-S1", "profile_created"),
        Map.entry("D-S2", "first_calendar_photo"),
        Map.entry("D-S3", "first_card_shared"),
        Map.entry("D-S4", "first_vet_note"),
        Map.entry("D-S5", "first_daily_post"),
        Map.entry("D-S6", "first_bath"),
        Map.entry("D-S7", "dog_first_grooming"),
        Map.entry("D-S8", "first_treat"),
        Map.entry("D-S9", "first_sleep_beside"),
        Map.entry("D-S10", "dog_first_tail_wag"),
        Map.entry("D-S11", "dog_collar_leash"),
        Map.entry("D-S12", "dog_ball_play"),
        Map.entry("D-S13", "dog_first_swim"),
        Map.entry("D-S14", "first_comment"),
        Map.entry("D-S15", "first_like"),
        Map.entry("D-S16", "lulus_pemula"),
        Map.entry("D-M1", "dog_first_walk"),
        Map.entry("D-M2", "first_car_ride"),
        Map.entry("D-M3", "first_vaccination"),
        Map.entry("D-M4", "first_deworming"),
        Map.entry("D-M5", "first_vet_visit"),
        Map.entry("D-M6", "dog_met_another_dog"),
        Map.entry("D-M7", "dog_first_command"),
        Map.entry("D-M8", "together_30_days"),
        Map.entry("D-M9", "spay_neuter"),
        Map.entry("D-M10", "growth_records_10"),
        Map.entry("D-L1", "first_birthday"),
        Map.entry("D-L2", "together_100_days"),
        Map.entry("D-L3", "together_365_days"),
        Map.entry("D-L4", "all_health_milestones"),
        Map.entry("D-L5", "growth_records_30"),
        // ===== GENERAL (G) =====
        Map.entry("G-S1", "profile_created"),
        Map.entry("G-S2", "first_calendar_photo"),
        Map.entry("G-S3", "first_card_shared"),
        Map.entry("G-S4", "first_vet_note"),
        Map.entry("G-S5", "first_daily_post"),
        Map.entry("G-S6", "first_treat"),
        Map.entry("G-S7", "first_comment"),
        Map.entry("G-S8", "first_like"),
        Map.entry("G-S9", "lulus_pemula"),
        Map.entry("G-M1", "first_vet_visit"),
        Map.entry("G-M2", "first_health_check"),
        Map.entry("G-M3", "together_30_days"),
        Map.entry("G-M4", "growth_records_10"),
        Map.entry("G-L1", "first_birthday"),
        Map.entry("G-L2", "together_100_days"),
        Map.entry("G-L3", "together_365_days"));

    /** 精确查表；未知 code（含 null）→ empty。 */
    public static Optional<String> keyOf(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(KEYS.get(code));
    }
}
