/// 账号级里程碑编码（V1.4.2 social-topic Story 2.3 · FR-154 · 决策日志 R-4 / B1）—— **App 侧唯一实现**。
///
/// 「第一次关注一个人」（C-S19 / D-S19 / G-S12）与「第一次收藏帖子」（C-S20 / D-S20 / G-S13）：
/// 按账号只完成 / 只庆祝一次，后端扇到账号下所有 ACTIVE 宠物（后端对应 `AccountMilestoneFlag`，Story 2.1）。
///
/// 🔴 **显式列举、精确匹配，不按后缀切分**：通用清单是 G-S12 / G-S13，与猫狗的 S19 / S20 不对齐（决策日志 B1）。
/// 一致性由 `test/profile/milestone_account_v142_test.dart` 与 `kMilestoneBadgeKeys` 对账钉住。
library;

/// 第一次关注一个人（first_friend）的三物种编码。
const Set<String> _kFirstFriendCodes = {'C-S19', 'D-S19', 'G-S12'};

/// 第一次收藏帖子（first_save）的三物种编码。
const Set<String> _kFirstSaveCodes = {'C-S20', 'D-S20', 'G-S13'};

/// 6 个账号级里程碑完整编码。
const Set<String> kAccountMilestoneCodes = {..._kFirstFriendCodes, ..._kFirstSaveCodes};

/// [code] 是否账号级里程碑编码（6 个之一，精确匹配）。
bool isAccountMilestone(String code) => kAccountMilestoneCodes.contains(code);

/// 同一 flag 的三物种编码（含 [code] 自身）；非账号级编码 → `{code}`。
///
/// 账号级里程碑按账号只庆祝一次：猫狗混养账号在猫列表弹过 C-S19，狗列表的 D-S19 就是同一次庆祝，
/// 本地已庆祝集合须按这份全集记 / 撤（Story 2.3 · AC5.3）。
Set<String> accountSiblings(String code) {
  if (_kFirstFriendCodes.contains(code)) return _kFirstFriendCodes;
  if (_kFirstSaveCodes.contains(code)) return _kFirstSaveCodes;
  return {code};
}

/// [codes] 逐个按 [accountSiblings] 扩展后的并集。
Set<String> expandAccountSiblings(Iterable<String> codes) => {for (final c in codes) ...accountSiblings(c)};
