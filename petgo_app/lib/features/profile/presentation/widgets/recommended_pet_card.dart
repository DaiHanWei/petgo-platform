import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/rounded.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/initial_avatar.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../data/pet_recommendation_repository.dart';
import '../../domain/pet_age.dart';
import '../visitor_archive_view.dart';

/// 推荐位埋点的 `from` 取值（Story 4.1 AC7 / 4.3 / 4.4 各一个位置）。
///
/// ⚠️ 只喂埋点，不影响任何行为。取值写成常量而不是散落字面量，是为了让
/// 「哪几个位置在用推荐位」这件事有一个能一眼看完的地方。
/// 🔴 事件名与属性 Map 本身仍写在调用点、用字面量 —— 埋点守卫是正则扫源码的
/// （同 `MentionContext` 那条教训）。
const String kPetRecommendFromDiaryEmpty = 'diary_empty';

/// 「逛别人家的毛孩子」宠物卡（V1.3.0 batch-b1 Story 4.1 · AC4/AC5/AC7）。
///
/// <h3>🔴 大图与小圆头像是**两个不同字段、不同来源**</h3>
/// UI 稿 UX-DR15 专门点过这一处：
/// - 大图 = [RecommendedPet.coverImageUrl]（该宠物**最近一张公开照片**，帖子配图）；
/// - 左下角小圆头像 = [RecommendedPet.avatarUrl]（**宠物档案自身**的头像）。
///
/// 做成同一张图重复摆放是明显 bug。大图可空（公开记录全是纯文字的宠物照样入池），
/// 此时渲染占位底色 —— 而**不是**拿头像顶上去。
///
/// <h3>AC5 点击落点：复用 Story 2.3 的站内入口，不新建通道</h3>
/// `/pets/{petId}`。游客点击走 FR-0C 强登录引导、**不发请求**
/// （站内访客接口仅登录可用，与公开主页那张宠物卡逐字同一套）。
class RecommendedPetCard extends ConsumerWidget {
  const RecommendedPetCard({super.key, required this.pet, required this.from});

  final RecommendedPet pet;

  /// 埋点的 `from`（AC7）：`diary_empty`（Story 4.1）/ 全屏集合页与横滑行各自的值（4.3 / 4.4）。
  ///
  /// ⚠️ 只喂埋点，**不影响任何行为**。
  final String from;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Material(
      color: AppColors.surface,
      borderRadius: AppRounded.lgRadius,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: ValueKey('recommendedPet_${pet.petId}'),
        onTap: () => _open(context, ref),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 🛡 大图**吃剩下的高度**，不锁 1:1 —— 网格的 `childAspectRatio` 是个定值，
            //    而下面三行文字的高度随系统字号/机型变。锁死 1:1 的表现是
            //    窄屏（360dp）上「一起 238 天」那一行被裁掉（code-review 2026-09-15）。
            Expanded(child: _cover()),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.sm),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    pet.name,
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w600),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: AppSpacing.xxs),
                  Text(
                    _meta(l10n),
                    style: AppTypography.micro,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: AppSpacing.xxs),
                  // 陪伴天数（「一起 238 天」）—— 后端与 H5 名片同一个算法算好下发。
                  Text(
                    l10n.petRecommendCompanionDays(pet.companionDays),
                    key: ValueKey('recommendedPetDays_${pet.petId}'),
                    style: AppTypography.micro.copyWith(color: AppColors.mint600),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 大图 + 左下角小圆头像。**两张图，两个来源。**
  Widget _cover() {
    return Stack(
      fit: StackFit.expand,
      children: [
        if (pet.coverImageUrl != null && pet.coverImageUrl!.isNotEmpty)
          Image(
            key: ValueKey('recommendedPetCover_${pet.petId}'),
            image: AppImage.provider(pet.coverImageUrl, thumbWidth: 480)!,
            fit: BoxFit.cover,
            // 🛡 图挂了要落回占位，**不能什么都不画**：`Image` 默认在加载失败时
            //    渲染空白并把 NetworkImageLoadException 抛到 FlutterError 里 ——
            //    表现是「卡片上方一块白，没人知道为什么」（code-review 2026-09-15）。
            errorBuilder: (context, _, _) => _coverPlaceholder(),
          )
        else
          _coverPlaceholder(),
        Positioned(
          left: AppSpacing.sm,
          bottom: AppSpacing.sm,
          child: Container(
            decoration: const BoxDecoration(shape: BoxShape.circle, color: AppColors.surface),
            padding: const EdgeInsets.all(2),
            // 宠物档案自身的头像（入池门槛保证非空；仍走同一个公共组件）。
            child: InitialAvatar(
              key: ValueKey('recommendedPetAvatar_${pet.petId}'),
              avatarUrl: pet.avatarUrl,
              nickname: pet.name,
              radius: 16,
            ),
          ),
        ),
      ],
    );
  }

  /// 🛡 没有公开照片（或图加载失败）时**渲染占位底色，绝不拿头像顶上去** ——
  /// 那正好做成了「同一张图重复摆放」的样子（UX-DR15 点名的 bug）。
  Widget _coverPlaceholder() {
    return Container(
      key: ValueKey('recommendedPetCoverPlaceholder_${pet.petId}'),
      color: AppColors.cream2,
      alignment: Alignment.center,
      child: const Icon(Icons.pets_rounded, color: AppColors.textTertiary),
    );
  }

  /// 「物种 · 年龄」—— **逐项复用既有出口**（物种文案 / `formatPetAge`），不另起口径。
  String _meta(AppLocalizations l10n) {
    final species = switch (pet.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    return [
      ?species,
      // 不满 1 个月按天表达，避免「0th 0bln」（与档案页同一出口）。
      ?formatPetAge(l10n, pet.birthday),
    ].join(' · ');
  }

  /// AC5：复用 Story 2.3 的**站内**访客入口，不新建通道。
  ///
  /// AC7 埋点：`pet_card_tapped`（属性只有 `from`，没有宠物名、没有图片 URL）。
  /// ⚠️ 上报在 `requireLogin` **之前** —— 「游客点了卡」也是一次真实的点击意图，
  /// 漏掉它会让转化漏斗的分子凭空少掉游客那一截。
  void _open(BuildContext context, WidgetRef ref) {
    Analytics.capture('pet_card_tapped', {'from': from});
    requireLogin(
      ref,
      context,
      onAllowed: () => context.push('${VisitorArchiveView.inAppRouteBase}/${pet.petId}?from=$from'),
    );
  }
}
