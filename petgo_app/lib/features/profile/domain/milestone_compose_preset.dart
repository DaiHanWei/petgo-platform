import 'package:flutter/widgets.dart';

import '../../../l10n/app_localizations.dart';
import 'celebration_pet.dart';
import 'milestone.dart';
import 'milestone_account_codes.dart';
import 'milestone_titles.dart';

/// 里程碑卡短日期（V1.4.2 social-topic Story 3.1 · UI D7）：「28 Okt 2026」/「28 Oct 2026」。
///
/// 手写月份表（同 `milestone_celebration.dart` `_formatLongDate`），不依赖 intl locale 数据加载。
String formatMilestoneShortDate(DateTime d, Locale locale) {
  final local = d.toLocal();
  final months = locale.languageCode == 'id' ? _kShortMonthsId : _kShortMonthsEn;
  return '${local.day} ${months[local.month - 1]} ${local.year}';
}

const List<String> _kShortMonthsId = [
  'Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'
];
const List<String> _kShortMonthsEn = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
];

/// 里程碑庆祝页「Share to community」发布页预填（V1.4.2 social-topic Story 3.1 · PRD §3.6 交互 / §7 · UI D4 / D5）。
///
/// - 非账号级：预选 [petId]，正文单宠句式（[petName]）；
/// - 账号级（[isAccountMilestone]）：预选 [accountPets] 全部（调用方给的 ACTIVE、建档顺序、≤5 只）；
///   ≥ 2 只 → 「Kami / We」句式；只 1 只 → 用那只的名字走单宠句式；[accountPets] 为空（读失败）→ 退化为非账号级。
/// - `{title}` = [localizedMilestoneTitle]（与卡面一致）；**不带 `#话题`**。
/// V1.5.0 onboarding-recall Story 3.3 · AC4（E4）：账号级里程碑且账号下**没有正常（ACTIVE）宠物**（无账号宠物、无当前宠物）→
/// 发布页预选 **Moment**（不是 Diary：Diary 必须关联宠物），预填 `milestoneShareComposeNoPet`（不带宠物名 / 「Kami」）。
/// 其余照 1.4.2：Diary + 选中全部 ACTIVE 宠物 / 当前宠物。
({String text, List<int> petIds, bool moment}) milestoneComposePreset({
  required MilestoneItem item,
  required int petId,
  required String petName,
  required List<CelebrationPet> accountPets,
  required AppLocalizations l10n,
  required Locale locale,
}) {
  final title = localizedMilestoneTitle(item.code, locale);
  // 没有当前宠物（petId ≤ 0：账号级页补弹、账号下无正常宠物）才走 Moment；有当前宠物而账号宠物读失败（空表）
  // 仍退化为这一只 + 单宠句式（V1.4.2 既有口径）。
  if (isAccountMilestone(item.code) && accountPets.isEmpty && petId <= 0) {
    return (text: l10n.milestoneShareComposeNoPet(title), petIds: const <int>[], moment: true);
  }
  if (isAccountMilestone(item.code) && accountPets.isNotEmpty) {
    final ids = [for (final p in accountPets) ?p.id];
    final petIds = ids.isEmpty ? [petId] : ids;
    if (accountPets.length >= 2) {
      return (text: l10n.milestoneShareComposeMulti(title), petIds: petIds, moment: false);
    }
    return (text: l10n.milestoneShareComposeSingle(accountPets.single.name, title), petIds: petIds, moment: false);
  }
  return (text: l10n.milestoneShareComposeSingle(petName, title), petIds: [petId], moment: false);
}
