import 'dart:ui' show Locale;

import 'content/ts_match_copy.dart';
import 'content/ts_text.dart';
import 'ts_match.dart';

/// 配型卡（9:16 分享卡）信息段数据（V1.3.2 Story 4.2 · 内容设计 §2.5 / §4.3 卡面拼接规则）。纯 Dart。
///
/// 🔴 差异句**不重写取句逻辑**：直接用 [computeTsMatch] 已算好的 `diffLineKeys`（优先级、条数都在 2.5 钉住），
/// 按键到 [kTsAxisDiffLines] 取文案；4/4 时键为空，改放档位总评（[kTsMatchTiers] `review`）首句。
class MatchShareCardData {
  const MatchShareCardData({
    required this.petName,
    required this.ownerName,
    required this.petCode,
    required this.ownerType,
    required this.sameCount,
    required this.tier,
    required this.tierName,
    required this.lines,
  });

  final String petName;

  /// 主人昵称；null → 名字行只显示宠物名（不兜底邮箱、不写 Kamu）。
  final String? ownerName;

  /// 宠物完整代号（带能量后缀，如 `ENTJ-H`）。
  final String petCode;

  /// 主人四字母（如 `INFP`）。
  final String ownerType;

  /// 相同字母数 0..4（= 埋点 `match_level`）。
  final int sameCount;

  /// 插画档号 1..5（[TsMatch.tier]）。
  final int tier;

  final String tierName;

  /// 信息段句子（最多 2 条）。
  final List<String> lines;

  /// 宠物四字母（不含能量后缀，= 埋点 `pet_type`）。
  String get petType => petCode.split('-').first;

  factory MatchShareCardData.from({
    required String petName,
    String? ownerName,
    required String petCode,
    required String ownerType,
    required Locale locale,
  }) {
    final pet4 = petCode.split('-').first;
    final m = computeTsMatch(ownerType, pet4);
    final tier = kTsMatchTiers[m.sameCount]!;
    final lines = m.diffLineKeys.isEmpty
        ? [firstSentence(tsFillPet(tier.review.of(locale), petName))]
        : [for (final k in m.diffLineKeys) kTsAxisDiffLines[k]!.of(locale)];
    final owner = ownerName?.trim();
    return MatchShareCardData(
      petName: petName,
      ownerName: owner == null || owner.isEmpty ? null : owner,
      petCode: petCode,
      ownerType: ownerType,
      sameCount: m.sameCount,
      tier: m.tier,
      tierName: tier.name.of(locale),
      lines: lines,
    );
  }

  /// 首句：到第一个后跟空白（或结尾）的 `.` / `!` / `?` 为止；去掉 `**` 加粗标记（卡面是纯文本）。
  static String firstSentence(String text) {
    final plain = text.replaceAll('**', '');
    final m = RegExp(r'^.*?[.!?](?=\s|$)', dotAll: true).firstMatch(plain);
    return (m?.group(0) ?? plain).trim();
  }
}
