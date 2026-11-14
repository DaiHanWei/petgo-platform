import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/pet_species.dart';
import '../../domain/pet_species_display.dart';
import 'species_picker_sheet.dart';

/// 物种展示文案（emoji + 本地化名）。V1.5.0 pet-species：转调统一函数 [petSpeciesView]；
/// 旧调用点不传 [species] 时按大类显示（Story 4.2 合并后补齐）。
String petTypeDisplay(AppLocalizations l10n, String type, {String? species}) =>
    petSpeciesView(species: species, petType: type).display(l10n);

/// JENIS HEWAN 选择字段（原型 P-30/P-32；V1.5.0 pet-species Story 3.2 起点开类型抽屉 [showPetSpeciesPicker]）。
/// [locked]=true → 灰底锁定只读（3.3：90 天冷却中）；否则点开类型抽屉，选中回调 [onChanged]。
/// 显示走统一函数：emoji + 宫格项名（「其他」组物种显示 Lainnya）。
class SpeciesField extends StatelessWidget {
  const SpeciesField({
    super.key,
    this.species,
    @Deprecated('V1.5.0：传 species（细分类型 wire）；Story 4.2 合并后清理') this.petType,
    this.onChanged,
    this.locked = false,
    this.lockedHint,
    this.scene = 'create',
  });

  /// 细分类型线上值（如 RABBIT / CIVET）；null = 未选。
  final String? species;

  /// 旧入参（大类）：别的线的旧调用点不传 [species] 时按大类显示。
  final String? petType;

  final ValueChanged<PetSpeciesPick>? onChanged;
  final bool locked;

  /// 锁定态框内右侧的红色说明（框内「🔒 …」）；null = 只画灰锁。
  final String? lockedHint;

  /// 埋点场景：create / edit / id_card。
  final String scene;

  // ignore: deprecated_member_use_from_same_package
  String? get _legacyPetType => petType;

  bool get _has => species != null || _legacyPetType != null;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final has = _has;
    return InkWell(
      key: const ValueKey('petProfileSpeciesField'),
      onTap: locked ? null : () => _pick(context),
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 14),
        decoration: BoxDecoration(
          color: locked ? const Color(0xFFF7F7F7) : AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.line, width: 1.5),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                has
                    ? petSpeciesView(species: species, petType: _legacyPetType).display(l10n)
                    : l10n.petProfileTypePick,
                style: TextStyle(
                    fontSize: 14,
                    color: !has
                        ? AppColors.muted
                        : (locked ? AppColors.textTertiary : AppColors.ink)),
              ),
            ),
            if (locked && lockedHint != null)
              Row(
                key: const ValueKey('petSpeciesLockedHint'),
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.lock, size: 12, color: AppColors.danger),
                  const SizedBox(width: 3),
                  Text(lockedHint!, style: const TextStyle(fontSize: 11.5, color: AppColors.danger)),
                ],
              )
            else
              Icon(locked ? Icons.lock_outline : Icons.keyboard_arrow_down,
                  size: locked ? 15 : 18,
                  color: locked ? const Color(0xFFCCCCCC) : AppColors.muted),
          ],
        ),
      ),
    );
  }

  Future<void> _pick(BuildContext context) async {
    final current = _has ? petSpeciesView(species: species, petType: _legacyPetType).code : null;
    final picked = await showPetSpeciesPicker(context, current: current, scene: scene);
    if (picked != null) onChanged?.call(picked);
  }
}

/// RAS 选择字段（原型 P-30/P-32；V1.5.0 pet-species Story 3.2 起点开品种抽屉 [showPetBreedPicker]，
/// 取代原「Lainnya」弹窗手填——清单外的值在抽屉里「Pakai "…"」直接用）。
/// breed 真值存于 [controller]（自由 String，后端 VARCHAR60）；选中写回 controller 后回调 [onChanged]，
/// 并把 [BreedPick] 交给 [onPicked]（「其他」里选了某组品种时，父页据 `pick.species` 更新细分类型）。
/// 未选类型 → 禁用（先选 JENIS HEWAN）。
class BreedField extends StatelessWidget {
  const BreedField({
    super.key,
    this.species,
    @Deprecated('V1.5.0：传 species（细分类型 wire）；Story 4.2 合并后清理') this.petType,
    required this.controller,
    required this.onChanged,
    this.onPicked,
    this.fieldKey,
    this.scene = 'create',
  });

  final String? species;
  final String? petType;
  final TextEditingController controller;
  final VoidCallback onChanged;
  final ValueChanged<BreedPick>? onPicked;
  final Key? fieldKey;
  final String scene;

  // ignore: deprecated_member_use_from_same_package
  String? get _legacyPetType => petType;

  PetSpeciesCode? get _type => species != null || _legacyPetType != null
      ? petSpeciesView(species: species, petType: _legacyPetType).code
      : null;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final enabled = _type != null;
    final value = controller.text;
    final display = value.isNotEmpty
        ? value
        : (enabled ? l10n.petProfileBreedPick : l10n.petProfileBreedNeedType);
    return InkWell(
      key: fieldKey ?? const ValueKey('petProfileBreedField'),
      onTap: enabled ? () => _pick(context) : null,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.line, width: 1.5),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(display,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 14, color: value.isEmpty ? AppColors.muted : AppColors.ink)),
            ),
            const Icon(Icons.keyboard_arrow_down, size: 18, color: AppColors.muted),
          ],
        ),
      ),
    );
  }

  Future<void> _pick(BuildContext context) async {
    final type = _type;
    if (type == null) return;
    final picked = await showPetBreedPicker(context,
        type: type, current: controller.text.isEmpty ? null : controller.text, scene: scene);
    if (picked == null) return;
    controller.text = picked.label;
    onPicked?.call(picked);
    onChanged();
  }
}
