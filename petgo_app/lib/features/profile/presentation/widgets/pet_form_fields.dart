import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/pet_breeds.dart';

/// 物种展示文案（emoji + 本地化名）。
String petTypeDisplay(AppLocalizations l10n, String type) => switch (type) {
      'CAT' => '🐱 ${l10n.petTypeCat}',
      'DOG' => '🐶 ${l10n.petTypeDog}',
      _ => '🐾 ${l10n.petTypeOther}',
    };

/// JENIS HEWAN 选择字段（原型 P-30/P-32）。
/// [locked]=true → 灰底锁定只读（编辑态 F6：创建后不可改）；否则点击弹底部清单按枚举选。
class SpeciesField extends StatelessWidget {
  const SpeciesField({super.key, required this.petType, this.onChanged, this.locked = false});

  final String? petType;
  final ValueChanged<String>? onChanged;
  final bool locked;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final has = petType != null;
    return InkWell(
      key: const ValueKey('petProfileSpeciesField'),
      onTap: locked ? null : () => _pick(context, l10n),
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
                has ? petTypeDisplay(l10n, petType!) : l10n.petProfileTypePick,
                style: TextStyle(
                    fontSize: 14,
                    color: !has
                        ? AppColors.muted
                        : (locked ? AppColors.textTertiary : AppColors.ink)),
              ),
            ),
            Icon(locked ? Icons.lock_outline : Icons.keyboard_arrow_down,
                size: locked ? 15 : 18,
                color: locked ? const Color(0xFFCCCCCC) : AppColors.muted),
          ],
        ),
      ),
    );
  }

  Future<void> _pick(BuildContext context, AppLocalizations l10n) async {
    final picked = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final t in const ['CAT', 'DOG', 'OTHER'])
              ListTile(
                key: ValueKey('speciesOption_$t'),
                title: Text(petTypeDisplay(l10n, t)),
                onTap: () => Navigator.of(ctx).pop(t),
              ),
          ],
        ),
      ),
    );
    if (picked != null) onChanged?.call(picked);
  }
}

/// RAS 选择字段（原型 P-30/P-32）：按物种精选清单 + 「Lainnya」手填。
/// breed 真值存于 [controller]（自由 String，后端 VARCHAR60）；变更后回调 [onChanged] 通知父重建。
/// 未选物种 → 禁用（先选 JENIS HEWAN）；物种=OTHER → 直接手填（无清单）。
class BreedField extends StatelessWidget {
  const BreedField({
    super.key,
    required this.petType,
    required this.controller,
    required this.onChanged,
    this.fieldKey,
  });

  final String? petType;
  final TextEditingController controller;
  final VoidCallback onChanged;
  final Key? fieldKey;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final enabled = petType != null;
    final value = controller.text;
    final display = value.isNotEmpty
        ? value
        : (enabled ? l10n.petProfileBreedPick : l10n.petProfileBreedNeedType);
    return InkWell(
      key: fieldKey ?? const ValueKey('petProfileBreedField'),
      onTap: enabled ? () => _pick(context, l10n) : null,
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

  Future<void> _pick(BuildContext context, AppLocalizations l10n) async {
    final list = breedsForPetType(petType);
    // OTHER 物种：无清单 → 直接手填。
    if (list.isEmpty) {
      await _enterCustom(context, l10n);
      return;
    }
    final picked = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            for (final b in list)
              ListTile(
                key: ValueKey('breedOption_$b'),
                title: Text(b),
                onTap: () => Navigator.of(ctx).pop(b),
              ),
            ListTile(
              key: const ValueKey('breedOption_OTHER'),
              title: Text(l10n.petProfileBreedOther),
              onTap: () => Navigator.of(ctx).pop('__OTHER__'),
            ),
          ],
        ),
      ),
    );
    if (picked == null) return;
    if (picked == '__OTHER__') {
      if (context.mounted) await _enterCustom(context, l10n);
    } else {
      controller.text = picked;
      onChanged();
    }
  }

  /// 「Lainnya」手填：弹输入框（预填既有自定义值），保存后写回 controller。
  Future<void> _enterCustom(BuildContext context, AppLocalizations l10n) async {
    final isPreset = breedsForPetType(petType).contains(controller.text);
    final ctrl = TextEditingController(text: isPreset ? '' : controller.text);
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.petProfileBreedOther),
        content: TextField(
          key: const ValueKey('petProfileBreedCustomInput'),
          controller: ctrl,
          autofocus: true,
          maxLength: 60,
          decoration: InputDecoration(hintText: l10n.petProfileBreedHint),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: Text(l10n.commonCancel)),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(ctrl.text.trim()),
            child: Text(l10n.commonSave),
          ),
        ],
      ),
    );
    if (result != null) {
      controller.text = result;
      onChanged();
    }
  }
}
