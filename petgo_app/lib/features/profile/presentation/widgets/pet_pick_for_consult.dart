import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../data/current_pet_provider.dart';
import '../../data/pet_list_repository.dart';
import 'pet_picker_sheet.dart';

/// 问诊 / 咨询选哪只宠物（V1.4.0 multi-pet Story 4.1 · UI 稿 H1 / H4）。
enum PetPickPurpose { triage, vetConsult }

/// [pickPetForConsult] 的结果：[pet] 为 null = 账号没有宠物（0 只，照现状继续、不带 petId）。
/// 函数本身返回 null 才表示**用户取消**（不进入后续页面）。
class PetPick {
  const PetPick(this.pet);

  final PetListItem? pet;

  int? get petId => pet?.id;
}

/// AI 问诊 / 兽医咨询前单选宠物（H1 / H4，复用 3.3 [PetPickerSheet] 单选模式，不另写抽屉）。
///
/// - 0 / 1 只 → **不弹**（H3，回归现状），直接返回那只（或 `PetPick(null)`）；单宠物也带它的 id（P-8：AI 用其上下文）；
/// - ≥ 2 只 → 弹单选抽屉：标题 / 说明按 [purpose]；只读行带锁 + H-3 副标题、**可选**（只读可问不可存档）；
///   默认选中 Diary 当前宠物；吸底「Lanjut」；下滑 / 返回 = 取消 → 返回 null。
/// - 宠物列表取不到（网络失败）→ 提示既有「加载失败，请重试」并返回 null（= 不进入后续页面，用户重点一次即重试）；
///   **不**悄悄按「不带 petId」放行（code review 4-1 A3：多宠用户会被落到默认宠物）。
///
/// ⚠️ 宠物名只用于界面渲染，不进日志 / 埋点（§5）。本 story 不做「选宠物」埋点（PRD §4 无此事件）。
Future<PetPick?> pickPetForConsult(
  BuildContext context,
  WidgetRef ref, {
  required PetPickPurpose purpose,
}) async {
  final List<PetListItem> pets;
  try {
    pets = await ref.read(petListProvider.future);
  } catch (_) {
    ref.invalidate(petListProvider); // 下次点击重新取，而不是复用失败结果
    if (context.mounted) showAppToast(context, AppLocalizations.of(context).detailNetworkError);
    return null;
  }
  if (pets.isEmpty) return const PetPick(null);
  if (pets.length == 1) return PetPick(pets.single);
  if (!context.mounted) return null;

  final l10n = AppLocalizations.of(context);
  final triage = purpose == PetPickPurpose.triage;
  final picked = await showPetPickerSheet(
    context,
    pets: pets,
    title: triage ? l10n.petPickerTriageTitle : l10n.petPickerConsultTitle,
    body: triage ? l10n.petPickerTriageHint : l10n.petPickerConsultHint,
    multiSelect: false,
    confirmLabel: l10n.petPickerContinue,
    initialSelectedId: resolveEffectivePetId(pets, ref.read(currentPetIdProvider)),
    lockedPetIds: {
      for (final p in pets)
        if (p.isReadOnly) p.id,
    },
    lockedSubtitle: l10n.petPickerReadOnlyNoSave,
  );
  if (picked == null || picked.isEmpty) return null;
  for (final p in pets) {
    if (p.id == picked.first) return PetPick(p);
  }
  return null;
}
