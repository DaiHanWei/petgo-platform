import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/utils/date_format.dart';
import '../../domain/pet_profile.dart';

/// 编辑页「Status Kesehatan」分组的表单状态（V1.5.0 健康记录提醒 Story 3.3，PRD FR-162）。
///
/// 编辑页只持有一个实例、进页时 [load]、保存时取 [changedNeuterStatus] 等「只发变化了的字段」——
/// 健康分组的状态全部收在这里，编辑页其余段落不必知道这些字段（④ 物种线也改这页，少碰少冲突）。
class PetHealthStatusDraft extends ChangeNotifier {
  String? _initialNeuter;
  bool? _initialCat;
  bool _initialPregnant = false;

  String? neuterStatus; // NEUTERED / INTACT / UNKNOWN；null = 没填（都不选中）
  bool? catInHome; // null = 没填
  bool pregnantNursing = false;

  /// 后端给的自动关闭日（只用于展示；保存后由刷新的档案带回新值）。
  DateTime? pregnantAutoOffDate;

  void load(PetProfile p) {
    _initialNeuter = neuterStatus = p.neuterStatus;
    _initialCat = catInHome = p.catInHome;
    _initialPregnant = pregnantNursing = p.pregnantNursing;
    pregnantAutoOffDate = p.pregnantAutoOffDate;
  }

  void setNeuter(String v) {
    neuterStatus = v;
    notifyListeners();
  }

  void setCatInHome(bool v) {
    catInHome = v;
    notifyListeners();
  }

  void setPregnant(bool v) {
    pregnantNursing = v;
    notifyListeners();
  }

  /// 变化了才返回（部分更新语义：null = 不改）。
  String? get changedNeuterStatus => neuterStatus != _initialNeuter ? neuterStatus : null;

  /// 只 DOG 才发；变化了才返回。
  bool? changedCatInHome(String? petType) =>
      petType == 'DOG' && catInHome != _initialCat ? catInHome : null;

  /// 只在怀孕项可见（sex 为 FEMALE 或未填）时发；变化了才返回。
  bool? changedPregnant(String? sex) =>
      PetHealthStatusSection.showsPregnant(sex) && pregnantNursing != _initialPregnant
          ? pregnantNursing
          : null;
}

/// 「Status Kesehatan」分组（V1.5.0 健康记录提醒 Story 3.3，PRD FR-162，UI 稿 F1 ~ F5）。
///
/// 三项等分切换（样式同 Diary 的 Linimasa / Kalender 切换）：
/// - STATUS STERIL：CAT / DOG；
/// - ADA KUCING DI RUMAH?：只 DOG；
/// - HAMIL / MENYUSUI：只在 sex = FEMALE 或未填时。
/// 物种非 CAT / DOG（含 ④ 物种线新增的物种）→ 整个分组不显示。
class PetHealthStatusSection extends StatelessWidget {
  const PetHealthStatusSection({
    super.key,
    required this.draft,
    required this.petType,
    required this.sex,
  });

  final PetHealthStatusDraft draft;
  final String? petType;

  /// 编辑页**当前**选择的性别（改成 MALE 时怀孕项立即隐藏）。
  final String? sex;

  static bool showsGroup(String? petType) => petType == 'CAT' || petType == 'DOG';

  static bool showsPregnant(String? sex) => sex == null || sex == 'FEMALE';

  @override
  Widget build(BuildContext context) {
    if (!showsGroup(petType)) return const SizedBox.shrink();
    final l10n = AppLocalizations.of(context);
    return ListenableBuilder(
      listenable: draft,
      builder: (context, _) {
        final offDate = draft.pregnantAutoOffDate;
        return Column(
          key: const ValueKey('petHealthStatusSection'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.petHealthStatusTitle,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 2),
            Text(l10n.petHealthStatusDesc,
                style: const TextStyle(fontSize: 11.5, color: AppColors.textTertiary)),
            const SizedBox(height: 12),
            _label(l10n.petHealthNeuterLabel),
            const SizedBox(height: 6),
            _segments(
              keyPrefix: 'petHealthNeuter',
              options: [
                ('NEUTERED', l10n.petHealthNeutered),
                ('INTACT', l10n.petHealthIntact),
                ('UNKNOWN', l10n.petHealthNeuterUnknown),
              ],
              selected: draft.neuterStatus,
              onTap: draft.setNeuter,
            ),
            if (petType == 'DOG') ...[
              const SizedBox(height: 14),
              _label(l10n.petHealthCatAtHomeLabel),
              const SizedBox(height: 6),
              _segments(
                keyPrefix: 'petHealthCatAtHome',
                options: [('true', l10n.petHealthYes), ('false', l10n.petHealthNo)],
                selected: draft.catInHome?.toString(),
                onTap: (v) => draft.setCatInHome(v == 'true'),
              ),
            ],
            if (showsPregnant(sex)) ...[
              const SizedBox(height: 14),
              _label(l10n.petHealthPregnantLabel),
              const SizedBox(height: 6),
              _segments(
                keyPrefix: 'petHealthPregnant',
                options: [('true', l10n.petHealthYes), ('false', l10n.petHealthNo)],
                selected: draft.pregnantNursing.toString(),
                onTap: (v) => draft.setPregnant(v == 'true'),
              ),
              if (draft.pregnantNursing && offDate != null) ...[
                const SizedBox(height: 6),
                Text(
                  l10n.petHealthPregnantAutoOff(formatDayMonthYear(context, offDate)),
                  key: const ValueKey('petHealthPregnantAutoOff'),
                  style: const TextStyle(fontSize: 11.5, color: AppColors.textTertiary),
                ),
              ],
            ],
          ],
        );
      },
    );
  }

  /// 同编辑页 `_sectionLabel`：小号粗体、一律大写。
  Widget _label(String text) => Text(
        text.toUpperCase(),
        style: const TextStyle(
            fontSize: 11.5, fontWeight: FontWeight.w700, letterSpacing: 0.5, color: AppColors.ink2),
      );

  Widget _segments({
    required String keyPrefix,
    required List<(String, String)> options,
    required String? selected,
    required void Function(String) onTap,
  }) {
    return Row(
      children: [
        for (var i = 0; i < options.length; i++) ...[
          if (i > 0) const SizedBox(width: 7),
          Expanded(
            child: _segment(
              key: ValueKey('${keyPrefix}_${options[i].$1}'),
              label: options[i].$2,
              on: selected == options[i].$1,
              onTap: () => onTap(options[i].$1),
            ),
          ),
        ],
      ],
    );
  }

  /// 同 Diary「Linimasa / Kalender」等分按钮：视觉 ~38 高、点击热区 ≥44。
  Widget _segment({required Key key, required String label, required bool on, required VoidCallback onTap}) =>
      Semantics(
        button: true,
        selected: on,
        child: GestureDetector(
          key: key,
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minHeight: 44),
            alignment: Alignment.center,
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 9, horizontal: 4),
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: on ? AppColors.mint : AppColors.card,
                borderRadius: BorderRadius.circular(10),
                border: on ? null : Border.all(color: AppColors.line, width: 1.5),
              ),
              child: Text(label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 12, fontWeight: FontWeight.w600, color: on ? AppColors.onAccent : AppColors.ink2)),
            ),
          ),
        ),
      );
}
