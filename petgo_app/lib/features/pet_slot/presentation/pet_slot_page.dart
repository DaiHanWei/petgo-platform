import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/presentation/widgets/pet_avatar_item.dart';

/// 名额管理页（`/me/pet-slots`）—— **V1.4.0 multi-pet Story 1.5 占位版**。
///
/// 只列本人宠物（头像 + 名字），**不加任何名额 / 租用文案**；
/// Story 2.4 在同一路由、同一文件里替换为 D1 ~ D15（名额卡、租用 / 续租、选择保留）。
class PetSlotPage extends ConsumerWidget {
  const PetSlotPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final pets = ref.watch(petListProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        scrolledUnderElevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.chevron_left_rounded, size: 28, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/me'),
        ),
        title: Text(l10n.petSlotPageTitle),
      ),
      body: pets.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        // 占位页：失败 / 空都不出文案（2.4 补 D15 加载失败态）。
        error: (_, _) => const SizedBox.shrink(),
        data: (list) => ListView(
          key: const ValueKey('petSlotPageList'),
          padding: const EdgeInsets.all(AppSpacing.lg),
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 12,
              children: [
                for (final p in list)
                  PetAvatarItem(
                    key: ValueKey('petSlotPagePet-${p.id}'),
                    name: p.name,
                    avatarUrl: p.avatarUrl,
                    petType: p.petType,
                    birthday: p.birthday,
                    tailsonalityBadge: null,
                    diaryCount: null,
                    readOnly: p.isReadOnly,
                    size: 56,
                    compact: true,
                    onTap: () {},
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
