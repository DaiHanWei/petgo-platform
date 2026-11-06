/// 为宠物推荐网格（v1.4.2 shop Story 8.1 AC5，FR-133.4 / FR-136.2、D-17，UI A1 / A9 / A7；8.3 空购物车复用）。
///
/// 实色浅紫底块、边界清楚；栏头 + **三列网格最多 6 个（两行）**；卡片只放图、名称、价格与「＋」，
/// **不显示单个商品的推荐理由**（接口仍返回，App 不用）。
///
/// - 有档案（推荐接口返回 `profileSummary` 且有商品）→ 栏头宠物头像 +「Untuk {pet}」+ 依据行（本地拼，
///   **不渲染后端显示串**）；`weightMissing` → 网格下方浅紫提示条「Isi berat badan {pet}…」进 `/profile/edit`。
/// - 无档案 / 游客：[ShopRecoSource.profileOnly]（首页）整块不显示；
///   [ShopRecoSource.profileOrTop]（搜索无结果 / 空购物车）→ 栏头「Mungkin kamu suka」、取运营权重前 6。
/// - 「只用默认宠物、离世 / 手术记录整栏隐藏」等规则由服务端返回空 / 降级表达，App 不判。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../data/shop_repository.dart';
import '../../data/shop_repurchase_repository.dart';
import '../../domain/shop_repurchase.dart';
import 'shop_pressable.dart';
import 'shop_product_card.dart';

/// 推荐网格的数据来源策略。
enum ShopRecoSource {
  /// 只出为宠物推荐；无档案整块不显示（Toko 首页）。
  profileOnly,

  /// 有档案出为宠物推荐，否则「Mungkin kamu suka」+ 运营权重前 6（搜索无结果 A7、空购物车 C6）。
  profileOrTop,
}

/// 网格最多几个（两行 × 三列）。
const int kShopRecoMax = 6;

/// 依据行（A1 / A9 栏头第二行）：ID「Anjing kecil · dewasa」/ EN「Small dog · adult」；
/// 体型缺省只留物种、年龄缺省省略该段、`weightMissing` 末尾加「berat belum diisi」。首字母大写。
String recoBasisLine(AppLocalizations l10n, ProfileSummary s) {
  final species = switch (s.species) {
    'DOG' => l10n.tokoRecoSpeciesDog,
    'CAT' => l10n.tokoRecoSpeciesCat,
    _ => null,
  };
  final size = switch (s.sizeClass) {
    'SMALL' => l10n.tokoRecoSizeSmall,
    'MEDIUM' => l10n.tokoRecoSizeMedium,
    'LARGE' => l10n.tokoRecoSizeLarge,
    _ => null,
  };
  final age = switch (s.ageStage) {
    'PUPPY' => l10n.tokoRecoAgePuppy,
    'ADULT' => l10n.tokoRecoAgeAdult,
    'SENIOR' => l10n.tokoRecoAgeSenior,
    _ => null,
  };
  final head = (species != null && size != null) ? l10n.tokoRecoSpeciesSize(species, size) : (species ?? size);
  final parts = <String>[?head, ?age, if (s.weightMissing) l10n.tokoRecoWeightMissing];
  final line = parts.join(' · ');
  if (line.isEmpty) return line;
  return line[0].toUpperCase() + line.substring(1);
}

class ShopRecoGrid extends ConsumerWidget {
  const ShopRecoGrid({super.key, required this.source});

  final ShopRecoSource source;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final reco = ref.watch(recommendationsProvider).maybeWhen<Recommendations?>(
        data: (v) => v, orElse: () => null);
    final summary = reco?.profileSummary;
    if (reco != null && summary != null && reco.items.isNotEmpty) {
      final items = reco.items.take(kShopRecoMax).toList(growable: false);
      return _Block(
        key: const ValueKey('shopRecoGridProfile'),
        header: _ProfileHeader(summary: summary),
        cards: [
          for (final it in items)
            ShopProductCard(
              key: ValueKey('shopRecoCard_${it.productToken}'),
              token: it.productToken,
              name: it.name,
              imageUrl: it.mainImageUrl,
              minPrice: it.minPrice,
              promo: it.promo,
              zone: 'profile_reco',
              entrySource: 'PROFILE_RECO',
              dense: true,
              onTapped: () => Analytics.capture('toko_profile_reco_tapped',
                  {'zone': 'profile_reco', 'product_id': it.productToken}),
            ),
        ],
        footer: summary.weightMissing
            ? _AddWeightHint(text: l10n.tokoRecoAddWeight(summary.petName))
            : null,
      );
    }
    if (source == ShopRecoSource.profileOnly) return const SizedBox.shrink();
    final top = ref.watch(shopTopProductsProvider).maybeWhen(
        data: (v) => v, orElse: () => const []);
    if (top.isEmpty) return const SizedBox.shrink();
    return _Block(
      key: const ValueKey('shopRecoGridTop'),
      header: Text(l10n.tokoMightLike,
          style: AppTypography.title.copyWith(fontSize: 16, color: AppColors.ink)),
      cards: [
        for (final p in top.take(kShopRecoMax))
          ShopProductCard.fromSummary(p,
              key: ValueKey('shopRecoCard_${p.token}'),
              zone: 'might_like',
              entrySource: 'TOKO_MIGHT_LIKE',
              dense: true),
      ],
    );
  }
}

class _Block extends StatelessWidget {
  const _Block({super.key, required this.header, required this.cards, this.footer});

  final Widget header;
  final List<Widget> cards;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    const gap = AppSpacing.sm;
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.violet100,
        borderRadius: BorderRadius.circular(kShopCardRadius),
        border: Border.all(color: AppColors.lineViolet),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          header,
          const SizedBox(height: AppSpacing.md),
          LayoutBuilder(builder: (context, c) {
            final cellW = (c.maxWidth - gap * 2) / 3;
            return GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              // 🔴 padding 必须显式给：null 时 BoxScrollView 会把 MediaQuery.padding 当自己的内边距。
              padding: EdgeInsets.zero,
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                mainAxisSpacing: gap,
                crossAxisSpacing: gap,
                mainAxisExtent: ShopProductCard.extentFor(context, cellW, dense: true),
              ),
              itemCount: cards.length,
              itemBuilder: (_, i) => cards[i],
            );
          }),
          if (footer != null) ...[const SizedBox(height: AppSpacing.md), footer!],
        ],
      ),
    );
  }
}

class _ProfileHeader extends StatelessWidget {
  const _ProfileHeader({required this.summary});

  final ProfileSummary summary;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final basis = recoBasisLine(l10n, summary);
    return Row(
      children: [
        Container(
          width: 36,
          height: 36,
          alignment: Alignment.center,
          decoration: const BoxDecoration(color: AppColors.card, shape: BoxShape.circle),
          child: const Icon(Icons.pets, size: 18, color: AppColors.mint),
        ),
        const SizedBox(width: AppSpacing.sm),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.tokoRecoHeader(summary.petName),
                  key: const ValueKey('shopRecoHeader'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.title.copyWith(fontSize: 16, color: AppColors.ink)),
              if (basis.isNotEmpty)
                Text(basis,
                    key: const ValueKey('shopRecoBasis'),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: AppTypography.caption.copyWith(color: AppColors.ink2)),
            ],
          ),
        ),
      ],
    );
  }
}

/// A9 未填体重提示条 → 编辑宠物档案页。
class _AddWeightHint extends StatelessWidget {
  const _AddWeightHint({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => ShopPressable(
        key: const ValueKey('shopRecoAddWeight'),
        onTap: () => context.push('/profile/edit'),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
          decoration: BoxDecoration(
            color: AppColors.mintTint,
            borderRadius: BorderRadius.circular(AppSpacing.md),
          ),
          child: Row(
            children: [
              const Icon(Icons.monitor_weight, size: 18, color: AppColors.mint),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(text,
                    style: AppTypography.caption.copyWith(color: AppColors.mint700)),
              ),
              const Icon(Icons.chevron_right, size: 18, color: AppColors.mint),
            ],
          ),
        ),
      );
}
