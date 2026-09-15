import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../data/pet_recommendation_repository.dart';
import '../pet_recommendation_list_page.dart';
import 'recommended_pet_card.dart';

/// 「逛别人家的毛孩子」2 列网格（V1.3.0 batch-b1 Story 4.1 · AC6）。
///
/// <h3>🔴 它是**追加**在既有引导之下的一段，不是一个新页面</h3>
/// AC6 原文：推荐集合追加在现有引导之下，原有「+ 建档」与「Ubah status」两个操作
/// **原样保留、一个不删**。所以本组件只负责「标题 + 网格」，
/// 上面那两个操作由调用方（Diary 未建档态）原样留着。
///
/// <h3>🛡 取不到 / 池子为空 → **整块不渲染**</h3>
/// 这一屏的主体是「去建档」，推荐区是锦上添花。加载失败时摆一个错误态
/// 会让用户以为「我这个页面坏了」，而他要做的那件事（建档）明明是好的。
/// 加载中同理不占位 —— 一闪而过的骨架屏会把下面的按钮顶得跳一下。
///
/// <h3>「查看全部」在**网格下面**（Story 4.3 · AC1 · UX-DR14）</h3>
/// 位置是「页面最下面」—— 看完这一屏想看更多再点，不是摆在标题右边勾人先点。
/// 它的落点是 Story 4.3 交付的 [PetRecommendationListPage]。
/// ⚠️ Story 4.1 交付时刻意**没有**这个入口：那时集合页还不存在，
/// 挂一个点不动的入口比没有更糟。
class PetRecommendationGrid extends ConsumerWidget {
  const PetRecommendationGrid({super.key, required this.from});

  /// 埋点的 `from`（AC7）。Story 4.1 是 `diary_empty`。
  final String from;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final pets = ref.watch(petRecommendationsProvider).value ?? const <RecommendedPet>[];
    if (pets.isEmpty) {
      return const SizedBox.shrink();
    }
    return Column(
      key: const ValueKey('petRecommendationGrid'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.sm),
          child: Text(l10n.petRecommendSectionTitle, style: AppTypography.title),
        ),
        GridView.builder(
          // 外层是可滚动容器（Diary 未建档态那一屏），这里不再自己滚。
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          padding: EdgeInsets.zero,
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2,
            crossAxisSpacing: AppSpacing.sm,
            mainAxisSpacing: AppSpacing.sm,
            // 大图是 1:1，下面还有三行文字 —— 比例给瘦一点，免得卡片底部被裁。
            childAspectRatio: 0.72,
          ),
          itemCount: pets.length,
          itemBuilder: (context, i) => RecommendedPetCard(pet: pets[i], from: from),
        ),
        // AC1：「查看全部」放**页面最下面**（UX-DR14）。
        // ⚠️ 只在真有网格时才出现 —— 上面已经 return 掉了空池子，所以这里天然成立。
        Align(
          alignment: Alignment.center,
          child: TextButton(
            key: const ValueKey('petRecommendSeeAll'),
            onPressed: () => context.push(PetRecommendationListPage.routePath),
            child: Text(l10n.petRecommendSeeAll),
          ),
        ),
      ],
    );
  }
}
