import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/rounded.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/initial_avatar.dart';
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

  /// 小卡宽度 = 方图边长。UI 稿 B1 原为 72×72，bug 20260922-522 整体缩小 10% → 65×65。
  /// 一屏露出四张多 —— 露出「半张」是横滑行可滑的唯一视觉暗示。
  static const double cardWidth = 65;

  /// 行高 = 方图 65 + 间距 8 + 一行 micro 文字（11 × 行高 1.3 × 字号上限 1.3 ≈ 18.6）≈ 91.6 → 92。
  /// 头像探出方图的 6px 落在那 8px 间距里，不额外占高（bug 20260922-522 由 100 缩到 92）。
  ///
  /// ⚠️ 这一行在首页**最显眼的位置**、feed 之上：早先直接塞网格卡（宽 150、三行字、
  /// 行高 216）把 feed 顶到了屏幕下半截，稿子要的是一条「顺手瞄一眼」的窄带。
  static const double rowHeight = 92;

  /// 小卡间距（UI 稿 B1 为 9，bug 20260922-522 随卡片缩 10% → 8）。
  static const double cardGap = 8;

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
                        // UI 稿 B1：「Lihat semua ›」w600 —— 带箭头才读得出「这是个入口」。
                        child: Text('${l10n.petRecommendSeeAll} ›',
                            style: AppTypography.micro.copyWith(
                                color: AppColors.mint600, fontWeight: FontWeight.w600)),
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
              separatorBuilder: (_, _) => const SizedBox(width: cardGap),
              itemBuilder: (context, i) => _StripPetTile(pet: pets[i]),
            ),
          ),
        ],
      ),
    );
  }
}

/// 横滑行里的紧凑小卡（UI 稿 B1）：72×72 圆角方图 + 左下角探出的小圆头像 +
/// 一行「名字 · 陪伴天数」。
///
/// <h3>为什么不直接用 [RecommendedPetCard]</h3>
/// 网格卡是「大图 + 三行字」，在 2 列网格里刚好；塞进首页顶部的横滑行就是一整块
/// 216 高的墙。两者**长相**不同，但**点下去必须是同一件事** —— 所以点击走
/// [openRecommendedPet]（同一个落点、同一个埋点、同一道登录门控），key 也沿用
/// `recommendedPet_{petId}`，只有 `from` 是这个位置自己的 `explore_strip`。
///
/// 🔴 大图与小圆头像仍是**两个字段、两个来源**（UX-DR15）：方图 = 最近一张公开照片，
/// 头像 = 宠物档案头像；没有公开照片时落占位底色，绝不拿头像顶上去。
class _StripPetTile extends ConsumerWidget {
  const _StripPetTile({required this.pet});

  final RecommendedPet pet;

  static const double _size = PetRecommendationStrip.cardWidth;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      width: _size,
      child: GestureDetector(
        key: ValueKey('recommendedPet_${pet.petId}'),
        behavior: HitTestBehavior.opaque,
        onTap: () => openRecommendedPet(context, ref,
            pet: pet, from: kPetRecommendFromExploreStrip),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(
              width: _size,
              height: _size,
              child: Stack(
                // 头像探出方图左下角（UI 稿 B1）→ 不裁剪溢出。
                clipBehavior: Clip.none,
                children: [
                  Positioned.fill(
                    child: ClipRRect(
                      borderRadius: AppRounded.mdRadius,
                      child: (pet.coverImageUrl != null && pet.coverImageUrl!.isNotEmpty)
                          ? Image(
                              key: ValueKey('recommendedPetCover_${pet.petId}'),
                              image: AppImage.provider(pet.coverImageUrl, thumbWidth: 240)!,
                              fit: BoxFit.cover,
                              // 🛡 图挂了落回占位，不留一块白（同网格卡）。
                              errorBuilder: (context, _, _) => _placeholder(),
                            )
                          : _placeholder(),
                    ),
                  ),
                  Positioned(
                    left: -4,
                    bottom: -6,
                    child: Container(
                      decoration: const BoxDecoration(
                          shape: BoxShape.circle, color: AppColors.surface),
                      padding: const EdgeInsets.all(2),
                      child: InitialAvatar(
                        key: ValueKey('recommendedPetAvatar_${pet.petId}'),
                        avatarUrl: pet.avatarUrl,
                        nickname: pet.name,
                        radius: 9, // 18 + 2×2 白边 = 22（bug 20260922-522：原 10 缩 10%）
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            // 「Miu · 238 hari」—— 陪伴天数复用名片页那个「N hari」出口（petCardDays），
            // 数值是后端与 H5 名片同一个算法下发的 companionDays。
            Text(
              '${pet.name} · ${l10n.petCardDays(pet.companionDays)}',
              key: ValueKey('recommendedPetDays_${pet.petId}'),
              style: AppTypography.micro,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }

  Widget _placeholder() => Container(
        key: ValueKey('recommendedPetCoverPlaceholder_${pet.petId}'),
        color: AppColors.cream2,
        alignment: Alignment.center,
        child: const Icon(Icons.pets_rounded, size: 20, color: AppColors.textTertiary),
      );
}
