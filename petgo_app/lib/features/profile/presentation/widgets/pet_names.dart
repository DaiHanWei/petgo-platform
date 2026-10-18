import '../../../../l10n/app_localizations.dart';

/// 多只宠物名字的拼接规则（V1.4.0 multi-pet Story 3.3 · AC4.6，发布页 F2 占位 / 详情页 F6 胶囊 / 打卡 G3 副标题共用）。
///
/// [names] 必须已按**宠物创建顺序**排好（唯一口径，与后端 `content_posts.pet_id`「第一只」一致）。
/// - 1 只：`{name}`（[withPrefix] → `diaryPetsWithOne`「bersama {name}」）；
/// - 2 只：`diaryPetsPair`「{first} & {second}」（[withPrefix] → `diaryPetsWithPair`）；
/// - ≥ 3 只：`diaryPetsMany`「{第一只} dkk.」（[withPrefix] → `diaryPetsWithMany`）。
///
/// [withPrefix] = 无图浅色胶囊（F6b）的「bersama …」句式。空表 → 空串。
/// ⚠️ 宠物名是用户数据，只用于渲染，不得写进日志 / 埋点（§5）。
String formatPetNames(AppLocalizations l10n, List<String> names, {bool withPrefix = false}) {
  if (names.isEmpty) return '';
  if (names.length == 1) return withPrefix ? l10n.diaryPetsWithOne(names.first) : names.first;
  if (names.length == 2) {
    return withPrefix
        ? l10n.diaryPetsWithPair(names[0], names[1])
        : l10n.diaryPetsPair(names[0], names[1]);
  }
  return withPrefix ? l10n.diaryPetsWithMany(names.first) : l10n.diaryPetsMany(names.first);
}
