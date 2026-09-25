import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/initial_avatar.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../data/pet_recommendation_repository.dart';
import '../visitor_archive_view.dart';

/// 推荐位埋点的 `from` 取值（Story 4.1 AC7 / 4.3 / 4.4 各一个位置）。
///
/// ⚠️ 只喂埋点，不影响任何行为。取值写成常量而不是散落字面量，是为了让
/// 「哪几个位置在用推荐位」这件事有一个能一眼看完的地方。
/// 🔴 事件名与属性 Map 本身仍写在调用点、用字面量 —— 埋点守卫是正则扫源码的
/// （同 `MentionContext` 那条教训）。
const String kPetRecommendFromDiaryEmpty = 'diary_empty';

/// Story 4.2（B1-D2）：Diary「声明未养宠 / 计划养宠」态。
///
/// 🔴 **必须与 [kPetRecommendFromDiaryEmpty] 是两个不同的值**（4.2 AC3）：两屏覆盖的是
/// 两批完全不同的人（「养了但没建档」vs「从没养过」），合成一个值之后
/// 再也分不开两批人的转化 —— 而那正是 B1-D2 扩这一屏的理由。
const String kPetRecommendFromDiaryNonOwner = 'diary_non_owner';

/// Story 4.3（AC5）：全屏推荐集合页。
///
/// ⚠️ 取值是 `explore_grid` 而不是 `recommend_grid` —— **AC5 原文照抄**。
/// 它与 Story 4.4 的探索 Tab 横滑行是**两个位置**，到时各有各的 `from`。
const String kPetRecommendFromExploreGrid = 'explore_grid';

/// Story 4.4（AC4）：首页顶部的宠物横滑行。
///
/// ⚠️ 与 [kPetRecommendFromExploreGrid] 是**两个位置**：横滑行是首页顶上那一行，
/// 集合页是点「查看全部」之后的整屏网格。合成一个值就分不清「顺手滑到的」
/// 与「专门点进去看的」——而这两批人的意图强弱完全不同。
const String kPetRecommendFromExploreStrip = 'explore_strip';

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

  /// 小圆头像（含 2px 白边）的直径。UI 稿 E1/E2：头像**一半压在大图下沿之外**，
  /// 所以文字区要先让出半个头像的高度。
  static const double _avatarDiameter = 32;

  /// UI 稿 E2：小圆头像略**探出大图左边缘**（负偏移，Stack 不裁剪）。
  static const double _avatarLeft = -6;

  /// UI 稿 E1/E2 `.pthumb`：大图圆角 11 + 1px 淡描边（inset rgba(0,0,0,.08)）。
  static const BorderRadius _coverRadius = BorderRadius.all(Radius.circular(11));

  /// 网格里一格的高度：**格宽**（大图 1:1，UI 稿 `.pthumb` aspect-ratio:1）+ 下方文字区。
  ///
  /// bug 20260922-530 还原度对齐：此前用固定 `childAspectRatio: 0.76`，
  /// 窄屏上大图被压扁、宽屏上被拉高，只有 375dp 附近接近 1:1。改为按实际格宽 + 文字行高
  /// （随系统字号缩放）算出每格高度 —— 大图恒为正方形，文字行也不会被裁。
  /// ⚠️ 与 [build] 里文字区的排法（半个头像 + 名字一行 + 天数一行）同源，改一边要改另一边。
  static double gridCellExtent(BuildContext context, double cellWidth) {
    final scaler = MediaQuery.textScalerOf(context);
    final textBlock = _avatarDiameter / 2 +
        AppSpacing.xxs +
        scaler.scale(AppTypography.body.fontSize!) * AppTypography.body.height! +
        AppSpacing.xxs +
        scaler.scale(AppTypography.micro.fontSize!) * AppTypography.micro.height!;
    // +4：字体度量取整的余量，宁可多一线空白也不能裁掉天数那一行。
    return cellWidth + textBlock + 4;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    // UI 稿 E1/E2：卡片**没有卡底**（透明），只有大图裁圆角 —— 卡底会把 2 列网格
    // 切成一块块白板，稿子里是「图 + 下面几行字」直接落在页面底色上。
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        key: ValueKey('recommendedPet_${pet.petId}'),
        borderRadius: _coverRadius,
        onTap: () => openRecommendedPet(context, ref, pet: pet, from: from),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 🛡 大图**吃剩下的高度**，不锁 1:1 —— 网格的 `childAspectRatio` 是个定值，
            //    而下面两行文字的高度随系统字号/机型变。锁死 1:1 的表现是
            //    窄屏（360dp）上「物种 · 天数」那一行被裁掉（code-review 2026-09-15）。
            Expanded(child: _cover()),
            Padding(
              // 顶部让出**半个头像**（它从大图下沿探出来），再留一点呼吸。
              padding: const EdgeInsets.fromLTRB(
                  AppSpacing.xxs, _avatarDiameter / 2 + AppSpacing.xxs, AppSpacing.xxs, 0),
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
                  // UI 稿 E2：名字下面**只有一行** micro 灰字「物种 · 陪伴天数」（Kucing · 238 hari）。
                  // 陪伴天数由后端与 H5 名片同一个算法算好下发。
                  Text(
                    _meta(l10n),
                    key: ValueKey('recommendedPetDays_${pet.petId}'),
                    style: AppTypography.micro,
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
  ///
  /// UI 稿 E1/E2：头像压在大图**下沿**（一半在图外）→ Stack 不裁剪溢出部分；
  /// 圆角只裁大图本身。
  Widget _cover() {
    return Stack(
      fit: StackFit.expand,
      clipBehavior: Clip.none,
      children: [
        ClipRRect(
          borderRadius: _coverRadius,
          child: (pet.coverImageUrl != null && pet.coverImageUrl!.isNotEmpty)
              ? Image(
                  key: ValueKey('recommendedPetCover_${pet.petId}'),
                  image: AppImage.provider(pet.coverImageUrl, thumbWidth: 480)!,
                  fit: BoxFit.cover,
                  // 🛡 图挂了要落回占位，**不能什么都不画**：`Image` 默认在加载失败时
                  //    渲染空白并把 NetworkImageLoadException 抛到 FlutterError 里 ——
                  //    表现是「卡片上方一块白，没人知道为什么」（code-review 2026-09-15）。
                  errorBuilder: (context, _, _) => _coverPlaceholder(),
                )
              : _coverPlaceholder(),
        ),
        // UI 稿 `.pthumb`：1px 淡描边压在大图上（浅色占位底与页面底色贴近时仍分得清卡片边界）。
        const IgnorePointer(
          child: DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: _coverRadius,
              border: Border.fromBorderSide(BorderSide(color: Color(0x14000000))),
            ),
          ),
        ),
        Positioned(
          left: _avatarLeft,
          bottom: -_avatarDiameter / 2,
          child: Container(
            decoration: const BoxDecoration(shape: BoxShape.circle, color: AppColors.surface),
            padding: const EdgeInsets.all(2),
            // 宠物档案自身的头像（入池门槛保证非空；仍走同一个公共组件）。
            child: InitialAvatar(
              key: ValueKey('recommendedPetAvatar_${pet.petId}'),
              avatarUrl: pet.avatarUrl,
              nickname: pet.name,
              // 直径 32 = 2 × 14 + 2px 白边 × 2。
              radius: (_avatarDiameter - 4) / 2,
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

  /// 「物种 · 陪伴天数」（UI 稿 E2）—— **逐项复用既有文案**（物种文案 / `petCardDays`），不另起口径。
  /// 物种未知时只剩天数，不留孤零零的分隔符。
  String _meta(AppLocalizations l10n) {
    final species = switch (pet.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    return [
      ?species,
      l10n.petCardDays(pet.companionDays),
    ].join(' · ');
  }
}

/// 点一张推荐宠物卡（Story 4.1 AC5/AC7）—— **所有推荐位的唯一点击出口**。
///
/// 网格卡（[RecommendedPetCard]）与首页横滑行的紧凑小卡长得不同，但点下去必须
/// 是同一件事：同一个落点、同一个埋点、同一道登录门控。抽成函数就是为了让
/// 「两种卡片的点击行为慢慢长歪」在结构上不可能发生。
///
/// AC5：复用 Story 2.3 的**站内**访客入口，不新建通道。
/// AC7 埋点：`pet_card_tapped`（属性只有 `from`，没有宠物名、没有图片 URL）。
/// ⚠️ 上报在 `requireLogin` **之前** —— 「游客点了卡」也是一次真实的点击意图，
/// 漏掉它会让转化漏斗的分子凭空少掉游客那一截。
void openRecommendedPet(
  BuildContext context,
  WidgetRef ref, {
  required RecommendedPet pet,
  required String from,
}) {
  Analytics.capture('pet_card_tapped', {'from': from});
  requireLogin(
    ref,
    context,
    onAllowed: () => context.push('${VisitorArchiveView.inAppRouteBase}/${pet.petId}?from=$from'),
  );
}
