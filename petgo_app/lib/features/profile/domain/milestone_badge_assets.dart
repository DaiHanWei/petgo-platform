/// 里程碑徽章：完整 code → 语义键（V1.3.2 Story 5.1 · AD-15 · 代码核对报告 §3）。
///
/// 78 个 code 各一条、40 个语义键；同一语义跨物种共用一个键（Camilan：C-S8 / D-S8 / G-S6 → `first_treat`）。
///
/// 🔴 **只按完整 code 精确查表**：通用套编号与猫狗不对齐 —— `G-S6` 是零食、`G-S8` 是点赞，
/// 按后缀寻址会让通用宠物的零食徽章显示成点赞图。本文件与 `milestone_badge.dart` 不得出现任何字符串切分 / 正则
/// （源码扫描测试钉住）。
///
/// 映射是**语义事实**，一次写全、不随素材到货变；「有没有图」看 asset manifest（`MilestoneBadgeAssets`），
/// 所以往 `assets/milestone/` 放一枚 `<语义键>.webp` 即生效，不改代码。
/// 后端跨库测试 `MilestoneBadgeMappingTest` 钉住：code 集合 == `MilestoneCatalog` 全集、恰 40 个不同值。
const Map<String, String> kMilestoneBadgeKeys = {
  // ===== CAT (C) =====
  'C-S1': 'profile_created',
  'C-S2': 'first_calendar_photo',
  'C-S3': 'first_card_shared',
  'C-S4': 'first_vet_note',
  'C-S5': 'first_daily_post',
  'C-S6': 'first_bath',
  'C-S7': 'cat_first_nail_trim',
  'C-S8': 'first_treat',
  'C-S9': 'first_sleep_beside',
  'C-S10': 'cat_first_purr',
  'C-S11': 'cat_window_sunbath',
  'C-S12': 'cat_wand_play',
  'C-S13': 'cat_box_dive',
  'C-S14': 'first_comment',
  'C-S15': 'first_like',
  'C-S16': 'lulus_pemula',
  'C-M1': 'cat_first_adventure',
  'C-M2': 'first_car_ride',
  'C-M3': 'first_vaccination',
  'C-M4': 'first_deworming',
  'C-M5': 'first_vet_visit',
  'C-M6': 'cat_met_another_cat',
  'C-M7': 'cat_knows_name',
  'C-M8': 'together_30_days',
  'C-M9': 'spay_neuter',
  'C-M10': 'growth_records_10',
  'C-L1': 'first_birthday',
  'C-L2': 'together_100_days',
  'C-L3': 'together_365_days',
  'C-L4': 'all_health_milestones',
  'C-L5': 'growth_records_30',
  // ===== DOG (D) =====
  'D-S1': 'profile_created',
  'D-S2': 'first_calendar_photo',
  'D-S3': 'first_card_shared',
  'D-S4': 'first_vet_note',
  'D-S5': 'first_daily_post',
  'D-S6': 'first_bath',
  'D-S7': 'dog_first_grooming',
  'D-S8': 'first_treat',
  'D-S9': 'first_sleep_beside',
  'D-S10': 'dog_first_tail_wag',
  'D-S11': 'dog_collar_leash',
  'D-S12': 'dog_ball_play',
  'D-S13': 'dog_first_swim',
  'D-S14': 'first_comment',
  'D-S15': 'first_like',
  'D-S16': 'lulus_pemula',
  'D-M1': 'dog_first_walk',
  'D-M2': 'first_car_ride',
  'D-M3': 'first_vaccination',
  'D-M4': 'first_deworming',
  'D-M5': 'first_vet_visit',
  'D-M6': 'dog_met_another_dog',
  'D-M7': 'dog_first_command',
  'D-M8': 'together_30_days',
  'D-M9': 'spay_neuter',
  'D-M10': 'growth_records_10',
  'D-L1': 'first_birthday',
  'D-L2': 'together_100_days',
  'D-L3': 'together_365_days',
  'D-L4': 'all_health_milestones',
  'D-L5': 'growth_records_30',
  // ===== GENERAL (G) =====
  'G-S1': 'profile_created',
  'G-S2': 'first_calendar_photo',
  'G-S3': 'first_card_shared',
  'G-S4': 'first_vet_note',
  'G-S5': 'first_daily_post',
  'G-S6': 'first_treat',
  'G-S7': 'first_comment',
  'G-S8': 'first_like',
  'G-S9': 'lulus_pemula',
  'G-M1': 'first_vet_visit',
  'G-M2': 'first_health_check',
  'G-M3': 'together_30_days',
  'G-M4': 'growth_records_10',
  'G-L1': 'first_birthday',
  'G-L2': 'together_100_days',
  'G-L3': 'together_365_days',
};

/// 锁定态全局一张（素材清单 §5「D. 锁定态」）：不属于任何 code，**不暴露是哪一枚**。
const String kMilestoneBadgeLockedKey = 'locked';

/// 精确查表；不在表里（含空串）→ null。
String? milestoneBadgeKeyOf(String code) => kMilestoneBadgeKeys[code];

/// 语义键 → 素材路径。**全仓库唯一出现该目录字面量的地方**；同一张图等比缩放，没有大小两套。
String milestoneBadgeAssetPath(String key) => 'assets/milestone/$key.webp';
