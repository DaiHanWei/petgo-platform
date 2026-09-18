import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../auth/domain/auth_state.dart';
import '../../data/pet_recommendation_repository.dart';
import '../pet_recommendation_list_page.dart';
import 'recommended_pet_card.dart';

/// 首页（Sosial Tab）顶部的宠物横滑行（V1.3.0 batch-b1 Story 4.4 · FR-121.2 · UX-DR1）。
///
/// <h2>位置：场所入口行**之下**、分类 chips **之上**（AC1/AC2）</h2>
/// 两者**分层清晰**：场所入口是单行导航条（图标 + 文字 + 箭头），这里是一行横滑卡片。
/// AppBar（品牌标 + 通知铃）、分类 chips、瀑布流、底部 Tab **一处不改**。
///
/// <h2>🔴 游客：**整行不渲染，且一个请求都不发**（AC 未定义，本 story 定的）</h2>
/// story Dev Notes 要求「必须明确游客态行为，不要留成未定义行为」。选的是**不渲染**，
/// 理由不是产品偏好而是硬约束：推荐接口挂在 `/api/v1/me` 下、**仅登录可用**，
/// 游客发这个请求会拿到 401，而 401 会弹**全局强登录窗** ——
/// 那等于游客一打开首页就被一个登录弹窗糊住脸（V1.1.6 Story 2.4 为此专门约束过
/// 「未登录时绝不订阅 `/me` 的 provider」）。
/// ⚠️ 所以判空必须在 `ref.watch(petRecommendationsProvider)` **之前** ——
/// 先 watch 再判就已经把请求发出去了。
///
/// <p>🛡 Story 4.3 的集合页路由本身**对游客开放**（那是为本 story 留的余地），
/// 但本版游客根本看不到这一行，也就走不到那个入口。将来若产品要给游客看推荐，
/// 需要的是**一个对游客放行的推荐接口**，不是把这里的判空删掉。
///
/// <h2>🛡 池子为空 / 取不到 → 整行不渲染（AC3）</h2>
/// 首页最显眼的位置摆一句「暂无推荐」，等于告诉用户「这儿什么都没有」。
/// 不留空占位、不摆错误态、加载中也不占位（骨架屏一闪会把下面的 chips 顶得跳一下）。
class PetRecommendationStrip extends ConsumerWidget {
  const PetRecommendationStrip({super.key});

  /// 卡片宽度。一屏能露出两张半 —— 露出「半张」是横滑行可滑的唯一视觉暗示。
  static const double cardWidth = 150;

  /// 行高 = 卡片宽 / 网格里那个 0.72 的比例，再给文字留一点余量。
  static const double rowHeight = 216;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // 🔴 游客：**先判、再决定要不要 watch**（见类注释；顺序反了就等于发了那个 401 请求）。
    if (!ref.watch(authControllerProvider).isLoggedIn) {
      return const SizedBox.shrink();
    }
    final l10n = AppLocalizations.of(context);
    final pets = ref.watch(petRecommendationsProvider).value ?? const <RecommendedPet>[];
    if (pets.isEmpty) {
      return const SizedBox.shrink();
    }
    return Padding(
      key: const ValueKey('petRecommendationStrip'),
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.xs),
            child: Row(
              children: [
                Expanded(
                  child: Text(l10n.petRecommendSectionTitle,
                      style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                ),
                // 「查看全部」落 Story 4.3 的集合页（AC1）。
                //
                // 🔴 热区必须 ≥44×44（UX-DR16），而**文字本身只有 22 高** ——
                //    裸着当按钮点不中（code-review 2026-09-15 实测 131.8×22.0；
                //    同屏的场所入口行与 4.1 的同名入口都特意做到 ≈48）。
                //    所以给 InkWell 套一个最小尺寸约束，而不是只加几像素 padding。
                InkWell(
                  key: const ValueKey('petRecommendStripSeeAll'),
                  onTap: () => context.push(PetRecommendationListPage.routePath),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm),
                      child: Center(
                        child: Text(l10n.petRecommendSeeAll,
                            style: AppTypography.micro.copyWith(color: AppColors.mint600)),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          SizedBox(
            height: rowHeight,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
              itemCount: pets.length,
              separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.sm),
              itemBuilder: (context, i) => SizedBox(
                width: cardWidth,
                // 卡片是 Story 4.1 那个组件**本体**（不另画一套）——
                // 它的大图用 Expanded 吃剩余高度，所以这里给一个确定的高度就够。
                child: RecommendedPetCard(
                    pet: pets[i], from: kPetRecommendFromExploreStrip),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
