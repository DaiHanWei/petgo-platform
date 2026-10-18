import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../profile/data/pet_list_repository.dart';
import '../data/pet_slot_repository.dart';
import 'pet_slot_pet_row.dart';

/// D13 改选「保持正常」的宠物（V1.4.0 multi-pet Story 2.4 · AC4）：**只**从名额管理页「Ubah pilihan ›」进
/// （2026-10-15 拍板：B4 横幅 / E1 抽屉不放改选入口）。
///
/// 骨架参照 `place_list_page.dart` 的 `_FilterSheet`：把手 → 粗体标题 → 说明 → 勾选行 → 底部「{n} / {total}」+「Simpan」。
/// 默认勾当前 ACTIVE 的宠物；勾满名额数后其余行不可再勾；未选满 Simpan 禁用。
/// 保存成功 → 关抽屉、返回 true（调用方刷新 + Toast D-32）；失败 → 抽屉保持、顶部 Toast 可重试。
Future<bool> showActiveSelectionSheet(
  BuildContext context, {
  required List<PetListItem> pets,
  required int slotTotal,
}) async {
  final saved = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.card,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => ActiveSelectionSheet(pets: pets, slotTotal: slotTotal),
  );
  return saved ?? false;
}

class ActiveSelectionSheet extends ConsumerStatefulWidget {
  const ActiveSelectionSheet({super.key, required this.pets, required this.slotTotal});

  final List<PetListItem> pets;
  final int slotTotal;

  @override
  ConsumerState<ActiveSelectionSheet> createState() => _ActiveSelectionSheetState();
}

class _ActiveSelectionSheetState extends ConsumerState<ActiveSelectionSheet> {
  late final Set<int> _selected = {
    for (final p in widget.pets.where((p) => !p.isReadOnly).take(widget.slotTotal)) p.id,
  };
  bool _saving = false;

  bool get _full => _selected.length >= widget.slotTotal;

  void _toggle(int id) {
    setState(() {
      if (_selected.contains(id)) {
        _selected.remove(id);
      } else if (!_full) {
        _selected.add(id);
      }
    });
  }

  Future<void> _save() async {
    if (_saving || _selected.length != widget.slotTotal) return;
    final l10n = AppLocalizations.of(context);
    setState(() => _saving = true);
    try {
      // 按列表顺序提交（最早建档在前），与后端 reconcile 顺序键一致、请求可复现。
      final ids = [for (final p in widget.pets) if (_selected.contains(p.id)) p.id];
      await ref.read(petSlotRepositoryProvider).changeSelection(ids);
      if (mounted) Navigator.of(context).pop(true);
    } catch (_) {
      if (!mounted) return;
      setState(() => _saving = false);
      showAppToast(context, l10n.petProfileSaveFailed,
          top: true, actionLabel: l10n.commonRetry, onAction: _save);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final canSave = !_saving && _selected.length == widget.slotTotal;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 6, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration:
                    BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            const SizedBox(height: 16),
            Text(l10n.petSlotSelectTitle,
                style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Text(l10n.petSlotSelectNote(widget.slotTotal),
                style: const TextStyle(color: AppColors.ink2, fontSize: 13, height: 1.5)),
            const SizedBox(height: 12),
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  children: [
                    for (final p in widget.pets)
                      InkWell(
                        key: ValueKey('petSlotSelectRow-${p.id}'),
                        borderRadius: BorderRadius.circular(12),
                        onTap: (_selected.contains(p.id) || !_full) && !_saving ? () => _toggle(p.id) : null,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: PetSlotPetRow(
                            pet: p,
                            showLock: false,
                            trailing: Icon(
                              _selected.contains(p.id) ? Icons.check_box : Icons.check_box_outline_blank,
                              key: ValueKey('petSlotSelectCheck-${p.id}'),
                              color: _selected.contains(p.id)
                                  ? AppColors.mint
                                  : (_full ? AppColors.line : AppColors.ink2),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: Text(l10n.petSlotSelectCount(_selected.length, widget.slotTotal),
                      key: const ValueKey('petSlotSelectCount'),
                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink2)),
                ),
                FilledButton(
                  key: const ValueKey('petSlotSelectSave'),
                  onPressed: canSave ? _save : null,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent,
                    padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 12),
                  ),
                  child: _saving
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.onAccent))
                      : Text(l10n.commonSave),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
