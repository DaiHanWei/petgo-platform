import 'milestone.dart';

/// V1.3.4 FR-126：两项「当场庆祝」里程碑的完整 code（三物种各一条；与后端 MilestoneAutoCompleteMap 一致）。
///
/// 🔴 一律显式集合，**不得**用后缀 / 正则拼 code（与 `milestone_badge_assets.dart` 同一纪律）。
const Set<String> kPlaceCheckinMilestoneCodes = {'C-S17', 'D-S17', 'G-S10'};
const Set<String> kTailsonalityMilestoneCodes = {'C-S18', 'D-S18', 'G-S11'};

/// 列表里属于 [codes] 的那一条（单宠清单只会命中一条）；没有 → null。
MilestoneItem? findMilestoneByCodes(MilestoneList list, Set<String> codes) {
  for (final g in list.groups) {
    for (final it in g.items) {
      if (codes.contains(it.code)) return it;
    }
  }
  return null;
}

/// 当场庆祝候选：命中 [codes]、`isUncelebrated`、且不在 [locallyCelebrated] 中 → 该条；否则 null。
MilestoneItem? pickInstantCelebration(
    MilestoneList list, Set<String> codes, Set<String> locallyCelebrated) {
  final it = findMilestoneByCodes(list, codes);
  if (it == null || !it.isUncelebrated || locallyCelebrated.contains(it.code)) return null;
  return it;
}

/// 预读判定：true = 不必轮询（不在列表 / 已完成且已庆祝 / 本机已庆祝）。
bool instantCelebrationSettled(
    MilestoneList list, Set<String> codes, Set<String> locallyCelebrated) {
  final it = findMilestoneByCodes(list, codes);
  if (it == null) return true;
  if (locallyCelebrated.contains(it.code)) return true;
  return it.completed && it.celebratedAt != null;
}
