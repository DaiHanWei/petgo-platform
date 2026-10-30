import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../pet_slot/presentation/add_pet_flow.dart';
import '../../data/current_pet_provider.dart';
import '../../data/pet_list_repository.dart';
import 'pet_avatar_item.dart';

/// Diary Tab 宠物切换器（V1.4.0 multi-pet Story 1.5 · AC3 · UI 稿 B1 ~ B5）。
///
/// 放在 Diary Tab **页面最顶部、标题行「Diary {name}」之上**：头像 40 一排 + 名字，当前宠物主色环，
/// 只读宠物灰化 + 锁标，末格「+」走 [startAddPet]（与「我的」页同一流程）；超出宽度横滑。
/// 只有 1 只也显示（= 1 个头像 +「+」，决策日志 2026-10-13）；没有宠物时调用方不渲染本组件（B6）。
///
/// 🔴 高亮的是 [currentId]（调用方用 `resolveEffectivePetId` **同步**算出），不等档案请求 ——
/// 点下去立即换高亮（B2）。切换只经 `currentPetIdProvider.select`，本组件不自存 petId。
class PetSwitcher extends ConsumerWidget {
  const PetSwitcher({super.key, required this.pets, required this.currentId});

  final List<PetListItem> pets;
  final int? currentId;

  static const double kAvatarSize = 40;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SingleChildScrollView(
      key: const ValueKey('petSwitcher'),
      scrollDirection: Axis.horizontal,
      // 锁标向左溢出 4，左边留出位置不被裁；它向下只溢出 2，落在头像与名字之间的空隙里，碰不到滚动区底边。
      padding: const EdgeInsets.only(left: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final p in pets)
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: PetAvatarItem(
                key: ValueKey('petSwitcherItem-${p.id}'),
                name: p.name,
                avatarUrl: p.avatarUrl,
                petType: p.petType,
                birthday: p.birthday,
                tailsonalityBadge: null,
                // 切换器不画 diary 角标（UI 稿 B1 只有头像 + 名字）。
                diaryCount: null,
                size: kAvatarSize,
                compact: true,
                selected: p.id == currentId,
                readOnly: p.isReadOnly,
                lockKey: ValueKey('petSwitcherLock-${p.id}'),
                onTap: () => _select(ref, p.id),
              ),
            ),
          const AddPetTile(
            key: ValueKey('petSwitcherAdd'),
            size: kAvatarSize,
            width: kAvatarSize + 12,
          ),
        ],
      ),
    );
  }

  void _select(WidgetRef ref, int petId) {
    if (petId == currentId) return;
    // 埋点只带 id 与来源枚举，不带宠物名（§5）。
    Analytics.capture('pet_switched', {
      'from_pet_id': ?currentId,
      'to_pet_id': petId,
      'source': 'diary_tab',
    });
    ref.read(currentPetIdProvider.notifier).select(petId);
  }
}
