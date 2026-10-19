import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../profile/data/current_pet_provider.dart';
import '../../profile/data/pet_list_repository.dart';
import '../data/pet_slot_repository.dart';
import 'add_pet_flow.dart' show kPetSlotsRoute;

/// 只读宠物体验的共用入口（V1.4.0 multi-pet Story 2.4 · AC5 · UI 稿 B4 / E1）。
///
/// - [isCurrentPetReadOnly] / [currentPetIfLoaded]：**同步**判当前宠物是不是只读（前端先按 `status` 拦，不发请求）；
/// - [guardPetWritable]：新增类入口的一行接入 —— 只读 → 弹 E1、返回 false；
/// - [showPetReadonlyBlockSheet]：E1 统一拦截抽屉（只提供续租，**不放改选入口**，2026-10-15 拍板）；
/// - [handlePetReadOnly]：后端兜底 409 `pet-read-only` → 弹 E1 + 失效宠物列表；
/// - [openPetSlotRenewal]：B4 横幅 / E1 主按钮「续租」的唯一去向。

/// `/me/pet-slots?action=` 的取值：进页后直接弹租用 / 续租抽屉（页面据摘要决定，见 `PetSlotPage`）。
const String kPetSlotActionRent = 'rent';
const String kPetSlotActionRenew = 'renew';

/// 当前宠物的列表项（宠物列表与当前宠物都已解析时）；否则 null。
///
/// 🔴 只 `exists` 后再读：不为判只读去**新建** provider（那会额外发一次列表请求，测试里也会打到真 dio）。
/// 取不到 = 不知道 → 调用方一律放行，由后端 409 兜底。
PetListItem? currentPetIfLoaded(WidgetRef ref) {
  if (!ref.exists(petListProvider) || !ref.exists(effectivePetIdProvider)) return null;
  final pets = ref.read(petListProvider).value;
  final id = ref.read(effectivePetIdProvider).value;
  if (pets == null || id == null) return null;
  for (final p in pets) {
    if (p.id == id) return p;
  }
  return null;
}

/// 当前宠物是否只读（未知按「否」）。
bool isCurrentPetReadOnly(WidgetRef ref) => currentPetIfLoaded(ref)?.isReadOnly ?? false;

/// 新增类入口接入：当前宠物只读 → 弹 E1 并返回 false（调用方就此 return）；否则 true。
bool guardPetWritable(BuildContext context, WidgetRef ref) {
  final pet = currentPetIfLoaded(ref);
  if (pet == null || !pet.isReadOnly) return true;
  showPetReadonlyBlockSheet(context, ref, pet);
  return false;
}

/// E1 统一拦截抽屉：紫色锁 →「Slot {name} sudah berakhir」→ E-2 →「Perpanjang slot」/「Nanti saja」。
Future<void> showPetReadonlyBlockSheet(BuildContext context, WidgetRef ref, PetListItem pet) async {
  final l10n = AppLocalizations.of(context);
  final router = GoRouter.maybeOf(context);
  final renew = await showConfirmSheet(
    context,
    title: l10n.petReadonlyBlockTitle(pet.name),
    message: l10n.petReadonlyBlockBody(pet.name),
    confirmLabel: l10n.petSlotRenewSheetTitle,
    cancelLabel: l10n.pushSoftGuideLater,
    icon: Icons.lock_rounded,
    confirmKey: const ValueKey('petReadonlyBlockRenew'),
    cancelKey: const ValueKey('petReadonlyBlockLater'),
  );
  if (renew && router != null) openPetSlotRenewalWith(router, source: 'block_sheet');
}

/// 后端兜底（AC5.6）：[pd] 是 409 `pet-read-only` → 弹 E1（宠物名取扩展成员 `petId` 对应的列表项，
/// 取不到用当前宠物）、失效宠物列表（状态可能刚变），返回 true；其它错误返回 false（调用方照旧处理）。
bool handlePetReadOnly(BuildContext context, WidgetRef ref, ProblemDetail pd) {
  if (pd.typeSlug != kPetReadOnlySlug) return false;
  final petId = (pd.extensions['petId'] as num?)?.toInt();
  PetListItem? pet;
  final pets = ref.exists(petListProvider) ? ref.read(petListProvider).value : null;
  if (pets != null && petId != null) {
    for (final p in pets) {
      if (p.id == petId) pet = p;
    }
  }
  pet ??= currentPetIfLoaded(ref);
  ref.invalidate(petListProvider);
  if (context.mounted) {
    // 名字取不到（列表未加载）时仍要弹：用空名字的列表项占位，文案照常可读。
    showPetReadonlyBlockSheet(context, ref, pet ?? PetListItem(id: petId ?? 0, name: ''));
  }
  return true;
}

/// B4 横幅 / E1 主按钮「Perpanjang」：进名额管理页并自动弹抽屉（P-5 拍板）——
/// 有生效中月租 → D11 续租；没有（只读正是因为月租全过期）→ D5 新租、数量默认 = 缺的名额数（AD-6）。
/// 分流在页面拿到名额摘要后做（`?action=renew`）。上报 `pet_readonly_renew_tapped {source}`。
void openPetSlotRenewal(BuildContext context, WidgetRef ref, {required String source}) {
  final router = GoRouter.maybeOf(context);
  if (router == null) return;
  openPetSlotRenewalWith(router, source: source);
}

void openPetSlotRenewalWith(GoRouter router, {required String source}) {
  // 属性只有入口枚举（banner / block_sheet），不带宠物名（§5）。
  Analytics.capture('pet_readonly_renew_tapped', {'source': source});
  router.push('$kPetSlotsRoute?action=$kPetSlotActionRenew');
}
