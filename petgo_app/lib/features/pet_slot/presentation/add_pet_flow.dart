import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../data/pet_slot_repository.dart';

/// 名额管理页路由（Story 1.5 占位；Story 2.4 在同一路由、同一文件里替换为 D1 ~ D15）。
const String kPetSlotsRoute = '/me/pet-slots';

/// 「添加宠物」唯一入口（V1.4.0 multi-pet Story 1.5 · AC4.1）：「我的」页与 Diary 切换器的「+」共用。
///
/// 先取 `GET /pet-slots/me`：`petCount ≥ cap` → A4b（已达上限）；`petCount ≥ total` → A4（名额已满）；
/// 否则进建档页。摘要取不到 → 直接进建档页（后端 409 兜底，见建档页）。
///
/// [source] 是 `pet_slot_full_shown.source` 的取值（本 story 两处「+」都是 `add_pet`）。
Future<void> startAddPet(BuildContext context, WidgetRef ref, {required String source}) async {
  // 每次点「+」重取：不预取（待拍板 2），也不吃上一次的缓存（期间可能升级 / 删了一只）。
  ref.invalidate(petSlotOverviewProvider);
  PetSlotOverview? overview;
  try {
    overview = await ref.read(petSlotOverviewProvider.future);
  } catch (_) {
    overview = null;
  }
  if (!context.mounted) return;
  // 抽屉不 await：本函数只负责「判定 + 打开」，返回即可让「+」格收起转圈（抽屉开着时它不该一直转）。
  if (overview != null && overview.atCap) {
    showPetLimitSheet(context);
    return;
  }
  if (overview != null && overview.isFull) {
    showPetSlotFullSheet(context, PetSlotFullInfo.fromOverview(overview), source: source);
    return;
  }
  // 摘要随路由带进建档页：建档成功后据它推 `pet_added.slot_source`（建档前口径）。
  // `?add=1`：建档页据此不走存量的「已有档案直达」守卫（code review 2026-10-18 #3）。
  context.push('/profile/create?add=1', extra: overview);
}

/// A4 名额已满抽屉（AC4.2）。弹出一次上报一次 `pet_slot_full_shown {source, action}`：
/// 点主按钮「Sewa slot」→ `rent`（进名额管理页，Story 2.4 接通 D5 租用抽屉）；关闭 / 次按钮 → `close`。
Future<void> showPetSlotFullSheet(
  BuildContext context,
  PetSlotFullInfo info, {
  required String source,
}) async {
  final l10n = AppLocalizations.of(context);
  final router = GoRouter.maybeOf(context);
  // TODO(v1.4.0-multi-pet: ① 合入后加次按钮 growthViewMyLevel → context.push('/me/growth')，pet_slot_full_shown.action = upgrade)
  final rent = await showConfirmSheet(
    context,
    title: l10n.petSlotFullTitle,
    message: l10n.petSlotFullBody(
      info.petCount,
      info.slotTotal,
      'Rp${formatIdrAmount(info.unitPrice)}',
    ),
    confirmLabel: l10n.petSlotFullRent,
    cancelLabel: l10n.commonClose,
    icon: Icons.pets,
    confirmKey: const ValueKey('petSlotFullRent'),
    cancelKey: const ValueKey('petSlotFullClose'),
  );
  // 埋点属性只有枚举值，不带宠物名 / 金额（§5）。
  Analytics.capture('pet_slot_full_shown', {
    'source': source,
    'action': rent ? 'rent' : 'close',
  });
  if (rent) router?.push(kPetSlotsRoute);
}

/// A4b 已达 5 只上限（AC4.3）：同一个确认抽屉，唯一按钮「Oke」。
Future<void> showPetLimitSheet(BuildContext context) async {
  final l10n = AppLocalizations.of(context);
  await showConfirmSheet(
    context,
    title: l10n.petSlotLimitTitle,
    message: l10n.petSlotLimitBody,
    confirmLabel: l10n.petSlotLimitOk,
    cancelLabel: l10n.commonClose,
    icon: Icons.pets,
    confirmKey: const ValueKey('petSlotLimitOk'),
    showCancel: false,
  );
}

/// 「+」格（「我的」页 56 / 切换器 40）：虚线圆 + 加号；点击走 [startAddPet]，取摘要期间显示小转圈并防连点
/// （弱网下的一次短等待，待拍板 2）。
class AddPetTile extends ConsumerStatefulWidget {
  const AddPetTile({
    super.key,
    required this.size,
    required this.width,
    this.source = 'add_pet',
  });

  /// 圆直径。
  final double size;

  /// 整格宽（与同排头像项对齐）。
  final double width;
  final String source;

  @override
  ConsumerState<AddPetTile> createState() => _AddPetTileState();
}

class _AddPetTileState extends ConsumerState<AddPetTile> {
  bool _busy = false;

  Future<void> _tap() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await startAddPet(context, ref, source: widget.source);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _tap,
      child: SizedBox(
        width: widget.width,
        child: Column(
          children: [
            // 与头像项外圈（2 + 2 边）对齐：外框 = size + 8。
            Padding(
              padding: const EdgeInsets.all(4),
              child: Container(
                width: size,
                height: size,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.card,
                  shape: BoxShape.circle,
                  border: Border.all(color: AppColors.dashedViolet, width: 1.5),
                ),
                child: _busy
                    ? SizedBox(
                        width: size * 0.4,
                        height: size * 0.4,
                        child: const CircularProgressIndicator(
                            strokeWidth: 2, color: AppColors.mint),
                      )
                    : Icon(Icons.add, size: size * 0.5, color: AppColors.mint),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
